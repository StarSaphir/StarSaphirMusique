package com.ytmusic.feature.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.hilt.navigation.compose.hiltViewModel
import com.ytmusic.core.domain.model.DetectedVideo
import android.content.Context
import androidx.compose.ui.platform.LocalContext

// ─── Modèle onglet ────────────────────────────────────────────────────────────

data class BrowserTab(
    val id:    Int,
    val title: String = "Nouvel onglet",
    val url:   String = "https://www.youtube.com"
)

// ─── BrowserUiLayer : interface Compose pure (pas de WebView ici) ─────────────
// Les WebViews sont gérées dans AppNavigation pour survivre aux changements de route

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserUiLayer(
    viewModel:    BrowserViewModel = hiltViewModel(),
    navVisible:   Boolean,
    onToggleNav:  () -> Unit,
    tabs:         List<BrowserTab>,
    activeTabId:  Int,
    tabTitles:    Map<Int, String>,
    tabUrls:      Map<Int, String>,
    isLoading:    Boolean,
    activeWebView: android.webkit.WebView?,
    onNavigate:   (String) -> Unit,
    onBack:       () -> Unit,
    onForward:    () -> Unit,
    onRefresh:    () -> Unit,
    onNewTab:     () -> Unit,
    onSwitchTab:  (Int) -> Unit,
    onCloseTab:   (Int) -> Unit,
    onClearData:      () -> Unit,
    onToolbarHeight:  (Int) -> Unit = {},
    canGoBack:        Boolean,
    canGoForward:     Boolean,
) {
    var showTabs       by remember { mutableStateOf(false) }
    var showMenu       by remember { mutableStateOf(false) }

    // BackHandler : reculer dans l'historique, ouvrir nav, ou rien
    BackHandler(enabled = true) {
        when {
            activeWebView?.canGoBack() == true -> activeWebView.goBack()
            !navVisible                        -> onToggleNav()
            else                               -> { /* ne pas fermer l'app */ }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Toolbar ───────────────────────────────────────────────────────────
        Box(modifier = Modifier.onGloballyPositioned { coords ->
            onToolbarHeight(coords.size.height)
        }) { BrowserToolbar(
            currentUrl    = tabUrls[activeTabId] ?: "https://www.youtube.com",
            navVisible    = navVisible,
            isLoading     = isLoading,
            tabCount      = tabs.size,
            showMenu      = showMenu,
            onToggleNav   = onToggleNav,
            onBack        = onBack,
            onForward     = onForward,
            onNavigate    = onNavigate,
            onNewTab      = onNewTab,
            onShowTabs    = { showTabs = !showTabs },
            onMenuToggle  = { showMenu = !showMenu },
            onMenuDismiss = { showMenu = false },
            canGoBack     = canGoBack,
            canGoForward  = canGoForward,
            onRefresh     = { showMenu = false; onRefresh() },
            onClearData       = { showMenu = false; onClearData() },
        ) }

        // ── Barre d'onglets ───────────────────────────────────────────────────
        AnimatedVisibility(visible = showTabs) {
            TabBar(
                tabs        = tabs,
                activeTabId = activeTabId,
                tabTitles   = tabTitles,
                onSelect    = { id -> onSwitchTab(id); showTabs = false },
                onClose     = onCloseTab,
                onNewTab    = { onNewTab(); showTabs = false }
            )
        }

        // ── Zone WebView (transparente — le vrai contenu est dans AppNavigation) ──
        // Ce spacer prend toute la place pour que la toolbar soit bien positionnée
        Box(modifier = Modifier.weight(1f))

        // Écran Extensions (plein écran par-dessus)

        // DownloadBar gérée dans AppNavigation (superposée aux WebViews)
    }
}

// ─── Toolbar ─────────────────────────────────────────────────────────────────

@Composable
fun BrowserToolbar(
    currentUrl:    String,
    navVisible:    Boolean,
    isLoading:     Boolean,
    tabCount:      Int,
    showMenu:      Boolean,
    onToggleNav:   () -> Unit,
    onBack:        () -> Unit,
    onForward:     () -> Unit,
    onRefresh:     () -> Unit,
    onNavigate:    (String) -> Unit,
    onNewTab:      () -> Unit,
    onShowTabs:    () -> Unit,
    onMenuToggle:  () -> Unit,
    onMenuDismiss: () -> Unit,
    canGoBack:     Boolean,
    canGoForward:  Boolean,
    onClearData:         () -> Unit,
) {
    // Mettre à jour le texte seulement quand l'URL externe change réellement
    var text by remember { mutableStateOf(currentUrl) }
    LaunchedEffect(currentUrl) { text = currentUrl }

    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp) {
        Column {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Menu nav
                IconButton(onClick = onToggleNav, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (navVisible) Icons.Default.MenuOpen else Icons.Default.Menu,
                        "Menu", modifier = Modifier.size(20.dp)
                    )
                }
                // Retour
                IconButton(onClick = onBack, enabled = canGoBack, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowBack, "Retour", modifier = Modifier.size(20.dp))
                }
                // Avant
                IconButton(onClick = onForward, enabled = canGoForward, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ArrowForward, "Avant", modifier = Modifier.size(20.dp))
                }

                // Champ URL — occupe tout l'espace restant
                OutlinedTextField(
                    value         = text,
                    onValueChange = { text = it },
                    modifier      = Modifier.weight(1f).height(46.dp),
                    singleLine    = true,
                    placeholder   = {
                        Text("Rechercher ou entrer une URL", style = MaterialTheme.typography.bodySmall)
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    trailingIcon = {
                        IconButton(onClick = { onNavigate(text) }) {
                            Icon(Icons.Default.Search, "Aller", modifier = Modifier.size(18.dp))
                        }
                    }
                )

                // Onglets avec badge
                IconButton(onClick = onShowTabs, modifier = Modifier.size(36.dp)) {
                    BadgedBox(badge = { if (tabCount > 1) Badge { Text("$tabCount") } }) {
                        Icon(Icons.Default.Tab, "Onglets", modifier = Modifier.size(20.dp))
                    }
                }

                // Menu ⋮ avec toutes les actions
                Box {
                    IconButton(onClick = onMenuToggle, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.MoreVert, "Options", modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(
                            text        = { Text("Nouvel onglet") },
                            leadingIcon = { Icon(Icons.Default.Add, null) },
                            onClick     = { onMenuDismiss(); onNewTab() }
                        )
                        DropdownMenuItem(
                            text        = { Text("Rafraîchir la page") },
                            leadingIcon = { Icon(Icons.Default.Refresh, null) },
                            onClick     = { onRefresh() }
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text        = { Text("Effacer données & cache") },
                            leadingIcon = { Icon(Icons.Default.DeleteSweep, null) },
                            onClick     = { onClearData() }
                        )
                    }
                }
            }

            // Indicateur de chargement
            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color    = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ─── Barre d'onglets ──────────────────────────────────────────────────────────

@Composable
fun TabBar(
    tabs:        List<BrowserTab>,
    activeTabId: Int,
    tabTitles:   Map<Int, String>,
    onSelect:    (Int) -> Unit,
    onClose:     (Int) -> Unit,
    onNewTab:    () -> Unit
) {
    Surface(tonalElevation = 6.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val isActive = tab.id == activeTabId
                val title    = tabTitles[tab.id]?.takeIf { it.isNotBlank() } ?: tab.title
                Surface(
                    modifier       = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onSelect(tab.id) }.padding(end = 4.dp),
                    color          = if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape          = RoundedCornerShape(8.dp),
                    tonalElevation = if (isActive) 4.dp else 0.dp
                ) {
                    Row(
                        modifier          = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Language, null, modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(title, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp))
                        Spacer(Modifier.width(4.dp))
                        if (tabs.size > 1) {
                            IconButton(onClick = { onClose(tab.id) }, modifier = Modifier.size(18.dp)) {
                                Icon(Icons.Default.Close, "Fermer", modifier = Modifier.size(11.dp))
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onNewTab, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, "Nouvel onglet", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── WebView builder ──────────────────────────────────────────────────────────

@SuppressLint("SetJavaScriptEnabled")
fun buildWebView(
    ctx:         android.content.Context,
    initialUrl:  String = "https://www.youtube.com",
    onLoading:   (Boolean) -> Unit        = {},
    onUrlChanged:(String, String) -> Unit = { _, _ -> }
): WebView {
    return WebView(ctx).apply {
        settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            // UA mobile : YouTube affiche sa vraie interface avec barre de recherche
            // et la mise en page est adaptée à la taille de l'écran
            userAgentString                  = MOBILE_UA
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            loadWithOverviewMode             = true
            useWideViewPort                  = false  // false = layout mobile natif
            mixedContentMode                 = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode                        = WebSettings.LOAD_DEFAULT
            allowFileAccess                  = true
            setTextZoom(100)
        }
        isLongClickable = true

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                mainHandler.post { onLoading(true) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                mainHandler.post { onLoading(false) }
                mainHandler.post { onUrlChanged(url, view.title ?: "") }
                CookieManager.getInstance().flush()
                view.evaluateJavascript(DISMISS_POPUPS_JS, null)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String) {
                onUrlChanged(view.url ?: "", title)
            }
        }
        loadUrl(initialUrl)
    }
}

// ─── Helpers URL ──────────────────────────────────────────────────────────────

fun resolveUrl(input: String): String {
    val trimmed = input.trim()
    return when {
        trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
        trimmed.contains(".") && !trimmed.contains(" ") -> "https://$trimmed"
        else -> "https://www.google.com/search?q=${android.net.Uri.encode(trimmed)}"
    }
}

// ─── DownloadBar & DownloadDialog ─────────────────────────────────────────────

@Composable
fun DownloadBar(
    video:      DetectedVideo,
    onDownload: (title: String, artist: String, language: String?, audioOnly: Boolean) -> Unit,
    onHide:     () -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    Surface(tonalElevation = 6.dp, shadowElevation = 4.dp) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(video.title, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Button(onClick = { showDialog = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Télécharger", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onHide, modifier = Modifier.size(30.dp)) {
                Icon(Icons.Default.ExpandMore, "Masquer")
            }
        }
    }
    if (showDialog) {
        DownloadDialog(
            suggestedTitle = video.title,
            onConfirm = { title, artist, language, audioOnly ->
                onDownload(title, artist, language, audioOnly); showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
fun DownloadDialog(
    suggestedTitle: String,
    onConfirm: (title: String, artist: String, language: String?, audioOnly: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var title     by remember { mutableStateOf(suggestedTitle) }
    var artist    by remember { mutableStateOf("") }
    var language  by remember { mutableStateOf("") }
    var audioOnly by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Télécharger") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it },
                    label = { Text("Titre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = artist, onValueChange = { artist = it },
                    label = { Text("Artiste (optionnel → Anonyme)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = language, onValueChange = { language = it },
                    label = { Text("Langue (optionnel)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (audioOnly) Icons.Default.MusicNote else Icons.Default.Videocam,
                            null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (audioOnly) "Audio uniquement" else "Vidéo + Audio", modifier = Modifier.weight(1f))
                        Switch(checked = audioOnly, onCheckedChange = { audioOnly = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(title.trim(), artist.trim().ifBlank { "Anonyme" },
                    language.trim().takeIf { it.isNotBlank() }, audioOnly)
            }, enabled = title.isNotBlank()) { Text("Télécharger") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

// ─── Constants ────────────────────────────────────────────────────────────────

// UA mobile Android Chrome — YouTube affiche sa vraie interface avec barre de recherche
// et respecte la taille réelle de l'écran (pas de débordement hors zone visible)
private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

private const val DISMISS_POPUPS_JS = """
(function() {
    function dismiss() {
        ['button[aria-label*="Reject"]','button[aria-label*="No thanks"]',
         'button[aria-label*="Ignorer"]','button[aria-label*="Non merci"]',
         '#dismiss-button','yt-button-shape#dismiss-button button'].forEach(function(sel){
            var b=document.querySelector(sel); if(b) b.click();
        });
    }
    dismiss(); setTimeout(dismiss,1500); setTimeout(dismiss,3000);
})();
"""


