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
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommands
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
            settingsRepository.getNormalizationEnabled().collect { enabled: Boolean ->
                loudnessEnhancer?.enabled = enabled
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = mediaSession

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
            val cmds = Player.Commands.Builder().addAllCommands().build()
            return MediaSession.ConnectionResult.accept(SessionCommands.EMPTY, cmds)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller:   MediaSession.ControllerInfo,
            mediaItems:   List<MediaItem>
        ): com.google.common.util.concurrent.ListenableFuture<List<MediaItem>> {
            val future = com.google.common.util.concurrent.SettableFuture.create<List<MediaItem>>()
            ioScope.launch {
                try {
                    val allTracks: List<Track> =
                        trackRepository.getAllTracks().firstOrNull() ?: emptyList()
                    val resolved = mediaItems.mapNotNull { item: MediaItem ->
                        allTracks.firstOrNull { t -> t.id == item.mediaId }?.toMediaItem()
                            ?: item.requestMetadata.mediaUri?.let { uri ->
                                item.buildUpon().setUri(uri).build()
                            }
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
        ): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = com.google.common.util.concurrent.SettableFuture
                .create<MediaSession.MediaItemsWithStartPosition>()
            ioScope.launch {
                try {
                    val tracks: List<Track> =
                        trackRepository.getAllTracks().firstOrNull() ?: emptyList()
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
