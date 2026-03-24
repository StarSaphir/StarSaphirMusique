package com.ytmusic.core.domain.model

// ─── Core domain models ───────────────────────────────────────────────────────

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val language: String? = null,
    val filePath: String,
    val thumbnailPath: String? = null,
    val durationMs: Long,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val isFavorite: Boolean = false,
    val downloadedAt: Long = 0L,
    val youtubeVideoId: String? = null,
    val playCount: Long = 0L,
    /** Gain de normalisation calculé au download (dB). 0f = pas de correction. */
    val replayGainDb: Float = 0f
) {
    val effectiveDurationMs: Long
        get() = (trimEndMs ?: durationMs) - trimStartMs
}

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track> = emptyList(),
    val isFavorite: Boolean = false,
    val createdAt: Long = 0L
)

data class DownloadJob(
    val workerId: String,
    val youtubeVideoId: String,
    val title: String,
    val audioOnly: Boolean,
    val quality: String,
    val status: DownloadJobStatus,
    val errorMessage: String? = null,
    val progressPercent: Int = 0,
    val createdAt: Long = 0L
)

enum class DownloadJobStatus { QUEUED, RUNNING, DONE, ERROR, CANCELLED }

data class DetectedVideo(
    val videoId: String,
    val title: String,
    val url: String
)

data class PlaybackState(
    val currentTrack: Track? = null,
    val currentPlaylistId: String? = null,
    val queue: List<Track> = emptyList(),
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF
)

enum class RepeatMode { OFF, ONE, ALL }

// ─── Stats models ─────────────────────────────────────────────────────────────

data class TrackStat(val track: Track, val totalListenedMs: Long, val sessionCount: Int)
data class ArtistStat(val artist: String, val totalListenedMs: Long)
data class GlobalStats(
    val totalListenedMs: Long,
    val totalTracks: Int,
    val totalPlaylists: Int,
    val dailyAverageMs: Double
)