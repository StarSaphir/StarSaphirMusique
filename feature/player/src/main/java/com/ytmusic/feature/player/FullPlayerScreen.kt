package com.ytmusic.feature.player

import android.app.Activity
import android.graphics.Bitmap
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.palette.graphics.Palette
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.ytmusic.core.domain.model.PlaybackState
import com.ytmusic.core.domain.model.RepeatMode
import com.ytmusic.core.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ── Extraction couleur dominante via Palette ─────────────────────────────────

@Composable
fun rememberDominantColor(thumbnailPath: String?): Color {
    val context = LocalContext.current
    val defaultColor = Color(0xFF1A1A2E)
    var dominantColor by remember(thumbnailPath) { mutableStateOf(defaultColor) }

    LaunchedEffect(thumbnailPath) {
        if (thumbnailPath == null) { dominantColor = defaultColor; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val req = ImageRequest.Builder(context).data(thumbnailPath).allowHardware(false).build()
                val result = context.imageLoader.execute(req)
                val bitmap = (result as? SuccessResult)?.drawable
                    ?.let { (it as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                    ?: return@withContext
                val palette = Palette.from(bitmap).generate()
                val swatch  = palette.darkVibrantSwatch
                    ?: palette.darkMutedSwatch
                    ?: palette.dominantSwatch
                swatch?.let {
                    dominantColor = Color(it.rgb).copy(alpha = 1f)
                }
            } catch (_: Exception) {}
        }
    }
    return dominantColor
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayerScreen(
    onDismiss:    () -> Unit = {},
    keepScreenOn: Boolean    = false,
    useCoverBackground: Boolean = false,   // paramètre depuis Settings
    viewModel:    PlayerViewModel = hiltViewModel()
) {
    val state: PlaybackState = viewModel.playbackState.collectAsState().value
    val track: Track?        = state.currentTrack

    // Wake lock
    val context = LocalContext.current
    DisposableEffect(keepScreenOn) {
        val window = (context as? Activity)?.window
        if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Couleur dominante — extraite uniquement si l'option est activée
    val dominantColor = if (useCoverBackground) {
        rememberDominantColor(track?.thumbnailPath)
    } else {
        MaterialTheme.colorScheme.background
    }

    // Animation douce lors du changement de morceau
    val animatedBgColor by animateColorAsState(
        targetValue = dominantColor,
        animationSpec = tween(durationMillis = 600),
        label = "bgColor"
    )

    // Gradient : couleur dominante en haut → noir en bas pour garder lisibilité
    val backgroundModifier = if (useCoverBackground) {
        Modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    animatedBgColor.copy(alpha = 0.85f),
                    Color.Black.copy(alpha = 0.95f)
                )
            )
        )
    } else {
        Modifier.background(MaterialTheme.colorScheme.background)
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Lecture en cours",
                    color = if (useCoverBackground) Color.White
                    else MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ExpandMore, "Fermer",
                            tint = if (useCoverBackground) Color.White
                            else MaterialTheme.colorScheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clearQueue(); onDismiss() }) {
                        Icon(Icons.Default.Close, "Vider la liste d'écoute",
                            tint = if (useCoverBackground) Color.White
                            else MaterialTheme.colorScheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier
            .fillMaxSize()
            .then(backgroundModifier)
            .padding(padding)
        ) {
            // ── Zone cover / vidéo ────────────────────────────────────────────
            if (track != null) {
                val isVideo = track.filePath.endsWith(".mp4") || track.filePath.endsWith(".webm")
                if (isVideo) {
                    // Vidéo : ratio 16:9 natif, pleine largeur
                    // Pas d'overlay — les contrôles sont affichés sous la vidéo
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(Color.Black)
                    ) {
                        ExoVideoPlayer(viewModel = viewModel)
                    }
                } else {
                    // Cover audio : carré centré avec hauteur max pour laisser place aux contrôles
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .padding(horizontal = if (useCoverBackground) 40.dp else 0.dp,
                            vertical   = if (useCoverBackground) 16.dp else 0.dp)
                    ) {
                        AsyncImage(
                            model              = track.thumbnailPath,
                            contentDescription = null,
                            modifier           = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .align(Alignment.Center)
                                .clip(if (useCoverBackground) RoundedCornerShape(16.dp)
                                else RoundedCornerShape(0.dp)),
                            contentScale       = ContentScale.Crop
                        )
                    }
                }
            }

            // ── Titre + artiste ───────────────────────────────────────────────
            if (track != null) {
                val textColor = if (useCoverBackground) Color.White
                else MaterialTheme.colorScheme.onBackground
                val subColor  = if (useCoverBackground) Color.White.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
                    Text(track.title, style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, color = textColor)
                    Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = subColor)
                }

                SeekBar(
                    positionMs = state.positionMs,
                    durationMs = track.effectiveDurationMs,
                    onSeek     = { viewModel.seekTo(it) },
                    tintColor  = if (useCoverBackground) Color.White else null
                )

                PlayerControls(
                    isPlaying      = state.isPlaying,
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode     = state.repeatMode,
                    tintColor      = if (useCoverBackground) Color.White else null,
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

            HorizontalDivider(color = if (useCoverBackground) Color.White.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.outlineVariant)

            Text(
                text     = "File d'attente (${state.queue.size})",
                style    = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                color    = if (useCoverBackground) Color.White.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            LazyColumn {
                itemsIndexed(state.queue) { idx, qTrack ->
                    QueueItem(
                        track     = qTrack,
                        isCurrent = qTrack.id == track?.id,
                        onClick   = { viewModel.skipToIndex(idx) },
                        onRemove  = { viewModel.removeFromQueue(idx) },
                        textColor = if (useCoverBackground) Color.White else null
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
    onRemove:  () -> Unit,
    textColor: Color? = null
) {
    val titleColor = when {
        isCurrent  -> textColor ?: MaterialTheme.colorScheme.primary
        textColor != null -> textColor
        else       -> MaterialTheme.colorScheme.onSurface
    }
    val subColor = textColor?.copy(alpha = 0.6f)
        ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)

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
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = titleColor)
        },
        supportingContent = {
            Text(track.artist, style = MaterialTheme.typography.bodySmall, color = subColor)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) Icon(Icons.Default.VolumeUp, null,
                    tint = textColor ?: MaterialTheme.colorScheme.primary)
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Remove, contentDescription = "Retirer de la file",
                        tint = subColor, modifier = Modifier.size(18.dp))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onClick() }
    )
    HorizontalDivider(color = subColor.copy(alpha = 0.15f))
}

// ─── SeekBar avec position live ───────────────────────────────────────────────

@Composable
fun SeekBar(
    positionMs: Long,
    durationMs: Long,
    onSeek:     (Long) -> Unit,
    tintColor:  Color? = null
) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val labelColor = tintColor?.copy(alpha = 0.7f) ?: MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    Column(modifier = Modifier.padding(horizontal = 24.dp)) {
        Slider(
            value         = fraction,
            onValueChange = { onSeek((it * durationMs).toLong()) },
            modifier      = Modifier.fillMaxWidth(),
            colors        = tintColor?.let {
                SliderDefaults.colors(
                    thumbColor            = it,
                    activeTrackColor      = it,
                    inactiveTrackColor    = it.copy(alpha = 0.3f)
                )
            } ?: SliderDefaults.colors()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall, color = labelColor)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = labelColor)
        }
    }
}

// ─── Contrôles ────────────────────────────────────────────────────────────────

@Composable
fun PlayerControls(
    isPlaying:      Boolean,
    shuffleEnabled: Boolean,
    repeatMode:     RepeatMode,
    tintColor:      Color? = null,
    onPlay:         () -> Unit,
    onPrev:         () -> Unit,
    onNext:         () -> Unit,
    onShuffle:      () -> Unit,
    onRepeat:       () -> Unit
) {
    val iconColor    = tintColor ?: MaterialTheme.colorScheme.onSurface
    val activeColor  = tintColor ?: MaterialTheme.colorScheme.primary

    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        IconButton(onClick = onShuffle) {
            Icon(Icons.Default.Shuffle, null,
                tint = if (shuffleEnabled) activeColor else iconColor.copy(alpha = 0.6f))
        }
        IconButton(onClick = onPrev) {
            Icon(Icons.Default.SkipPrevious, null,
                modifier = Modifier.size(36.dp), tint = iconColor)
        }
        FilledIconButton(
            onClick = onPlay,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = tintColor?.copy(alpha = 0.3f) ?: MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Icon(
                imageVector        = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = null,
                modifier           = Modifier.size(32.dp),
                tint               = tintColor ?: MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Default.SkipNext, null,
                modifier = Modifier.size(36.dp), tint = iconColor)
        }
        IconButton(onClick = onRepeat) {
            Icon(
                imageVector = when (repeatMode) {
                    RepeatMode.ONE -> Icons.Default.RepeatOne
                    else           -> Icons.Default.Repeat
                },
                contentDescription = null,
                tint = if (repeatMode != RepeatMode.OFF) activeColor else iconColor.copy(alpha = 0.6f)
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

@androidx.annotation.OptIn(UnstableApi::class)
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