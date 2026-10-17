package com.tv
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URL

class FiveTVProvider : MainAPI() {
    override var mainUrl = "https://new61.5tv.lol"
    override var name = "5TV"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, headers = browserHeaders).document
        val homeSections = mutableListOf<HomePageList>()

        document.select("section.ftv-home-section").forEach { section ->
            val sectionTitle = section.selectFirst("h2")?.text()?.trim() ?: return@forEach
            val items = mutableListOf<SearchResponse>()

            section.select("article.ftv-home-poster-card").forEach { card ->
                parseCard(card)?.let { items.add(it) }
            }

            section.select("article.ftv-home-episode-card").forEach { card ->
                parseEpisodeCard(card)?.let { items.add(it) }
            }

            section.select("article.ftv-status-row__card").forEach { card ->
                parseStatusCard(card)?.let { items.add(it) }
            }

            if (items.isNotEmpty()) {
                homeSections.add(HomePageList(sectionTitle, items))
            }
        }

        return newHomePageResponse(homeSections)
    }

    private fun parseCard(element: Element): SearchResponse? {
        val title = element.selectFirst(".ftv-home-poster-card__title")?.text()?.trim() ?: return null
        val url = element.selectFirst(".ftv-home-poster-card__image")?.attr("href") ?: return null
        val poster = element.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        val isMovie = element.hasClass("ftv-home-poster-card--movie") || url.contains("/movie/")

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    private fun parseEpisodeCard(element: Element): SearchResponse? {
        val titleEl = element.selectFirst(".ftv-home-episode-card__title") ?: return null
        val title = titleEl.text().trim()
        val url = titleEl.attr("href") ?: return null
        val poster = element.selectFirst("img")?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        val meta = element.selectFirst(".ftv-home-episode-card__meta")?.text()?.replace("\n", " ")?.trim()
        val fullTitle = if (!meta.isNullOrEmpty()) "$title ($meta)" else title

        return newTvSeriesSearchResponse(fullTitle, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun parseStatusCard(element: Element): SearchResponse? {
        val link = element.selectFirst("a.ftv-status-row__link") ?: return null
        val url = link.attr("href") ?: return null
        val title = element.selectFirst("h3")?.text()?.trim() ?: return null
        val poster = element.selectFirst("img")?.attr("src")

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/?q=$query"
        val document = app.get(searchUrl, headers = browserHeaders).document

        return document.select(".ftv-search-grid article").mapNotNull { element ->
            parseCard(element)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        var finalUrl = url
        if (url.contains("/episode/")) {
            try {
                val epDoc = app.get(url, headers = browserHeaders).document
                val backLink = epDoc.selectFirst("a.ftv-episode-poster-back")?.attr("href")
                if (!backLink.isNullOrEmpty()) {
                    finalUrl = backLink
                }
            } catch (_: Exception) {}
        }

        val document = app.get(finalUrl, headers = browserHeaders).document

        val title = document.selectFirst("h1.ftv-content-hero__title")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".ftv-content-hero__poster img")?.attr("src")
        val description = document.selectFirst(".ftv-content-hero__description p")?.text()?.trim()
        val year = document.selectFirst(".ftv-content-hero__year a")?.text()?.trim()?.toIntOrNull()
        val genres = document.select(".ftv-content-hero__genres a").map { it.text().trim() }

        val isMovie = finalUrl.contains("/movie/")

        return if (isMovie) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val episodeElements = document.select(".ftv-series-episodes-grid .ftv-episode-completion-item, .ftv-episodes-grid .ftv-episode-completion-item")

            episodeElements.forEach { el ->
                val epHref = el.selectFirst("a.ftv-series-episode-card, a.ftv-episode-card")?.attr("href") ?: return@forEach
                val epTitle = el.selectFirst(".ftv-series-episode-card__body strong, .ftv-episode-card__text strong")?.text()?.trim() ?: "حلقة"
                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
            episodes.sortBy { it.episode }

            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = genres
            }
        }
    }

    private fun unescapeHtml(text: String): String {
        return text
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
    }

    private fun unpackDeanEdwards(scriptText: String): String {
        try {
            var splitIdx = scriptText.lastIndexOf(".split('|')")
            if (splitIdx == -1) {
                splitIdx = scriptText.lastIndexOf(".split(\"|\")")
            }
            if (splitIdx == -1) return ""

            val sub = scriptText.substring(0, splitIdx)
            val paramsRegex = Regex(""",\s*(\d+)\s*,\s*(\d+)\s*,\s*['"]""")
            val matches = paramsRegex.findAll(sub).toList()
            if (matches.isEmpty()) return ""

            val lastMatch = matches.last()
            val a = lastMatch.groupValues[1].toIntOrNull() ?: return ""
            val c = lastMatch.groupValues[2].toIntOrNull() ?: return ""

            val kStr = sub.substring(lastMatch.range.last + 1)
            val k = kStr.split("|")

            val lastBrace = sub.lastIndexOf('}', lastMatch.range.first)
            if (lastBrace == -1) return ""

            var pStart = sub.indexOf('\'', lastBrace)
            if (pStart == -1 || pStart > lastMatch.range.first) {
                pStart = sub.indexOf('"', lastBrace)
            }
            if (pStart == -1) return ""

            val p = sub.substring(pStart + 1, lastMatch.range.first).trimEnd('\'', '"')

            fun getBaseNString(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                if (num == 0) return "0"
                var n = num
                var res = ""
                while (n > 0) {
                    res = chars[n % base] + res
                    n /= base
                }
                return if (res.isEmpty()) "0" else res
            }

            val lookup = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val key = getBaseNString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                lookup[key] = value
            }

            val wordRegex = Regex("""\b\w+\b""")
            return wordRegex.replace(p) { matchResult ->
                val word = matchResult.value
                lookup[word] ?: word
            }
        } catch (_: Exception) {
            return ""
        }
    }

    private fun cleanUrl(rawUrl: String): String {
        var clean = rawUrl.replace("\\/", "/")
        clean = unescapeHtml(clean)
        clean = clean.split("\"")[0].split("'")[0]
        clean = clean.split("<")[0].split(">")[0]
        clean = clean.split(",")[0].split("\\")[0]
        return clean.trim()
    }

    private suspend fun verifyAndGetHeaders(videoUrl: String, domain: String): Map<String, String>? {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

        val headersWithReferer = mapOf(
            "User-Agent" to userAgent,
            "Referer" to domain,
            "Origin" to domain.removeSuffix("/"),
            "Range" to "bytes=0-1"
        )

        val headersWithoutReferer = mapOf(
            "User-Agent" to userAgent,
            "Range" to "bytes=0-1"
        )

        try {
            val resNoRef = app.get(videoUrl, headers = headersWithoutReferer, timeout = 6)
            if (resNoRef.code in 200..299) {
                return emptyMap()
            }
        } catch (_: Exception) {}

        try {
            val resWithRef = app.get(videoUrl, headers = headersWithReferer, timeout = 6)
            if (resWithRef.code in 200..299) {
                return mapOf("Referer" to domain, "Origin" to domain.removeSuffix("/"))
            }
        } catch (_: Exception) {}

        if (videoUrl.contains(".m3u8")) {
            try {
                val resM3u8WithRef = app.get(videoUrl, headers = mapOf("Referer" to domain), timeout = 6)
                if (resM3u8WithRef.code in 200..299) return mapOf("Referer" to domain)
            } catch (_: Exception) {}

            try {
                val resM3u8NoRef = app.get(videoUrl, headers = emptyMap(), timeout = 6)
                if (resM3u8NoRef.code in 200..299) return emptyMap()
            } catch (_: Exception) {}
        }

        return null
    }

    data class ServerTarget(val name: String, val url: String, val isDownload: Boolean)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try {
            app.get(data, headers = browserHeaders).document
        } catch (_: Exception) {
            return false
        }

        val targets = mutableListOf<ServerTarget>()
        val seenUrls = mutableSetOf<String>()

        val iframeSrc = document.selectFirst(".ftv-player-iframe")?.attr("src")?.trim() ?: ""
        if (iframeSrc.isNotEmpty() && !seenUrls.contains(iframeSrc)) {
            targets.add(ServerTarget("Direct Player", iframeSrc, false))
            seenUrls.add(iframeSrc)
        }

        document.select(".ftv-watch-tabs button[data-server-url]").forEach { tab ->
            val u = tab.attr("data-server-url").trim()
            val name = tab.selectFirst("strong")?.text()?.trim() ?: "Stream"
            if (u.isNotEmpty() && !seenUrls.contains(u)) {
                targets.add(ServerTarget(name, u, false))
                seenUrls.add(u)
            }
        }

        document.select(".ftv-download-list article.ftv-download-server a").forEach { link ->
            val u = link.attr("href").trim()
            val parentArticle = link.parents().firstOrNull { it.hasClass("ftv-download-server") }
            val name = parentArticle?.selectFirst("strong")?.text()?.trim() ?: "Download"
            if (u.isNotEmpty() && !seenUrls.contains(u)) {
                targets.add(ServerTarget(name, u, true))
                seenUrls.add(u)
            }
        }

        coroutineScope {
            targets.map { target ->
                async {
                    var serverUrl = target.url

                    if (serverUrl.contains("dsvplay.com")) {
                        serverUrl = serverUrl.replace("dsvplay.com", "playmogo.com")
                    }

                    try {
                        loadExtractor(serverUrl, data, subtitleCallback, callback)

                        val uri = URL(serverUrl)
                        val domain = "${uri.protocol}://${uri.host}/"

                        val res = app.get(
                            serverUrl,
                            headers = mapOf(
                                "Referer" to data,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                            ),
                            timeout = 10
                        )

                        if (res.code == 200) {
                            var content = unescapeHtml(res.text)

                            if (content.contains("eval(function(p,a,c,k")) {
                                val unpacked = unpackDeanEdwards(content)
                                if (unpacked.isNotEmpty()) {
                                    content += "\n" + unescapeHtml(unpacked)
                                }
                            }

                            val matchedUrls = mutableSetOf<String>()

                            val m3u8Regex = Regex("""(https?:[\\/]+[^\s"'<>]+\.m3u8[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            m3u8Regex.findAll(content).forEach { m ->
                                matchedUrls.add(cleanUrl(m.groupValues[1]))
                            }

                            val r2Regex = Regex("""(https?:[\\/]+[^\s"'<>]+cloudflarestorage[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            r2Regex.findAll(content).forEach { m ->
                                matchedUrls.add(cleanUrl(m.groupValues[1]))
                            }

                            val mediaRegex = Regex("""(https?:[\\/]+[^\s"'<>]+\.(?:mp4|mkv)[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
                            mediaRegex.findAll(content).forEach { m ->
                                matchedUrls.add(cleanUrl(m.groupValues[1]))
                            }

                            val fileRegex = Regex("""["']?file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            fileRegex.findAll(content).forEach { m ->
                                val f = m.groupValues[1]
                                if (f.startsWith("http")) {
                                    matchedUrls.add(cleanUrl(f))
                                }
                            }

                            if (target.isDownload) {
                                val doc = Jsoup.parse(res.text)
                                doc.select("a[href]").forEach { a ->
                                    val href = a.attr("href")
                                    if (href.contains(".mp4") || href.contains(".mkv") || href.contains(".m3u8")) {
                                        matchedUrls.add(cleanUrl(href))
                                    }
                                }
                            }

                            matchedUrls.forEach { videoUrl ->
                                if (videoUrl.length > 20 && videoUrl != serverUrl) {
                                    val workingHeaders = verifyAndGetHeaders(videoUrl, domain)

                                    if (workingHeaders != null) {
                                        val isM3u8 = videoUrl.contains(".m3u8")
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = "[5TV] ${target.name}",
                                                url = videoUrl,
                                            ) {
                                                this.referer = workingHeaders["Referer"] ?: ""
                                                this.quality = Qualities.P1080.value
                                                this.headers = workingHeaders
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }.awaitAll()
        }

        return true
    }
}