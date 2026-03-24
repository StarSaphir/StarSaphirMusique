package com.ytmusic.feature.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytmusic.core.domain.repository.LanguageStat
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

    val totalSessionCount: StateFlow<Int> = statsRepository.getTotalSessionCount()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)

    val topArtists: StateFlow<List<ArtistStat>> = statsRepository.getTopArtists(50)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val topTracks: StateFlow<List<TrackStat>> = statsRepository.getTopTracks(50)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val topLanguages: StateFlow<List<LanguageStat>> = statsRepository.getTopLanguages(50)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val recentTracks: StateFlow<List<Track>> = statsRepository.getRecentTracks(20)
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dailyAverageMs: StateFlow<Double> = statsRepository.getDailyAverageMs()
        .stateIn(viewModelScope, SharingStarted.Lazily, 0.0)

    val totalTracks: StateFlow<Int> = trackRepository.getAllTracks()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.Lazily, 0)
}

// ─── Tabs ─────────────────────────────────────────────────────────────────────

private enum class StatsTab { GLOBAL, ARTISTES, MUSIQUES, LANGUES }

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val totalMs       by viewModel.totalListenedMs.collectAsState()
    val totalSessions by viewModel.totalSessionCount.collectAsState()
    val topArtists    by viewModel.topArtists.collectAsState()
    val topTracks     by viewModel.topTracks.collectAsState()
    val topLanguages  by viewModel.topLanguages.collectAsState()
    val recentTracks  by viewModel.recentTracks.collectAsState()
    val dailyAvgMs    by viewModel.dailyAverageMs.collectAsState()
    val totalTracks   by viewModel.totalTracks.collectAsState()

    var selectedTab by remember { mutableStateOf(StatsTab.GLOBAL) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Statistiques") })

        ScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
            StatsTab.entries.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick  = { selectedTab = tab },
                    text = {
                        Text(when (tab) {
                            StatsTab.GLOBAL   -> "Global"
                            StatsTab.ARTISTES -> "Artistes"
                            StatsTab.MUSIQUES -> "Musiques"
                            StatsTab.LANGUES  -> "Langues"
                        })
                    }
                )
            }
        }

        when (selectedTab) {
            StatsTab.GLOBAL   -> GlobalTab(totalMs, totalSessions, totalTracks, dailyAvgMs, recentTracks)
            StatsTab.ARTISTES -> ArtistesTab(topArtists)
            StatsTab.MUSIQUES -> MusiquesTab(topTracks)
            StatsTab.LANGUES  -> LanguesTab(topLanguages)
        }
    }
}

// ─── Onglet Global ────────────────────────────────────────────────────────────

@Composable
private fun GlobalTab(
    totalMs:       Long,
    totalSessions: Int,
    totalTracks:   Int,
    dailyAvgMs:    Double,
    recentTracks:  List<Track>
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Vue globale", style = MaterialTheme.typography.titleMedium)
                    HorizontalDivider()
                    StatRow("Temps total d'écoute",          formatDuration(totalMs))
                    StatRow("Nombre total d'écoutes",        "$totalSessions")
                    StatRow("Morceaux dans la bibliothèque", "$totalTracks")
                    StatRow("Moyenne quotidienne",           formatDuration(dailyAvgMs.toLong()))
                }
            }
        }
        if (recentTracks.isNotEmpty()) {
            item {
                Text("Récemment écoutés",
                    modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp),
                    style    = MaterialTheme.typography.titleSmall,
                    color    = MaterialTheme.colorScheme.primary)
            }
            items(recentTracks, key = { it.id }) { track ->
                ListItem(
                    leadingContent    = { Icon(Icons.Default.History, null) },
                    headlineContent   = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(track.artist, style = MaterialTheme.typography.bodySmall) }
                )
                HorizontalDivider()
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─── Onglet Artistes ──────────────────────────────────────────────────────────

@Composable
private fun ArtistesTab(topArtists: List<ArtistStat>) {
    if (topArtists.isEmpty()) { EmptyState("Aucune donnée d'écoute par artiste."); return }
    val maxMs = topArtists.maxOf { it.totalListenedMs }.coerceAtLeast(1L)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("${topArtists.size} artiste(s)",
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        items(topArtists) { stat ->
            StatItemWithBar(
                rank     = topArtists.indexOf(stat) + 1,
                title    = stat.artist,
                subtitle = formatDuration(stat.totalListenedMs),
                barFill  = stat.totalListenedMs.toFloat() / maxMs.toFloat(),
                icon     = Icons.Default.Person
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─── Onglet Musiques ──────────────────────────────────────────────────────────

@Composable
private fun MusiquesTab(topTracks: List<TrackStat>) {
    if (topTracks.isEmpty()) { EmptyState("Aucune donnée d'écoute par morceau."); return }
    val maxMs = topTracks.maxOf { it.totalListenedMs }.coerceAtLeast(1L)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("${topTracks.size} morceau(x)",
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        items(topTracks) { stat ->
            StatItemWithBar(
                rank     = topTracks.indexOf(stat) + 1,
                title    = stat.track.title,
                subtitle = "${stat.track.artist} • ${stat.sessionCount} écoute(s) • ${formatDuration(stat.totalListenedMs)}",
                barFill  = stat.totalListenedMs.toFloat() / maxMs.toFloat(),
                icon     = Icons.Default.MusicNote
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─── Onglet Langues ───────────────────────────────────────────────────────────

@Composable
private fun LanguesTab(topLanguages: List<LanguageStat>) {
    if (topLanguages.isEmpty()) { EmptyState("Aucun morceau avec une langue définie."); return }
    val maxMs = topLanguages.maxOf { it.totalListenedMs }.coerceAtLeast(1L)
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("${topLanguages.size} langue(s)",
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        }
        items(topLanguages) { stat ->
            StatItemWithBar(
                rank     = topLanguages.indexOf(stat) + 1,
                title    = stat.language,
                subtitle = "${stat.sessionCount} écoute(s) • ${formatDuration(stat.totalListenedMs)}",
                barFill  = stat.totalListenedMs.toFloat() / maxMs.toFloat(),
                icon     = Icons.Default.Language
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ─── Composants partagés ──────────────────────────────────────────────────────

/**
 * Ligne avec barre de progression proportionnelle au maximum de la liste.
 * Le #1 remplit toute la largeur, les suivants sont proportionnels.
 */
@Composable
private fun StatItemWithBar(
    rank:    Int,
    title:   String,
    subtitle: String,
    barFill: Float,
    icon:    androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("#$rank",
                style    = MaterialTheme.typography.labelMedium,
                color    = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(28.dp))
            Icon(icon, null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress  = { barFill.coerceIn(0f, 1f) },
            modifier  = Modifier.fillMaxWidth().height(3.dp),
            color     = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
    HorizontalDivider()
}

@Composable
private fun EmptyState(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
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
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else  -> "${s}s"
    }
}