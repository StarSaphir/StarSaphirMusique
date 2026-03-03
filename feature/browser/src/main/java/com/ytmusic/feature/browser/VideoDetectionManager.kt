package com.ytmusic.feature.browser

import com.ytmusic.core.domain.model.DetectedVideo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoDetectionManager @Inject constructor() {

    private val _detectedVideo = MutableStateFlow<DetectedVideo?>(null)
    val detectedVideo: StateFlow<DetectedVideo?> = _detectedVideo.asStateFlow()

    /** Appelé à chaque changement d'URL dans la WebView */
    fun onUrlChanged(url: String, pageTitle: String = "") {
        val videoId = extractVideoId(url)
        if (videoId != null) {
            _detectedVideo.value = DetectedVideo(
                videoId = videoId,
                title   = pageTitle.ifBlank { "Vidéo YouTube" },
                url     = url
            )
        } else {
            _detectedVideo.value = null
        }
    }

    fun clear() { _detectedVideo.value = null }

    private fun extractVideoId(url: String): String? {
        // https://www.youtube.com/watch?v=VIDEO_ID
        val watchRegex = Regex("""[?&]v=([a-zA-Z0-9_-]{11})""")
        // https://youtu.be/VIDEO_ID
        val shortRegex = Regex("""youtu\.be/([a-zA-Z0-9_-]{11})""")
        // YouTube Shorts
        val shortsRegex = Regex("""youtube\.com/shorts/([a-zA-Z0-9_-]{11})""")

        return watchRegex.find(url)?.groupValues?.get(1)
            ?: shortRegex.find(url)?.groupValues?.get(1)
            ?: shortsRegex.find(url)?.groupValues?.get(1)
    }
}
