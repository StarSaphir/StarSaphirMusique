package com.ytmusic.core.domain.repository

import com.ytmusic.core.domain.model.*
import kotlinx.coroutines.flow.Flow

/** Statistiques d'écoute par langue — défini dans le domaine pour rester indépendant de Room. */
data class LanguageStat(val language: String, val totalListenedMs: Long, val sessionCount: Int)

interface TrackRepository {
    fun getAllTracks(): Flow<List<Track>>
    fun searchTracks(query: String): Flow<List<Track>>
    fun getFavoriteTracks(): Flow<List<Track>>
    suspend fun getTrackById(id: String): Track?
    suspend fun insertTrack(track: Track)
    suspend fun deleteTrack(track: Track)
    suspend fun setFavorite(id: String, fav: Boolean)
    suspend fun updateMetadata(id: String, title: String, artist: String, language: String?)
    suspend fun updateTrim(id: String, startMs: Long, endMs: Long?)
    suspend fun incrementPlayCount(id: String)
}

interface PlaylistRepository {
    fun getAllPlaylists(): Flow<List<Playlist>>
    fun getFavoritePlaylists(): Flow<List<Playlist>>
    fun getPlaylistWithTracks(id: String): Flow<Playlist>
    fun getAllPlaylistsWithTracks(): Flow<List<Playlist>>
    suspend fun createPlaylist(name: String): String
    suspend fun deletePlaylist(id: String)
    suspend fun addTrackToPlaylist(playlistId: String, trackId: String)
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)
    suspend fun setFavorite(id: String, fav: Boolean)
}

interface DownloadRepository {
    fun getAllJobs(): Flow<List<DownloadJob>>
    suspend fun enqueue(videoId: String, title: String, artist: String = "Anonyme", language: String? = null, audioOnly: Boolean, quality: String): String
    suspend fun cancel(workerId: String)
    suspend fun deleteJob(workerId: String)
}

interface StatsRepository {
    fun getTotalListenedMs(): Flow<Long>
    fun getTotalSessionCount(): Flow<Int>            // nouveau
    fun getTopTracks(limit: Int): Flow<List<TrackStat>>
    fun getTopArtists(limit: Int): Flow<List<ArtistStat>>
    fun getTopLanguages(limit: Int): Flow<List<LanguageStat>>        // nouveau
    fun getRecentTracks(limit: Int): Flow<List<Track>>
    fun getRecentPlaylistIds(limit: Int): Flow<List<String>>
    fun getDailyAverageMs(): Flow<Double>
    suspend fun recordSession(trackId: String, playlistId: String?, listenedMs: Long)
    suspend fun resetListenStats()
}

interface SettingsRepository {
    fun getNormalizationEnabled(): Flow<Boolean>
    suspend fun setNormalizationEnabled(enabled: Boolean)
    fun getDefaultShuffle(): Flow<Boolean>
    suspend fun setDefaultShuffle(enabled: Boolean)
    fun getDefaultQuality(): Flow<String>
    suspend fun setDefaultQuality(quality: String)
    fun getKeepScreenOn(): Flow<Boolean>
    suspend fun setKeepScreenOn(enabled: Boolean)
    fun getCoverBackground(): Flow<Boolean>          // nouveau
    suspend fun setCoverBackground(enabled: Boolean) // nouveau
}