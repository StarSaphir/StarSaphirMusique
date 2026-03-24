package com.ytmusic.core.database.entity

import androidx.room.*

// ─── Track ───────────────────────────────────────────────────────────────────

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val language: String? = null,
    val filePath: String,
    val thumbnailPath: String? = null,
    val durationMs: Long,
    /** Offset logique — fichier toujours intact */
    val trimStartMs: Long = 0L,
    val trimEndMs: Long? = null,
    val isFavorite: Boolean = false,
    val downloadedAt: Long = System.currentTimeMillis(),
    val youtubeVideoId: String? = null,
    val playCount: Long = 0L,
    /** Gain de normalisation calculé au download (en dB, typiquement entre -10 et +10).
     *  0.0 = pas de correction. Appliqué via LoudnessEnhancer.setTargetGain(). */
    val replayGainDb: Float = 0f
)

// ─── Playlist ─────────────────────────────────────────────────────────────────

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Cross-ref playlist ↔ track ───────────────────────────────────────────────

@Entity(
    tableName = "playlist_track_cross_ref",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(PlaylistEntity::class, ["id"], ["playlistId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TrackEntity::class, ["id"], ["trackId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("trackId")]
)
data class PlaylistTrackCrossRef(
    val playlistId: String,
    val trackId: String,
    val position: Int = 0
)

// ─── Download Job ─────────────────────────────────────────────────────────────

enum class DownloadStatus { QUEUED, RUNNING, DONE, ERROR, CANCELLED }

@Entity(tableName = "download_jobs")
data class DownloadJobEntity(
    @PrimaryKey val workerId: String,
    val youtubeVideoId: String,
    val title: String,
    val audioOnly: Boolean,
    val quality: String = "192k",
    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null,
    val progressPercent: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Playback Session (stats) ─────────────────────────────────────────────────

@Entity(tableName = "playback_sessions")
data class PlaybackSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val playlistId: String? = null,
    /** Millisecondes réellement écoutées (hors pause/seek) */
    val listenedMs: Long,
    val startedAt: Long = System.currentTimeMillis()
)

// ─── Relations ────────────────────────────────────────────────────────────────

data class PlaylistWithTracks(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistTrackCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "trackId"
        )
    )
    val tracks: List<TrackEntity>
)