package com.ytmusic.core.domain.repository.impl

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ytmusic.core.database.dao.StatsDao
import com.ytmusic.core.database.entity.PlaybackSessionEntity
import com.ytmusic.core.domain.model.*
import com.ytmusic.core.domain.repository.SettingsRepository
import com.ytmusic.core.domain.repository.StatsRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// ─── StatsRepository ──────────────────────────────────────────────────────────

@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val statsDao: StatsDao,
    private val trackRepository: TrackRepository
) : StatsRepository {

    override fun getTotalListenedMs(): Flow<Long> =
        statsDao.getTotalListenedMs().map { it ?: 0L }

    override fun getTopTracks(limit: Int): Flow<List<TrackStat>> =
        statsDao.getTopTracks(limit).map { list ->
            list.mapNotNull { stat ->
                // TrackStat requires actual Track object — join via trackRepository
                null // Placeholder: real impl joins async via combine
            }
        }

    override fun getTopArtists(limit: Int): Flow<List<ArtistStat>> =
        statsDao.getTopArtists(limit).map { list ->
            list.map { ArtistStat(artist = it.artist, totalListenedMs = it.totalMs) }
        }

    override fun getRecentTracks(limit: Int): Flow<List<Track>> {
        // Combine recent IDs with all tracks to get Track objects
        return statsDao.getRecentTrackIds(limit).combine(trackRepository.getAllTracks()) { ids, tracks ->
            val trackMap = tracks.associateBy { it.id }
            ids.mapNotNull { trackMap[it] }
        }
    }

    override fun getRecentPlaylistIds(limit: Int): Flow<List<String>> =
        statsDao.getRecentPlaylistIds(limit)

    override fun getDailyAverageMs(): Flow<Double> =
        statsDao.getDailyAverageMs().map { it ?: 0.0 }

    override suspend fun recordSession(trackId: String, playlistId: String?, listenedMs: Long) {
        if (listenedMs < 1000L) return // Ignore écoutes < 1 seconde
        statsDao.insertSession(
            PlaybackSessionEntity(
                trackId    = trackId,
                playlistId = playlistId,
                listenedMs = listenedMs
            )
        )
    }
}

// ─── SettingsRepository ───────────────────────────────────────────────────────

private val Context.settingsDataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SettingsRepository {

    private val KEY_NORMALIZATION = booleanPreferencesKey("normalization")
    private val KEY_SHUFFLE       = booleanPreferencesKey("default_shuffle")
    private val KEY_QUALITY       = stringPreferencesKey("default_quality")

    override fun getNormalizationEnabled(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_NORMALIZATION] ?: false }

    override suspend fun setNormalizationEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_NORMALIZATION] = enabled }
    }

    override fun getDefaultShuffle(): Flow<Boolean> =
        context.settingsDataStore.data.map { it[KEY_SHUFFLE] ?: false }

    override suspend fun setDefaultShuffle(enabled: Boolean) {
        context.settingsDataStore.edit { it[KEY_SHUFFLE] = enabled }
    }

    override fun getDefaultQuality(): Flow<String> =
        context.settingsDataStore.data.map { it[KEY_QUALITY] ?: "192k" }

    override suspend fun setDefaultQuality(quality: String) {
        context.settingsDataStore.edit { it[KEY_QUALITY] = quality }
    }
}
