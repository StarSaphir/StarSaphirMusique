package com.ytmusic.feature.browser

import kotlinx.coroutines.*
import org.json.JSONArray
import java.net.URL

// ─── SponsorBlock : API publique pour skipper les segments pub/sponsor ─────────
// https://wiki.sponsor.ajay.app/w/API_Docs
// Retourne les segments soumis par la communauté pour chaque vidéo YouTube.
// Catégories : sponsor, selfpromo, interaction, intro, outro, preview, filler, music_offtopic

data class Segment(
    val start:    Double,
    val end:      Double,
    val category: String
)

object SponsorBlock {

    private const val API = "https://sponsor.ajay.app/api/skipSegments"

    // Catégories à sauter automatiquement
    private val AUTO_SKIP = setOf(
        "sponsor",       // Pubs intégrées par le créateur
        "selfpromo",     // Auto-promotion
        "interaction",   // "Abonne-toi", "like"
        "intro",         // Intro animée répétitive
        "outro",         // Outro / écran de fin
        "preview",       // Résumé/recap en début de vidéo
        "filler",        // Contenu de remplissage
        "music_offtopic" // Section non-musicale dans une vidéo musicale
    )

    // Cache en mémoire : videoId → segments
    private val cache = mutableMapOf<String, List<Segment>>()

    suspend fun getSegments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        cache[videoId]?.let { return@withContext it }
        try {
            val cats = AUTO_SKIP.joinToString("&category=") { "\"$it\"" }
            val url  = "$API?videoID=$videoId&categories=[$cats]"
            val json = JSONArray(URL(url).readText())
            val segments = (0 until json.length()).mapNotNull { i ->
                val obj = json.getJSONObject(i)
                val seg = obj.getJSONArray("segment")
                val cat = obj.getString("category")
                if (cat in AUTO_SKIP) Segment(seg.getDouble(0), seg.getDouble(1), cat) else null
            }
            cache[videoId] = segments
            segments
        } catch (e: Exception) {
            emptyList()
        }
    }

    // JS à injecter dans la page — surveille la position de la vidéo
    // et saute les segments connus
    fun buildSkipperJs(segments: List<Segment>): String {
        if (segments.isEmpty()) return ""
        val segsJson = segments.joinToString(",") {
            "{s:${it.start},e:${it.end},c:'${it.category}'}"
        }
        return """
(function(){
    if (window.__sbInstalled) return;
    window.__sbInstalled = true;
    var segs = [$segsJson];
    var skipped = {};
    function checkSponsor() {
        var v = document.querySelector('video');
        if (!v || v.paused || v.duration === 0) return;
        var t = v.currentTime;
        for (var i = 0; i < segs.length; i++) {
            var seg = segs[i];
            if (t >= seg.s && t < seg.end - 0.5 && !skipped[i]) {
                skipped[i] = true;
                v.currentTime = seg.e;
                showNotice(seg.c);
                return;
            }
            // Réinitialiser si on revient en arrière
            if (t < seg.s) skipped[i] = false;
        }
    }
    function showNotice(cat) {
        var labels = {
            sponsor: 'Sponsor', selfpromo: 'Auto-promo',
            interaction: 'Interaction', intro: 'Intro',
            outro: 'Outro', preview: 'Aperçu',
            filler: 'Remplissage', music_offtopic: 'Hors-sujet'
        };
        var notice = document.getElementById('sb-notice');
        if (!notice) {
            notice = document.createElement('div');
            notice.id = 'sb-notice';
            notice.style.cssText = 'position:fixed;top:70px;right:12px;z-index:99999;' +
                'background:#00b4d8;color:#fff;padding:8px 14px;border-radius:8px;' +
                'font-size:13px;font-weight:bold;opacity:0;transition:opacity 0.3s;' +
                'pointer-events:none;font-family:sans-serif;';
            document.body.appendChild(notice);
        }
        notice.textContent = '⏭ ' + (labels[cat] || cat) + ' ignoré';
        notice.style.opacity = '1';
        setTimeout(function(){ notice.style.opacity = '0'; }, 2500);
    }
    setInterval(checkSponsor, 500);
})();
""".trimIndent()
    }
}
