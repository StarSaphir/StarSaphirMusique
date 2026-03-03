package com.ytmusic.feature.browser

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtensionsScreen(onDismiss: () -> Unit) {
    val context  = LocalContext.current
    val prefs    = remember { context.getSharedPreferences("extensions_prefs", Context.MODE_PRIVATE) }

    // État des extensions (activé/désactivé persisté dans SharedPreferences)
    var extensions by remember {
        mutableStateOf(
            AdBlockEngine.BUILTIN_EXTENSIONS.map { ext ->
                ext.copy(enabled = prefs.getBoolean("ext_${ext.id}", ext.enabled))
            }
        )
    }
    var loadingStatus by remember { mutableStateOf("") }
    var stats         by remember { mutableStateOf(AdBlockEngine.getLoadedStats()) }
    var showAddDialog by remember { mutableStateOf(false) }

    fun saveAndReload() {
        extensions.forEach { ext ->
            prefs.edit().putBoolean("ext_${ext.id}", ext.enabled).apply()
        }
        AdBlockEngine.reset()
        loadingStatus = "Rechargement des listes…"
        AdBlockEngine.loadExtensions(
            context    = context,
            extensions = extensions,
            onProgress = { msg -> loadingStatus = msg },
            onComplete = {
                loadingStatus = "Chargé • ${AdBlockEngine.getLoadedStats()}"
                stats = AdBlockEngine.getLoadedStats()
            }
        )
    }

    // Charger au premier affichage
    LaunchedEffect(Unit) {
        if (loadingStatus.isEmpty()) {
            loadingStatus = "Chargement des listes de filtres…"
            AdBlockEngine.loadExtensions(
                context    = context,
                extensions = extensions,
                onProgress = { msg -> loadingStatus = msg },
                onComplete = {
                    loadingStatus = "Chargé"
                    stats = AdBlockEngine.getLoadedStats()
                }
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title          = { Text("Extensions & Filtres") },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, "Retour")
                }
            },
            actions = {
                IconButton(onClick = { saveAndReload() }) {
                    Icon(Icons.Default.Refresh, "Recharger les listes")
                }
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, "Ajouter une liste")
                }
            }
        )

        // Statut de chargement
        if (loadingStatus.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color    = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Row(
                    modifier          = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (loadingStatus.startsWith("Chargement") || loadingStatus.startsWith("Recharg")) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.CheckCircle, null,
                            modifier = Modifier.size(16.dp),
                            tint     = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(loadingStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Text(
                    "Listes de filtres intégrées",
                    style    = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    color    = MaterialTheme.colorScheme.primary
                )
            }

            items(extensions.filter { it.builtIn }, key = { it.id }) { ext ->
                ExtensionItem(
                    extension = ext,
                    onToggle  = { enabled ->
                        extensions = extensions.map {
                            if (it.id == ext.id) it.copy(enabled = enabled) else it
                        }
                        prefs.edit().putBoolean("ext_${ext.id}", enabled).apply()
                    }
                )
            }

            val custom = extensions.filter { !it.builtIn }
            if (custom.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        "Listes personnalisées",
                        style    = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color    = MaterialTheme.colorScheme.primary
                    )
                }
                items(custom, key = { it.id }) { ext ->
                    ExtensionItem(
                        extension  = ext,
                        onToggle   = { enabled ->
                            extensions = extensions.map {
                                if (it.id == ext.id) it.copy(enabled = enabled) else it
                            }
                            prefs.edit().putBoolean("ext_${ext.id}", enabled).apply()
                        },
                        onDelete   = {
                            extensions = extensions.filter { it.id != ext.id }
                            prefs.edit().remove("ext_${ext.id}").remove("custom_${ext.id}").apply()
                        }
                    )
                }
            }

            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                // Instructions
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color    = MaterialTheme.colorScheme.surfaceVariant,
                    shape    = MaterialTheme.shapes.medium
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Comment ajouter une liste ?",
                            style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Entrez l'URL d'une liste au format ABP/uBlock (.txt). " +
                            "Exemples : FilterLists.com, GitHub uBlockOrigin/uAssets.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick  = { saveAndReload() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Icon(Icons.Default.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Appliquer les changements")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showAddDialog) {
        AddFilterListDialog(
            onAdd = { name, url ->
                val newExt = BrowserExtension(
                    id          = "custom_${System.currentTimeMillis()}",
                    name        = name,
                    description = url,
                    filterUrl   = url,
                    builtIn     = false,
                    enabled     = true
                )
                extensions = extensions + newExt
                // Persister l'extension custom
                prefs.edit()
                    .putBoolean("ext_${newExt.id}", true)
                    .putString("custom_${newExt.id}", "$name|||$url")
                    .apply()
                showAddDialog = false
                saveAndReload()
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

@Composable
fun ExtensionItem(
    extension: BrowserExtension,
    onToggle:  (Boolean) -> Unit,
    onDelete:  (() -> Unit)? = null
) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = if (extension.builtIn) Icons.Default.Shield else Icons.Default.Extension,
                contentDescription = null,
                tint = if (extension.enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        },
        headlineContent = {
            Text(extension.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                extension.description,
                style   = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color   = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, "Supprimer",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp))
                    }
                }
                Switch(
                    checked         = extension.enabled,
                    onCheckedChange = onToggle
                )
            }
        }
    )
    HorizontalDivider()
}

@Composable
fun AddFilterListDialog(
    onAdd:     (name: String, url: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var url  by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter une liste de filtres") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("Nom de la liste") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true
                )
                OutlinedTextField(
                    value         = url,
                    onValueChange = { url = it },
                    label         = { Text("URL (.txt format ABP/uBlock)") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    placeholder   = { Text("https://...", style = MaterialTheme.typography.bodySmall) }
                )
                Text(
                    "Exemple : https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        },
        confirmButton = {
            Button(
                onClick  = { onAdd(name.trim(), url.trim()) },
                enabled  = name.isNotBlank() && url.startsWith("http")
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
