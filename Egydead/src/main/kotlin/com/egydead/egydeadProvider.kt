package com.egydead

import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.Episode as CS3Episode
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque
import java.net.URL

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EgyDead : MainAPI() {
    override var mainUrl = "https://egydead.beer"
    override var name = "ايجي ديد"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    private var savedCookies: String? = null
    private val cfMutex = Mutex()

    private fun log(tag: String, msg: String) {
        println("EgyDeadDebug | [$tag] -> $msg")
    }

    private val imageHeaders: Map<String, String>
        get() {
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "$mainUrl/"
            )
            savedCookies?.let { headers["Cookie"] = it }
            return headers
        }
    private fun buildHeaders(referer: String?): MutableMap<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to (referer ?: mainUrl),
            "Accept-Language" to "ar,en-US;q=0.9",
            "Upgrade-Insecure-Requests" to "1"
        )
        savedCookies?.let { headers["Cookie"] = it }
        return headers
    }


    private suspend fun httpGet(url: String, referer: String? = null): Document {
        var currentRequestUrl = url
        log("GET-REQUEST", "Fetching: $currentRequestUrl")
        var headers = buildHeaders(referer)

        var res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val currentCookies = android.webkit.CookieManager.getInstance().getCookie(currentRequestUrl)
                if (currentCookies != null && currentCookies != savedCookies && currentCookies.contains("cf_clearance")) {
                    log("GET-REQUEST", "Cloudflare already solved by another thread.")
                    savedCookies = currentCookies
                } else {
                    log("GET-REQUEST", "Cloudflare detected (Code: ${res.code}). Running Cookie Hunter...")
                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity

                    if (activity != null) {
                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)

                        if (solverResult != null) {
                            if (!solverResult.cookies.isNullOrEmpty()) {
                                savedCookies = solverResult.cookies
                                log("GET-REQUEST", "تم حفظ الكوكيز في الإضافة بنجاح.")
                            }
                            if (solverResult.finalUrl != currentRequestUrl) {
                                log("DOMAIN-UPDATE", "تم اكتشاف توجيه من $currentRequestUrl إلى ${solverResult.finalUrl}")
                                try {
                                    val newHost = java.net.URL(solverResult.finalUrl).host
                                    mainUrl = "https://$newHost"
                                    log("DOMAIN-UPDATE", "تم تحديث mainUrl ليصبح: $mainUrl")
                                } catch (e: Exception) {}
                                currentRequestUrl = solverResult.finalUrl
                            }
                        }
                    }
                }
            }
            log("GET-REQUEST", "إعادة الطلب (Retry) بالكوكيز الجديدة للرابط: $currentRequestUrl")
            headers = buildHeaders(referer)
            res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        }

        return res.document
    }
    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null): Document {
        var currentRequestUrl = url
        log("POST-REQUEST", "Sending to: $currentRequestUrl")
        var headers = buildHeaders(referer).apply {
            put("X-Requested-With", "XMLHttpRequest")
            put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        }

        var res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 30)

        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val currentCookies = android.webkit.CookieManager.getInstance().getCookie(currentRequestUrl)
                if (currentCookies != null && currentCookies != savedCookies && currentCookies.contains("cf_clearance")) {
                    savedCookies = currentCookies
                } else {
                    log("POST-REQUEST", "Cloudflare detected (Code: ${res.code}). Running Cookie Hunter...")
                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity
                    if (activity != null) {
                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)
                        if (solverResult != null) {
                            if (!solverResult.cookies.isNullOrEmpty()) {
                                savedCookies = solverResult.cookies
                            }
                            if (solverResult.finalUrl != currentRequestUrl) {
                                try {
                                    val newHost = java.net.URL(solverResult.finalUrl).host
                                    mainUrl = "https://$newHost"
                                } catch (e: Exception) {}
                                currentRequestUrl = solverResult.finalUrl
                            }
                        }
                    }
                }
            }

            headers = buildHeaders(referer).apply {
                put("X-Requested-With", "XMLHttpRequest")
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            }
            res = app.post(currentRequestUrl, data = data, headers = headers, timeout = 30)
        }

        return res.document
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val logTag = "MAIN-PAGE"
        log(logTag, "Starting getMainPage | Page: $page | Request: ${request.name}")

        val document = try {
            log(logTag, "Attempting to fetch HTML from: $mainUrl")
            httpGet(mainUrl)
        } catch (e: Exception) {
            log(logTag, "CRITICAL ERROR: Failed to fetch main page -> ${e.message}")
            return newHomePageResponse(emptyList())
        }

        val homePageList = ArrayList<HomePageList>()

        log(logTag, "Parsing Pinned Section (div.pin-posts-list)...")
        val pinnedSection = document.selectFirst("div.pin-posts-list")
        if (pinnedSection != null) {
            val sectionTitle = pinnedSection.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "المميز"
            log(logTag, "Pinned Section found! Title: '$sectionTitle'")

            val items = pinnedSection.select("li.movieItem").mapNotNull {
                it.toSearchResponse("PINNED")
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items, isHorizontalImages = true))
            }
        }

        log(logTag, "Parsing Main Sections (section.main-section)...")
        val mainSections = document.select("section.main-section")

        mainSections.forEachIndexed { index, section ->
            val sectionTitle = section.selectFirst("h1.TitleMaster em")?.text()?.trim() ?: "قسم ${index + 1}"
            val items = section.select("li.movieItem").mapNotNull {
                it.toSearchResponse("SECTION-$index")
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionTitle, items))
            }
        }

        return newHomePageResponse(homePageList.filter { it.list.isNotEmpty() })
    }
    private fun Element.toSearchResponse(parentTag: String): SearchResponse? {
        try {
            val linkEl = this.selectFirst("a") ?: return null
            val href = linkEl.attr("href")
            val fullUrl = fixUrlNull(href) ?: return null
            val title = this.selectFirst("h1.BottomTitle")?.text()?.trim() ?: return null
            val posterUrl = this.selectFirst("img")?.attr("src")

            return newMovieSearchResponse(title, fullUrl) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders
            }
        } catch (e: Exception) {
            log("PARSER-$parentTag", "CRITICAL Item Error: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = httpGet(url)
        return document.select("ul.posts-list li.movieItem").mapNotNull {
            it.toSearchResponse("SEARCH")
        }
    }

    private val seasonNumRegex = Regex(
        """(?ix)(?:الموسم[\s:\-_.]*0*(\d+))|(?:S(?:eason)?[\s:\-_.]*0*(\d+))"""
    )

    private val episodeNumRegex = Regex(
        """(?ix)(?:حلقة[\s:\-_.]*0*(\d+))|(?:Episode[\s:\-_.]*0*(\d+))|(?:EP[\s:\-_.]*0*(\d+))|(?:\d+[xX]0*(\d+))|(?:S(?:eason)?[\s:\-_.]*\d+[\s\-_.,]*E(?:p(?:isode)?)?[\s:\-_.]*0*(\d+))"""
    )

    private fun getSeasonNum(title: String?): Int {
        if (title == null) return 9999
        val match = seasonNumRegex.find(title) ?: return 9999
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 9999
    }

    private fun getEpisodeNum(title: String?): Int {
        if (title == null) return 9999
        val match = episodeNumRegex.find(title) ?: return 9999
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 9999
    }

    private fun normalizeUrl(link: String?, base: String): String? {
        if (link.isNullOrBlank()) return null
        val t = link.trim()
        if (t.startsWith("#") || t.lowercase().startsWith("javascript:")) return null
        return try {
            val resolved = if (t.startsWith("http")) t else URL(URL(base), t).toString()
            fixUrl(resolved)
        } catch (e: Exception) { null }
    }
    private suspend fun batchFetch(
        urls: List<String>,
        concurrency: Int = 8
    ): Map<String, Document?> {
        val sem = Semaphore(concurrency)
        val out = mutableMapOf<String, Document?>()
        coroutineScope {
            val jobs = urls.map { u ->
                async {
                    sem.withPermit {
                        try {
                            val res = httpGet(u)
                            out[u] = res
                        } catch (e: Exception) {
                            out[u] = null
                        }
                    }
                }
            }
            jobs.awaitAll()
        }
        return out
    }
    private suspend fun discoverSeasonsPreserveOrder(
        startUrl: String,
        concurrency: Int = 8
    ): List<Triple<Int, String, String>> {
        val discovered = mutableListOf<Triple<Int, String, String>>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(startUrl); seen.add(startUrl)
        var nextIndex = 0

        while (queue.isNotEmpty()) {
            val batch = mutableListOf<String>()
            repeat(minOf(queue.size, concurrency)) { batch.add(queue.poll()) }
            if (batch.isEmpty()) break

            val docs = batchFetch(batch, concurrency)
            for (u in batch) {
                val doc = docs[u] ?: continue
                val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "موسم غير معروف"
                discovered.add(Triple(nextIndex++, title, u))

                val seasonsCont = doc.selectFirst("div.seasons-list") ?: doc.selectFirst("div.seasons")
                seasonsCont?.select("li.movieItem a, a")?.forEach { a ->
                    val href = normalizeUrl(a.attr("href"), u) ?: return@forEach
                    if (href !in seen && href.contains("/season/")) {
                        seen.add(href)
                        queue.add(href)
                    }
                }
            }
        }
        return discovered.distinctBy { it.third }
    }
    private fun extractEpisodesFromSeasonDoc(seasonUrl: String, doc: Document): List<CS3Episode> {
        val episodes = mutableListOf<CS3Episode>()
        val epsContainer = doc.selectFirst("div.EpsList") ?: doc.selectFirst("div.episodes-list") ?: doc.selectFirst("ul") ?: return emptyList()

        val items = epsContainer.select("li, a")
        for (el in items) {
            val a: Element = if (el.tagName() == "a") el else el.selectFirst("a") ?: continue
            val rawTitle = (a.attr("title").takeIf { it.isNotBlank() } ?: a.text()).trim()
            val href = normalizeUrl(a.attr("href"), seasonUrl) ?: continue

            if (href.contains("/season/")) continue
            if (href.contains("/film/")) continue

            val epNum = getEpisodeNum(rawTitle)
            val ep: CS3Episode = newEpisode(href) {
                this.name = rawTitle
                this.episode = if (epNum != 9999) epNum else null
                this.data = href
            }
            episodes.add(ep)
        }
        return episodes.sortedBy { it.episode ?: 9999 }
    }
    private fun parseRecommendations(doc: Document, base: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val nodes = doc.select(".related-posts li.movieItem, .related-posts a, .related-posts li")
        for (li in nodes) {
            val a = li.selectFirst("a") ?: continue
            val href = normalizeUrl(a.attr("href"), base) ?: continue
            val title = a.selectFirst("h1, span, .title")?.text() ?: a.attr("title").takeIf { it.isNotBlank() } ?: a.text()
            val poster = a.selectFirst("img")?.attr("src")
            val sr = when {
                href.contains("/film/") -> newMovieSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
                href.contains("/season/") || href.contains("/series/") || href.contains("/show/") || href.contains("/serie/") || href.contains("/assembly/") -> newTvSeriesSearchResponse(title, href) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                }
                else -> null
            }
            sr?.let { out.add(it) }
        }
        return out
    }
    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            httpGet(url)
        } catch (e: Exception) {
            return null
        }

        val movieCollectionList = document.selectFirst("div.salery-list ul")
        if (movieCollectionList != null) {
            val seriesTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "Movie Collection"
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = document.selectFirst("div.singleStory")?.text()?.trim()

            val moviesAsEpisodes = movieCollectionList.select("li.movieItem").mapIndexedNotNull { index, item ->
                val a = item.selectFirst("a") ?: return@mapIndexedNotNull null
                val href = normalizeUrl(a.attr("href"), url) ?: return@mapIndexedNotNull null
                if (!href.contains("/film/")) return@mapIndexedNotNull null

                val movieTitle = item.selectFirst("h1.BottomTitle")?.text() ?: "Movie ${index + 1}"
                val moviePoster = item.selectFirst("img")?.attr("src")

                newEpisode(href) {
                    this.name = movieTitle
                    this.posterUrl = moviePoster
                    this.season = 1
                    this.episode = index + 1
                    this.data = href
                }
            }

            if (moviesAsEpisodes.isNotEmpty()) {
                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, moviesAsEpisodes) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                    this.recommendations = parseRecommendations(document, url)
                }
            }
        }

        if (url.contains("/film/")) {
            val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: return null
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = document.selectFirst("div.singleStory")?.text()?.trim()
            val year = document.select("div.LeftBox li:has(span:contains(السنه)) a").text().toIntOrNull()
            val tags = document.select("div.LeftBox li:has(span:contains(النوع)) a").map { it.text() }
            val recommendations = parseRecommendations(document, url)

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }

        val isEpisode = url.contains("/episode/")
        val isSeason = url.contains("/season/")
        val isSeriesPage = url.contains("/serie/")
        val hasSeasonsList = document.selectFirst("div.seasons-list") != null

        var startSeasonUrl: String? = null

        if (isEpisode) {
            val bc = document.selectFirst("div.breadcrumbs-single, div.breadcrumbs")
            bc?.select("a")?.forEach { a ->
                val href = a.attr("href")
                if (href.contains("/season/") || href.contains("/serie/")) {
                    startSeasonUrl = normalizeUrl(href, url)
                }
            }
            if (startSeasonUrl == null) {
                val linkInPage = document.selectFirst("div.seasons-list li.movieItem a, div.seasons-list a")?.attr("href")
                startSeasonUrl = normalizeUrl(linkInPage, url)
            }
        } else if (isSeason) {
            startSeasonUrl = url
        } else {
            val maybeEps = document.selectFirst("div.EpsList, div.episodes-list, ul.episodes")
            if (isSeriesPage || hasSeasonsList) {
                startSeasonUrl = url
            } else if (maybeEps != null) {
                val eps = extractEpisodesFromSeasonDoc(url, document)
                val pageImage = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it, url) }
                val epsWithImage = eps.map { ep ->
                    if (pageImage != null) { try { ep.posterUrl = pageImage } catch (_: Exception) {} }
                    ep
                }
                val seriesTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "TV Series"
                val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                val plot = document.selectFirst("div.singleStory")?.text()?.trim()

                return newTvSeriesLoadResponse(seriesTitle, url, TvType.TvSeries, epsWithImage) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                }
            } else {
                val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: return null
                val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
                val plot = document.selectFirst("div.singleStory")?.text()?.trim()
                val recommendations = parseRecommendations(document, url)
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.posterHeaders = imageHeaders
                    this.plot = plot
                    this.recommendations = recommendations
                }
            }
        }

        if (startSeasonUrl == null) return null

        val startDoc = try { httpGet(startSeasonUrl!!) } catch (e: Exception) { return null }

        val seasonAnchors = startDoc.select("div.seasons-list li.movieItem a, div.seasons-list a, div.seasons-list ul li a")
        val candidateSeasonUrls = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (a in seasonAnchors) {
            val raw = a.attr("href")
            val href = normalizeUrl(raw, startSeasonUrl!!)
            if (href != null && href !in seen) {
                seen.add(href)
                candidateSeasonUrls.add(href)
            }
        }

        if (candidateSeasonUrls.isNotEmpty()) {
            candidateSeasonUrls.reverse()
            val fetched = batchFetch(candidateSeasonUrls, concurrency = 8)
            val seasonsResults = mutableListOf<Pair<Int, List<CS3Episode>>>()

            for ((idx, sUrl) in candidateSeasonUrls.withIndex()) {
                val doc = fetched[sUrl]
                val seasonTitle = doc?.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                val titleForSeason = seasonTitle ?: "Season ${idx + 1}"
                val rawImg = doc?.selectFirst("meta[property=og:image]")?.attr("content")
                val seasonImage = rawImg?.let { normalizeUrl(it, sUrl) }

                val eps = if (doc != null) extractEpisodesFromSeasonDoc(sUrl, doc) else emptyList()
                val seasonNumber = getSeasonNum(titleForSeason).takeIf { it != 9999 } ?: (idx + 1)

                val epsWithSeason = eps.mapIndexed { epIdx, ep ->
                    ep.season = seasonNumber
                    ep.episode = ep.episode ?: (epIdx + 1)
                    if (seasonImage != null) { try { ep.posterUrl = seasonImage } catch (_: Exception) {} }
                    ep
                }
                seasonsResults.add(Pair(idx, epsWithSeason))
            }

            val allEpisodes = seasonsResults.flatMap { it.second }
            val seriesTitle = startDoc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.replace(Regex("""\s*(الموسم|الحلقة)\s+.*"""), "")?.trim() ?: "TV Series"
            val poster = startDoc.selectFirst("meta[property=og:image]")?.attr("content")
            val plot = startDoc.selectFirst("div.singleStory")?.text()?.trim()

            return newTvSeriesLoadResponse(seriesTitle, startSeasonUrl!!, TvType.TvSeries, allEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
                this.plot = plot
            }
        }

        val fallbackEpisodes = extractEpisodesFromSeasonDoc(startSeasonUrl!!, startDoc)
        val seriesTitleFallback = startDoc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "TV Series"
        val posterFallback = startDoc.selectFirst("meta[property=og:image]")?.attr("content")?.let { normalizeUrl(it, startSeasonUrl!!) }
        val plotFallback = startDoc.selectFirst("div.singleStory")?.text()?.trim()

        val epsFixed = fallbackEpisodes.mapIndexed { idx, ep ->
            ep.season = ep.season ?: 1
            ep.episode = ep.episode ?: (idx + 1)
            if (posterFallback != null) { try { ep.posterUrl = posterFallback } catch (_: Exception) {} }
            ep
        }

        return newTvSeriesLoadResponse(seriesTitleFallback, startSeasonUrl!!, TvType.TvSeries, epsFixed) {
            this.posterUrl = posterFallback
            this.posterHeaders = imageHeaders
            this.plot = plotFallback
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val originalUrl = data
            val watchPageUrl = if (!data.contains("?view=watch")) "$data?view=watch" else data

            try {
                httpGet(originalUrl, referer = originalUrl)
            } catch (e: Exception) {}

            val document = try {
                httpPost(watchPageUrl, data = mapOf("View" to "1"), referer = originalUrl)
            } catch (e: Exception) {
                try {
                    httpGet(watchPageUrl, referer = originalUrl)
                } catch (e2: Exception) {
                    return false
                }
            }

            val allSeenLinks = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val candidates = mutableListOf<Pair<String?, String?>>()

            fun normalizeCandidate(raw: String?): String? {
                if (raw.isNullOrBlank()) return null
                return try { normalizeUrl(raw, watchPageUrl) } catch (e: Exception) { null }
            }

            val downloadSelectors = listOf("ul.donwload-servers-list li", "ul.download-servers-list li", "ul.donwload-servers-list > li", "div.donwload-servers-list li")
            for (sel in downloadSelectors) {
                val nodes = document.select(sel)
                for (li in nodes) {
                    val serverName = li.selectFirst("span.ser-name")?.text()?.trim() ?: li.selectFirst("p")?.text()?.trim()
                    val href = li.selectFirst("a.ser-link")?.attr("href") ?: li.selectFirst("a")?.attr("href") ?: li.attr("data-link")
                    val normalized = normalizeCandidate(href)
                    if (normalized != null) candidates += Pair(normalized, serverName)
                }
            }

            val watchSelectors = listOf("ul.serversList li", "ul.servers-list li", "div.serversList li", "div.servers-list li")
            for (sel in watchSelectors) {
                val nodes = document.select(sel)
                for (li in nodes) {
                    val serverName = li.selectFirst("p")?.text()?.trim() ?: li.selectFirst(".ser-name")?.text()?.trim() ?: li.selectFirst("span.ser-name")?.text()?.trim()
                    val dataLink = li.attr("data-link").takeIf { it.isNotBlank() }
                    val childDataLink = li.selectFirst("[data-link]")?.attr("data-link")
                    val hrefFromA = li.selectFirst("a")?.attr("href")
                    val hrefFromBtn = li.selectFirst("button[data-link]")?.attr("data-link")

                    val candidate = dataLink ?: childDataLink ?: hrefFromBtn ?: hrefFromA
                    val normalized = normalizeCandidate(candidate)
                    if (normalized != null) candidates += Pair(normalized, serverName)
                }
            }

            for (el in document.select("[data-link]")) {
                val serverName = el.attr("data-name").takeIf { it.isNotBlank() } ?: el.attr("data-provider")
                val dl = el.attr("data-link")
                val normalized = normalizeCandidate(dl)
                if (normalized != null) candidates += Pair(normalized, serverName)
            }

            for (a in document.select("a")) {
                val href = a.attr("href")
                if (href.contains("player") || href.contains("embed") || href.contains("download") || href.contains("drive") || href.contains("mp4")) {
                    val serverName = a.attr("title").takeIf { it.isNotBlank() } ?: a.text().takeIf { it.isNotBlank() }
                    val normalized = normalizeCandidate(href)
                    if (normalized != null) candidates += Pair(normalized, serverName)
                }
            }

            val maxConcurrency = 8
            val semaphore = kotlinx.coroutines.sync.Semaphore(maxConcurrency)

            suspend fun prepareAndSendParallel(linkRaw: String?, serverName: String?): Boolean {
                if (linkRaw.isNullOrBlank()) return false
                val normalized = normalizeUrl(linkRaw, watchPageUrl) ?: return false

                if (!allSeenLinks.add(normalized)) return false

                try {
                    loadExtractor(normalized, data, subtitleCallback, callback)
                } catch (ex: Exception) {}

                try {
                    if (serverName != null && (serverName.equals("EarnVids", true) || serverName.equals("StreamHG", true))) {
                        val customLink: String? = try {
                            withContext(Dispatchers.IO) {
                                ExternalEarnVidsExtractor.extract(normalized, this@EgyDead.mainUrl)
                            }
                        } catch (ee: Exception) { null }

                        if (!customLink.isNullOrBlank()) {
                            val finalLink = customLink.toString()
                            try {
                                callback.invoke(
                                    newExtractorLink(
                                        source = this@EgyDead.name,
                                        name = "${serverName} (Custom)",
                                        url = finalLink,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = this@EgyDead.mainUrl
                                    }
                                )
                            } catch (cbEx: Exception) {}
                        }
                    }
                } catch (outer: Exception) {}

                return true
            }

            coroutineScope {
                candidates.map { pair ->
                    async {
                        semaphore.acquire()
                        try {
                            prepareAndSendParallel(pair.first, pair.second)
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}