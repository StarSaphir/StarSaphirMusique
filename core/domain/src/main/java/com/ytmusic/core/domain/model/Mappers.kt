package com.ytmusic.core.domain.model

import com.ytmusic.core.database.entity.*

// ─── Track ────────────────────────────────────────────────────────────────────

fun TrackEntity.toDomain() = Track(
    id            = id,
    title         = title,
    artist        = artist,
    language      = language,
    filePath      = filePath,
    thumbnailPath = thumbnailPath,
    durationMs    = durationMs,
    trimStartMs   = trimStartMs,
    trimEndMs     = trimEndMs,
    isFavorite    = isFavorite,
    downloadedAt  = downloadedAt,
    youtubeVideoId = youtubeVideoId,
    playCount     = playCount
)

fun Track.toEntity() = TrackEntity(
    id            = id,
    title         = title,
    artist        = artist,
    language      = language,
    filePath      = filePath,
    thumbnailPath = thumbnailPath,
    durationMs    = durationMs,
    trimStartMs   = trimStartMs,
    trimEndMs     = trimEndMs,
    isFavorite    = isFavorite,
    downloadedAt  = downloadedAt,
    youtubeVideoId = youtubeVideoId,
    playCount     = playCount
)

// ─── Playlist ─────────────────────────────────────────────────────────────────

fun PlaylistWithTracks.toDomain() = Playlist(
    id         = playlist.id,
    name       = playlist.name,
    isFavorite = playlist.isFavorite,
    createdAt  = playlist.createdAt,
    tracks     = tracks.map { it.toDomain() }
)

fun PlaylistEntity.toDomain() = Playlist(
    id         = id,
    name       = name,
    isFavorite = isFavorite,
    createdAt  = createdAt
)

// ─── DownloadJob ──────────────────────────────────────────────────────────────

fun DownloadJobEntity.toDomain() = DownloadJob(
    workerId       = workerId,
    youtubeVideoId = youtubeVideoId,
    title          = title,
    audioOnly      = audioOnly,
    quality        = quality,
    status         = status.toDomain(),
    errorMessage   = errorMessage,
    progressPercent = progressPercent,
    createdAt      = createdAt
)

fun DownloadStatus.toDomain() = when (this) {
    DownloadStatus.QUEUED    -> DownloadJobStatus.QUEUED
    DownloadStatus.RUNNING   -> DownloadJobStatus.RUNNING
    DownloadStatus.DONE      -> DownloadJobStatus.DONE
    DownloadStatus.ERROR     -> DownloadJobStatus.ERROR
    DownloadStatus.CANCELLED -> DownloadJobStatus.CANCELLED
}

fun DownloadJobStatus.toEntity() = when (this) {
    DownloadJobStatus.QUEUED    -> DownloadStatus.QUEUED
    DownloadJobStatus.RUNNING   -> DownloadStatus.RUNNING
    DownloadJobStatus.DONE      -> DownloadStatus.DONE
    DownloadJobStatus.ERROR     -> DownloadStatus.ERROR
    DownloadJobStatus.CANCELLED -> DownloadStatus.CANCELLED
}
