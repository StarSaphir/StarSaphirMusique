package com.ytmusic.feature.player

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
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
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AutoMediaBrowserService").apply {
            // ⚠️ CRITIQUE : le callback doit être défini AVANT setActive(true)
            // C'est ici qu'Android Auto envoie les commandes de lecture
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
            } catch (_: Exception) {
                // PlayerService pas encore démarré — sera reconnecté à la prochaine lecture
            }
        }, MoreExecutors.directExecutor())
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        val ctrl = controller
        if (ctrl != null && ctrl.isConnected) {
            onReady(ctrl)
            return
        }
        // Reconnexion si nécessaire
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

    // ── MediaSession Callback — reçoit les commandes d'Android Auto ───────────

    inner class MediaSessionCallback : MediaSessionCompat.Callback() {

        // ⚠️ CRITIQUE : c'est cette méthode qu'Android Auto appelle quand
        // l'utilisateur tape sur un item playable. Sans elle → chargement infini.
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
        }

        override fun onSeekTo(pos: Long) {
            ensureController { it.seekTo(pos) }
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

    private fun sendToPlayer(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        ensureController { ctrl ->
            scope.launch(Dispatchers.Main) {
                ctrl.stop()
                ctrl.clearMediaItems()
                ctrl.setMediaItems(tracks.map { it.toMedia3Item() }, startIndex, 0L)
                ctrl.shuffleModeEnabled = shuffle
                ctrl.prepare()
                ctrl.play()
            }
        }
    }

    // ── Sync état PlayerService → MediaSessionCompat ──────────────────────────

    inner class PlayerStateListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = syncState()
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) = syncState()
        override fun onPlaybackStateChanged(playbackState: Int) = syncState()
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val pbState = when {
            ctrl.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            ctrl.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_STOP
                )
                .setState(pbState, ctrl.currentPosition, 1f)
                .build()
        )
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

    /**
     * Mega Playlists : chaque playlist est affichée avec un bouton shuffle direct.
     * Android Auto ne supporte pas la sélection multiple native, donc chaque
     * playlist Mega a son propre bouton "Lancer en shuffle" qui combine
     * toutes les playlists sélectionnées via l'ID concaténé.
     *
     * Pour combiner plusieurs playlists : l'ID du dossier Mega contient
     * les IDs séparés par virgule (ex: "AUTO_MEGA_id1,id2").
     * L'utilisateur navigue dans une playlist → voit le bouton shuffle
     * qui lancera cette playlist + toutes les autres en shuffle combiné.
     */
    private suspend fun buildMegaList(): List<MediaBrowserCompat.MediaItem> {
        val playlists = playlistRepository.getAllPlaylistsWithTracks().firstOrNull() ?: emptyList()
        // Ajouter un item "Tout shuffler" qui combine toutes les playlists
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

    private fun Track.toMedia3Item() = MediaItem.Builder()
        .setMediaId(id)
        .setUri(filePath)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build()
        )
        .build()
}