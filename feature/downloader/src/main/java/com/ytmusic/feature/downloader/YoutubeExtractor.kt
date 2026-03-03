package com.ytmusic.feature.downloader

import android.util.Log
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "YoutubeExtractor"

// ─── Downloader HTTP minimal requis par NewPipeExtractor ──────────────────────

object AndroidDownloader : Downloader() {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    override fun execute(request: Request): Response {
        val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
            requestMethod = request.httpMethod()
            connectTimeout = 30_000
            readTimeout    = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            request.headers().forEach { (name, values) ->
                values.forEach { v -> setRequestProperty(name, v) }
            }
            request.dataToSend()?.let {
                doOutput = true
                outputStream.use { os -> os.write(it) }
            }
        }

        val code = conn.responseCode
        val headers: Map<String, List<String>> = conn.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key!! }
        val body = try {
            (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText() ?: ""
        } catch (_: Exception) { "" }
        conn.disconnect()

        return Response(code, conn.responseMessage ?: "", headers, body, request.url())
    }
}

// ─── Données retournées ───────────────────────────────────────────────────────

data class StreamInfo(
    val url:      String,
    val mimeType: String,
    val itag:     Int,
    val bitrate:  Int,
    val isVideo:  Boolean = false
)

// ─── Extracteur principal ─────────────────────────────────────────────────────

object YoutubeExtractor {

    @Volatile private var initialized = false

    private fun ensureInit() {
        if (!initialized) synchronized(this) {
            if (!initialized) {
                NewPipe.init(AndroidDownloader)
                initialized = true
                Log.d(TAG, "NewPipe initialized")
            }
        }
    }

    fun extract(videoId: String, audioOnly: Boolean = true): StreamInfo {
        ensureInit()
        val pageUrl = "https://www.youtube.com/watch?v=$videoId"
        Log.d(TAG, "Fetching: $pageUrl audioOnly=$audioOnly")

        val extractor = ServiceList.YouTube.getStreamExtractor(pageUrl)
        extractor.fetchPage()

        return if (audioOnly) extractAudio(extractor) else extractVideo(extractor)
    }

    private fun extractAudio(extractor: org.schabi.newpipe.extractor.stream.StreamExtractor): StreamInfo {
        val streams: List<AudioStream> = extractor.audioStreams
        Log.d(TAG, "Audio streams: ${streams.size}")
        if (streams.isEmpty()) throw RuntimeException("Aucun flux audio disponible")

        val valid = streams.filter { !it.content.isNullOrBlank() }
        if (valid.isEmpty()) throw RuntimeException("Aucun flux audio avec URL directe")

        val best = valid.maxWithOrNull(
            compareBy<AudioStream> {
                val fmt = it.format?.name?.lowercase() ?: ""
                if (fmt.contains("m4a") || fmt.contains("aac") || fmt.contains("mp4")) 1 else 0
            }.thenBy { it.averageBitrate }
        ) ?: valid.first()

        Log.d(TAG, "Audio: format=${best.format?.name} bitrate=${best.averageBitrate}")
        return StreamInfo(
            url      = best.content!!,
            mimeType = best.format?.mimeType ?: "audio/mp4",
            itag     = 0,
            bitrate  = best.averageBitrate,
            isVideo  = false
        )
    }

    private fun extractVideo(extractor: org.schabi.newpipe.extractor.stream.StreamExtractor): StreamInfo {
        // Essayer d'abord les streams vidéo avec audio intégré (non-DASH)
        val videoStreams: List<VideoStream> = extractor.videoStreams
        Log.d(TAG, "Video streams (with audio): ${videoStreams.size}")

        val validVideos = videoStreams.filter { !it.content.isNullOrBlank() }
        if (validVideos.isNotEmpty()) {
            // Préférer mp4 en qualité max
            val best = validVideos.maxWithOrNull(
                compareBy<VideoStream> {
                    val fmt = it.format?.name?.lowercase() ?: ""
                    if (fmt.contains("mp4")) 1 else 0
                }.thenBy {
                    it.resolution?.replace("p", "")?.toIntOrNull() ?: 0
                }
            ) ?: validVideos.first()

            Log.d(TAG, "Video: format=${best.format?.name} res=${best.resolution}")
            return StreamInfo(
                url      = best.content!!,
                mimeType = best.format?.mimeType ?: "video/mp4",
                itag     = 0,
                bitrate  = 0,
                isVideo  = true
            )
        }

        // Fallback : si pas de stream vidéo avec audio, prendre audio seul
        Log.w(TAG, "No video+audio streams, falling back to audio only")
        return extractAudio(extractor)
    }
}
