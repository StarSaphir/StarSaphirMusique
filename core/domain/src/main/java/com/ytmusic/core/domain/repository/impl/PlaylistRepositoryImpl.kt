package com.ytmusic.core.domain.repository.impl

import com.ytmusic.core.database.dao.PlaylistDao
import com.ytmusic.core.database.entity.PlaylistEntity
import com.ytmusic.core.database.entity.PlaylistTrackCrossRef
import com.ytmusic.core.domain.model.*
import com.ytmusic.core.domain.repository.PlaylistRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    private val playlistDao: PlaylistDao
) : PlaylistRepository {

    override fun getAllPlaylists(): Flow<List<Playlist>> =
        playlistDao.getAllPlaylists().map { list -> list.map { it.toDomain() } }

    override fun getFavoritePlaylists(): Flow<List<Playlist>> =
        playlistDao.getFavoritePlaylists().map { list -> list.map { it.toDomain() } }

    override fun getPlaylistWithTracks(id: String): Flow<Playlist> =
        playlistDao.getPlaylistWithTracks(id).map { it.toDomain() }

    override fun getAllPlaylistsWithTracks(): Flow<List<Playlist>> =
        playlistDao.getAllPlaylistsWithTracks().map { list -> list.map { it.toDomain() } }

    override suspend fun createPlaylist(name: String): String {
        val id = UUID.randomUUID().toString()
        playlistDao.insertPlaylist(PlaylistEntity(id = id, name = name))
        return id
    }

    override suspend fun deletePlaylist(id: String) {
        playlistDao.deletePlaylist(PlaylistEntity(id = id, name = ""))
    }

    override suspend fun addTrackToPlaylist(playlistId: String, trackId: String) {
        val currentRefs = playlistDao.getCrossRefs(playlistId)
        val nextPosition = currentRefs.maxOfOrNull { it.position + 1 } ?: 0
        playlistDao.addTrackToPlaylist(
            PlaylistTrackCrossRef(playlistId, trackId, nextPosition)
        )
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) =
        playlistDao.removeTrack(playlistId, trackId)

    override suspend fun setFavorite(id: String, fav: Boolean) =
        playlistDao.setFavorite(id, fav)
}
