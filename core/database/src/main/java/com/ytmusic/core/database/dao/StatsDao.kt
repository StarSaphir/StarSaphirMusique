package com.ytmusic.core.database.dao

import androidx.room.*
import com.ytmusic.core.database.entity.DownloadJobEntity
import com.ytmusic.core.database.entity.DownloadStatus
import com.ytmusic.core.database.entity.PlaybackSessionEntity
import kotlinx.coroutines.flow.Flow

// ─── Download ─────────────────────────────────────────────────────────────────

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_jobs ORDER BY createdAt DESC")
    fun getAllJobs(): Flow<List<DownloadJobEntity>>

    @Query("SELECT * FROM download_jobs WHERE workerId = :id")
    suspend fun getJob(id: String): DownloadJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: DownloadJobEntity)

    @Query("UPDATE download_jobs SET status = :status, progressPercent = :progress WHERE workerId = :id")
    suspend fun updateProgress(id: String, status: DownloadStatus, progress: Int)

    @Query("UPDATE download_jobs SET status = :status, errorMessage = :error WHERE workerId = :id")
    suspend fun updateError(id: String, status: DownloadStatus, error: String?)

    @Delete
    suspend fun deleteJob(job: DownloadJobEntity)
}

// ─── Stats ────────────────────────────────────────────────────────────────────

data class TrackListenStat(val trackId: String, val totalMs: Long, val sessionCount: Int)
data class ArtistListenStat(val artist: String, val totalMs: Long)

@Dao
interface StatsDao {

    @Insert
    suspend fun insertSession(session: PlaybackSessionEntity)

    @Query("SELECT SUM(listenedMs) FROM playback_sessions")
    fun getTotalListenedMs(): Flow<Long?>

    @Query("""
        SELECT trackId, SUM(listenedMs) as totalMs, COUNT(*) as sessionCount
        FROM playback_sessions
        GROUP BY trackId
        ORDER BY totalMs DESC
        LIMIT :limit
    """)
    fun getTopTracks(limit: Int = 20): Flow<List<TrackListenStat>>

    @Query("""
        SELECT t.artist as artist, SUM(s.listenedMs) as totalMs
        FROM playback_sessions s
        JOIN tracks t ON t.id = s.trackId
        GROUP BY t.artist
        ORDER BY totalMs DESC
        LIMIT :limit
    """)
    fun getTopArtists(limit: Int = 20): Flow<List<ArtistListenStat>>

    @Query("""
        SELECT DISTINCT trackId FROM playback_sessions
        ORDER BY startedAt DESC LIMIT :limit
    """)
    fun getRecentTrackIds(limit: Int = 30): Flow<List<String>>

    @Query("""
        SELECT DISTINCT playlistId FROM playback_sessions
        WHERE playlistId IS NOT NULL
        ORDER BY startedAt DESC LIMIT :limit
    """)
    fun getRecentPlaylistIds(limit: Int = 10): Flow<List<String>>

    @Query("SELECT AVG(dailyMs) FROM (SELECT SUM(listenedMs) as dailyMs FROM playback_sessions GROUP BY DATE(startedAt / 1000, 'unixepoch'))")
    fun getDailyAverageMs(): Flow<Double?>

    /** Efface uniquement les sessions d'écoute — ne touche pas aux tracks ni playlists */
    @Query("DELETE FROM playback_sessions")
    suspend fun deleteAllSessions()

    // Nombre total de sessions (= nombre d'écoutes)
    @Query("SELECT COUNT(*) FROM playback_sessions")
    fun getTotalSessionCount(): Flow<Int>

    // Stats par langue — jointure sur la table tracks
    @Query("""
        SELECT t.language as language, SUM(s.listenedMs) as totalMs, COUNT(*) as sessionCount
        FROM playback_sessions s
        JOIN tracks t ON t.id = s.trackId
        WHERE t.language IS NOT NULL AND t.language != ''
        GROUP BY t.language
        ORDER BY totalMs DESC
        LIMIT :limit
    """)
    fun getTopLanguages(limit: Int = 20): Flow<List<LanguageListenStat>>
}

data class LanguageListenStat(val language: String, val totalMs: Long, val sessionCount: Int)