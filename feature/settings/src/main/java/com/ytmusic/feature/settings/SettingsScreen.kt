package com.ytmusic.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytmusic.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val normalizationEnabled: StateFlow<Boolean> = settingsRepository.getNormalizationEnabled()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val defaultShuffle: StateFlow<Boolean> = settingsRepository.getDefaultShuffle()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val defaultQuality: StateFlow<String> = settingsRepository.getDefaultQuality()
        .stateIn(viewModelScope, SharingStarted.Lazily, "192k")

    fun setNormalization(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNormalizationEnabled(enabled)
    }

    fun setDefaultShuffle(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDefaultShuffle(enabled)
    }

    fun setQuality(quality: String) = viewModelScope.launch {
        settingsRepository.setDefaultQuality(quality)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val normalization  by viewModel.normalizationEnabled.collectAsState()
    val defaultShuffle by viewModel.defaultShuffle.collectAsState()
    val defaultQuality by viewModel.defaultQuality.collectAsState()

    val qualities = listOf("128k" to "128 kbps (économique)", "192k" to "192 kbps (équilibre)", "320k" to "320 kbps (haute qualité)")

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Paramètres") })

        LazyColumn {
            item {
                Text("Lecture",
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }

            item {
                SettingSwitch(
                    icon    = Icons.Default.GraphicEq,
                    title   = "Normalisation du volume",
                    subtitle = "Applique un gain compensatoire non-destructif",
                    checked = normalization,
                    onCheckedChange = viewModel::setNormalization
                )
            }

            item {
                SettingSwitch(
                    icon    = Icons.Default.Shuffle,
                    title   = "Shuffle par défaut",
                    subtitle = "Activer le shuffle à chaque nouvelle lecture de playlist",
                    checked = defaultShuffle,
                    onCheckedChange = viewModel::setDefaultShuffle
                )
            }

            item {
                Text("Téléchargements",
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Qualité audio par défaut", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    qualities.forEach { (value, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultQuality == value,
                                onClick  = { viewModel.setQuality(value) }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(label)
                        }
                    }
                }
            }

            item {
                Text("Stockage",
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }

            item {
                ListItem(
                    leadingContent  = { Icon(Icons.Default.Storage, null) },
                    headlineContent = { Text("Stockage interne privé") },
                    supportingContent = { Text("Les fichiers sont supprimés avec l'application") }
                )
            }
        }
    }
}

@Composable
fun SettingSwitch(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        leadingContent  = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    )
    HorizontalDivider()
}
