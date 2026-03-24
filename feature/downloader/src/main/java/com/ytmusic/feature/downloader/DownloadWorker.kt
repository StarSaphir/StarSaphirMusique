package com.ytmusic.feature.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.ytmusic.core.database.dao.DownloadDao
import com.ytmusic.core.database.dao.TrackDao
import com.ytmusic.core.database.entity.DownloadStatus
import com.ytmusic.core.database.entity.TrackEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadDao: DownloadDao,
    private val trackDao: TrackDao
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_VIDEO_ID   = "video_id"
        const val KEY_TITLE      = "title"
        const val KEY_ARTIST     = "artist"
        const val KEY_LANGUAGE   = "language"
        const val KEY_AUDIO_ONLY = "audio_only"
        const val KEY_QUALITY    = "quality"
        const val CHANNEL_ID     = "download_channel"
        const val NOTIF_ID       = 2001
        private const val TAG    = "DownloadWorker"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId   = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        val title     = inputData.getString(KEY_TITLE) ?: "Unknown"
        val artist    = inputData.getString(KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: "Anonyme"
        val language  = inputData.getString(KEY_LANGUAGE)?.takeIf { it.isNotBlank() }
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, false)

        downloadDao.updateProgress(id.toString(), DownloadStatus.RUNNING, 0)
        setForeground(buildForegroundInfo(title, 0))

        try {
            Log.d(TAG, "Starting download: videoId=$videoId title=$title audioOnly=$audioOnly")
            downloadDao.updateProgress(id.toString(), DownloadStatus.RUNNING, 5)

            val stream = YoutubeExtractor.extract(videoId, audioOnly)
            Log.d(TAG, "Stream: mime=${stream.mimeType} isVideo=${stream.isVideo}")

            downloadDao.updateProgress(id.toString(), DownloadStatus.RUNNING, 15)

            // Télécharger la miniature YouTube
            val thumbDir  = File(applicationContext.filesDir, "thumbs").also { it.mkdirs() }
            val thumbFile = File(thumbDir, "$videoId.jpg")
            if (!thumbFile.exists()) {
                try {
                    downloadThumbnail(videoId, thumbFile)
                } catch (e: Exception) {
                    Log.w(TAG, "Thumbnail download failed: ${e.message}")
                }
            }

            downloadDao.updateProgress(id.toString(), DownloadStatus.RUNNING, 20)

            val ext = when {
                stream.isVideo && stream.mimeType.contains("mp4") -> "mp4"
                stream.isVideo                                     -> "webm"
                stream.mimeType.contains("mp4")                   -> "m4a"
                else                                               -> "webm"
            }
            val outputDir  = File(applicationContext.filesDir, "tracks").also { it.mkdirs() }
            val trackId    = UUID.randomUUID().toString()
            val outputFile = File(outputDir, "$trackId.$ext")

            downloadFile(stream.url, outputFile) { progress ->
                kotlinx.coroutines.runBlocking {
                    downloadDao.updateProgress(id.toString(), DownloadStatus.RUNNING, 20 + (progress * 0.78).toInt())
                }
            }

            if (!outputFile.exists() || outputFile.length() < 1024) {
                throw RuntimeException("Fichier invalide après téléchargement (${outputFile.length()} bytes)")
            }

            Log.d(TAG, "Downloaded ${outputFile.length() / 1024}KB → ${outputFile.name}")

            val durationMs    = getDuration(outputFile)
            val replayGainDb  = computeReplayGainDb(outputFile)
            trackDao.insertTrack(TrackEntity(
                id             = trackId,
                title          = title,
                artist         = artist,
                language       = language,
                filePath       = outputFile.absolutePath,
                thumbnailPath  = if (thumbFile.exists()) thumbFile.absolutePath else null,
                durationMs     = durationMs,
                youtubeVideoId = videoId,
                downloadedAt   = System.currentTimeMillis(),
                replayGainDb   = replayGainDb
            ))

            downloadDao.updateProgress(id.toString(), DownloadStatus.DONE, 100)
            notifyComplete(title)
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.javaClass.simpleName}: ${e.message}")
            downloadDao.updateError(id.toString(), DownloadStatus.ERROR, e.message ?: "Erreur inconnue")
            notifyError(title, e.message ?: "Erreur inconnue")
            Result.failure()
        }
    }

    private fun downloadThumbnail(videoId: String, dest: File) {
        // Essayer maxresdefault puis hqdefault
        val urls = listOf(
            "https://img.youtube.com/vi/$videoId/maxresdefault.jpg",
            "https://img.youtube.com/vi/$videoId/hqdefault.jpg"
        )
        for (url in urls) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout    = 10_000
                if (conn.responseCode == 200 && conn.contentLength > 1000) {
                    conn.inputStream.use { input ->
                        dest.outputStream().use { it.write(input.readBytes()) }
                    }
                    conn.disconnect()
                    Log.d(TAG, "Thumbnail saved: ${dest.name}")
                    return
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun downloadFile(streamUrl: String, outputFile: File, onProgress: (Int) -> Unit) {
        val conn = URL(streamUrl).openConnection() as HttpURLConnection
        conn.apply {
            requestMethod  = "GET"
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            setRequestProperty("Referer", "https://www.youtube.com/")
            connectTimeout = 30_000
            readTimeout    = 120_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        if (code !in 200..206) {
            conn.disconnect()
            throw RuntimeException("HTTP $code lors du téléchargement")
        }
        val total      = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
        var downloaded = 0L
        conn.inputStream.use { input ->
            outputFile.outputStream().use { out ->
                val buf = ByteArray(32_768)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) onProgress((downloaded * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
        conn.disconnect()
    }

    /**
     * Calcule un gain de normalisation basé sur l'amplitude RMS du fichier audio.
     * Cible : -18 dBFS (niveau broadcast standard).
     *
     * Utilise MediaCodec pour décoder un échantillon ~10 s au milieu du fichier
     * (suffisant pour estimer le niveau sans traiter tout le fichier).
     * Retourne un delta en dB limité à [-12, +12] pour éviter la saturation.
     * Retourne 0f en cas d'erreur (normalisation désactivée pour ce morceau).
     */
    private fun computeReplayGainDb(file: File): Float {
        val TARGET_DBFS = -18f          // cible broadcast
        val MAX_GAIN_DB =  12f
        try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            // Trouver la piste audio
            var audioTrack = -1
            var mime = ""
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val m = fmt.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (m.startsWith("audio/")) { audioTrack = i; mime = m; break }
            }
            if (audioTrack < 0) { extractor.release(); return 0f }
            extractor.selectTrack(audioTrack)

            // Sauter au milieu du fichier pour échantillonner ~5 s
            val durationUs = getDuration(file) * 1000L
            if (durationUs > 10_000_000L)
                extractor.seekTo(durationUs / 2, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val format = extractor.getTrackFormat(audioTrack)
            val codec  = android.media.MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var sumSq  = 0.0
            var count  = 0L
            val info   = android.media.MediaCodec.BufferInfo()
            val maxUs  = extractor.sampleTime + 5_000_000L  // 5 secondes
            var eos    = false

            while (!eos && extractor.sampleTime < maxUs) {
                // Alimenter l'entrée
                val inIdx = codec.dequeueInputBuffer(5_000)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n   = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0,
                            android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                // Lire la sortie PCM
                val outIdx = codec.dequeueOutputBuffer(info, 5_000)
                if (outIdx >= 0) {
                    val buf = codec.getOutputBuffer(outIdx)!!
                    val pcm = ShortArray(info.size / 2)
                    buf.rewind()
                    buf.asShortBuffer().get(pcm)
                    for (s in pcm) sumSq += (s / 32768.0) * (s / 32768.0)
                    count += pcm.size
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
            codec.stop(); codec.release(); extractor.release()
            if (count == 0L) return 0f

            val rms    = Math.sqrt(sumSq / count).toFloat()
            val dbfs   = if (rms > 0) 20f * Math.log10(rms.toDouble()).toFloat() else -60f
            val gain   = (TARGET_DBFS - dbfs).coerceIn(-MAX_GAIN_DB, MAX_GAIN_DB)
            Log.d(TAG, "ReplayGain: rms=${"%.2f".format(rms)} dbfs=${"%.1f".format(dbfs)} gain=${"%.1f".format(gain)}dB")
            return gain
        } catch (e: Exception) {
            Log.w(TAG, "computeReplayGainDb failed: ${e.message}")
            return 0f
        }
    }

    private fun getDuration(file: File): Long = try {
        android.media.MediaMetadataRetriever().use { mmr ->
            mmr.setDataSource(file.absolutePath)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        }
    } catch (_: Exception) { 0L }

    private fun buildForegroundInfo(title: String, progress: Int): ForegroundInfo {
        createChannel()
        return ForegroundInfo(NOTIF_ID,
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Téléchargement")
                .setContentText(title)
                .setProgress(100, progress, progress == 0)
                .setOngoing(true).build(),
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyComplete(title: String) {
        createChannel()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID + 2, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("✓ Téléchargé").setContentText(title).setAutoCancel(true).build())
    }

    private fun notifyError(title: String, error: String) {
        createChannel()
        applicationContext.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID + 1, NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Échec").setContentText(error).setAutoCancel(true).build())
    }

    private fun createChannel() {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Téléchargements", NotificationManager.IMPORTANCE_LOW)
            )
    }
}