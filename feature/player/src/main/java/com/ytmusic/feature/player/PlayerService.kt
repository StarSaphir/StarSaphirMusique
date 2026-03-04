package com.ytmusic.feature.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.audiofx.LoudnessEnhancer
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

        mainScope.launch {
            settingsRepository.getNormalizationEnabled().collect { enabled ->
                loudnessEnhancer?.enabled = enabled
            }
        }
    }

    // Retourne null pour Android Auto (gearhead) : il doit se connecter
    // à AutoMediaBrowserService à la place (ancienne API MediaBrowserServiceCompat).
    // Tous les autres clients reçoivent la session Media3 normale.
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        val isAndroidAuto = controllerInfo.packageName == "com.google.android.projection.gearhead"
        return if (isAndroidAuto) null else mediaSession
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
        private var sessionStartMs = 0L
        private var lastTrackId: String? = null

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) sessionStartMs = System.currentTimeMillis() else flushSession()
        }

        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            flushSession()
            item?.let {
                lastTrackId    = it.mediaId
                sessionStartMs = System.currentTimeMillis()
                statsTracker.onTrackStarted(
                    it.mediaId,
                    it.mediaMetadata.extras?.getString("playlistId")
                )
            }
        }

        private fun flushSession() {
            val elapsed = System.currentTimeMillis() - sessionStartMs
            if (elapsed > 2000 && lastTrackId != null)
                mainScope.launch { statsTracker.recordListenTime(lastTrackId!!, elapsed) }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        }
    }

    // ── Helpers système ───────────────────────────────────────────────────────

    private fun setupLoudnessEnhancer() {
        try {
            loudnessEnhancer = LoudnessEnhancer(player.audioSessionId).apply {
                setTargetGain(500); enabled = false
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