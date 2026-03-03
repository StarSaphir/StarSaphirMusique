package com.ytmusic.feature.browser

import android.content.Context
import android.webkit.WebResourceResponse
import android.webkit.WebResourceRequest
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.net.URL

// ─── Extension model ──────────────────────────────────────────────────────────

data class BrowserExtension(
    val id:          String,
    val name:        String,
    val description: String,
    val filterUrl:   String,   // URL de la liste de filtres
    val enabled:     Boolean   = true,
    val builtIn:     Boolean   = false
)

// ─── AdBlockEngine ────────────────────────────────────────────────────────────

object AdBlockEngine {

    // Listes de filtres intégrées (vraies listes EasyList/uBlock depuis GitHub)
    val BUILTIN_EXTENSIONS = listOf(
        BrowserExtension(
            id          = "easylist",
            name        = "EasyList",
            description = "Liste principale de blocage des publicités (EasyList)",
            filterUrl   = "https://easylist.to/easylist/easylist.txt",
            builtIn     = true,
            enabled     = true
        ),
        BrowserExtension(
            id          = "easyprivacy",
            name        = "EasyPrivacy",
            description = "Bloque les trackers et scripts de surveillance",
            filterUrl   = "https://easylist.to/easylist/easyprivacy.txt",
            builtIn     = true,
            enabled     = true
        ),
        BrowserExtension(
            id          = "ublock-filters",
            name        = "uBlock Origin – Filtres de base",
            description = "Listes de filtres officielles uBlock Origin",
            filterUrl   = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            builtIn     = true,
            enabled     = true
        ),
        BrowserExtension(
            id          = "ublock-badware",
            name        = "uBlock Origin – Badware",
            description = "Bloque les sites malveillants et logiciels espions",
            filterUrl   = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
            builtIn     = true,
            enabled     = true
        ),
        BrowserExtension(
            id          = "annoyances",
            name        = "uBlock Origin – Nuisances",
            description = "Bloque les popups cookie, newsletters, notifications",
            filterUrl   = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/annoyances.txt",
            builtIn     = true,
            enabled     = false  // optionnel
        ),
        BrowserExtension(
            id          = "french",
            name        = "Liste FR",
            description = "Filtres spécifiques aux sites francophones",
            filterUrl   = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters-2024.txt",
            builtIn     = true,
            enabled     = false
        )
    )

    // Règles chargées en mémoire depuis les listes téléchargées
    private val loadedDomainRules  = mutableSetOf<String>()
    private val loadedPatternRules = mutableListOf<String>()
    private val loadedCssFilters   = mutableListOf<String>()
    private var isLoaded           = false

    // ── Règles statiques (domaines purement publicitaires, jamais de contenu légitime) ──
    private val staticDomainRules = setOf(
        // Réseaux pub Google (pas googletagmanager ni analytics qui peuvent casser des sites)
        "doubleclick.net",
        "googlesyndication.com",
        "pagead2.googlesyndication.com",
        "tpc.googlesyndication.com",
        "adservice.google.com",
        // YouTube ad delivery (distinct du contenu vidéo)
        "ads.youtube.com",
        // Réseaux pub tiers — domaines 100% publicitaires
        "adsrvr.org", "adnxs.com", "rubiconproject.com",
        "pubmatic.com", "casalemedia.com",
        "criteo.com", "criteo.net",
        "taboola.com", "outbrain.com",
        "moatads.com", "moatpixel.com",
        "omtrdc.net", "demdex.net"
    )

    // Patterns très spécifiques — uniquement pour les endpoints pub YouTube connus
    private val staticPatternRules = listOf(
        "/api/stats/ads",      // endpoint stats pubs YouTube
        "/pagead/lvz",         // Google ad pixel précis
        "/ptracking",          // YouTube pre-roll tracking
        "get_midroll_info"     // YouTube midroll
    )

    // ── Chargement asynchrone des listes depuis internet ──────────────────────

    fun loadExtensions(
        context: Context,
        extensions: List<BrowserExtension>,
        onProgress: (String) -> Unit = {},
        onComplete: () -> Unit = {}
    ) {
        if (isLoaded) { onComplete(); return }
        CoroutineScope(Dispatchers.IO).launch {
            extensions.filter { it.enabled }.forEach { ext ->
                try {
                    withContext(Dispatchers.Main) { onProgress("Chargement : ${ext.name}") }
                    val prefs = context.getSharedPreferences("adblock_cache", Context.MODE_PRIVATE)
                    val cacheKey  = "filter_${ext.id}"
                    val cacheTime = "filter_time_${ext.id}"
                    val now       = System.currentTimeMillis()
                    val cached    = prefs.getString(cacheKey, null)
                    val lastFetch = prefs.getLong(cacheTime, 0)
                    // Rafraîchir toutes les 24h
                    val content = if (cached != null && now - lastFetch < 86_400_000L) {
                        cached
                    } else {
                        try {
                            val text = URL(ext.filterUrl).readText()
                            prefs.edit()
                                .putString(cacheKey, text)
                                .putLong(cacheTime, now)
                                .apply()
                            text
                        } catch (e: Exception) { cached ?: "" }
                    }
                    parseFilterList(content)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isLoaded = true
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    // Parser format ABP/uBlock — strict pour éviter les faux positifs
    private fun parseFilterList(content: String) {
        // Domaines légitimes à ne JAMAIS bloquer, peu importe les listes
        val hardWhitelist = setOf(
            "google.com", "googleapis.com", "gstatic.com", "youtube.com",
            "ytimg.com", "googlevideo.com", "ggpht.com", "googleusercontent.com",
            "cloudflare.com", "cloudfront.net", "akamaized.net",
            "jsdelivr.net", "jquery.com", "bootstrapcdn.com",
            "fonts.googleapis.com", "fonts.gstatic.com",
            "accounts.google.com", "ssl.gstatic.com"
        )
        content.lines().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("!") || line.startsWith("[") -> return@forEach
                // Whitelist — ajouter à notre whitelist interne
                line.startsWith("@@") -> return@forEach
                // Filtre CSS cosmétique uniquement (##.selector sans domaine prefix)
                line.startsWith("##") -> {
                    val css = line.removePrefix("##")
                    if (css.isNotBlank() && css.length < 200) {
                        synchronized(loadedCssFilters) { loadedCssFilters.add(css) }
                    }
                }
                // Filtre CSS avec domaine (example.com##.selector) → garder juste le CSS
                line.contains("##") && !line.startsWith("||") -> {
                    val css = line.substringAfter("##")
                    if (css.isNotBlank() && css.length < 200) {
                        synchronized(loadedCssFilters) { loadedCssFilters.add(css) }
                    }
                }
                // Règle de domaine strict (||example.com^) — le cas le plus sûr
                line.startsWith("||") && line.contains("^") && !line.contains("*") -> {
                    val domain = line.removePrefix("||")
                        .substringBefore("^").substringBefore("/")
                        .substringBefore("$").trim().lowercase()
                    // Vérifier : domaine valide, pas dans la whitelist, pas trop court
                    if (domain.isNotBlank() && domain.contains(".") && domain.length > 4
                        && !hardWhitelist.any { domain.endsWith(it) || domain == it }) {
                        synchronized(loadedDomainRules) { loadedDomainRules.add(domain) }
                    }
                }
                // Ignorer les autres patterns génériques (trop risqués)
                else -> return@forEach
            }
        }
    }

    // ── shouldBlock : appelé depuis shouldInterceptRequest (thread IO) ─────────

    fun shouldBlock(url: String): Boolean {
        val lower = url.lowercase()

        // ── Whitelist stricte — jamais bloquer ────────────────────────────────
        if (isWhitelisted(lower)) return false

        // ── Extraire le hostname pour comparaison précise ─────────────────────
        val host = try {
            java.net.URI(url).host?.lowercase() ?: return false
        } catch (e: Exception) { return false }

        // ── Règles statiques sur le hostname ──────────────────────────────────
        staticDomainRules.forEach { domain ->
            if (host == domain || host.endsWith(".$domain")) return true
        }

        // ── Patterns statiques sur l'URL complète ─────────────────────────────
        staticPatternRules.forEach { pattern ->
            if (lower.contains(pattern)) return true
        }

        // ── Règles dynamiques des listes téléchargées ─────────────────────────
        if (isLoaded) {
            synchronized(loadedDomainRules) {
                loadedDomainRules.forEach { domain ->
                    if (host == domain || host.endsWith(".$domain")) return true
                }
            }
        }

        return false
    }

    private fun isWhitelisted(lower: String): Boolean {
        // Tout ce qui vient de ces domaines passe toujours
        val alwaysAllow = listOf(
            "youtube.com", "ytimg.com", "googlevideo.com", "ggpht.com",
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com",
            "accounts.google.com", "fonts.googleapis.com",
            "cloudflare.com", "cloudfront.net",
            "jsdelivr.net", "jquery.com"
        )
        return alwaysAllow.any { lower.contains(it) }
    }

    // ── CSS cosmétiques pour injection dans les pages ─────────────────────────

    // JS principal injecté une fois par page — installe un MutationObserver
    // permanent qui réagit à chaque changement DOM (YouTube est une SPA)
    val AD_BLOCK_JS = """
(function(){
    if (window.__adBlockInstalled) return;
    window.__adBlockInstalled = true;

    // ── CSS : masquer les éléments publicitaires ──────────────────────────────
    var style = document.createElement('style');
    style.id  = 'ab-style';
    style.textContent = [
        '.ytp-ad-module', '.ytp-ad-overlay-container', '.ytp-ad-text-overlay',
        '.ytp-ad-skip-button-container', '.ytp-ad-image-overlay',
        '.video-ads', '#player-ads', '#masthead-ad',
        'ytd-banner-promo-renderer', 'ytd-ad-slot-renderer',
        'ytd-in-feed-ad-layout-renderer', 'ytd-promoted-sparkles-web-renderer',
        'ytd-search-pyv-renderer', 'ytd-display-ad-renderer',
        'ytd-companion-slot-renderer',
        '.ytd-merch-shelf-renderer',
        '[id^="google_ads"]', '.adsbygoogle',
        'div[data-ad="true"]'
    ].join(',') + '{display:none!important}';
    (document.head || document.documentElement).appendChild(style);

    // ── Logique de skip des pré-rolls / mid-rolls ─────────────────────────────
    function handleAd() {
        // 1. Cliquer sur le bouton "Passer l'annonce" s'il est visible
        var skipSelectors = [
            '.ytp-skip-ad-button',
            '.ytp-ad-skip-button',
            '.ytp-ad-skip-button-modern',
            'button[class*="skip"]'
        ];
        for (var i = 0; i < skipSelectors.length; i++) {
            var btn = document.querySelector(skipSelectors[i]);
            if (btn) { btn.click(); return; }
        }

        // 2. Si une pub vidéo est en cours → passer à la fin immédiatement
        var adBadge = document.querySelector(
            '.ad-showing, .ytp-ad-player-overlay, .ytp-ad-text'
        );
        if (adBadge) {
            var videos = document.querySelectorAll('video');
            for (var j = 0; j < videos.length; j++) {
                var v = videos[j];
                if (!v.paused && v.duration > 0) {
                    v.muted = true;          // mute d'abord pour éviter le son
                    v.currentTime = v.duration;
                }
            }
        }

        // 3. Fermer les overlays pub
        [
            '.ytp-ad-overlay-close-button',
            '.ytp-ad-text-overlay .ytp-ad-overlay-close-button',
            '#dismiss-button button',
            'yt-button-shape#dismiss-button button'
        ].forEach(function(sel) {
            var el = document.querySelector(sel);
            if (el) el.click();
        });
    }

    // ── Lancer handleAd immédiatement puis via MutationObserver ──────────────
    handleAd();

    // Intervalle régulier pour les cas où le MutationObserver rate quelque chose
    var interval = setInterval(handleAd, 300);

    // MutationObserver : réagit dès que le DOM change (navigation SPA YouTube)
    var observer = new MutationObserver(function(mutations) {
        for (var i = 0; i < mutations.length; i++) {
            if (mutations[i].addedNodes.length > 0) {
                handleAd();
                break;
            }
        }
    });
    observer.observe(document.body || document.documentElement, {
        childList: true,
        subtree:   true
    });

    // ── YouTube SPA navigation (yt-navigate-finish) ───────────────────────────
    // YouTube ne recharge pas la page entre les vidéos — cet événement se déclenche
    // à chaque changement de vidéo
    window.addEventListener('yt-navigate-finish', function() {
        setTimeout(handleAd, 500);
        setTimeout(handleAd, 1500);
        setTimeout(handleAd, 3000);
    });

})();
""".trimIndent()

    fun buildCssInjection(): String = AD_BLOCK_JS

    fun getLoadedStats(): String {
        val domains  = synchronized(loadedDomainRules)  { loadedDomainRules.size }
        val patterns = synchronized(loadedPatternRules) { loadedPatternRules.size }
        val css      = synchronized(loadedCssFilters)   { loadedCssFilters.size }
        return "$domains règles de domaines, $patterns patterns, $css filtres CSS"
    }

    fun reset() {
        loadedDomainRules.clear()
        loadedPatternRules.clear()
        loadedCssFilters.clear()
        isLoaded = false
    }
}
