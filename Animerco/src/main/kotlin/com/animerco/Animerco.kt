package com.animerco

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import org.jsoup.nodes.Element
import kotlinx.coroutines.launch
import android.util.Log
import android.util.Base64
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.animerco.animerco
import kotlin.collections.set
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup
import java.net.URI

class animerco : MainAPI() {
    override var mainUrl = "https://gat.animerco.org"
    override var name = "Animerco"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )
    @Volatile
    private var resolvedMainUrl: String? = null

    private suspend fun resolveMainUrl(): String {
        resolvedMainUrl?.let { return it }

        val resp = app.get(
            "https://gat.animerco.org",
            interceptor = interceptor,
            allowRedirects = true
        )
        val finalUrl = resp.url.trimEnd('/')

        resolvedMainUrl = finalUrl
        mainUrl = finalUrl

        Log.d("Animerco", "✅ Resolved mainUrl -> $finalUrl")
        return finalUrl
    }

    private val interceptor = CloudflareKiller()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl, interceptor = interceptor).document
        val homePageList = ArrayList<HomePageList>()

        fun toSearchResponse(element: Element): AnimeSearchResponse? {
            val link = element.selectFirst("a")?.attr("href") ?: return null
            val title = element.selectFirst("div.info a h3")?.text() ?: return null
            val poster = element.selectFirst("a.image")?.attr("data-src")
            val year = element.selectFirst("span.anime-aired")?.text()?.toIntOrNull()

            return newAnimeSearchResponse(title, link) {
                this.posterUrl = poster
                this.year = year
            }
        }

        document.select("div.featured-slider div.anime-card").mapNotNull {
            toSearchResponse(it)
        }.let { if (it.isNotEmpty()) homePageList.add(HomePageList("أنميات مميزة", it)) }

        val latestEpisodes =
            document.select("div.media-section:contains(آخر الحلقات المضافة) div.episode-card")
                .mapNotNull {
                    val animeTitle =
                        it.selectFirst("div.info a h3")?.text() ?: return@mapNotNull null
                    val link = it.selectFirst("a.image")?.attr("href") ?: return@mapNotNull null
                    val poster = it.selectFirst("a.image")?.attr("data-src")
                    val episodeTitle = it.selectFirst("a.episode span")?.text()
                    val finalTitle = "$animeTitle - $episodeTitle"

                    newAnimeSearchResponse(finalTitle, link) {
                        this.posterUrl = poster
                        this.type = TvType.Anime
                    }
                }
        if (latestEpisodes.isNotEmpty()) {
            homePageList.add(
                HomePageList(
                    "آخر الحلقات المضافة",
                    latestEpisodes
                )
            )
        }

        document.select("div.media-section:contains(آخر الأنميات المضافة) div.anime-card")
            .mapNotNull {
                toSearchResponse(it)
            }
            .let { if (it.isNotEmpty()) homePageList.add(HomePageList("آخر الأنميات المضافة", it)) }

        document.select("div.media-section:contains(آخر الأفلام المضافة) div.anime-card")
            .mapNotNull {
                toSearchResponse(it)
            }.let { if (it.isNotEmpty()) homePageList.add(HomePageList("آخر الأفلام المضافة", it)) }

        return newHomePageResponse(homePageList)
    }
    data class PlayerAjaxResponse(
        @JsonProperty("embed_url") val embedUrl: String?,
        @JsonProperty("type") val type: String?
    )

    data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
    data class DownloadMeta(val server: String, val quality: String, val language: String)
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val seenHrefs = mutableSetOf<String>()

        val encoded = try {
            java.net.URLEncoder.encode(query, "UTF-8")
        } catch (e: Exception) {
            query // fallback unlikely
        }
        for (page in 1..3) {
            val url = if (page == 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded"
            try {
                Log.d("AnimercoSearch", "Fetching search page $page -> $url")
                val resp = app.get(url, interceptor = interceptor)
                val doc = resp.document

                val cards = doc.select("div.search-card")
                if (cards.isEmpty()) {
                    Log.d("AnimercoSearch", "No search results on page $page, stopping.")
                    break
                }

                cards.forEach { item ->
                    val linkTag = item.selectFirst("a.image")
                    val infoTag = item.selectFirst("div.info")
                    if (linkTag == null || infoTag == null) return@forEach

                    val href = linkTag.attr("href")
                    if (href.isBlank() || seenHrefs.contains(href)) return@forEach

                    val poster = linkTag.attr("data-src").ifBlank { null }
                    val title = infoTag.selectFirst("h3")?.text()?.trim() ?: return@forEach

                    val sr = newAnimeSearchResponse(title, href) {
                        this.posterUrl = poster
                    }
                    results.add(sr)
                    seenHrefs.add(href)
                }
            } catch (e: Exception) {
                Log.e("AnimercoSearch", "Failed fetching search page $page: ${e.message}", e)
                continue
            }
        }

        return results
    }



    override suspend fun load(url: String): LoadResponse? {
        fun normalizeUrl(u: String?): String? {
            if (u.isNullOrBlank()) return null
            return when {
                u.startsWith("//") -> "https:$u"
                u.startsWith("http://") -> u.replaceFirst("http://", "https://")
                u.startsWith("http") -> u
                u.startsWith("/") -> mainUrl.trimEnd('/') + u
                else -> if (u.startsWith("www.")) "https:$u" else mainUrl.trimEnd('/') + "/" + u.trimStart('/')
            }
        }

        suspend fun fetchEpisodesFromDoc(doc: org.jsoup.nodes.Document): List<Episode> {
            val episodes = mutableListOf<Episode>()
            doc.select("ul.episodes-lists#filter li").forEach { el ->
                val link = el.selectFirst("a.title") ?: return@forEach
                val epUrlRaw = link.attr("href") ?: return@forEach
                val epUrl = normalizeUrl(epUrlRaw) ?: return@forEach
                val epTitle = link.selectFirst("h3")?.text()?.trim() ?: link.text().trim()
                val poster = el.selectFirst("a.image")?.attr("data-src")
                val epNumber = el.attr("data-number").toIntOrNull()
                    ?: Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()
                episodes.add(
                    newEpisode(epUrl) {
                        name = epTitle
                        posterUrl = poster
                        episode = epNumber ?: 1
                    }
                )
            }
            return episodes.sortedBy { it.episode ?: Int.MAX_VALUE }
        }

        try {
            val doc = app.get(url, interceptor = interceptor).document

            val title = doc.selectFirst("div.media-title h1")?.text()?.trim().orEmpty()
            val poster = doc.selectFirst("div.anime-card div.image")?.attr("data-src")
            val plot = doc.selectFirst("div.media-story div.content p")?.text()
            val tags = doc.select("div.genres a").map { it.text() }
            val year = doc.select("ul.media-info li:contains(بداية العرض) a").text().toIntOrNull()
            val scoreValue = doc.selectFirst("div.votes span.score")?.text()?.toDoubleOrNull()
            val typeText = doc.select("div.media-info li:contains(النوع) span").text()
            val isMovieByText = typeText.contains("Movie", ignoreCase = true) || typeText.contains("film", ignoreCase = true)
            val isMovieByUrl = url.contains("/movies/", ignoreCase = true) || url.contains("/movie/", ignoreCase = true)
            val isMovie = isMovieByText || isMovieByUrl

            if (isMovie) {
                val singleEpisode = newEpisode(url) {
                    name = title.ifBlank { "Movie" }
                    posterUrl = poster
                    episode = 1
                    season = 1
                }
                return newTvSeriesLoadResponse(title, url, TvType.AnimeMovie, listOf(singleEpisode)) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.score = Score.from10(scoreValue)
                }
            }
            val seasonNodes = doc.select("div.media-seasons ul.episodes-lists li")
            if (seasonNodes.isEmpty()) {
                val candidateSelectors = listOf(
                    "a.btn.seasons",
                    "a.seasons",
                    "a:contains(المواسم)",
                    "div.page-controls a.btn.seasons",
                    "div.page-controls a.seasons",
                    "a[href*='/animes/']",
                    "a[href*='/anime/']",
                    "a[href*='season']",
                    "div.breadcrumb a",
                    "a[title*='المواسم']"
                )

                var parentHref: String? = null
                for (sel in candidateSelectors) {
                    val el = try { doc.selectFirst(sel) } catch (e: Exception) { null }
                    if (el != null) {
                        val h = el.attr("href")
                        if (!h.isNullOrBlank()) {
                            parentHref = h
                            break
                        }
                    }
                }

                val parentUrl = normalizeUrl(parentHref)
                if (!parentUrl.isNullOrBlank() && parentUrl != url) {
                    Log.d("AnimercoLoad", "Detected episode page — redirecting to parent anime page: $parentUrl")
                    return load(parentUrl)
                }
                val epTitle = doc.selectFirst("div.media-title h1")?.text()?.trim() ?: title
                val epNumber = doc.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                    ?: doc.selectFirst("span.episode-number")?.text()?.filter { it.isDigit() }?.toIntOrNull()

                val singleEpisode = newEpisode(url) {
                    name = epTitle
                    posterUrl = poster
                    episode = epNumber ?: 1
                    season = 1
                }

                return newTvSeriesLoadResponse(title, url, TvType.Anime, listOf(singleEpisode)) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.tags = tags
                    this.year = year
                    this.score = Score.from10(scoreValue)
                }
            }
            val episodes = mutableListOf<Episode>()
            seasonNodes.forEach { season ->
                val seasonUrlRaw = season.selectFirst("a.title")?.attr("href") ?: return@forEach
                val seasonUrl = normalizeUrl(seasonUrlRaw) ?: return@forEach

                val seasonDoc = try {
                    app.get(seasonUrl, interceptor = interceptor).document
                } catch (e: Exception) {
                    Log.w("AnimercoLoad", "failed fetch season $seasonUrl : ${e.message}")
                    return@forEach
                }

                val seasonName = seasonDoc.selectFirst("div.media-title h1")?.text()?.trim()
                val seasonNum = seasonName?.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

                val eps = fetchEpisodesFromDoc(seasonDoc)
                eps.forEach { it.season = seasonNum ?: 1 }
                episodes.addAll(eps)
            }

            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.sortedBy { it.episode ?: 0 }) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.score = Score.from10(scoreValue)
            }
        } catch (e: Exception) {
            Log.e("AnimercoLoad", "load failed: ${e.message}", e)
            return null
        }
    }

    private fun ensureHttpsRaw(u: String?): String? {
        if (u.isNullOrBlank()) return null
        val s = u.trim()
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("http://", ignoreCase = true) -> s.replaceFirst("http://", "https://", ignoreCase = true)
            s.startsWith("https://", ignoreCase = true) -> s
            else -> "https://$s"
        }
    }

    data class MegaboxEntry(
        val type: String = "megabox",
        val server: String,
        val driver: String? = null,
        val url: String,
        val source: String
    )

    private fun ensureHttpsRaw(link: String?, base: String? = null): String? {
        if (link.isNullOrBlank()) return null
        val s = link.trim()
        return when {
            s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("http://") -> "https://" + s.removePrefix("http://")
            base != null && s.startsWith("/") -> (base.trimEnd('/') + s)
            Regex("""^[\w\-]+\.[\w\-.]+""").containsMatchIn(s) -> "https://$s"
            else -> s
        }
    }
    @Serializable
    data class Share4maxMirror(
        @SerialName("link") val link: String?,
        @SerialName("driver") val driver: String?
    )

    @Serializable
    data class Share4maxQuality(
        @SerialName("label") val label: String?,
        @SerialName("mirrors") val mirrors: List<com.animerco.animerco.Share4maxMirror>?
    )

    @Serializable
    data class Share4maxStreamsData(
        @SerialName("data") val data: List<Share4maxQuality>?
    )

    @Serializable
    data class Share4maxProps(
        @SerialName("streams") val streams: Share4maxStreamsData?
    )

    @Serializable
    data class Share4maxInertiaResponse(
        @SerialName("props") val props: Share4maxProps?
    )

    @Serializable
    data class Share4maxInitialPage(
        @SerialName("version") val version: String?
    )
        private suspend fun processMegabox(url: String, referer: String): List<String> {
            val extractedIframes = mutableListOf<String>()

            try {
                val targetUrl = url
                val initialResponse = app.get(targetUrl, referer = referer)
                val soup = initialResponse.document
                val version = soup.selectFirst("script[data-page=app]")?.html()?.let {
                    parseJson<Share4maxInitialPage>(it).version
                }

                if (version == null) return emptyList()
                val inertiaHeaders = mapOf(
                    "X-Inertia" to "true",
                    "X-Inertia-Partial-Component" to "files/mirror/video",
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Inertia-Version" to version,
                    "X-Requested-With" to "XMLHttpRequest"
                )

                val streamResponse = app.get(targetUrl, headers = inertiaHeaders, referer = referer)
                val streamJson = parseJson<Share4maxInertiaResponse>(streamResponse.text)
                streamJson.props?.streams?.data?.forEach { qualityLevel ->
                    qualityLevel.mirrors?.forEach { mirror ->
                        mirror.link?.let { link ->
                            val finalUrl = if (link.startsWith("//")) "https:$link" else link
                            extractedIframes.add(finalUrl)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return extractedIframes
        }



    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "AnimercoLinks"
        Log.d(TAG, "➡️ loadLinks start for: $data")
        val base = resolveMainUrl()
        val ajaxUrl = "$base/wp-admin/admin-ajax.php"
        val BASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html, application/xhtml+xml",
            "Referer" to mainUrl
        )
        fun ensureHttpsRaw(link: String?, base: String? = null): String? {
            if (link.isNullOrBlank()) return link
            val s = link.trim()
            return when {
                s.startsWith("https://") -> s
                s.startsWith("//") -> "https:$s"
                s.startsWith("http://") -> "https://" + s.removePrefix("http://")
                base != null && s.startsWith("/") -> (mainUrl.trimEnd('/') + s)
                Regex("""^[\w\-]+\.[\w\-.]+""").containsMatchIn(s) -> "https://$s"
                else -> s
            }
        }

        fun extractIframeSrc(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val m = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(text)
            return m?.groupValues?.get(1)?.trim()
        }

        suspend fun fetchPlayerPageAndExtract(sessionReferer: String?, url: String?): String? {
            if (url.isNullOrBlank()) return null
            return try {
                val headers = mapOf("Referer" to (sessionReferer ?: data), "User-Agent" to BASE_HEADERS["User-Agent"]!!)
                val resp = runCatching { app.get(url, interceptor = interceptor, headers = headers) }.getOrNull()
                val html = resp?.text ?: ""
                val iframe = extractIframeSrc(html)
                if (!iframe.isNullOrBlank()) {
                    ensureHttpsRaw(iframe, url)
                } else {
                    val m = Regex("""https?://[^\s"']+\.(m3u8|mp4)(?:\?[^\s"']*)?""", RegexOption.IGNORE_CASE).find(html)
                    m?.value
                }
            } catch (e: Exception) {
                null
            }
        }

        try {
            val pageResp = app.get(data, interceptor = interceptor)
            val doc = pageResp.document
            Log.d(TAG, "Fetched page title='${doc.selectFirst("title")?.text() ?: ""}'")
            val scriptTag = doc.selectFirst("script#dt_main_ajax-js-extra")
            val scriptData = scriptTag?.data() ?: doc.html()
            val nonceMatch = Regex(""""nonce"\s*:\s*"([a-f0-9]+)"""", RegexOption.IGNORE_CASE).find(scriptData)
            val globalNonce = nonceMatch?.groupValues?.get(1) ?: ""
            Log.d(TAG, "Found nonce=$globalNonce")
            val serverButtons = doc.select("ul.server-list li a.option")
            if (serverButtons.isEmpty()) {
                Log.w(TAG, "No server buttons found.")
                return true
            }

            data class Btn(val name: String, val post: String, val nume: String, val type: String, val security: String)
            val btns = mutableListOf<Btn>()
            for (b in serverButtons) {
                val postId = b.attr("data-post").ifBlank { "" }
                val nume = b.attr("data-nume").ifBlank { "" }
                val dtype = b.attr("data-type").ifBlank { "" }
                val sname = b.selectFirst("span.server")?.text()?.trim() ?: b.text().trim().ifBlank { "server" }
                val securityNonce = b.attr("data-nonce").ifBlank { globalNonce }
                if (postId.isBlank() || nume.isBlank() || dtype.isBlank()) continue
                btns.add(Btn(sname, postId, nume, dtype, securityNonce))
            }
            val linkChannel = kotlinx.coroutines.channels.Channel<ExtractorLink>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            val consumer = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                for (link in linkChannel) {
                    try { callback.invoke(link) } catch (e: Exception) { Log.w(TAG, "Callback failed: ${e.message}") }
                }
            }
            val maxConcurrent = kotlin.math.min(12, kotlin.math.max(4, btns.size))
            val sem = kotlinx.coroutines.sync.Semaphore(maxConcurrent)
            val jobs = btns.map { btn ->
                kotlinx.coroutines.GlobalScope.async(kotlinx.coroutines.Dispatchers.IO) {
                    sem.withPermit {
                        try {
                            val ajaxHeaders = mutableMapOf<String, String>(
                                "User-Agent" to (BASE_HEADERS["User-Agent"] ?: ""),
                                "Referer" to data,
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                                "X-Requested-With" to "XMLHttpRequest"
                            )

                            val payload = mutableMapOf(
                                "action" to "player_ajax",
                                "post" to btn.post,
                                "nume" to btn.nume,
                                "type" to btn.type
                            )
                            if (btn.security.isNotBlank()) payload["security"] = btn.security
                            if (globalNonce.isNotBlank()) payload["nonce"] = globalNonce
                            val ajaxResp = runCatching {
                                app.post(ajaxUrl, data = payload, referer = data, headers = ajaxHeaders)
                            }.getOrNull()
                            val txt = ajaxResp?.text ?: ""
                            var embedRaw: String? = null
                            runCatching {
                                val parsed = parseJson<PlayerAjaxResponse>(txt)
                                embedRaw = parsed?.embedUrl
                            }.onFailure { /* ignore */ }

                            if (embedRaw.isNullOrBlank()) {
                                val iframeMatch = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(txt)
                                embedRaw = iframeMatch?.groupValues?.get(1) ?: txt.trim()
                            }
                            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(embedRaw ?: "")?.groupValues?.get(1)
                            var cleanUrl = iframeSrc?.trim() ?: (embedRaw?.replace(Regex("""<iframe[^>]+src=["']|["'].*"""), "")?.trim().orEmpty())
                            var abs = ensureHttpsRaw(cleanUrl, data) ?: ""

                            Log.d(TAG, "Server[${btn.name}] -> $abs")
                            if (abs.isNotBlank() && (abs.contains("jwplayer", ignoreCase = true) || abs.contains("jw.", ignoreCase = true) || abs.contains(".php", ignoreCase = true) || abs.contains("player", ignoreCase = true))) {
                                val extracted = fetchPlayerPageAndExtract(data, abs)
                                if (!extracted.isNullOrBlank()) abs = ensureHttpsRaw(extracted, abs) ?: abs
                            }
                            suspend fun sendLinkSafe(link: ExtractorLink) {
                                try { linkChannel.send(link) } catch (e: Exception) { Log.w(TAG, "send channel failed: ${e.message}") }
                            }
                            when {
                                abs.contains("yonaplay.net", ignoreCase = true) -> {
                                    runCatching {
                                        decodeYonaplayAndLoad(abs, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } }
                                    }
                                }

                                abs.contains("videa.hu", ignoreCase = true) -> {
                                    runCatching { VideaExtractor().getUrl(abs, null, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                    runCatching { loadExtractor(abs, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                }

                                abs.contains("my.mail.ru", ignoreCase = true) || abs.contains("/video/embed/", ignoreCase = true) -> {
                                    runCatching { MailruExtractor().getUrl(abs, null, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                    runCatching { loadExtractor(abs, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                }

                                abs.contains("drive.google.com", ignoreCase = true) -> {
                                    val fileId = Regex(""".*/file/d/([0-9A-Za-z_-]{10,})""").find(abs)?.groupValues?.get(1)
                                    if (!fileId.isNullOrBlank()) {
                                        val direct = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                                        runCatching { loadExtractor(direct, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                    } else {
                                        runCatching { loadExtractor(abs, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                    }
                                }

                                abs.contains("streamhg", ignoreCase = true) || abs.contains("earnvids", ignoreCase = true) -> {
                                    runCatching {
                                        val extracted = try { ExternalEarnVidsExtractor.extract(abs, data) } catch (_: Throwable) { null }
                                        if (extracted != null) {
                                            val pair = when (extracted) {
                                                is String -> Pair(extracted, "EarnVids")
                                                is Pair<*, *> -> Pair(extracted.first as? String ?: extracted.toString(), extracted.second as? String ?: "EarnVids")
                                                is Map<*, *> -> Pair((extracted["url"] ?: extracted["link"] ?: extracted["href"]).toString(), (extracted["name"] as? String) ?: "EarnVids")
                                                else -> Pair(extracted.toString(), "EarnVids")
                                            }
                                            val customUrl = ensureHttpsRaw(pair.first, data) ?: pair.first
                                            val link = newExtractorLink(
                                                name = pair.second,
                                                source = "ExternalEarnVids",
                                                url = customUrl,
                                                type = ExtractorLinkType.VIDEO
                                            ) {
                                                referer = data
                                                quality = Qualities.Unknown.value
                                            }
                                            sendLinkSafe(link)
                                        } else {
                                            runCatching { loadExtractor(abs, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                        }
                                    }.onFailure {
                                        runCatching { loadExtractor(abs, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                    }
                                }

                                else -> {
                                    runCatching { loadExtractor(abs, data, subtitleCallback) { l -> kotlinx.coroutines.runBlocking { sendLinkSafe(l) } } }
                                }
                            }
                            val lowerServer = btn.name.lowercase()
                            if (lowerServer.contains("megabox") || lowerServer.contains("megamax") || abs.lowercase().contains("megabox") || abs.lowercase().contains("megamax")) {
                                runCatching {
                                    val extras = processMegabox(abs, data) // processMegabox يجب أن يعيد قائمة مشابهة للبايثون
                                    for (mb in extras) {
                                        val mbUrl = when (mb) {
                                            is Pair<*, *> -> mb.second as? String
                                            is Map<*, *> -> (mb["url"] ?: mb["link"] ?: mb["href"]) as? String
                                            else -> mb.toString()
                                        }
                                        val finalMb = ensureHttpsRaw(mbUrl, abs) ?: mbUrl
                                        if (finalMb != null && (finalMb.contains("streamhg", ignoreCase = true) || finalMb.contains("earnvids", ignoreCase = true))) {
                                            val extracted = try { ExternalEarnVidsExtractor.extract(finalMb, data) } catch (_: Throwable) { null }
                                            if (extracted != null) {
                                                val pair = when (extracted) {
                                                    is String -> Pair(extracted, "EarnVids")
                                                    is Pair<*, *> -> Pair(extracted.first as? String ?: extracted.toString(), extracted.second as? String ?: "EarnVids")
                                                    is Map<*, *> -> Pair((extracted["url"] ?: extracted["link"] ?: extracted["href"]).toString(), (extracted["name"] as? String) ?: "EarnVids")
                                                    else -> Pair(extracted.toString(), "EarnVids")
                                                }
                                                val customUrl = ensureHttpsRaw(pair.first, data) ?: pair.first
                                                val link = newExtractorLink(
                                                    name = pair.second,
                                                    source = "ExternalEarnVids",
                                                    url = customUrl,
                                                    type = ExtractorLinkType.VIDEO
                                                ) {
                                                    referer = data
                                                    quality = Qualities.Unknown.value
                                                }
                                                sendLinkSafe(link)
                                                continue
                                            }
                                        }
                                        val fm = finalMb
                                        val main = data
                                        if (fm != null && main != null) {
                                            runCatching {
                                                loadExtractor(fm, main, subtitleCallback) { l ->
                                                    kotlinx.coroutines.runBlocking { sendLinkSafe(l) }
                                                }
                                            }.onFailure {
                                                Log.w(TAG, "processMegabox failed: ${it.message}")
                                            }
                                        }
                                    }
                                }.onFailure { Log.w(TAG, "processMegabox failed: ${it.message}") }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing server ${btn.name}: ${e.message}", e)
                        }
                    } // end withPermit
                } // end async
            } // end map
            jobs.forEach { runCatching { it.await() } }
            linkChannel.close()
            consumer.join()

            Log.d(TAG, "✅ loadLinks finished (parallel flow)")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ loadLinks fatal error: ${e.message}", e)
            return false
        }
    }


    private suspend fun decodeYonaplayAndLoad(
        yonaplayUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "YonaplayDecoder"
        Log.d(TAG, "🟣 Start decoding: $yonaplayUrl")

        try {
            val resp = try {
                app.get(yonaplayUrl, referer = mainUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch yonaplay page: ${e.message}", e)
                return
            }

            val html = resp.text
            Log.d(TAG, "📄 Page length = ${html.length}")
            val tokenRegex = Regex("""go_to_player\('([A-Za-z0-9+/=]+)'\)""")
            val tokens = tokenRegex.findAll(html).map { it.groupValues[1] }.toList()

            if (tokens.isNotEmpty()) {
                for (token in tokens) {
                    try {
                        var fixed = token
                        val padding = token.length % 4
                        if (padding != 0) fixed += "=".repeat(4 - padding)

                        val decoded =
                            String(android.util.Base64.decode(fixed, android.util.Base64.DEFAULT))
                        Log.d(TAG, "🔗 Decoded token -> $decoded")
                        if (decoded.contains("drive.google.com/file/d/")) {
                            val match = Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decoded)
                            val fileId = match?.groupValues?.get(1)
                            if (!fileId.isNullOrBlank()) {
                                val directUrl =
                                    "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                                Log.d(TAG, "🎯 Direct Google Drive link: $directUrl")
                                try {
                                    callback(
                                        newExtractorLink(
                                            name = "Google Drive",
                                            source = "Yonaplay",
                                            url = directUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            referer = "https://drive.google.com/"
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to push Google Drive link: ${e.message}", e)
                                }
                                continue // انتقل للتوكين التالي
                            }
                        }
                        val iframeMatch =
                            Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                                .find(decoded)?.groupValues?.get(1)
                        if (!iframeMatch.isNullOrBlank()) {
                            val final =
                                if (iframeMatch.startsWith("//")) "https:$iframeMatch" else iframeMatch
                            try {
                                loadExtractor(final, yonaplayUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.w(TAG, "loadExtractor failed for iframe $final: ${e.message}")
                            }
                            continue
                        }
                        if (decoded.startsWith("http")) {
                            try {
                                loadExtractor(decoded, yonaplayUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "loadExtractor failed for decoded link $decoded: ${e.message}"
                                )
                            }
                            continue
                        }
                        val candidate = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
                            .find(decoded)?.value
                        if (!candidate.isNullOrBlank()) {
                            try {
                                loadExtractor(candidate, yonaplayUrl, subtitleCallback, callback)
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "loadExtractor failed for candidate $candidate: ${e.message}"
                                )
                            }
                            continue
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to decode token -> ${e.message}", e)
                    }
                }
                return
            }
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1)
            if (!iframeSrc.isNullOrBlank()) {
                val final = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
                if (final.contains("drive.google.com", ignoreCase = true)) {
                    val fileId =
                        Regex(""".*/file/d/([0-9A-Za-z_-]{10,})""").find(final)?.groupValues?.get(1)
                    if (!fileId.isNullOrBlank()) {
                        val directUrl =
                            "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                        try {
                            callback(
                                newExtractorLink(
                                    name = "Google Drive",
                                    source = "Yonaplay",
                                    url = directUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    referer = "https://drive.google.com/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to push Drive link: ${e.message}", e)
                        }
                        return
                    }
                }

                try {
                    loadExtractor(final, yonaplayUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to loadExtractor for iframe $final: ${e.message}", e)
                }
                return
            }
            Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
                val candidate = m.value
                if (candidate.contains(
                        "yonaplay.net",
                        ignoreCase = true
                    )
                ) return@forEach // تجاهل روابط yonaplay نفسها
                try {
                    loadExtractor(candidate, yonaplayUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.w(TAG, "loadExtractor failed for candidate $candidate : ${e.message}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error while decoding Yonaplay: ${e.message}", e)
        }
    }
}
