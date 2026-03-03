package com.ytmusic.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import coil.compose.AsyncImage

@Composable
fun MiniPlayerBar(
    modifier:  Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
    onExpand:  () -> Unit = {}
) {
    val state = viewModel.playbackState.collectAsState().value
    val track = state.currentTrack ?: return

    Surface(
        modifier        = modifier.fillMaxWidth(),
        tonalElevation  = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column {
            // Barre de progression fine
            LinearProgressIndicator(
                progress = {
                    if (track.effectiveDurationMs > 0)
                        (state.positionMs / track.effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
                    else 0f
                },
                modifier = Modifier.fillMaxWidth(),
                color    = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { onExpand() }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model              = track.thumbnailPath,
                    contentDescription = null,
                    modifier           = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title,  style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                IconButton(onClick = { viewModel.prev() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Précédent", modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick  = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector        = if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                        modifier           = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.SkipNext, "Suivant", modifier = Modifier.size(22.dp))
                }
                // Bouton fermer la liste d'écoute
                IconButton(
                    onClick  = { viewModel.clearQueue() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Fermer la liste d'écoute",
                        tint               = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier           = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
