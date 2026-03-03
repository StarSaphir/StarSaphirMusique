package com.ytmusic.feature.downloader

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
import com.ytmusic.core.domain.model.DownloadJob
import com.ytmusic.core.domain.model.DownloadJobStatus
import com.ytmusic.core.domain.repository.DownloadRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ─── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class DownloaderViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository
) : ViewModel() {

    val jobs: StateFlow<List<DownloadJob>> = downloadRepository.getAllJobs()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun cancel(workerId: String) = viewModelScope.launch {
        downloadRepository.cancel(workerId)
    }

    fun delete(workerId: String) = viewModelScope.launch {
        downloadRepository.deleteJob(workerId)
    }
}

// ─── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(viewModel: DownloaderViewModel = hiltViewModel()) {
    val jobs by viewModel.jobs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Téléchargements") })

        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Download, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
                    Spacer(Modifier.height(16.dp))
                    Text("Aucun téléchargement", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                }
            }
        } else {
            LazyColumn {
                items(jobs, key = { it.workerId }) { job ->
                    DownloadJobItem(
                        job      = job,
                        onCancel = { viewModel.cancel(job.workerId) },
                        onDelete = { viewModel.delete(job.workerId) }
                    )
                }
            }
        }
    }
}

@Composable
fun DownloadJobItem(job: DownloadJob, onCancel: () -> Unit, onDelete: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(job.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(job.status)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text  = if (job.audioOnly) "Audio uniquement" else "Audio + Vidéo",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                if (job.status == DownloadJobStatus.RUNNING) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { job.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text  = "${job.progressPercent}%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                job.errorMessage?.let {
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        trailingContent = {
            when (job.status) {
                DownloadJobStatus.RUNNING, DownloadJobStatus.QUEUED ->
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, contentDescription = "Annuler", tint = MaterialTheme.colorScheme.error)
                    }
                DownloadJobStatus.DONE, DownloadJobStatus.ERROR, DownloadJobStatus.CANCELLED ->
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer")
                    }
            }
        }
    )
    HorizontalDivider()
}

@Composable
fun StatusChip(status: DownloadJobStatus) {
    val (label, color) = when (status) {
        DownloadJobStatus.QUEUED    -> "En attente"   to MaterialTheme.colorScheme.secondary
        DownloadJobStatus.RUNNING   -> "En cours"     to MaterialTheme.colorScheme.primary
        DownloadJobStatus.DONE      -> "Terminé"      to MaterialTheme.colorScheme.tertiary
        DownloadJobStatus.ERROR     -> "Erreur"       to MaterialTheme.colorScheme.error
        DownloadJobStatus.CANCELLED -> "Annulé"       to MaterialTheme.colorScheme.onSurface
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.15f)
    ) {
        Text(
            text     = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style    = MaterialTheme.typography.labelSmall,
            color    = color
        )
    }
}
