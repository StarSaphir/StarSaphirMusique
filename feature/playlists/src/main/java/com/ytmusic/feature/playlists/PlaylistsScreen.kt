package com.ytmusic.feature.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
class PlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val trackRepository: TrackRepository
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylistsWithTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val allTracks: StateFlow<List<Track>> = trackRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun createPlaylist(name: String) = viewModelScope.launch {
        playlistRepository.createPlaylist(name)
    }

    fun deletePlaylist(id: String) = viewModelScope.launch {
        playlistRepository.deletePlaylist(id)
    }

    fun toggleFavorite(playlist: Playlist) = viewModelScope.launch {
        playlistRepository.setFavorite(playlist.id, !playlist.isFavorite)
    }

    fun removeTrack(playlistId: String, trackId: String) = viewModelScope.launch {
        playlistRepository.removeTrackFromPlaylist(playlistId, trackId)
    }

    fun addTrack(playlistId: String, trackId: String) = viewModelScope.launch {
        playlistRepository.addTrackToPlaylist(playlistId, trackId)
    }
}

// ─── SuperPlaylistViewModel ───────────────────────────────────────────────────

@HiltViewModel
class SuperPlaylistViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {

    val playlists: StateFlow<List<Playlist>> = playlistRepository.getAllPlaylistsWithTracks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds

    private val _superQueue = MutableStateFlow<List<Track>>(emptyList())
    val superQueue: StateFlow<List<Track>> = _superQueue

    fun toggleSelection(playlistId: String) {
        _selectedIds.update { if (playlistId in it) it - playlistId else it + playlistId }
    }

    fun build() = viewModelScope.launch {
        val tracks = playlists.value
            .filter { it.id in _selectedIds.value }
            .flatMap { it.tracks }
            .distinctBy { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            .shuffled()
        _superQueue.value = tracks
    }

    fun clear() { _selectedIds.value = emptySet(); _superQueue.value = emptyList() }
}

// ─── PlaylistsScreen : liste des playlists ────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    viewModel:      PlaylistViewModel = hiltViewModel(),
    onPlayPlaylist: (tracks: List<Track>, startIndex: Int) -> Unit = { _, _ -> }
) {
    val playlists  by viewModel.playlists.collectAsState()
    val allTracks  by viewModel.allTracks.collectAsState()
    var showCreate by remember { mutableStateOf(false) }
    // Playlist ouverte en vue détail
    var openedPlaylist by remember { mutableStateOf<Playlist?>(null) }

    // Vue détail → écran dédié
    openedPlaylist?.let { pl ->
        // Trouver la version fraîche dans playlists (mise à jour en temps réel)
        val fresh = playlists.find { it.id == pl.id } ?: pl
        val alreadyIn = fresh.tracks.map { it.id }.toSet()
        val available = allTracks.filter { it.id !in alreadyIn }

        PlaylistDetailScreen(
            playlist       = fresh,
            availableTracks = available,
            onBack         = { openedPlaylist = null },
            onPlay         = { tracks, idx -> onPlayPlaylist(tracks, idx) },
            onRemoveTrack  = { trackId -> viewModel.removeTrack(fresh.id, trackId) },
            onAddTracks    = { ids -> ids.forEach { viewModel.addTrack(fresh.id, it) } },
            onToggleFav    = { viewModel.toggleFavorite(fresh) },
            onDelete       = { viewModel.deletePlaylist(fresh.id); openedPlaylist = null }
        )
        return
    }

    // ── Liste des playlists ───────────────────────────────────────────────────
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title   = { Text("Playlists") },
            actions = {
                IconButton(onClick = { showCreate = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Nouvelle playlist")
                }
            }
        )
        if (playlists.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune playlist.\nAppuie sur + pour en créer une.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        } else {
            LazyColumn {
                items(playlists, key = { it.id }) { playlist ->
                    PlaylistRow(
                        playlist  = playlist,
                        onClick   = { openedPlaylist = playlist },
                        onPlay    = { onPlayPlaylist(playlist.tracks, 0) },
                        onShuffle = {
                            val shuffled = playlist.tracks.shuffled()
                            onPlayPlaylist(shuffled, 0)
                        }
                    )
                }
            }
        }
    }

    if (showCreate) {
        CreatePlaylistDialog(
            onCreate  = { name -> viewModel.createPlaylist(name); showCreate = false },
            onDismiss = { showCreate = false }
        )
    }
}

// ─── PlaylistRow : ligne simple dans la liste ─────────────────────────────────

@Composable
fun PlaylistRow(
    playlist:  Playlist,
    onClick:   () -> Unit,
    onPlay:    () -> Unit,
    onShuffle: () -> Unit
) {
    ListItem(
        modifier          = Modifier.clickable { onClick() },
        leadingContent    = {
            // Grille 2×2 des miniatures ou icône par défaut
            Box(
                modifier          = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
                contentAlignment  = Alignment.Center
            ) {
                val thumbs = playlist.tracks.mapNotNull { it.thumbnailPath }.take(1)
                if (thumbs.isNotEmpty()) {
                    AsyncImage(
                        model              = thumbs[0],
                        contentDescription = null,
                        modifier           = Modifier.fillMaxSize(),
                        contentScale       = ContentScale.Crop
                    )
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.QueueMusic, null,
                            modifier = Modifier.padding(12.dp),
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        headlineContent   = {
            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text("${playlist.tracks.size} morceau(x)", style = MaterialTheme.typography.bodySmall)
        },
        trailingContent   = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlay) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Lire en ordre")
                }
                IconButton(onClick = onShuffle) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Lire en aléatoire",
                        tint = MaterialTheme.colorScheme.primary)
                }
                Icon(Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
    )
    HorizontalDivider()
}

// ─── PlaylistDetailScreen : vue dédiée d'une playlist ────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlist:        Playlist,
    availableTracks: List<Track>,
    onBack:          () -> Unit,
    onPlay:          (tracks: List<Track>, startIndex: Int) -> Unit,
    onRemoveTrack:   (trackId: String) -> Unit,
    onAddTracks:     (List<String>) -> Unit,
    onToggleFav:     () -> Unit,
    onDelete:        () -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showConfirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── TopAppBar ─────────────────────────────────────────────────────────
        TopAppBar(
            title           = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon  = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, "Retour")
                }
            },
            actions = {
                IconButton(onClick = onToggleFav) {
                    Icon(
                        imageVector = if (playlist.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favori",
                        tint = if (playlist.isFavorite) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.PlaylistAdd, "Ajouter des morceaux")
                }
                IconButton(onClick = { showConfirmDelete = true }) {
                    Icon(Icons.Default.Delete, "Supprimer", tint = MaterialTheme.colorScheme.error)
                }
            }
        )

        // ── Boutons Lire / Shuffle ─────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick  = { onPlay(playlist.tracks, 0) },
                enabled  = playlist.tracks.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Lire en ordre")
            }
            FilledTonalButton(
                onClick  = {
                    if (playlist.tracks.isNotEmpty()) {
                        val shuffled = playlist.tracks.shuffled()
                        onPlay(shuffled, 0)
                    }
                },
                enabled  = playlist.tracks.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Shuffle, null, modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Aléatoire")
            }
        }

        HorizontalDivider()

        // ── Liste des morceaux ────────────────────────────────────────────────
        if (playlist.tracks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MusicOff, null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    Spacer(Modifier.height(8.dp))
                    Text("Aucun morceau dans cette playlist.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Ajouter des morceaux")
                    }
                }
            }
        } else {
            LazyColumn {
                itemsIndexed(playlist.tracks) { idx, track ->
                    ListItem(
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${idx + 1}",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.width(20.dp))
                                Spacer(Modifier.width(6.dp))
                                AsyncImage(
                                    model              = track.thumbnailPath,
                                    contentDescription = null,
                                    modifier           = Modifier.size(44.dp).clip(MaterialTheme.shapes.small),
                                    contentScale       = ContentScale.Crop
                                )
                            }
                        },
                        headlineContent = {
                            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(track.artist, style = MaterialTheme.typography.bodySmall)
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemoveTrack(track.id) }) {
                                Icon(Icons.Default.Remove, "Retirer de la playlist",
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        modifier = Modifier.clickable { onPlay(playlist.tracks, idx) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    // ── Dialog ajout de morceaux ──────────────────────────────────────────────
    if (showAddDialog) {
        AddTrackToPlaylistDialog(
            availableTracks = availableTracks,
            onAdd           = { ids -> onAddTracks(ids); showAddDialog = false },
            onDismiss       = { showAddDialog = false }
        )
    }

    // ── Confirmation suppression ──────────────────────────────────────────────
    if (showConfirmDelete) {
        AlertDialog(
            onDismissRequest = { showConfirmDelete = false },
            title            = { Text("Supprimer \"${playlist.name}\" ?") },
            text             = { Text("Cette action est irréversible.") },
            confirmButton    = {
                Button(
                    onClick = { onDelete(); showConfirmDelete = false },
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton    = { TextButton(onClick = { showConfirmDelete = false }) { Text("Annuler") } }
        )
    }
}

// ─── Dialog : ajout multi-sélection ──────────────────────────────────────────

@Composable
fun AddTrackToPlaylistDialog(
    availableTracks: List<Track>,
    onAdd:           (trackIds: List<String>) -> Unit,
    onDismiss:       () -> Unit
) {
    var search   by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() }
    val filtered = availableTracks.filter {
        search.isBlank() ||
        it.title.contains(search, ignoreCase = true) ||
        it.artist.contains(search, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ajouter des morceaux", modifier = Modifier.weight(1f))
                if (selected.isNotEmpty()) {
                    Text("${selected.size} sélectionné(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value         = search,
                    onValueChange = { search = it },
                    label         = { Text("Rechercher…") },
                    leadingIcon   = { Icon(Icons.Default.Search, null) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                if (availableTracks.isEmpty()) {
                    Text("Tous les morceaux sont déjà dans cette playlist.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                } else {
                    Column(modifier = Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                        filtered.forEach { track ->
                            val isChecked = track.id in selected
                            ListItem(
                                modifier = Modifier.clickable {
                                    if (isChecked) selected.remove(track.id) else selected.add(track.id)
                                },
                                leadingContent = {
                                    Checkbox(
                                        checked         = isChecked,
                                        onCheckedChange = {
                                            if (isChecked) selected.remove(track.id)
                                            else selected.add(track.id)
                                        }
                                    )
                                },
                                headlineContent = {
                                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                },
                                supportingContent = {
                                    Text(track.artist, style = MaterialTheme.typography.bodySmall)
                                },
                                trailingContent = {
                                    AsyncImage(
                                        model              = track.thumbnailPath,
                                        contentDescription = null,
                                        modifier           = Modifier.size(36.dp).clip(MaterialTheme.shapes.small),
                                        contentScale       = ContentScale.Crop
                                    )
                                }
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(selected.toList()) }, enabled = selected.isNotEmpty()) {
                Text("Ajouter (${selected.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ─── Dialogs utilitaires ──────────────────────────────────────────────────────

@Composable
fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouvelle playlist") },
        text  = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Nom") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }, enabled = name.isNotBlank()) {
                Text("Créer")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ─── SuperPlaylistScreen ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperPlaylistScreen(
    viewModel: SuperPlaylistViewModel = hiltViewModel(),
    onPlay:    (List<Track>) -> Unit = {}
) {
    val playlists   by viewModel.playlists.collectAsState()
    val selectedIds by viewModel.selectedIds.collectAsState()
    val superQueue  by viewModel.superQueue.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title   = { Text("Play Liste") },
            actions = {
                if (superQueue.isNotEmpty()) {
                    IconButton(onClick = viewModel::clear) { Icon(Icons.Default.Clear, null) }
                }
            }
        )
        if (superQueue.isEmpty()) {
            Text("Sélectionner des playlists à mélanger :",
                modifier = Modifier.padding(16.dp),
                style    = MaterialTheme.typography.bodyMedium)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(playlists, key = { it.id }) { playlist ->
                    val selected = playlist.id in selectedIds
                    ListItem(
                        headlineContent   = { Text(playlist.name) },
                        supportingContent = { Text("${playlist.tracks.size} morceau(x)") },
                        leadingContent    = {
                            Checkbox(checked = selected,
                                onCheckedChange = { viewModel.toggleSelection(playlist.id) })
                        },
                        modifier = Modifier.clickable { viewModel.toggleSelection(playlist.id) }
                    )
                    HorizontalDivider()
                }
            }
            Button(
                onClick  = { viewModel.build() },
                enabled  = selectedIds.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Icon(Icons.Default.Shuffle, null)
                Spacer(Modifier.width(8.dp))
                Text("Générer la super-playlist")
            }
        } else {
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${superQueue.size} morceaux", modifier = Modifier.weight(1f))
                Button(onClick = { onPlay(superQueue) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Lire")
                }
            }
            LazyColumn {
                itemsIndexed(superQueue) { idx, track ->
                    ListItem(
                        leadingContent    = { Text("${idx + 1}",
                            style = MaterialTheme.typography.labelSmall) },
                        headlineContent   = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(track.artist) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
