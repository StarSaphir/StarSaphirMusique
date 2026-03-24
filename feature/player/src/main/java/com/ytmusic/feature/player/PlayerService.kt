package com.ytmusic.feature.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.audiofx.LoudnessEnhancer
import android.os.PowerManager
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.repository.PlaylistRepository
import com.ytmusic.core.domain.repository.SettingsRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val CMD_TOGGLE_SHUFFLE = "CMD_TOGGLE_SHUFFLE"
private const val CMD_QUIT_PLAYLIST  = "CMD_QUIT_PLAYLIST"

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaLibraryService() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var statsTracker:       StatsTracker
    @Inject lateinit var trackRepository:    TrackRepository
    @Inject lateinit var playlistRepository: PlaylistRepository

    private lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var normalizationEnabled: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val ioScope   = CoroutineScope(Dispatchers.IO   + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        const val CHANNEL_ID = "player_channel"
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback()).build()

        player.addListener(createPlayerListener())
        setupLoudnessEnhancer()
        // Observer le setting normalisation pour activer/désactiver en temps réel
        mainScope.launch {
            settingsRepository.getNormalizationEnabled().collect { enabled ->
                normalizationEnabled = enabled
                if (!enabled) loudnessEnhancer?.enabled = false
            }
        }

        // Observer keepScreenOn — WakeLock géré ici pour fonctionner
        // même quand le lecteur tourne en arrière-plan
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "YTMusicApp:PlayerWakeLock"
        )
        mainScope.launch {
            settingsRepository.getKeepScreenOn().collect { enabled ->
                if (enabled) {
                    if (wakeLock?.isHeld == false) wakeLock?.acquire(4 * 60 * 60 * 1000L) // max 4h
                } else {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                }
            }
        }
    }

    // FIX BUG #5 : on accepte tous les clients sauf gearhead en connexion DIRECTE.
    // AutoMediaBrowserService se connecte avec son propre package → il reçoit la session.
    // gearhead (Android Auto) en connexion directe est rejeté → il utilisera
    // AutoMediaBrowserService via l'intent MediaBrowserService du Manifest.
    // Note : on vérifie aussi com.google.android.projection.gearhead pour les Automotive OS.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val pkg = controllerInfo.packageName
        val isDirectAutoConnection = pkg == "com.google.android.projection.gearhead"
                || pkg == "com.google.android.carassistant"
        return if (isDirectAutoConnection) null else mediaSession
    }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        mainScope.launch { PlaybackStateManager.saveState(this@PlayerService, player, null) }
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        ioScope.cancel()
        mainScope.cancel()
        mediaSession?.release()
        loudnessEnhancer?.release()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        player.release()
        super.onDestroy()
    }

    // ── MediaLibrarySession.Callback ──────────────────────────────────────────

    inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(
            session:    MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCmds = SessionCommands.Builder()
                .add(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
                .add(SessionCommand(CMD_QUIT_PLAYLIST,  Bundle.EMPTY))
                .build()
            val playerCmds = Player.Commands.Builder().addAllCommands().build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCmds)
                .setAvailablePlayerCommands(playerCmds)
                .setCustomLayout(buildCustomLayout(player.shuffleModeEnabled))
                .build()
        }

        override fun onCustomCommand(
            session:       MediaSession,
            controller:    MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args:          Bundle
        ): ListenableFuture<SessionResult> = when (customCommand.customAction) {
            CMD_TOGGLE_SHUFFLE -> {
                val newShuffle = !player.shuffleModeEnabled
                player.shuffleModeEnabled = newShuffle
                mediaSession?.setCustomLayout(buildCustomLayout(newShuffle))
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            CMD_QUIT_PLAYLIST -> {
                player.stop()
                player.clearMediaItems()
                Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            else -> super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller:   MediaSession.ControllerInfo,
            mediaItems:   List<MediaItem>
        ): ListenableFuture<List<MediaItem>> {
            val future = SettableFuture.create<List<MediaItem>>()
            ioScope.launch {
                try {
                    val resolved = mediaItems.mapNotNull { item ->
                        if (item.localConfiguration != null) return@mapNotNull item
                        trackRepository.getTrackById(item.mediaId)?.toMediaItem()
                            ?: item.requestMetadata.mediaUri
                                ?.let { item.buildUpon().setUri(it).build() }
                    }
                    future.set(resolved)
                } catch (e: CancellationException) {
                    future.cancel(false)
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller:   MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            ioScope.launch {
                try {
                    val tracks = trackRepository.getAllTracks().firstOrNull() ?: emptyList()
                    future.set(
                        MediaSession.MediaItemsWithStartPosition(
                            tracks.map { it.toMediaItem() }, 0, C.TIME_UNSET
                        )
                    )
                } catch (e: CancellationException) {
                    future.cancel(false)
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    // ── Track → MediaItem ─────────────────────────────────────────────────────

    internal fun Track.toMediaItem(): MediaItem {
        val art = ThumbnailProvider.toContentUri(this@PlayerService, thumbnailPath)
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(filePath)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(language?.let { "[$it]" } ?: "")
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                    .apply { art?.let { setArtworkUri(it) } }
                    .build()
            )
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(Uri.parse(filePath))
                    .build()
            )
            .build()
    }

    // ── Helpers de construction des boutons custom ────────────────────────────

    private fun buildCustomLayout(shuffleEnabled: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .setDisplayName(if (shuffleEnabled) "Shuffle ON" else "Shuffle OFF")
            .setIconResId(R.drawable.ic_shuffle)
            .build(),
        CommandButton.Builder()
            .setSessionCommand(SessionCommand(CMD_QUIT_PLAYLIST, Bundle.EMPTY))
            .setDisplayName("Quitter")
            .setIconResId(R.drawable.ic_close)
            .build()
    )

    // ── Player listener ───────────────────────────────────────────────────────

    private fun createPlayerListener() = object : Player.Listener {
        /**
         * Stratégie : on accumule le temps écouté en segments.
         * Un segment = période entre un "start" et un "stop" sur le MÊME morceau.
         *
         * lastTrackId     = ID du morceau actuellement suivi
         * segmentStartMs  = timestamp du début du segment actif (0 si pas de segment)
         * accumulatedMs   = temps déjà accumulé pour ce morceau (segments précédents)
         *
         * onIsPlayingChanged(true)  → ouvre un segment
         * onIsPlayingChanged(false) → ferme le segment, accumule
         * onMediaItemTransition     → flush + reset pour le nouveau morceau
         */
        private var lastTrackId:    String? = null
        private var segmentStartMs: Long    = 0L
        private var accumulatedMs:  Long    = 0L
        private var segmentActive:  Boolean = false

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                // Ouvrir un segment si on a un morceau en cours
                if (lastTrackId != null) {
                    segmentStartMs = System.currentTimeMillis()
                    segmentActive  = true
                }
            } else {
                // Fermer le segment et accumuler
                closeSegment()
            }
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            // Fermer le segment du morceau précédent
            closeSegment()
            // Flush le temps accumulé sur l'ancien morceau
            flushAccumulated()

            // Initialiser pour le nouveau morceau
            lastTrackId   = item?.mediaId
            accumulatedMs = 0L
            segmentActive = false

            item?.let {
                statsTracker.onTrackStarted(
                    it.mediaId,
                    it.mediaMetadata.extras?.getString("playlistId")
                )
                // Ouvrir un segment immédiatement si la lecture est active
                if (player.isPlaying) {
                    segmentStartMs = System.currentTimeMillis()
                    segmentActive  = true
                }
                // Normalisation par morceau
                if (normalizationEnabled) {
                    mainScope.launch {
                        val track  = trackRepository.getTrackById(it.mediaId)
                        val gainDb = track?.replayGainDb ?: 0f
                        val gainMb = (gainDb * 100).toInt()
                        if (gainMb > 0) {
                            loudnessEnhancer?.setTargetGain(gainMb)
                            loudnessEnhancer?.enabled = true
                        } else {
                            loudnessEnhancer?.enabled = false
                        }
                    }
                }
            }
        }

        /** Ferme le segment actif et ajoute la durée à accumulatedMs */
        private fun closeSegment() {
            if (!segmentActive || segmentStartMs == 0L) return
            accumulatedMs += System.currentTimeMillis() - segmentStartMs
            segmentStartMs = 0L
            segmentActive  = false
        }

        /** Enregistre le temps accumulé en base si suffisant */
        private fun flushAccumulated() {
            val id = lastTrackId ?: return
            if (accumulatedMs < 3_000L) return        // skip trop court
            if (accumulatedMs > 2 * 60 * 60 * 1000L) return  // valeur aberrante
            val ms = accumulatedMs
            accumulatedMs = 0L
            mainScope.launch { statsTracker.recordListenTime(id, ms) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
    }

    // ── Helpers système ───────────────────────────────────────────────────────

    private fun setupLoudnessEnhancer() {
        try {
            loudnessEnhancer = LoudnessEnhancer(player.audioSessionId).apply {
                setTargetGain(0); enabled = false   // le gain est défini par morceau
            }
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Lecture musicale", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Contrôles de lecture" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}