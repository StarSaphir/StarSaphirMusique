package com.ytmusic.feature.browser

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytmusic.core.domain.model.DetectedVideo
import com.ytmusic.core.domain.repository.DownloadRepository
import com.ytmusic.core.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

private val Context.browserDataStore by preferencesDataStore("browser_state")
private val KEY_LAST_URL = stringPreferencesKey("last_url")

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val detectionManager: VideoDetectionManager,
    private val downloadRepository: DownloadRepository,
    private val settingsRepository: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val detectedVideo: StateFlow<DetectedVideo?> = detectionManager.detectedVideo

    private val _currentUrl = MutableStateFlow("https://www.youtube.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    init {
        viewModelScope.launch {
            context.browserDataStore.data
                .map { it[KEY_LAST_URL] }
                .firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { _currentUrl.value = it }
        }
    }

    fun onUrlChanged(url: String, title: String) {
        if (url.isBlank() || url == "about:blank") return
        _currentUrl.value = url
        detectionManager.onUrlChanged(url, title)
        viewModelScope.launch {
            context.browserDataStore.edit { it[KEY_LAST_URL] = url }
        }
    }

    fun navigate(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://www.google.com/search?q=${input.replace(" ", "+")}"
        }
        _currentUrl.value = url
    }

    fun download(video: DetectedVideo, title: String, artist: String, language: String?, audioOnly: Boolean) {
        viewModelScope.launch {
            val quality = settingsRepository.getDefaultQuality().first()
            downloadRepository.enqueue(
                videoId   = video.videoId,
                title     = title,
                artist    = artist,
                language  = language,
                audioOnly = audioOnly,
                quality   = quality
            )
        }
    }
}
