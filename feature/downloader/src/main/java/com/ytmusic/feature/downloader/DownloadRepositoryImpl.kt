package com.ytmusic.feature.downloader

import android.content.Context
import androidx.work.*
import com.ytmusic.core.database.dao.DownloadDao
import com.ytmusic.core.database.entity.DownloadJobEntity
import com.ytmusic.core.database.entity.DownloadStatus
import com.ytmusic.core.domain.model.DownloadJob
import com.ytmusic.core.domain.model.toDomain
import com.ytmusic.core.domain.repository.DownloadRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloadDao: DownloadDao
) : DownloadRepository {

    override fun getAllJobs(): Flow<List<DownloadJob>> =
        downloadDao.getAllJobs().map { list -> list.map { it.toDomain() } }

    override suspend fun enqueue(
        videoId: String, title: String, artist: String, language: String?, audioOnly: Boolean, quality: String
    ): String {
        val workerId = UUID.randomUUID().toString()

        downloadDao.insertJob(
            DownloadJobEntity(
                workerId       = workerId,
                youtubeVideoId = videoId,
                title          = title,
                audioOnly      = audioOnly,
                quality        = quality,
                status         = DownloadStatus.QUEUED
            )
        )

        val data = workDataOf(
            DownloadWorker.KEY_VIDEO_ID   to videoId,
            DownloadWorker.KEY_TITLE      to title,
            DownloadWorker.KEY_ARTIST     to artist,
            DownloadWorker.KEY_LANGUAGE   to language,
            DownloadWorker.KEY_AUDIO_ONLY to audioOnly,
            DownloadWorker.KEY_QUALITY    to quality
        )

        // Aucune contrainte réseau → fonctionne sur WiFi ET données mobiles (4G/5G)
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(UUID.fromString(workerId))
            .setInputData(data)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        return workerId
    }

    override suspend fun cancel(workerId: String) {
        WorkManager.getInstance(context).cancelWorkById(UUID.fromString(workerId))
        downloadDao.updateError(workerId, DownloadStatus.CANCELLED, null)
    }

    override suspend fun deleteJob(workerId: String) {
        downloadDao.getJob(workerId)?.let { downloadDao.deleteJob(it) }
    }
}
