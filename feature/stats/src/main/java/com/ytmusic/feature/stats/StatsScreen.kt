package com.ytmusic.feature.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytmusic.core.domain.model.*
import com.ytmusic.core.domain.repository.StatsRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    val totalListenedMs: StateFlow<Long> = statsRepository.getTotalListenedMs()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0L)

    val topArtists: StateFlow<List<ArtistStat>> = statsRepository.getTopArtists(10)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val topTracks: StateFlow<List<TrackStat>> = statsRepository.getTopTracks(10)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentTracks: StateFlow<List<Track>> = statsRepository.getRecentTracks(20)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dailyAverageMs: StateFlow<Double> = statsRepository.getDailyAverageMs()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val totalTracks: StateFlow<Int> = trackRepository.getAllTracks()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val totalMs      by viewModel.totalListenedMs.collectAsState()
    val topArtists   by viewModel.topArtists.collectAsState()
    val topTracks    by viewModel.topTracks.collectAsState()
    val recentTracks by viewModel.recentTracks.collectAsState()
    val dailyAvgMs   by viewModel.dailyAverageMs.collectAsState()
    val totalTracks  by viewModel.totalTracks.collectAsState()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { TopAppBar(title = { Text("Statistiques") }) }

        // Global overview
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Vue globale", style = MaterialTheme.typography.titleMedium)
                    StatRow("Temps total d'écoute", formatDuration(totalMs))
                    StatRow("Morceaux dans la bibliothèque", totalTracks.toString())
                    StatRow("Moyenne quotidienne", formatDuration(dailyAvgMs.toLong()))
                }
            }
        }

        // Top Artists
        if (topArtists.isNotEmpty()) {
            item {
                Text("Top Artistes", modifier = Modifier.padding(16.dp, 8.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            items(topArtists.take(5)) { stat ->
                ListItem(
                    leadingContent    = { Icon(Icons.Default.Person, null) },
                    headlineContent   = { Text(stat.artist) },
                    trailingContent   = { Text(formatDuration(stat.totalListenedMs)) }
                )
                HorizontalDivider()
            }
        }

        // Top Tracks
        if (topTracks.isNotEmpty()) {
            item {
                Text("Top Morceaux", modifier = Modifier.padding(16.dp, 8.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            items(topTracks.take(5)) { stat ->
                ListItem(
                    leadingContent    = { Icon(Icons.Default.MusicNote, null) },
                    headlineContent   = { Text(stat.track.title, maxLines = 1) },
                    supportingContent = { Text(stat.track.artist) },
                    trailingContent   = { Text(formatDuration(stat.totalListenedMs)) }
                )
                HorizontalDivider()
            }
        }

        // Recent Tracks
        if (recentTracks.isNotEmpty()) {
            item {
                Text("Récemment écoutés", modifier = Modifier.padding(16.dp, 8.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            items(recentTracks.take(10), key = { it.id }) { track ->
                ListItem(
                    headlineContent   = { Text(track.title, maxLines = 1) },
                    supportingContent = { Text(track.artist) }
                )
                HorizontalDivider()
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0   -> "${h}h ${m}m"
        m > 0   -> "${m}m ${s}s"
        else    -> "${s}s"
    }
}
