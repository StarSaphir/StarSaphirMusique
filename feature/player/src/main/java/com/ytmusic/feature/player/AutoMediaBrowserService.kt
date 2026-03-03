package com.ytmusic.feature.player

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.session.MediaSessionCompat
import androidx.media.MediaBrowserServiceCompat
import com.ytmusic.core.domain.model.Playlist
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.repository.PlaylistRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AutoMediaBrowserService : MediaBrowserServiceCompat() {

    @Inject lateinit var trackRepository:    TrackRepository
    @Inject lateinit var playlistRepository: PlaylistRepository

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var mediaSession: MediaSessionCompat

    companion object {
        const val ROOT_ID         = "AUTO_ROOT"
        const val ARCHIVE_ID      = "AUTO_ARCHIVE"
        const val PLAYLISTS_ID    = "AUTO_PLAYLISTS"
        const val MEGA_ID         = "AUTO_MEGA"
        const val PLAYLIST_PREFIX = "AUTO_PL_"
        const val MEGA_PREFIX     = "AUTO_MEGA_"
    }

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "AutoMediaBrowserService")
        mediaSession.isActive = true
        sessionToken = mediaSession.sessionToken
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putInt("android.media.browse.CONTENT_STYLE_BROWSABLE_HINT", 1)
            putInt("android.media.browse.CONTENT_STYLE_PLAYABLE_HINT",  1)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result:   Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        result.detach()
        scope.launch {
            val items: List<MediaBrowserCompat.MediaItem> = when {
                parentId == ROOT_ID         -> buildRoot()
                parentId == ARCHIVE_ID      -> buildArchive()
                parentId == PLAYLISTS_ID    -> buildPlaylists()
                parentId == MEGA_ID         -> buildMegaList()
                parentId.startsWith(PLAYLIST_PREFIX) ->
                    buildPlaylistTracks(parentId.removePrefix(PLAYLIST_PREFIX))
                parentId.startsWith(MEGA_PREFIX) ->
                    buildMegaTracks(parentId.removePrefix(MEGA_PREFIX).split(","))
                else -> emptyList()
            }
            result.sendResult(items.toMutableList())
        }
    }

    private fun buildRoot(): List<MediaBrowserCompat.MediaItem> = listOf(
        browsable(ARCHIVE_ID,   "Archive",        "Tous les morceaux"),
        browsable(PLAYLISTS_ID, "Playlists",      "Mes listes de lecture"),
        browsable(MEGA_ID,      "Mega Playlists", "Lecture shuffle combinee")
    )

    private suspend fun buildArchive(): List<MediaBrowserCompat.MediaItem> {
        val tracks: List<Track> = trackRepository.getAllTracks().firstOrNull()
            ?.sortedBy { it.title.lowercase() } ?: emptyList()
        return tracks.map { t -> playable(t) }
    }

    private suspend fun buildPlaylists(): List<MediaBrowserCompat.MediaItem> {
        val playlists: List<Playlist> =
            playlistRepository.getAllPlaylistsWithTracks().firstOrNull() ?: emptyList()
        return playlists.map { pl ->
            browsable("$PLAYLIST_PREFIX${pl.id}", pl.name, "${pl.tracks.size} morceaux")
        }
    }

    private suspend fun buildMegaList(): List<MediaBrowserCompat.MediaItem> {
        val playlists: List<Playlist> =
            playlistRepository.getAllPlaylistsWithTracks().firstOrNull() ?: emptyList()
        return playlists.map { pl ->
            browsable("$MEGA_PREFIX${pl.id}", pl.name, "Shuffle - ${pl.tracks.size} morceaux")
        }
    }

    private suspend fun buildPlaylistTracks(id: String): List<MediaBrowserCompat.MediaItem> {
        val tracks: List<Track> = playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
            ?.firstOrNull { pl -> pl.id == id }?.tracks ?: emptyList()
        return tracks.map { t -> playable(t) }
    }

    private suspend fun buildMegaTracks(ids: List<String>): List<MediaBrowserCompat.MediaItem> {
        val tracks: List<Track> = playlistRepository.getAllPlaylistsWithTracks().firstOrNull()
            ?.filter  { pl -> pl.id in ids }
            ?.flatMap { pl -> pl.tracks }
            ?.distinctBy { t -> "${t.title.lowercase()}|${t.artist.lowercase()}" }
            ?.shuffled() ?: emptyList()
        return tracks.map { t -> playable(t) }
    }

    private fun browsable(id: String, title: String, subtitle: String): MediaBrowserCompat.MediaItem =
        MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(id).setTitle(title).setSubtitle(subtitle)
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
        )

    private fun playable(track: Track): MediaBrowserCompat.MediaItem {
        val artUri = ThumbnailProvider.toContentUri(this, track.thumbnailPath)
            ?.let { android.net.Uri.parse(it.toString()) }
        return MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId(track.id)
                .setTitle(track.title)
                .setSubtitle(track.artist)
                .apply {
                    track.language?.let { setDescription(it) }
                    artUri?.let { setIconUri(it) }
                    setMediaUri(android.net.Uri.parse(track.filePath))
                }
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession.release()
        super.onDestroy()
    }
}
