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
import com.ytmusic.core.domain.repository.StatsRepository
import com.ytmusic.feature.library.LibraryScanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val statsRepository:    StatsRepository,
    private val libraryScanner:     LibraryScanner
) : ViewModel() {

    val normalizationEnabled: StateFlow<Boolean> = settingsRepository.getNormalizationEnabled()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val defaultShuffle: StateFlow<Boolean> = settingsRepository.getDefaultShuffle()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val defaultQuality: StateFlow<String> = settingsRepository.getDefaultQuality()
        .stateIn(viewModelScope, SharingStarted.Lazily, "192k")

    val keepScreenOn: StateFlow<Boolean> = settingsRepository.getKeepScreenOn()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    val coverBackground: StateFlow<Boolean> = settingsRepository.getCoverBackground()
        .stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun setNormalization(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNormalizationEnabled(enabled)
    }

    fun setDefaultShuffle(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDefaultShuffle(enabled)
    }

    fun setQuality(quality: String) = viewModelScope.launch {
        settingsRepository.setDefaultQuality(quality)
    }

    fun setKeepScreenOn(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setKeepScreenOn(enabled)
    }

    fun setCoverBackground(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setCoverBackground(enabled)
    }

    // ── Reset statistiques ───────────────────────────────────────────────────

    private val _resetState = MutableStateFlow<ResetState>(ResetState.Idle)
    val resetState: StateFlow<ResetState> = _resetState

    sealed class ResetState {
        object Idle    : ResetState()
        object Running : ResetState()
        object Done    : ResetState()
    }

    fun resetListenStats() {
        if (_resetState.value is ResetState.Running) return
        viewModelScope.launch {
            _resetState.value = ResetState.Running
            statsRepository.resetListenStats()
            _resetState.value = ResetState.Done
        }
    }

    // ── Réindexation bibliothèque ─────────────────────────────────────────────

    sealed class ScanState {
        object Idle    : ScanState()
        object Running : ScanState()
        data class Done(val inserted: Int, val skipped: Int) : ScanState()
    }

    private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scanState

    fun scanLibrary() {
        if (_scanState.value is ScanState.Running) return
        viewModelScope.launch {
            _scanState.value = ScanState.Running
            val result = libraryScanner.scan()
            _scanState.value = ScanState.Done(result.inserted, result.skipped)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val normalization  by viewModel.normalizationEnabled.collectAsState()
    val defaultShuffle by viewModel.defaultShuffle.collectAsState()
    val defaultQuality by viewModel.defaultQuality.collectAsState()
    val keepScreenOn      by viewModel.keepScreenOn.collectAsState()
    val coverBackground   by viewModel.coverBackground.collectAsState()
    val scanState      by viewModel.scanState.collectAsState()
    val resetState     by viewModel.resetState.collectAsState()

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
                SettingSwitch(
                    icon    = Icons.Default.LightMode,
                    title   = "Garder l'écran allumé",
                    subtitle = "Empêche la mise en veille pendant la lecture",
                    checked = keepScreenOn,
                    onCheckedChange = viewModel::setKeepScreenOn
                )
            }

            item {
                SettingSwitch(
                    icon    = Icons.Default.Palette,
                    title   = "Fond couleur dominante",
                    subtitle = "Teinte le lecteur avec la couleur de la couverture",
                    checked = coverBackground,
                    onCheckedChange = viewModel::setCoverBackground
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
                Text("Bibliothèque",
                    modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }

            item {
                val isScanning = scanState is SettingsViewModel.ScanState.Running
                ListItem(
                    leadingContent  = { Icon(Icons.Default.LibraryMusic, null) },
                    headlineContent = { Text("Réindexer la bibliothèque") },
                    supportingContent = {
                        Text(
                            when (val s = scanState) {
                                is SettingsViewModel.ScanState.Idle    -> "Retrouve les musiques après une réinstallation"
                                is SettingsViewModel.ScanState.Running -> "Scan en cours…"
                                is SettingsViewModel.ScanState.Done    -> "${s.inserted} ajoutée(s), ${s.skipped} déjà présente(s)"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        if (isScanning) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            FilledTonalButton(onClick = viewModel::scanLibrary) {
                                Text("Scanner")
                            }
                        }
                    }
                )
                HorizontalDivider()
            }

            item {
                var showResetConfirm by remember { mutableStateOf(false) }
                val isResetting = resetState is SettingsViewModel.ResetState.Running

                ListItem(
                    leadingContent  = { Icon(Icons.Default.DeleteSweep, null) },
                    headlineContent = { Text("Réinitialiser les statistiques") },
                    supportingContent = {
                        Text(
                            when (resetState) {
                                is SettingsViewModel.ResetState.Idle    -> "Efface uniquement les temps d'écoute"
                                is SettingsViewModel.ResetState.Running -> "Réinitialisation…"
                                is SettingsViewModel.ResetState.Done    -> "Statistiques réinitialisées"
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    trailingContent = {
                        if (isResetting) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            FilledTonalButton(
                                onClick = { showResetConfirm = true },
                                colors  = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) { Text("Effacer") }
                        }
                    }
                )
                HorizontalDivider()

                if (showResetConfirm) {
                    AlertDialog(
                        onDismissRequest = { showResetConfirm = false },
                        icon    = { Icon(Icons.Default.Warning, null) },
                        title   = { Text("Réinitialiser les statistiques ?") },
                        text    = { Text("Cette action effacera uniquement les temps d'écoute enregistrés. Vos musiques et playlists ne seront pas affectées.") },
                        confirmButton = {
                            Button(
                                onClick = { viewModel.resetListenStats(); showResetConfirm = false },
                                colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("Effacer") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetConfirm = false }) { Text("Annuler") }
                        }
                    )
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