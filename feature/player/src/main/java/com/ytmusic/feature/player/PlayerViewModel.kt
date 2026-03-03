package com.ytmusic.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytmusic.core.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController
) : ViewModel() {

    val playbackState: StateFlow<PlaybackState> = playerController.playbackState

    init {
        viewModelScope.launch { playerController.connect() }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, playlistId: String? = null) =
        playerController.playTracks(tracks, startIndex, playlistId)

    fun play()              = playerController.play()
    fun pause()             = playerController.pause()
    fun next()              = playerController.skipToNext()
    fun prev()              = playerController.skipToPrev()
    fun toggleShuffle()     = playerController.toggleShuffle()
    fun setRepeatMode(mode: RepeatMode) = playerController.setRepeatMode(mode)
    fun seekTo(posMs: Long) = playerController.seekTo(posMs)
    fun skipToIndex(index: Int)         = playerController.skipToIndex(index)
    fun getMediaController()            = playerController.getMediaController()
    fun removeFromQueue(index: Int)     = playerController.removeFromQueue(index)
    fun clearQueue()                    = playerController.clearQueue()

    override fun onCleared() { playerController.disconnect() }
}
