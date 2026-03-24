package com.ytmusic.feature.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
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
        const val ROOT_ID           = "AUTO_ROOT"
        const val ARCHIVE_ID        = "AUTO_ARCHIVE"
        const val PLAYLISTS_ID      = "AUTO_PLAYLISTS"
        const val MEGA_ID           = "AUTO_MEGA"
        const val PLAYLIST_PREFIX   = "AUTO_PL_"
        const val MEGA_PREFIX       = "AUTO_MEGA_"
        const val SUFFIX_NORMAL     = "__NORMAL"
        const val SUFFIX_SHUFFLE    = "__SHUFFLE"

        // Super-playlists automatiques par artiste et par langue
        const val AUTO_ARTIST_ID    = "AUTO_BY_ARTIST"
        const val AUTO_LANG_ID      = "AUTO_BY_LANG"
        const val ARTIST_PREFIX     = "AUTO_ARTIST_"
        const val LANG_PREFIX       = "AUTO_LANG_"

        const val ACTION_QUIT       = "ACTION_QUIT_PLAYLIST"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()

        mediaSession = MediaSessionCompat(this, "AutoMediaBrowserService").apply {
            setCallback(MediaSessionCallback())
            setPlaybackState(buildStoppedState())

            // Hints pour Android Auto : activer le layout "Now Playing" avec artwork plein écran
            val sessionExtras = Bundle().apply {
                // Demande à Auto d'afficher l'artwork en plein écran dans le lecteur
                putBoolean("android.media.browse.SHOW_QUEUE_HINT", true)
                // Style d'affichage : CATEGORY_STATUS (layout immersif avec artwork)
                putInt("com.google.android.gms.car.media.CAR_MEDIA_BROWSE_STYLE", 1)
            }
            setExtras(sessionExtras)
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
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d("AutoBrowser", "onLoadChildren: parentId=$parentId")
        result.detach()
        scope.launch {
            val items = when {
                parentId == ROOT_ID                      -> buildRoot()
                parentId == ARCHIVE_ID                   -> buildArchive()
                parentId == PLAYLISTS_ID                 -> buildPlaylistList()
                parentId == MEGA_ID                      -> buildMegaList()
                parentId == AUTO_ARTIST_ID               -> buildAutoArtistList()
                parentId == AUTO_LANG_ID                 -> buildAutoLangList()
                parentId.startsWith(ARTIST_PREFIX)       -> buildAutoGroupContent(
                    parentId.removePrefix(ARTIST_PREFIX), groupByArtist = true)
                parentId.startsWith(LANG_PREFIX)         -> buildAutoGroupContent(
                    parentId.removePrefix(LANG_PREFIX), groupByArtist = false)
                parentId.startsWith(PLAYLIST_PREFIX)     ->
                    buildPlaylistContent(parentId.removePrefix(PLAYLIST_PREFIX))
                parentId.startsWith(MEGA_PREFIX)         ->
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
        (mediaId.endsWith(SUFFIX_NORMAL) || mediaId.endsWith(SUFFIX_SHUFFLE))
                && !mediaId.startsWith("EMPTY_")

    private suspend fun launchPlaylistAction(mediaId: String) {
        val shuffle = mediaId.endsWith(SUFFIX_SHUFFLE)
        val allTracks = trackRepository.getAllTracks().firstOrNull() ?: emptyList()

        val tracks: List<Track> = when {
            // Super-playlist par artiste
            mediaId.startsWith(ARTIST_PREFIX) -> {
                val artist = mediaId.removePrefix(ARTIST_PREFIX)
                    .removeSuffix(SUFFIX_NORMAL).removeSuffix(SUFFIX_SHUFFLE)
                allTracks.filter { it.artist.ifBlank { "Inconnu" } == artist }
            }
            // Super-playlist par langue
            mediaId.startsWith(LANG_PREFIX) -> {
                val lang = mediaId.removePrefix(LANG_PREFIX)
                    .removeSuffix(SUFFIX_NORMAL).removeSuffix(SUFFIX_SHUFFLE)
                allTracks.filter { it.language == lang }
            }
            // Playlist manuelle
            mediaId.startsWith(PLAYLIST_PREFIX) -> {
                val id = mediaId.removePrefix(PLAYLIST_PREFIX)
                    .removeSuffix(SUFFIX_NORMAL).removeSuffix(SUFFIX_SHUFFLE)
                playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
                    ?.firstOrNull { it.id == id }?.tracks ?: emptyList()
            }
            // Mega playlist
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
    private fun sendToPlayer(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        ensureController { ctrl ->
            scope.launch(Dispatchers.Main) {
                ctrl.stop()
                ctrl.clearMediaItems()
                ctrl.setMediaItems(tracks.map { it.toMedia3Item() }, startIndex, 0L)
                ctrl.shuffleModeEnabled = shuffle
                ctrl.prepare()
                ctrl.play()

                // ── FIX BUG #1 : envoyer les métadonnées du morceau courant ──
                // Sans ça Android Auto affiche un lecteur vide.
                // On utilise le morceau à startIndex comme métadonnées initiales
                // avant que PlayerStateListener.syncState() prenne le relais.
                val firstTrack = if (shuffle) tracks.firstOrNull() else tracks.getOrNull(startIndex)
                firstTrack?.let { pushMetadata(it) }

                // ── FIX BUG #6 : basculer Auto vers la vue lecteur ──
                // Envoyer STATE_PLAYING force Android Auto à quitter la navigation
                // et ouvrir le lecteur. Il n'y retourne que sur STATE_NONE/STOPPED.
                mediaSession.setPlaybackState(buildActiveState(PlaybackStateCompat.STATE_PLAYING, 0L))
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

        // Lire la durée depuis controller sur le Main thread AVANT de lancer IO
        val duration = try { controller?.takeIf { it.duration > 0 }?.duration } catch (_: Exception) { null }
        if (duration != null) builder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)

        // Charger le bitmap en IO (lecture disque), puis poster setMetadata sur Main
        scope.launch(Dispatchers.IO) {
            val track = trackRepository.getTrackById(item.mediaId)
            val bitmap = loadAlbumArtBitmap(track?.thumbnailPath)
            if (bitmap != null) {
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
                builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
            }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                mediaSession.setMetadata(builder.build())
            }
        }
    }

    /**
     * Pousse les métadonnées d'un Track directement (avant que le Player
     * ne déclenche onMediaItemTransition), pour un affichage immédiat.
     *
     * On charge le Bitmap depuis le fichier local pour que gearhead
     * puisse l'afficher en plein écran dans le lecteur Auto.
     * METADATA_KEY_ALBUM_ART (Bitmap) a priorité sur METADATA_KEY_ALBUM_ART_URI.
     */
    private fun pushMetadata(track: Track) {
        val artUri = ThumbnailProvider.toContentUri(this, track.thumbnailPath)
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, track.id)
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)

        // Charger le bitmap depuis le fichier
        // ALBUM_ART = artwork standard ; DISPLAY_ICON = fond plein écran dans gearhead
        val bitmap = loadAlbumArtBitmap(track.thumbnailPath)
        if (bitmap != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, bitmap)
        }
        artUri?.let {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString())
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, it.toString())
        }
        mediaSession.setMetadata(builder.build())
    }

    /**
     * Charge le bitmap de la miniature depuis le disque.
     * Redimensionné à 800×800 max pour éviter les TransactionTooLargeException
     * lors du passage via Binder vers gearhead.
     */
    private fun loadAlbumArtBitmap(thumbnailPath: String?): Bitmap? {
        if (thumbnailPath == null) return null
        return try {
            val opts = BitmapFactory.Options().apply {
                // Première passe : lire les dimensions sans décoder
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(thumbnailPath, this)
                // Calculer le sous-échantillonnage pour rester sous 800px
                val maxSize = 800
                inSampleSize = maxOf(1, maxOf(outWidth, outHeight) / maxSize)
                inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(thumbnailPath, opts)
        } catch (_: Exception) { null }
    }

    private fun syncState() {
        val ctrl = controller ?: return
        val pbState = when {
            ctrl.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            ctrl.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            ctrl.playbackState == Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        mediaSession.setPlaybackState(buildActiveState(pbState, ctrl.currentPosition))

        // Sync métadonnées si une piste est active
        ctrl.currentMediaItem?.let { syncMetadata(it) }
    }

    /**
     * Construit un PlaybackState avec les contrôles standard (play/pause/prev/next/seek)
     * ET le bouton "Quitter" comme CustomAction.
     * Android Auto affiche les CustomActions dans la barre de contrôle du lecteur,
     * à côté des boutons media standards.
     * onCustomAction("ACTION_QUIT_PLAYLIST") est déclenché quand l'utilisateur appuie dessus.
     */
    private fun buildActiveState(state: Int, position: Long): PlaybackStateCompat {
        val quitAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_QUIT,
            "Quitter",
            android.R.drawable.ic_menu_close_clear_cancel
        ).build()

        return PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
            )
            .addCustomAction(quitAction)
            .setState(state, position, 1f)
            .build()
    }

    private fun buildStoppedState() = PlaybackStateCompat.Builder()
        .setActions(PlaybackStateCompat.ACTION_PLAY)
        .setState(PlaybackStateCompat.STATE_NONE, 0, 1f)
        .build()

    // ── Construction des nœuds de navigation ─────────────────────────────────

    private fun buildRoot(): List<MediaBrowserCompat.MediaItem> {
        Log.d("AutoBrowser", "buildRoot() called")
        return listOf(
            browsable(PLAYLISTS_ID,  "Playlists",          "Mes listes de lecture"),
            browsable(AUTO_ARTIST_ID,"Par artiste",        "Super-playlists automatiques"),
            browsable(AUTO_LANG_ID,  "Par langue",         "Super-playlists automatiques"),
            browsable(ARCHIVE_ID,    "Archive",            "Tous les morceaux"),
            browsable(MEGA_ID,       "Mega Playlists",     "Lecture shuffle combinée")
        )
    }

    private suspend fun buildArchive(): List<MediaBrowserCompat.MediaItem> {
        val tracks = trackRepository.getAllTracks().firstOrNull()
            ?.sortedBy { it.title.lowercase() } ?: emptyList()
        return tracks.map { it.toBrowserItem() }
    }

    /** Liste des groupes "par artiste" — un dossier par artiste */
    private suspend fun buildAutoArtistList(): List<MediaBrowserCompat.MediaItem> {
        val tracks = trackRepository.getAllTracks().firstOrNull() ?: emptyList()
        Log.d("AutoBrowser", "buildAutoArtistList: ${tracks.size} tracks total")
        val groups = tracks
            .groupBy { it.artist.ifBlank { "Inconnu" } }
            .filter { (_, v) -> v.size >= 2 }
        Log.d("AutoBrowser", "buildAutoArtistList: ${groups.size} artist groups")
        return groups.entries.sortedBy { it.key.lowercase() }
            .map { (artist, artistTracks) ->
                browsable("$ARTIST_PREFIX$artist", artist, "${artistTracks.size} morceaux")
            }
    }

    /** Liste des groupes "par langue" — un dossier par langue */
    private suspend fun buildAutoLangList(): List<MediaBrowserCompat.MediaItem> {
        val tracks = trackRepository.getAllTracks().firstOrNull() ?: emptyList()
        return tracks
            .filter { !it.language.isNullOrBlank() }
            .groupBy { it.language!! }
            .entries.sortedBy { it.key.lowercase() }
            .map { (lang, langTracks) ->
                browsable("$LANG_PREFIX$lang", lang, "${langTracks.size} morceaux")
            }
    }

    /**
     * Contenu d'un groupe auto (artiste ou langue) :
     * - bouton "Lire en ordre"
     * - bouton "Lire en shuffle"
     * - liste des morceaux
     */
    private suspend fun buildAutoGroupContent(
        groupKey:     String,
        groupByArtist: Boolean
    ): List<MediaBrowserCompat.MediaItem> {
        val allTracks = trackRepository.getAllTracks().firstOrNull() ?: emptyList()
        val tracks = if (groupByArtist) {
            allTracks.filter { it.artist.ifBlank { "Inconnu" } == groupKey }
        } else {
            allTracks.filter { it.language == groupKey }
        }
        if (tracks.isEmpty()) return emptyList()

        val prefix = if (groupByArtist) ARTIST_PREFIX else LANG_PREFIX
        return listOf(
            playableAction("$prefix${groupKey}$SUFFIX_NORMAL",  "Lecture en ordre"),
            playableAction("$prefix${groupKey}$SUFFIX_SHUFFLE", "Lecture en shuffle")
        ) + tracks.sortedBy { it.title.lowercase() }.map { it.toBrowserItem() }
    }

    private suspend fun buildPlaylistList(): List<MediaBrowserCompat.MediaItem> {
        val playlists = playlistRepository.getAllPlaylistsWithTracks().firstOrNull() ?: emptyList()
        if (playlists.isEmpty()) {
            // Afficher un item informatif si aucune playlist n'existe
            return listOf(
                playableAction("EMPTY_PLAYLISTS", "Aucune playlist — créez-en une dans l'app")
            )
        }
        return playlists.map { pl ->
            val subtitle = if (pl.tracks.isEmpty()) "Vide" else "${pl.tracks.size} morceau(x)"
            browsable("$PLAYLIST_PREFIX${pl.id}", pl.name, subtitle)
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
        if (tracks.isEmpty()) return listOf(
            playableAction("EMPTY_PL_$playlistId", "Playlist vide")
        )
        return listOf(
            playableAction("$PLAYLIST_PREFIX${playlistId}$SUFFIX_NORMAL",  "▶ Lire en ordre (${tracks.size})"),
            playableAction("$PLAYLIST_PREFIX${playlistId}$SUFFIX_SHUFFLE", "🔀 Lecture shuffle")
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