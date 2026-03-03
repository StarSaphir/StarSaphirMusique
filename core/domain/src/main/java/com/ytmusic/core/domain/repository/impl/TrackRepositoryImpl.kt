package com.ytmusic.core.domain.repository.impl

import com.ytmusic.core.database.dao.TrackDao
import com.ytmusic.core.domain.model.*
import com.ytmusic.core.domain.repository.TrackRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackRepositoryImpl @Inject constructor(
    private val trackDao: TrackDao
) : TrackRepository {

    override fun getAllTracks(): Flow<List<Track>> =
        trackDao.getAllTracks().map { it.map(TrackEntity::toDomain) }

    override fun searchTracks(query: String): Flow<List<Track>> =
        trackDao.searchTracks(query).map { it.map(TrackEntity::toDomain) }

    override fun getFavoriteTracks(): Flow<List<Track>> =
        trackDao.getFavoriteTracks().map { it.map(TrackEntity::toDomain) }

    override suspend fun getTrackById(id: String): Track? =
        trackDao.getTrackById(id)?.toDomain()

    override suspend fun insertTrack(track: Track) =
        trackDao.insertTrack(track.toEntity())

    override suspend fun deleteTrack(track: Track) =
        trackDao.deleteTrack(track.toEntity())

    override suspend fun setFavorite(id: String, fav: Boolean) =
        trackDao.setFavorite(id, fav)

    override suspend fun updateMetadata(id: String, title: String, artist: String, language: String?) =
        trackDao.updateMetadata(id, title, artist, language)

    override suspend fun updateTrim(id: String, startMs: Long, endMs: Long?) =
        trackDao.updateTrim(id, startMs, endMs)

    override suspend fun incrementPlayCount(id: String) =
        trackDao.incrementPlayCount(id)
}

// Required import alias
private typealias TrackEntity = com.ytmusic.core.database.entity.TrackEntity
