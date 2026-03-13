package com.ytmusic.feature.player

import android.os.Bundle
import androidx.media.app.NotificationCompat
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.repository.PlaylistRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutoMediaBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var trackRepository:    TrackRepository
    @Inject lateinit var playlistRepository: PlaylistRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    companion object {
        const val ROOT_ID         = "AUTO_ROOT"
        const val ARCHIVE_ID      = "AUTO_ARCHIVE"
        const val PLAYLISTS_ID    = "AUTO_PLAYLISTS"
        const val MEGA_ID         = "AUTO_MEGA"
        const val PLAYLIST_PREFIX = "AUTO_PL_"
        const val MEGA_PREFIX     = "AUTO_MEGA_"
        const val SUFFIX_NORMAL   = "__NORMAL"
        const val SUFFIX_SHUFFLE  = "__SHUFFLE"

        // Action personnalisée "Quitter la lecture" affichée dans le lecteur Auto
        const val ACTION_QUIT = "ACTION_QUIT_PLAYLIST"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AutoMediaBrowserService").apply {
            setCallback(MediaSessionCallback())
            setPlaybackState(buildStoppedState())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        connectToPlayerService()
    }

    override fun onDestroy() {
        scope.cancel()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaSession.release()
        super.onDestroy()
    }

    // ── Connexion à PlayerService ─────────────────────────────────────────────

    private fun connectToPlayerService() {
        val token = SessionToken(
            this,
            android.content.ComponentName(this, PlayerService::class.java)
        )
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                controller = controllerFuture?.get()
                controller?.addListener(PlayerStateListener())
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        val ctrl = controller
        if (ctrl != null && ctrl.isConnected) {
            onReady(ctrl)
            return
        }
        val token = SessionToken(
            this,
            android.content.ComponentName(this, PlayerService::class.java)
        )
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            try {
                val newCtrl = controllerFuture?.get() ?: return@addListener
                controller = newCtrl
                newCtrl.addListener(PlayerStateListener())
                onReady(newCtrl)
            } catch (_: Exception) {}
        }, MoreExecutors.directExecutor())
    }

    // ── Navigation Android Auto ───────────────────────────────────────────────

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",  1)
            // Indique à Android Auto de toujours afficher le panneau queue sur la droite
            // quand l'écran est suffisamment large. Pas garanti sur tous les profils
            // (c'est le système qui décide en dernier ressort), mais c'est le signal correct.
            putBoolean("android.media.browse.SHOW_QUEUE_HINT", true)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        scope.launch {
            val items = when {
                parentId == ROOT_ID         -> buildRoot()
                parentId == ARCHIVE_ID      -> buildArchive()
                parentId == PLAYLISTS_ID    -> buildPlaylistList()
                parentId == MEGA_ID         -> buildMegaList()
                parentId.startsWith(PLAYLIST_PREFIX) ->
                    buildPlaylistContent(parentId.removePrefix(PLAYLIST_PREFIX))
                parentId.startsWith(MEGA_PREFIX) ->
                    buildMegaContent(parentId.removePrefix(MEGA_PREFIX))
                else -> emptyList()
            }
            result.sendResult(items.toMutableList())
        }
    }

    // ── MediaSession Callback ─────────────────────────────────────────────────

    inner class MediaSessionCallback : MediaSessionCompat.Callback() {

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            mediaId ?: return
            scope.launch {
                when {
                    isPlaylistAction(mediaId) -> launchPlaylistAction(mediaId)
                    else -> launchSingleTrack(mediaId)
                }
            }
        }

        override fun onPlay() {
            ensureController { it.play() }
        }

        override fun onPause() {
            ensureController { it.pause() }
        }

        override fun onSkipToNext() {
            ensureController { it.seekToNextMediaItem() }
        }

        override fun onSkipToPrevious() {
            ensureController { it.seekToPreviousMediaItem() }
        }

        override fun onStop() {
            ensureController {
                it.stop()
                it.clearMediaItems()
            }
            // Revenir à l'état stopped pour qu'Auto repasse en vue navigation
            mediaSession.setMetadata(null)
            mediaSession.setPlaybackState(buildStoppedState())
        }

        override fun onSeekTo(pos: Long) {
            ensureController { it.seekTo(pos) }
        }

        // Bouton "Quitter" déclaré comme CustomAction dans le PlaybackState.
        // Android Auto l'affiche dans le lecteur à côté des contrôles standard.
        // onCustomAction est appelé quand l'utilisateur appuie dessus.
        override fun onCustomAction(action: String, extras: Bundle?) {
            if (action == ACTION_QUIT) {
                ensureController {
                    it.stop()
                    it.clearMediaItems()
                }
                mediaSession.setMetadata(null)
                mediaSession.setPlaybackState(buildStoppedState())
            }
        }
    }

    // ── Lancement de la lecture ───────────────────────────────────────────────

    private suspend fun launchSingleTrack(mediaId: String) {
        val allTracks = trackRepository.getAllTracks().firstOrNull() ?: return
        val idx = allTracks.indexOfFirst { it.id == mediaId }
        if (idx == -1) return
        sendToPlayer(allTracks, startIndex = idx, shuffle = false)
    }

    private fun isPlaylistAction(mediaId: String) =
        mediaId.endsWith(SUFFIX_NORMAL) || mediaId.endsWith(SUFFIX_SHUFFLE)

    private suspend fun launchPlaylistAction(mediaId: String) {
        val shuffle = mediaId.endsWith(SUFFIX_SHUFFLE)
        val tracks: List<Track> = when {
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val id = mediaId.removePrefix(PLAYLIST_PREFIX)
                    .removeSuffix(SUFFIX_NORMAL).removeSuffix(SUFFIX_SHUFFLE)
                playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
                    ?.firstOrNull { it.id == id }?.tracks ?: emptyList()
            }
            mediaId.startsWith(MEGA_PREFIX) -> {
                val ids = mediaId.removePrefix(MEGA_PREFIX)
                    .removeSuffix(SUFFIX_SHUFFLE).split(",")
                playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
                    ?.filter  { pl -> pl.id in ids }
                    ?.flatMap { pl -> pl.tracks }
                    ?.distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
                    ?: emptyList()
            }
            else -> emptyList()
        }
        if (tracks.isEmpty()) return
        sendToPlayer(tracks, startIndex = 0, shuffle = shuffle)
    }

    /**
     * Charge la queue dans ExoPlayer via le MediaController, puis :
     * 1. Envoie les métadonnées du premier morceau à la MediaSessionCompat
     *    → Android Auto peut afficher le lecteur immédiatement
     * 2. Envoie un état STATE_PLAYING
     *    → Android Auto bascule automatiquement vers la vue lecteur
     *    et ne revient à la navigation que si l'utilisateur appuie sur Stop
     */
    /**
     * FIX SHUFFLE : on mélange la liste EN AMONT en Kotlin avant de l'envoyer à ExoPlayer.
     * Appeler ctrl.shuffleModeEnabled APRÈS setMediaItems ne change pas l'ordre immédiat —
     * ExoPlayer commence toujours par startIndex. En mélangeant d'abord, le vrai premier
     * morceau joué est aléatoire.
     * On laisse shuffleModeEnabled = false car on a déjà mélangé manuellement — évite
     * un double-mélange au prochain skip.
     */
    private fun sendToPlayer(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        val orderedTracks = if (shuffle) tracks.shuffled() else tracks
        val effectiveStart = if (shuffle) 0 else startIndex

        ensureController { ctrl ->
            scope.launch(Dispatchers.Main) {
                ctrl.stop()
                ctrl.clearMediaItems()
                ctrl.setMediaItems(orderedTracks.map { it.toMedia3Item() }, effectiveStart, 0L)
                ctrl.shuffleModeEnabled = false // déjà mélangé manuellement
                ctrl.prepare()
                ctrl.play()

                orderedTracks.getOrNull(effectiveStart)?.let { pushMetadata(it) }

                // Alimente le panneau "Prochaines lectures" à droite
                val queue = orderedTracks.mapIndexed { idx, track ->
                    MediaSessionCompat.QueueItem(
                        MediaDescriptionCompat.Builder()
                            .setMediaId(track.id)
                            .setTitle(track.title)
                            .setSubtitle(track.artist)
                            .apply {
                                ThumbnailProvider.toContentUri(
                                    this@AutoMediaBrowserService, track.thumbnailPath
                                )?.let { setIconUri(it) }
                            }
                            .build(),
                        idx.toLong()
                    )
                }
                mediaSession.setQueue(queue)
                mediaSession.setQueueTitle(if (shuffle) "Lecture aléatoire" else "Liste de lecture")
                mediaSession.setPlaybackState(
                    buildActiveState(PlaybackStateCompat.STATE_PLAYING, 0L, effectiveStart.toLong())
                )
            }
        }
    }

    // ── Sync état PlayerService → MediaSessionCompat ──────────────────────────

    inner class PlayerStateListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = syncState()
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // Sync les métadonnées à chaque changement de piste
            item?.let { syncMetadata(it) }
            syncState()
        }
        override fun onPlaybackStateChanged(playbackState: Int) = syncState()
    }

    /**
     * FIX BUG #1 : synchronise titre + artiste + artwork vers MediaSessionCompat.
     * Android Auto lit ces métadonnées pour afficher le lecteur.
     * Sans setMetadata(), le lecteur reste vide même si la musique joue.
     */
    private fun syncMetadata(item: MediaItem) {
        val meta = item.mediaMetadata
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, item.mediaId)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                meta.title?.toString() ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST,
                meta.artist?.toString() ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                meta.artworkUri?.toString() ?: "")

        // Durée : disponible uniquement si ExoPlayer l'a chargée
        val ctrl = controller
        if (ctrl != null && ctrl.duration > 0) {
            builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, ctrl.duration)
        }

        mediaSession.setMetadata(builder.build())
    }

    /**
     * Pousse les métadonnées d'un Track directement (avant que le Player
     * ne déclenche onMediaItemTransition), pour un affichage immédiat.
     */
    private fun pushMetadata(track: Track) {
        val artUri = ThumbnailProvider.toContentUri(this, track.thumbnailPath)
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
        artUri?.let {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString())
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val pbState = when {
            ctrl.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            ctrl.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            ctrl.playbackState == Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val activeId = if (ctrl.currentMediaItemIndex >= 0)
            ctrl.currentMediaItemIndex.toLong() else -1L
        mediaSession.setPlaybackState(buildActiveState(pbState, ctrl.currentPosition, activeId))
        ctrl.currentMediaItem?.let { syncMetadata(it) }
    }

    /**
     * Construit un PlaybackState avec :
     * - contrôles standard play/pause/prev/next/seek/stop
     * - ACTION_SKIP_TO_QUEUE_ITEM : tap sur un morceau dans la queue pour y sauter
     * - CustomAction "Quitter" avec icône monochrome path unique (ic_auto_quit)
     * - activeQueueItemId : met en surbrillance le morceau actif dans le panneau queue
     */
    private fun buildActiveState(
        state: Int,
        position: Long,
        activeQueueId: Long = -1L
    ): PlaybackStateCompat {
        val quitAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_QUIT,
            "Quitter",
            R.drawable.ic_auto_quit
        ).build()

        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM
            )
            .addCustomAction(quitAction)
            .setActiveQueueItemId(activeQueueId)
            .setState(state, position, 1f)
            .build()
    }

    private fun buildStoppedState() = PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY)
        .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
        .build()

    // ── Construction des nœuds de navigation ─────────────────────────────────

    private fun buildRoot() = listOf(
        browsable(ARCHIVE_ID,   "Archive",        "Tous les morceaux"),
        browsable(PLAYLISTS_ID, "Playlists",      "Mes listes de lecture"),
        browsable(MEGA_ID,      "Mega Playlists", "Lecture shuffle combinée")
    )

    private suspend fun buildArchive(): List<MediaBrowserCompat.MediaItem> {
        val tracks = trackRepository.getAllTracks().firstOrNull()
            ?.sortedBy { it.title.lowercase() } ?: emptyList()
        return tracks.map { it.toBrowserItem() }
    }

    private suspend fun buildPlaylistList(): List<MediaBrowserCompat.MediaItem> {
        val playlists = playlistRepository.getAllPlaylistsWithTracks().firstOrNull() ?: emptyList()
        return playlists.map { pl ->
            browsable("$PLAYLIST_PREFIX${pl.id}", pl.name, "${pl.tracks.size} morceaux")
        }
    }

    private suspend fun buildMegaList(): List<MediaBrowserCompat.MediaItem> {
        val playlists = playlistRepository.getAllPlaylistsWithTracks().firstOrNull() ?: emptyList()
        val allIds = playlists.map { it.id }.joinToString(",")
        val shuffleAll = if (playlists.size > 1) listOf(
            playableAction(
                "$MEGA_PREFIX${allIds}$SUFFIX_SHUFFLE",
                "Tout shuffler (${playlists.sumOf { it.tracks.size }} morceaux)"
            )
        ) else emptyList()

        return shuffleAll + playlists.map { pl ->
            browsable("$MEGA_PREFIX${pl.id}", pl.name, "Shuffle — ${pl.tracks.size} morceaux")
        }
    }

    private suspend fun buildMegaContent(playlistId: String): List<MediaBrowserCompat.MediaItem> {
        val tracks = playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
            ?.firstOrNull { it.id == playlistId }?.tracks ?: emptyList()
        return listOf(
            playableAction("$MEGA_PREFIX${playlistId}$SUFFIX_SHUFFLE", "Lancer en shuffle")
        ) + tracks.map { it.toBrowserItem() }
    }

    private suspend fun buildPlaylistContent(playlistId: String): List<MediaBrowserCompat.MediaItem> {
        val tracks = playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
            ?.firstOrNull { it.id == playlistId }?.tracks ?: emptyList()
        return listOf(
            playableAction("$PLAYLIST_PREFIX${playlistId}$SUFFIX_NORMAL",  "Lecture normale"),
            playableAction("$PLAYLIST_PREFIX${playlistId}$SUFFIX_SHUFFLE", "Lecture en shuffle")
        ) + tracks.map { it.toBrowserItem() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun browsable(id: String, title: String, subtitle: String) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun playableAction(id: String, title: String) =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )

    private fun Track.toBrowserItem(): MediaBrowserCompat.MediaItem {
        val artUri = ThumbnailProvider.toContentUri(this@AutoMediaBrowserService, thumbnailPath)
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id)
                .setTitle(title)
                .setSubtitle(artist)
                .apply {
                    language?.let { setDescription(it) }
                    artUri?.let { setIconUri(it) }
                    setMediaUri(android.net.Uri.parse(filePath))
                }
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    /**
     * FIX BUG #3 : inclure l'artworkUri dans le MediaItem envoyé à ExoPlayer.
     * Sans ça, PlayerStateListener.syncMetadata() ne peut pas récupérer
     * l'artwork depuis item.mediaMetadata.artworkUri → miniature absente dans Auto.
     */
    private fun Track.toMedia3Item(): MediaItem {
        val artUri = ThumbnailProvider.toContentUri(this@AutoMediaBrowserService, thumbnailPath)
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(filePath)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .apply { artUri?.let { setArtworkUri(it) } }
                    .build()
            )
            .build()
    }
}