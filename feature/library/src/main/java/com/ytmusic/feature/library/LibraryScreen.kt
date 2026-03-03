package com.ytmusic.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.ytmusic.core.domain.model.Playlist
import com.ytmusic.core.domain.model.Track
import com.ytmusic.core.domain.repository.PlaylistRepository
import com.ytmusic.core.domain.repository.TrackRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val tracks: StateFlow<List<Track>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) trackRepository.getAllTracks()
            else trackRepository.searchTracks(query)
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylistsWithTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun onSearchChanged(q: String) { _searchQuery.value = q }

    fun deleteTrack(track: Track) = viewModelScope.launch {
        trackRepository.deleteTrack(track)
        java.io.File(track.filePath).delete()
    }

    fun addToPlaylist(trackId: String, playlistId: String) = viewModelScope.launch {
        playlistRepository.addTrackToPlaylist(playlistId, trackId)
    }

    fun updateMetadata(track: Track, title: String, artist: String, language: String?) =
        viewModelScope.launch {
            trackRepository.updateMetadata(track.id, title, artist, language)
        }

    fun updateTrim(track: Track, startMs: Long, endMs: Long?) =
        viewModelScope.launch {
            trackRepository.updateTrim(track.id, startMs, endMs)
        }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
    onPlayTrack: (List<Track>, Int) -> Unit = { _, _ -> }
) {
    val tracks      by viewModel.tracks.collectAsState()
    val playlists   by viewModel.playlists.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var editingTrack       by remember { mutableStateOf<Track?>(null) }
    var trimmingTrack      by remember { mutableStateOf<Track?>(null) }
    var addToPlaylistTrack by remember { mutableStateOf<Track?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Archive") })

        OutlinedTextField(
            value         = searchQuery,
            onValueChange = viewModel::onSearchChanged,
            modifier      = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder   = { Text("Rechercher…") },
            leadingIcon   = { Icon(Icons.Default.Search, null) },
            singleLine    = true
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(tracks, key = { it.id }) { track ->
                TrackItem(
                    track           = track,
                    onPlay          = { onPlayTrack(tracks, tracks.indexOf(track)) },
                    onDelete        = { viewModel.deleteTrack(track) },
                    onEditMeta      = { editingTrack = track },
                    onEditTrim      = { trimmingTrack = track },
                    onAddToPlaylist = { addToPlaylistTrack = track }
                )
            }
        }
    }

    editingTrack?.let { track ->
        MetadataDialog(
            track     = track,
            onSave    = { title, artist, lang ->
                viewModel.updateMetadata(track, title, artist, lang)
                editingTrack = null
            },
            onDismiss = { editingTrack = null }
        )
    }

    trimmingTrack?.let { track ->
        TrimDialog(
            track     = track,
            onSave    = { start, end ->
                viewModel.updateTrim(track, start, end)
                trimmingTrack = null
            },
            onDismiss = { trimmingTrack = null }
        )
    }

    addToPlaylistTrack?.let { track ->
        AddToPlaylistDialog(
            playlists = playlists,
            onAdd     = { playlistId ->
                viewModel.addToPlaylist(track.id, playlistId)
                addToPlaylistTrack = null
            },
            onDismiss = { addToPlaylistTrack = null }
        )
    }
}

// ─── Track Item ───────────────────────────────────────────────────────────────

@Composable
fun TrackItem(
    track:           Track,
    onPlay:          () -> Unit,
    onDelete:        () -> Unit,
    onEditMeta:      () -> Unit,
    onEditTrim:      () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        modifier        = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        leadingContent  = {
            AsyncImage(
                model              = track.thumbnailPath,
                contentDescription = null,
                modifier           = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small),
                contentScale       = ContentScale.Crop
            )
        },
        headlineContent = {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                text  = track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                }
                DropdownMenu(
                    expanded        = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text        = { Text("Ajouter à une playlist") },
                        onClick     = { menuExpanded = false; onAddToPlaylist() },
                        leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) }
                    )
                    DropdownMenuItem(
                        text        = { Text("Modifier les métadonnées") },
                        onClick     = { menuExpanded = false; onEditMeta() },
                        leadingIcon = { Icon(Icons.Default.Edit, null) }
                    )
                    DropdownMenuItem(
                        text        = { Text("Découper") },
                        onClick     = { menuExpanded = false; onEditTrim() },
                        leadingIcon = { Icon(Icons.Default.ContentCut, null) }
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text        = { Text("Supprimer", color = MaterialTheme.colorScheme.error) },
                        onClick     = { menuExpanded = false; onDelete() },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) }
                    )
                }
            }
        }
    )
    HorizontalDivider()
}

// ─── Dialogs ──────────────────────────────────────────────────────────────────

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onAdd:     (playlistId: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Ajouter à une playlist") },
        text             = {
            if (playlists.isEmpty()) {
                Text("Aucune playlist disponible.\nCrée d'abord une playlist dans l'onglet Playlists.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    playlists.forEach { playlist ->
                        ListItem(
                            headlineContent   = { Text(playlist.name) },
                            supportingContent = {
                                Text(
                                    "${playlist.tracks.size} morceau(x)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            trailingContent   = {
                                FilledTonalIconButton(onClick = { onAdd(playlist.id) }) {
                                    Icon(Icons.Default.Add, contentDescription = "Ajouter")
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton  = {},
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Fermer") } }
    )
}

@Composable
fun MetadataDialog(
    track:     Track,
    onSave:    (String, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var title    by remember { mutableStateOf(track.title) }
    var artist   by remember { mutableStateOf(track.artist) }
    var language by remember { mutableStateOf(track.language ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Modifier les métadonnées") },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = title,
                    onValueChange = { title = it },
                    label         = { Text("Titre") },
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = artist,
                    onValueChange = { artist = it },
                    label         = { Text("Artiste") },
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = language,
                    onValueChange = { language = it },
                    label         = { Text("Langue (optionnel)") },
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                onSave(title, artist, language.takeIf { it.isNotBlank() })
            }) { Text("Enregistrer") }
        },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun TrimDialog(
    track:     Track,
    onSave:    (Long, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var startSec by remember { mutableStateOf((track.trimStartMs / 1000).toString()) }
    var endSec   by remember { mutableStateOf(track.trimEndMs?.let { (it / 1000).toString() } ?: "") }
    val totalSec = track.durationMs / 1000

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Découpe audio") },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Durée totale : ${totalSec}s", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value         = startSec,
                    onValueChange = { startSec = it },
                    label         = { Text("Début (secondes)") }
                )
                OutlinedTextField(
                    value         = endSec,
                    onValueChange = { endSec = it },
                    label         = { Text("Fin (secondes, vide = fin)") }
                )
                Text(
                    "⚠ La découpe est appliquée à la lecture uniquement.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton  = {
            TextButton(onClick = {
                val start = (startSec.toLongOrNull() ?: 0L) * 1000L
                val end   = endSec.toLongOrNull()?.let { it * 1000L }
                onSave(start, end)
            }) { Text("Appliquer") }
        },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
