package com.ytmusic.feature.player

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.ytmusic.core.domain.repository.StatsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

// ─── StatsTracker ─────────────────────────────────────────────────────────────

@Singleton
class StatsTracker @Inject constructor(
    private val statsRepository: StatsRepository
) {
    private var currentTrackId: String? = null
    private var currentPlaylistId: String? = null

    fun onTrackStarted(trackId: String, playlistId: String?) {
        currentTrackId = trackId
        currentPlaylistId = playlistId
    }

    suspend fun recordListenTime(trackId: String, elapsedMs: Long) {
        statsRepository.recordSession(
            trackId    = trackId,
            playlistId = currentPlaylistId,
            listenedMs = elapsedMs
        )
    }
}

// ─── PlaybackStateManager (DataStore persistence) ─────────────────────────────

private val Context.playbackDataStore by preferencesDataStore("playback_state")

data class PersistedPlaybackState(
    val queue: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val currentPlaylistId: String? = null
)

object PlaybackStateManager {

    private val KEY_QUEUE       = stringPreferencesKey("queue")
    private val KEY_INDEX       = intPreferencesKey("index")
    private val KEY_POSITION    = longPreferencesKey("position")
    private val KEY_SHUFFLE     = booleanPreferencesKey("shuffle")
    private val KEY_REPEAT      = intPreferencesKey("repeat")
    private val KEY_PLAYLIST_ID = stringPreferencesKey("playlist_id")

    suspend fun saveState(context: Context, player: ExoPlayer, currentPlaylistId: String?) {
        val queueIds = (0 until player.mediaItemCount).map {
            player.getMediaItemAt(it).mediaId
        }
        context.playbackDataStore.edit { prefs ->
            prefs[KEY_QUEUE]       = queueIds.joinToString(",")
            prefs[KEY_INDEX]       = player.currentMediaItemIndex
            prefs[KEY_POSITION]    = player.currentPosition
            prefs[KEY_SHUFFLE]     = player.shuffleModeEnabled
            prefs[KEY_REPEAT]      = player.repeatMode
            prefs[KEY_PLAYLIST_ID] = currentPlaylistId ?: ""
        }
    }

    suspend fun restoreState(context: Context): PersistedPlaybackState? {
        return try {
            val prefs = context.playbackDataStore.data.first()
            val queueStr = prefs[KEY_QUEUE] ?: return null
            val queue = queueStr.split(",").filter { it.isNotBlank() }
            if (queue.isEmpty()) return null
            PersistedPlaybackState(
                queue             = queue,
                currentIndex      = prefs[KEY_INDEX] ?: 0,
                positionMs        = prefs[KEY_POSITION] ?: 0L,
                shuffleEnabled    = prefs[KEY_SHUFFLE] ?: false,
                repeatMode        = prefs[KEY_REPEAT] ?: Player.REPEAT_MODE_OFF,
                currentPlaylistId = prefs[KEY_PLAYLIST_ID]?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) { null }
    }
}
