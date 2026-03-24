package com.ytmusic.core.domain.repository.impl

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.ytmusic.core.database.dao.StatsDao
import com.ytmusic.core.domain.repository.LanguageStat
import com.ytmusic.core.database.entity.PlaybackSessionEntity
import com.ytmusic.core.domain.model.ArtistStat
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.model.TrackStat
import com.ytmusic.core.domain.repository.SettingsRepository
import com.ytmusic.core.domain.repository.StatsRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class StatsAndSettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val statsDao: StatsDao,
    private val trackRepository: TrackRepository   // pour résoudre Track depuis un id
) : StatsRepository, SettingsRepository {

    // ── Clés DataStore ────────────────────────────────────────────────────────

    private object Keys {
        val NORMALIZATION   = booleanPreferencesKey("normalization_enabled")
        val DEFAULT_SHUFFLE = booleanPreferencesKey("default_shuffle")
        val DEFAULT_QUALITY = stringPreferencesKey("default_quality")
        val KEEP_SCREEN_ON    = booleanPreferencesKey("keep_screen_on")
        val COVER_BACKGROUND  = booleanPreferencesKey("cover_background")
    }

    // ── SettingsRepository ────────────────────────────────────────────────────

    override fun getNormalizationEnabled(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.NORMALIZATION] ?: false }

    override suspend fun setNormalizationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.NORMALIZATION] = enabled }
    }

    override fun getDefaultShuffle(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.DEFAULT_SHUFFLE] ?: false }

    override suspend fun setDefaultShuffle(enabled: Boolean) {
        context.dataStore.edit { it[Keys.DEFAULT_SHUFFLE] = enabled }
    }

    override fun getDefaultQuality(): Flow<String> =
        context.dataStore.data.map { it[Keys.DEFAULT_QUALITY] ?: "192k" }

    override suspend fun setDefaultQuality(quality: String) {
        context.dataStore.edit { it[Keys.DEFAULT_QUALITY] = quality }
    }

    override fun getKeepScreenOn(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.KEEP_SCREEN_ON] ?: false }

    override suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    override fun getCoverBackground(): Flow<Boolean> =
        context.dataStore.data.map { it[Keys.COVER_BACKGROUND] ?: false }

    override suspend fun setCoverBackground(enabled: Boolean) {
        context.dataStore.edit { it[Keys.COVER_BACKGROUND] = enabled }
    }

    // ── StatsRepository ───────────────────────────────────────────────────────

    override suspend fun recordSession(trackId: String, playlistId: String?, listenedMs: Long) {
        statsDao.insertSession(
            PlaybackSessionEntity(
                trackId    = trackId,
                playlistId = playlistId,
                listenedMs = listenedMs
            )
        )
    }

    override fun getTotalListenedMs(): Flow<Long> =
        statsDao.getTotalListenedMs().map { it ?: 0L }

    override fun getTotalSessionCount(): Flow<Int> =
        statsDao.getTotalSessionCount()

    override fun getDailyAverageMs(): Flow<Double> =
        statsDao.getDailyAverageMs().map { it ?: 0.0 }

    override fun getTopTracks(limit: Int): Flow<List<TrackStat>> =
        statsDao.getTopTracks(limit).map { list ->
            // Résolution des Track via TrackRepository (évite de dépendre du mapper DB)
            val allTracks = trackRepository.getAllTracks().first().associateBy { it.id }
            list.mapNotNull { stat ->
                val track = allTracks[stat.trackId] ?: return@mapNotNull null
                TrackStat(
                    track           = track,
                    totalListenedMs = stat.totalMs,
                    sessionCount    = stat.sessionCount
                )
            }
        }

    override fun getTopArtists(limit: Int): Flow<List<ArtistStat>> =
        statsDao.getTopArtists(limit).map { list ->
            list.map { ArtistStat(artist = it.artist, totalListenedMs = it.totalMs) }
        }

    override fun getTopLanguages(limit: Int): Flow<List<LanguageStat>> =
        statsDao.getTopLanguages(limit).map { list ->
            list.map { LanguageStat(it.language, it.totalMs, it.sessionCount) }
        }

    override fun getRecentTracks(limit: Int): Flow<List<Track>> =
        statsDao.getRecentTrackIds(limit).map { ids ->
            val allTracks = trackRepository.getAllTracks().first().associateBy { it.id }
            ids.mapNotNull { allTracks[it] }
        }

    override fun getRecentPlaylistIds(limit: Int): Flow<List<String>> =
        statsDao.getRecentPlaylistIds(limit)

    override suspend fun resetListenStats() =
        statsDao.deleteAllSessions()
}