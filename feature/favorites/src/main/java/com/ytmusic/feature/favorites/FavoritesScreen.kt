package com.ytmusic.feature.favorites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel : uniquement les morceaux favoris ──────────────────────────────

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val trackRepository: TrackRepository
) : ViewModel() {

    val favoriteTracks: StateFlow<List<Track>> = trackRepository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun toggleFavorite(track: Track) = viewModelScope.launch {
        trackRepository.setFavorite(track.id, !track.isFavorite)
    }
}

// ─── FavoritesScreen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    viewModel:   FavoritesViewModel = hiltViewModel(),
    onPlayTrack: (tracks: List<Track>, startIndex: Int) -> Unit = { _, _ -> }
) {
    val tracks by viewModel.favoriteTracks.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title   = { Text("Favoris") },
            actions = {
                if (tracks.isNotEmpty()) {
                    // Bouton lecture aléatoire de tous les favoris
                    IconButton(onClick = { onPlayTrack(tracks.shuffled(), 0) }) {
                        Icon(Icons.Default.Shuffle, contentDescription = "Lecture aléatoire")
                    }
                    IconButton(onClick = { onPlayTrack(tracks, 0) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Tout lire")
                    }
                }
            }
        )

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Star, null,
                        modifier = Modifier.size(64.dp),
                        tint     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Aucun morceau en favori",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Appuie sur ★ depuis l'archive pour en ajouter.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    )
                }
            }
        } else {
            LazyColumn {
                itemsIndexed(tracks, key = { _, t -> t.id }) { idx, track ->
                    FavoriteTrackItem(
                        track    = track,
                        index    = idx,
                        onClick  = { onPlayTrack(tracks, idx) },
                        onToggle = { viewModel.toggleFavorite(track) }
                    )
                }
            }
        }
    }
}

@Composable
fun FavoriteTrackItem(
    track:    Track,
    index:    Int,
    onClick:  () -> Unit,
    onToggle: () -> Unit
) {
    ListItem(
        modifier          = Modifier.clickable { onClick() },
        leadingContent    = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${index + 1}",
                    style    = MaterialTheme.typography.labelSmall,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.width(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                AsyncImage(
                    model              = track.thumbnailPath,
                    contentDescription = null,
                    modifier           = Modifier
                        .size(44.dp)
                        .clip(MaterialTheme.shapes.small),
                    contentScale       = ContentScale.Crop
                )
            }
        },
        headlineContent   = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                "${track.artist}${track.language?.let { " • $it" } ?: ""}",
                style = MaterialTheme.typography.bodySmall
            )
        },
        trailingContent   = {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector        = Icons.Default.Star,
                    contentDescription = "Retirer des favoris",
                    tint               = MaterialTheme.colorScheme.primary
                )
            }
        }
    )
    HorizontalDivider()
}
