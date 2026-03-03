package com.ytmusic.navigation

import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.ytmusic.core.domain.model.DetectedVideo
import com.ytmusic.core.domain.model.Track
import com.ytmusic.feature.browser.BrowserTab
import com.ytmusic.feature.browser.BrowserUiLayer
import com.ytmusic.feature.browser.BrowserViewModel
import com.ytmusic.feature.browser.DownloadBar
import com.ytmusic.feature.browser.buildWebView
import com.ytmusic.feature.browser.resolveUrl
import com.ytmusic.feature.downloader.DownloaderScreen
import com.ytmusic.feature.library.LibraryScreen
import com.ytmusic.feature.player.PlayerViewModel
import com.ytmusic.feature.playlists.PlaylistsScreen
import com.ytmusic.feature.playlists.SuperPlaylistScreen
import com.ytmusic.feature.settings.SettingsScreen
import com.ytmusic.feature.stats.StatsScreen

data class NavItem(val route: String, val label: String, val icon: ImageVector)

@Composable
fun rememberNavItems(): List<NavItem> = remember {
    listOf(
        NavItem("browser",   "Navigateur",      Icons.Default.Language),
        NavItem("downloads", "Téléchargements", Icons.Default.Download),
        NavItem("library",   "Archive",         Icons.Default.MusicNote),
        NavItem("playlists", "Playlists",       Icons.Default.QueueMusic),
        NavItem("superplay", "Play Liste",      Icons.Default.Shuffle),
        NavItem("stats",     "Stats",           Icons.Default.BarChart),
        NavItem("settings",  "Paramètres",      Icons.Default.Settings),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(playerViewModel: PlayerViewModel = hiltViewModel()) {
    val navController     = rememberNavController()
    val navItems          = rememberNavItems()
    var navVisible        by remember { mutableStateOf(true) }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute      = navBackStackEntry?.destination?.route
    val browserViewModel: BrowserViewModel = hiltViewModel()

    // ── Détection vidéo (pour la DownloadBar flottante) ───────────────────────
    val detectedVideo: DetectedVideo? by browserViewModel.detectedVideo.collectAsState()
    var downloadBarHidden by remember { mutableStateOf(false) }

    LaunchedEffect(currentRoute) {
        if (currentRoute != "browser") navVisible = true
    }


    // ── État navigateur — persiste hors du NavHost ────────────────────────────
    // Hauteur de la toolbar browser — pour pousser la WebView en dessous
    var toolbarHeightPx by remember { mutableIntStateOf(0) }

    var tabs        by remember { mutableStateOf(listOf(BrowserTab(id = 0))) }
    var activeTabId by remember { mutableIntStateOf(0) }
    var nextTabId   by remember { mutableIntStateOf(1) }
    var tabTitles   by remember { mutableStateOf(mapOf<Int, String>()) }
    var tabUrls     by remember { mutableStateOf(mapOf<Int, String>()) }
    var isLoading   by remember { mutableStateOf(false) }
    val webViews    = remember { mutableStateMapOf<Int, WebView>() }
    val frameRef    = remember { mutableStateOf<android.widget.FrameLayout?>(null) }

    // Référence mutable pour éviter stale closures dans les lambdas WebView
    val activeTabIdRef = remember { mutableIntStateOf(0) }
    activeTabIdRef.intValue = activeTabId

    val activeWebView = webViews[activeTabId]

    fun makeWebViewCallbacks(tabId: Int): Pair<(Boolean) -> Unit, (String, String) -> Unit> {
        val onLoading: (Boolean) -> Unit = { loading ->
            if (tabId == activeTabIdRef.intValue) isLoading = loading
        }
        val onUrlChanged: (String, String) -> Unit = { url, title ->
            tabUrls   = tabUrls   + (tabId to url)
            tabTitles = tabTitles + (tabId to title)
            if (tabId == activeTabIdRef.intValue) {
                browserViewModel.onUrlChanged(url, title)
                downloadBarHidden = false
            }
        }
        return Pair(onLoading, onUrlChanged)
    }

    fun switchTab(id: Int) {
        webViews[activeTabId]?.visibility = View.INVISIBLE
        webViews[id]?.visibility          = View.VISIBLE
        activeTabId = id
    }

    fun addTab(url: String = "https://www.youtube.com") {
        webViews[activeTabId]?.visibility = View.INVISIBLE
        val newId  = nextTabId++
        val newTab = BrowserTab(id = newId, url = url)
        tabs = tabs + newTab
        // La WebView sera créée dans update{}
        activeTabId = newId
    }

    fun closeTab(id: Int) {
        if (tabs.size == 1) return
        val idx    = tabs.indexOfFirst { it.id == id }
        val nextId = if (activeTabId == id)
            tabs.getOrElse(if (idx > 0) idx - 1 else 1) { tabs.last() }.id
        else activeTabId
        val wv = webViews.remove(id)
        frameRef.value?.removeView(wv)
        wv?.destroy()
        tabs = tabs.filter { it.id != id }
        if (activeTabId == id) switchTab(nextId)
    }

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(modifier = Modifier.fillMaxSize()) {

            // ── Rail de navigation ────────────────────────────────────────────
            AnimatedVisibility(
                visible = navVisible,
                enter   = slideInHorizontally { -it } + fadeIn(),
                exit    = slideOutHorizontally { -it } + fadeOut()
            ) {
                NavigationRail(
                    modifier = Modifier.fillMaxHeight().width(72.dp),
                    header   = {
                        if (currentRoute == "browser") {
                            IconButton(onClick = { navVisible = false }) {
                                Icon(Icons.Default.MenuOpen, "Cacher")
                            }
                        }
                    }
                ) {
                    val dest = navBackStackEntry?.destination
                    navItems.forEach { item ->
                        val selected = dest?.hierarchy?.any { it.route == item.route } == true
                        NavigationRailItem(
                            icon            = { Icon(item.icon, item.label) },
                            label           = null,
                            selected        = selected,
                            alwaysShowLabel = false,
                            onClick         = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState    = true
                                }
                            }
                        )
                    }
                }
            }

            // ── Zone contenu ──────────────────────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {

                // ── FrameLayout Android natif avec toutes les WebViews ────────
                // Créé une seule fois, survit aux changements de route dans NavHost.
                // La visibilité est GONE quand on n'est pas sur le navigateur.
                AndroidView(
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).also { fl ->
                            frameRef.value = fl
                            val (onLoad, onUrl) = makeWebViewCallbacks(0)
                            val firstWv = buildWebView(
                                ctx          = ctx,
                                initialUrl   = "https://www.youtube.com",
                                onLoading    = onLoad,
                                onUrlChanged = onUrl
                            )
                            webViews[0] = firstWv
                            fl.addView(firstWv, ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT)
                        }
                    },
                    update = { fl ->
                        // Créer les WebViews des nouveaux onglets
                        tabs.forEach { tab ->
                            if (webViews[tab.id] == null) {
                                val (onLoad, onUrl) = makeWebViewCallbacks(tab.id)
                                val wv = buildWebView(
                                    ctx          = fl.context,
                                    initialUrl   = tab.url,
                                    onLoading    = onLoad,
                                    onUrlChanged = onUrl
                                )
                                wv.visibility = View.INVISIBLE
                                fl.addView(wv, ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT)
                                webViews[tab.id] = wv
                            }
                            // Synchroniser visibilité de chaque WebView
                            webViews[tab.id]?.visibility =
                                if (tab.id == activeTabId) View.VISIBLE else View.INVISIBLE
                        }
                        // Masquer le FrameLayout entier hors du navigateur
                        fl.visibility =
                            if (currentRoute == "browser" || currentRoute == null)
                                View.VISIBLE else View.GONE
                        // Décaler la WebView sous la toolbar pour éviter que
                        // la barre de recherche YouTube soit cachée derrière notre header
                        if (currentRoute == "browser") {
                            fl.setPadding(0, toolbarHeightPx, 0, 0)
                        } else {
                            fl.setPadding(0, 0, 0, 0)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // ── NavHost ───────────────────────────────────────────────────
                NavHost(
                    navController    = navController,
                    startDestination = "browser",
                    modifier         = Modifier.fillMaxSize()
                ) {
                    composable("browser") {
                        BrowserUiLayer(
                            viewModel     = browserViewModel,
                            navVisible    = navVisible,
                            onToggleNav   = { navVisible = !navVisible },
                            tabs          = tabs,
                            activeTabId   = activeTabId,
                            tabTitles     = tabTitles,
                            tabUrls       = tabUrls,
                            isLoading     = isLoading,
                            activeWebView = activeWebView,
                            onNavigate    = { input ->
                                val url = resolveUrl(input)
                                activeWebView?.loadUrl(url)
                                browserViewModel.navigate(url)
                            },
                            onBack        = { activeWebView?.goBack() },
                            onForward     = { activeWebView?.goForward() },
                            onRefresh     = { activeWebView?.reload() },
                            onNewTab      = { addTab() },
                            onSwitchTab   = { switchTab(it) },
                            onCloseTab    = { closeTab(it) },
                            onToolbarHeight = { h -> toolbarHeightPx = h },
                            onClearData   = {
                                WebStorage.getInstance().deleteAllData()
                                CookieManager.getInstance().removeAllCookies(null)
                                activeWebView?.clearCache(true)
                                activeWebView?.clearHistory()
                            },
                            canGoBack          = activeWebView?.canGoBack() == true,
                            canGoForward       = activeWebView?.canGoForward() == true,

                        )
                    }
                    composable("downloads") { DownloaderScreen() }
                    composable("library") {
                        LibraryScreen(onPlayTrack = { t, i -> playerViewModel.playTracks(t, i) })
                    }
                    composable("playlists") {
                        PlaylistsScreen(onPlayPlaylist = { t, i -> playerViewModel.playTracks(t, i) })
                    }
                    composable("superplay") {
                        SuperPlaylistScreen(onPlay = { t -> playerViewModel.playTracks(t, 0) })
                    }
                    composable("stats")    { StatsScreen() }
                    composable("settings") { SettingsScreen() }
                }

                // ── DownloadBar flottante — par-dessus WebViews et NavHost ─────
                val currentVideo: DetectedVideo? = detectedVideo
                if (currentRoute == "browser" && currentVideo != null && !downloadBarHidden) {
                    Box(
                        modifier         = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        // Appel explicite androidx.compose.animation.AnimatedVisibility
                        // pour éviter la résolution RowScope depuis le Row parent
                        androidx.compose.animation.AnimatedVisibility(
                            visible = true,
                            enter   = slideInVertically { it } + fadeIn(),
                            exit    = slideOutVertically { it } + fadeOut()
                        ) {
                            DownloadBar(
                                video      = currentVideo,
                                onDownload = { title, artist, language, audioOnly ->
                                    browserViewModel.download(currentVideo, title, artist, language, audioOnly)
                                    downloadBarHidden = true
                                },
                                onHide = { downloadBarHidden = true }
                            )
                        }
                    }
                }
            }
        }
    }
}
