package com.ytmusic.feature.player

import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.ytmusic.core.domain.model.PlaybackState
import com.ytmusic.core.domain.model.RepeatMode
import com.ytmusic.core.domain.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    onDismiss: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state: PlaybackState = viewModel.playbackState.collectAsState().value
    val track: Track?        = state.currentTrack

    Scaffold(
        topBar = {
            TopAppBar(
                title           = { Text("Lecture en cours") },
                navigationIcon  = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ExpandMore, "Fermer")
                    }
                },
                actions = {
                    // Fermer toute la liste d'écoute
                    IconButton(onClick = { viewModel.clearQueue(); onDismiss() }) {
                        Icon(Icons.Default.Close, contentDescription = "Vider la liste d'écoute")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // ── Zone vidéo / thumbnail ────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            ) {
                if (track != null) {
                    val isVideo = track.filePath.endsWith(".mp4") || track.filePath.endsWith(".webm")
                    if (isVideo) {
                        ExoVideoPlayer(viewModel = viewModel)
                    } else {
                        AsyncImage(
                            model              = track.thumbnailPath,
                            contentDescription = null,
                            modifier           = Modifier.fillMaxSize(),
                            contentScale       = ContentScale.Fit
                        )
                        PlaybackOverlay(
                            isPlaying = state.isPlaying,
                            onPlay    = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
                            onPrev    = { viewModel.prev() },
                            onNext    = { viewModel.next() }
                        )
                    }
                }
            }

            // ── Titre + artiste ───────────────────────────────────────────────
            if (track != null) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(track.title,  style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }

                // ── Seek bar ─────────────────────────────────────────────────
                SeekBar(
                    positionMs = state.positionMs,
                    durationMs = track.effectiveDurationMs,
                    onSeek     = { viewModel.seekTo(it) }
                )

                // ── Contrôles ─────────────────────────────────────────────────
                PlayerControls(
                    isPlaying      = state.isPlaying,
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode     = state.repeatMode,
                    onPlay         = { if (state.isPlaying) viewModel.pause() else viewModel.play() },
                    onPrev         = { viewModel.prev() },
                    onNext         = { viewModel.next() },
                    onShuffle      = { viewModel.toggleShuffle() },
                    onRepeat       = {
                        viewModel.setRepeatMode(
                            when (state.repeatMode) {
                                RepeatMode.OFF -> RepeatMode.ALL
                                RepeatMode.ALL -> RepeatMode.ONE
                                RepeatMode.ONE -> RepeatMode.OFF
                            }
                        )
                    }
                )
            }

            HorizontalDivider()

            // ── Queue ─────────────────────────────────────────────────────────
            Text(
                text     = "File d'attente (${state.queue.size})",
                style    = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            LazyColumn {
                itemsIndexed(state.queue) { idx, qTrack ->
                    QueueItem(
                        track     = qTrack,
                        isCurrent = qTrack.id == track?.id,
                        onClick   = { viewModel.skipToIndex(idx) },
                        onRemove  = { viewModel.removeFromQueue(idx) }
                    )
                }
            }
        }
    }
}

// ─── QueueItem avec bouton retirer ────────────────────────────────────────────

@Composable
fun QueueItem(
    track:     Track,
    isCurrent: Boolean,
    onClick:   () -> Unit,
    onRemove:  () -> Unit
) {
    ListItem(
        leadingContent = {
            AsyncImage(
                model              = track.thumbnailPath,
                contentDescription = null,
                modifier           = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)),
                contentScale       = ContentScale.Crop
            )
        },
        headlineContent = {
            Text(
                track.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (isCurrent) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
            )
        },
        supportingContent = { Text(track.artist, style = MaterialTheme.typography.bodySmall) },
        trailingContent   = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) Icon(Icons.Default.VolumeUp, null,
                    tint = MaterialTheme.colorScheme.primary)
                // Retirer de la file d'attente (pas de la playlist)
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Retirer de la file",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp))
                }
            }
        },
        modifier = Modifier.clickable { onClick() }
    )
    HorizontalDivider()
}

// ─── SeekBar avec position live ───────────────────────────────────────────────

@Composable
fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Slider(
            value         = fraction,
            onValueChange = { onSeek((it * durationMs).toLong()) },
            modifier      = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─── Contrôles ────────────────────────────────────────────────────────────────

@Composable
fun PlayerControls(
    isPlaying:      Boolean,
    shuffleEnabled: Boolean,
    repeatMode:     RepeatMode,
    onPlay:         () -> Unit,
    onPrev:         () -> Unit,
    onNext:         () -> Unit,
    onShuffle:      () -> Unit,
    onRepeat:       () -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShuffle) {
            Icon(Icons.Default.Shuffle, null,
                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface)
        }
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.SkipPrevious, null, modifier = Modifier.size(36.dp))
        }
        FilledIconButton(onClick = onPlay, modifier = Modifier.size(56.dp)) {
            Icon(
                imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier           = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, null, modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = onRepeat) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else           -> Icons.Default.Repeat
                },
                contentDescription = null,
                tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ─── Overlay contrôles sur thumbnail ──────────────────────────────────────────

@Composable
fun PlaybackOverlay(
    isPlaying: Boolean,
    onPlay:    () -> Unit,
    onPrev:    () -> Unit,
    onNext:    () -> Unit
) {
    Box(
        modifier         = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(24.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
            Box(
                modifier         = Modifier.size(64.dp).background(Color.Black.copy(0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPlay) {
                    Icon(
                        imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint               = Color.White,
                        modifier           = Modifier.size(40.dp)
                    )
                }
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
    }
}

// ─── ExoPlayer vidéo sans contrôles natifs ────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
fun ExoVideoPlayer(viewModel: PlayerViewModel) {
    val controller = viewModel.getMediaController()
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams  = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                useController = false   // contrôles custom uniquement
                player        = controller
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min      = totalSec / 60
    val sec      = totalSec % 60
    return "%d:%02d".format(min, sec)
}
