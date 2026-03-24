package com.ytmusic.feature.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.ytmusic.core.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerController @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var mediaController: MediaController? = null

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var trackCache: List<Track> = emptyList()
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionTickerJob: Job? = null

    fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, PlayerService::class.java))
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener({
            try {
                mediaController = future.get()
                mediaController?.addListener(createListener())
                emitCurrentState()
                startPositionTicker()
            } catch (e: Exception) { e.printStackTrace() }
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        positionTickerJob?.cancel()
        controllerScope.cancel()
        mediaController?.release()
    }

    // Ticker toutes les 500ms pour mettre à jour la position en cours de lecture
    private fun startPositionTicker() {
        positionTickerJob?.cancel()
        positionTickerJob = controllerScope.launch {
            while (true) {
                delay(500)
                val ctrl = mediaController ?: continue
                if (ctrl.isPlaying) {
                    _playbackState.value = _playbackState.value.copy(
                        positionMs = ctrl.currentPosition
                    )
                }
            }
        }
    }

    fun play()  = mediaController?.play()
    fun pause() = mediaController?.pause()

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, playlistId: String? = null) {
        trackCache = tracks
        val items = tracks.map { it.toMediaItem(context, playlistId) }
        mediaController?.apply {
            setMediaItems(items, startIndex, C.TIME_UNSET)
            prepare()
            play()
        }
    }

    fun removeFromQueue(index: Int) {
        val ctrl = mediaController ?: return
        if (index < 0 || index >= trackCache.size) return

        // Supprimer de la timeline ExoPlayer
        ctrl.removeMediaItem(index)

        // Mettre à jour le cache local
        val mutable = trackCache.toMutableList()
        mutable.removeAt(index)
        trackCache = mutable

        // Note : si index < currentMediaItemIndex, ExoPlayer décale automatiquement
        // son curseur interne vers le bas. trackCache étant maintenant synchrone avec
        // la nouvelle timeline, emitCurrentState() lira le bon track depuis le bon index.
        // Si on supprime le morceau courant, ExoPlayer passe au suivant automatiquement.
        emitCurrentState()
    }

    fun clearQueue() {
        mediaController?.clearMediaItems()
        trackCache = emptyList()
        _playbackState.value = PlaybackState()
    }

    fun skipToNext()              = mediaController?.seekToNextMediaItem()
    fun skipToPrev()              = mediaController?.seekToPreviousMediaItem()
    fun skipToIndex(index: Int)   = mediaController?.seekTo(index, 0)
    fun seekTo(positionMs: Long)  = mediaController?.seekTo(positionMs)
    fun getMediaController()      = mediaController

    fun toggleShuffle() {
        val ctrl = mediaController ?: return
        if (_playbackState.value.shuffleEnabled) {
            // Désactiver : juste mettre à jour le flag, on garde l'ordre actuel
            _playbackState.value = _playbackState.value.copy(shuffleEnabled = false)
        } else {
            // Activer : piste courante en 0, reste mélangé — sans relancer la musique
            val current     = _playbackState.value.currentTrack ?: return
            val savedPos    = ctrl.currentPosition       // position exacte en ms
            val wasPlaying  = ctrl.isPlaying
            val others      = trackCache.filter { it.id != current.id }.shuffled()
            val newQueue    = listOf(current) + others
            trackCache = newQueue
            val items = newQueue.map { it.toMediaItem(context) }
            // Remplacer les items en conservant la position
            ctrl.setMediaItems(items, 0, savedPos)
            ctrl.prepare()
            if (wasPlaying) ctrl.play() else ctrl.pause()
            _playbackState.value = _playbackState.value.copy(
                queue          = newQueue,
                currentTrack   = current,
                shuffleEnabled = true
            )
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        mediaController?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    private fun createListener() = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int)                                                        = emitCurrentState()
        override fun onIsPlayingChanged(isPlaying: Boolean)                                                    = emitCurrentState()
        override fun onMediaItemTransition(item: MediaItem?, reason: Int)                                      = emitCurrentState()
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean)                                  = emitCurrentState()
        override fun onRepeatModeChanged(repeatMode: Int)                                                      = emitCurrentState()
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) = emitCurrentState()
        override fun onTimelineChanged(timeline: Timeline, reason: Int)                                        = emitCurrentState()
    }

    private fun emitCurrentState() {
        val ctrl = mediaController ?: return
        // Reconstruire le cache depuis la timeline si elle a changé
        if (ctrl.mediaItemCount != trackCache.size) {
            // La timeline a changé (shuffle réorganise) — on garde trackCache original
            // mais on met à jour currentTrack depuis l'index courant
        }
        val currentIdx   = ctrl.currentMediaItemIndex
        val currentTrack = trackCache.getOrNull(currentIdx)
        _playbackState.value = PlaybackState(
            currentTrack   = currentTrack,
            queue          = trackCache,
            isPlaying      = ctrl.isPlaying,
            positionMs     = ctrl.currentPosition,
            shuffleEnabled = ctrl.shuffleModeEnabled,
            repeatMode     = ctrl.repeatMode.toDomainRepeatMode()
        )
    }

    private fun Int.toDomainRepeatMode() = when (this) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else                   -> RepeatMode.OFF
    }
}

@OptIn(UnstableApi::class)
fun Track.toMediaItem(context: android.content.Context? = null, playlistId: String? = null): MediaItem {
    val extras = Bundle().apply { playlistId?.let { putString("playlistId", it) } }
    val clippingConfig = MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(trimStartMs)
        .apply { trimEndMs?.let { setEndPositionMs(it) } }
        .build()
    // Artwork : content:// via FileProvider si context dispo, sinon pas d'artwork
    // (file:// est inaccessible à SystemUI et Android Auto → FileNotFoundException en boucle)
    val artworkUri = context?.let { ThumbnailProvider.toContentUri(it, thumbnailPath) }
    return MediaItem.Builder()
        .setMediaId(id)
        .setUri(filePath)
        .setClippingConfiguration(clippingConfig)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setExtras(extras)
                .apply { artworkUri?.let { setArtworkUri(it) } }
                .build()
        )
        .setRequestMetadata(
            MediaItem.RequestMetadata.Builder()
                .setMediaUri(Uri.parse(filePath))
                .build()
        )
        .build()
}