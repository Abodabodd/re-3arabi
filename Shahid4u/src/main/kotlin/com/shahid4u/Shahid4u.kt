package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Log
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

class Shahid4u : MainAPI() {
    override var mainUrl = "https://shhaiid4u.net/"
    override var name = "Shahid4u"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val logTag = "Shahid4uProvider"
    private var resolvedReferer: String? = null

    private val TRANSPARENT_PNG_DATA_URI =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg=="
    override var sequentialMainPage = true
    override var sequentialMainPageDelay = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller
    private fun encodeUri(url: String): String {
        return try {
            url.toCharArray().joinToString("") { char ->
                if (char.code <= 127) char.toString() else URLEncoder.encode(
                    char.toString(),
                    "UTF-8"
                )
            }
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun buildBrowserHeaders(referer: String? = null): Map<String, String> {
        val ref = referer ?: resolvedReferer ?: mainUrl
        val safeRef = encodeUri(ref) // <-- تنظيف الرابط هنا

        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to safeRef, // <-- استخدام الرابط الآمن
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun posterheader(referer: String? = null): Map<String, String> {
        val ref = referer ?: resolvedReferer ?: mainUrl
        val safeRef = encodeUri(ref) // <-- تنظيف الرابط هنا

        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Referer" to safeRef, // <-- استخدام الرابط الآمن
            "Connection" to "keep-alive",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Site" to "same-origin",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Dest" to "document"
        )
    }

    private fun buildMergedHeaders(url: String, referer: String? = null): Map<String, String> {
        val base = buildBrowserHeaders(referer).toMutableMap()

        return try {
            val cloudHeaders = cloudflareKiller.getCookieHeaders(url).toMultimap()
                .mapValues { entry -> entry.value.joinToString("; ") }
            base.putAll(cloudHeaders)
            base
        } catch (e: Exception) {
            Log.w(logTag, "buildMergedHeaders -> failed to get cloudflare headers: ${e.message}")
            base
        }
    }

    private fun makeAbsoluteUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val p = url.trim()
        return when {
            p.startsWith("http://", true) || p.startsWith("https://", true) -> p
            p.startsWith("//") -> "https:$p"
            p.startsWith("/") -> mainUrl.trimEnd('/') + p
            else -> {
                mainUrl + p
            }
        }
    }

    private suspend fun httpGet(url: String, referer: String? = null): org.jsoup.nodes.Document {
        val headers = buildMergedHeaders(url, referer)
        val safeRef = encodeUri(referer ?: mainUrl) // <-- تنظيف الرابط هنا

        val response = app.get(
            url,
            referer = safeRef, // <-- استخدام الرابط الآمن
            headers = headers,
            interceptor = cfInterceptor
        )
        if (resolvedReferer == null) {
            val finalUrl = response.url
            val match = Regex("^(https?://[^/]+/)").find(finalUrl)
            resolvedReferer = match?.value ?: mainUrl
            Log.d(logTag, "تم التقاط الرابط النهائي للصور (Referer): $resolvedReferer")
        }

        return response.document
    }

    private fun parseCard(element: Element): SearchResponse? {
        val linkElement = element.selectFirst("a.show.card, a.glide_post, a")
        if (linkElement == null) return null

        val href = linkElement.attr("href").ifBlank { linkElement.absUrl("href") }

        val mainTitle = element.selectFirst("p.title")?.text()?.trim()
        val description = element.selectFirst("p.description")?.text()?.trim()
        val title = if (!mainTitle.isNullOrBlank()) {
            if (!description.isNullOrBlank()) "$mainTitle - $description" else mainTitle
        } else {
            element.selectFirst("div.card-content")?.text()?.trim()
                ?: element.selectFirst("h3")?.text()?.trim()
        }
        if (title.isNullOrBlank()) return null

        val posterStyle = linkElement.attr("style")
        var posterUrl = Regex("""url\(['"]?(.*?)['"]?\)""").find(posterStyle)?.groupValues?.get(1)
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = element.selectFirst("img")?.attr("src")
        posterUrl = makeAbsoluteUrl(posterUrl) ?: TRANSPARENT_PNG_DATA_URI

        val isTvSeries =
            element.selectFirst(".ep_num, .الحلقة") != null || href.contains("/episode/")

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheader()
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = posterheader()
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.isNotEmpty()) {
            val categoryUrl = "${request.data}?page=$page"
            val document = httpGet(categoryUrl, referer = mainUrl)
            val items = document.select("div.shows-container.row div[class*=col-]").mapNotNull {
                parseCard(it)
            }
            val hasNext =
                document.selectFirst("ul.pagination li.page-item.active + li.page-item a") != null
            return newHomePageResponse(request.name, items, hasNext)
        }

        if (page > 1) return newHomePageResponse(emptyList())

        val homePageList = mutableListOf<HomePageList>()
        val document = httpGet(mainUrl, referer = mainUrl)

        try {
            val sliderItems =
                document.select("div.glide li.glide__slide:not(.glide__slide--clone)").mapNotNull {
                    parseCard(it)
                }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("أبرز العروض", sliderItems))
            }
        } catch (e: Exception) {
            Log.e(logTag, "Error parsing slider items: ${e.message}")
        }

        val categories = listOf(
            "مسلسلات أجنبي" to "${mainUrl}category/مسلسلات-اجنبي",
            "مسلسلات عربي" to "${mainUrl}category/مسلسلات-عربي",
            "مسلسلات تركية" to "${mainUrl}category/مسلسلات-تركية",
            "مسلسلات انمي" to "${mainUrl}category/مسلسلات-انمي",
        )

        for ((title, url) in categories) {
            try {
                val doc = httpGet(url, referer = mainUrl)
                val items =
                    doc.select("div.shows-container.row div[class*=col-]").take(40).mapNotNull {
                        parseCard(it)
                    }
                if (items.isNotEmpty()) homePageList.add(HomePageList(title, items, true))
            } catch (e: Exception) {
                Log.e(logTag, "Failed to load category '$title': ${e.message}")
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "${mainUrl}search?s=$encoded"

        return try {
            val document = httpGet(searchUrl, referer = mainUrl)
            val resultItems = document.select("div.shows-container.row div[class*=col-]")

            if (resultItems.isEmpty()) return emptyList()

            resultItems.mapIndexedNotNull { index, element ->
                try {
                    parseCard(element)
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = httpGet(url)

        val title = document.selectFirst("span.title")?.text()?.trim() ?: "غير متوفر"
        val posterStyle = document.selectFirst("div.poster-side div.poster")?.attr("style").orEmpty()
        val poster = document.selectFirst("div.poster-side img")?.attr("src")
            ?: Regex("""--background-image-url:\s*url\(['"]?(.*?)['"]?\)""")
                .find(posterStyle)?.groupValues?.get(1)
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("span.description")?.text()?.trim()
        val tags = document.select("div.qualities span.q-tag a").map { it.text() }
        val isAnime = url.contains("انمي") || title.contains("انمي") ||
                url.contains("anime", ignoreCase = true)

        val seasons = document.select("div.w-100.bg-main.rounded.my-4 a.epss[href*='/season/']")
        val episodes = ArrayList<Episode>()

        if (seasons.isNotEmpty()) {
            seasons.amap { seasonElement ->
                val seasonUrl = seasonElement.attr("href")
                val seasonDoc = httpGet(seasonUrl, referer = url)

                seasonDoc.select("div.w-100.bg-main.rounded.my-4 a.epss:not([href*='/season/'])")
                    .forEach { episodeElement ->
                        val epName = episodeElement.text().trim()
                        val epUrl = episodeElement.attr("href")
                        val episodeNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()
                        val seasonNumber =
                            Regex("""الموسم\s*(\d+)""").find(seasonElement.text())?.groupValues?.get(
                                1
                            )?.toIntOrNull()

                        episodes.add(newEpisode(epUrl) {
                            this.name = epName
                            episode = episodeNumber
                            season = seasonNumber
                            posterUrl = poster
                        })
                    }
            }
        } else {
            document.select("div.w-100.bg-main.rounded.my-4 a.epss:not([href*='/season/'])")
                .forEach { episodeElement ->
                    val epName = episodeElement.text().trim()
                    val epUrl = episodeElement.attr("href")
                    val episodeNumber = Regex("""\d+""").find(epName)?.value?.toIntOrNull()

                    episodes.add(newEpisode(epUrl) {
                        this.name = epName
                        this.episode = episodeNumber
                        this.posterUrl = poster
                    })
                }
        }

        val sortedEpisodes = episodes.sortedWith(compareBy({ it.season }, { it.episode }))

        return if (sortedEpisodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, if (isAnime) TvType.Anime else TvType.Movie, url) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = data
            .replace("/film/", "/watch/")
            .replace("/episode/", "/watch/")
            .replace("/download/", "/watch/")
            .replace("/season/", "/watch/")

        val embedUrls = linkedSetOf<String>()
        val browserHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9",
            "Upgrade-Insecure-Requests" to "1"
        )
        try {
            val watchResponse = app.get(
                watchUrl,
                headers = browserHeaders,
                interceptor = cfInterceptor
            )
            val htmlContent = watchResponse.text
            embedUrls.addAll(parseEmbedUrls(htmlContent))
            watchResponse.document.select("iframe[src]").forEach { iframe ->
                val src = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                if (src.isNotBlank()) embedUrls.add(src)
            }
        } catch (e: Exception) {
            Log.e(logTag, "loadLinks -> failed to fetch watch page $watchUrl: ${e.message}")
        }
        try {
            val downloadUrl = watchUrl.replace("/watch/", "/download/")
            if (downloadUrl != watchUrl) {
                val dlResponse = app.get(
                    downloadUrl,
                    headers = browserHeaders + ("Referer" to watchUrl),
                    interceptor = cfInterceptor
                )
                if (dlResponse.isSuccessful) {
                    dlResponse.document.select("a.btn-down[href], a[href*='/d/']").forEach { a ->
                        val href = a.absUrl("href").ifBlank { a.attr("href") }
                        val link = makeAbsoluteUrl(href)
                        if (!link.isNullOrBlank()) embedUrls.add(link)
                    }
                } else {
                    Log.w(logTag, "download page $downloadUrl returned code ${dlResponse.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(logTag, "loadLinks -> download page failed: ${e.message}")
        }

        if (embedUrls.isEmpty()) {
            Log.e(logTag, "loadLinks -> no embed urls found on $watchUrl")
            return false
        }

        val results = embedUrls.toList().amap { embedUrl ->
            try {
                resolveEmbedUrl(embedUrl, watchUrl, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.w(logTag, "resolveEmbedUrl failed ($embedUrl): ${e.message}")
                false
            }
        }

        return results.any { it }
    }

    /**
     * فحص إدخالات سيرفرات الفخ (canary) التي يضيفها الموقع ولا تمثل سيرفراً حقيقياً.
     */
    private fun isCanaryServer(name: String?, rank: Int?, url: String?): Boolean {
        if (rank != null && rank >= 900000) return true
        val n = (name ?: "").lowercase()
        if (n.contains("backup") || n.contains("mirror") || n.contains("cdn player")) return true
        val u = (url ?: "").lowercase()
        return u.contains("/media/watch/") || u.contains("/media/api/") || u.contains("/media/page/")
    }

    /**
     * استخراج روابط السيرفرات من كتل JSON.parse المضمّنة في الصفحة.
     */
    private fun parseEmbedUrls(html: String): List<String> {
        val out = linkedSetOf<String>()
        val cleaned = html
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&amp;", "&")

        val jsonParseRegex = Regex("""JSON\.parse\(\s*['"]([\s\S]*?)['"]\s*\)""")
        for (match in jsonParseRegex.findAll(cleaned)) {
            val raw = match.groupValues[1].replace("\\/", "/")
            try {
                val array = JSONArray(raw)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val url = obj.optString("url").ifBlank { obj.optString("src") }
                    val name = obj.optString("name")
                    val rank = obj.optInt("rank", 0)
                    if (url.isNotBlank() && !isCanaryServer(name, rank, url)) {
                        out.add(url)
                    }
                }
            } catch (e1: JSONException) {
                try {
                    val obj = JSONObject(raw)
                    val url = obj.optString("url").ifBlank { obj.optString("src") }
                    val name = obj.optString("name")
                    val rank = obj.optInt("rank", 0)
                    if (url.isNotBlank() && !isCanaryServer(name, rank, url)) out.add(url)
                } catch (e2: JSONException) {
                    Log.w(logTag, "parseEmbedUrls -> could not parse JSON block: ${e2.message}")
                }
            }
        }

        if (out.isEmpty()) {
            Regex("""["']url["']\s*:\s*["']((?:\\.|[^"'])+)["']""")
                .findAll(cleaned)
                .forEach { m ->
                    val u = m.groupValues[1].replace("\\/", "/")
                    if (u.startsWith("http")) out.add(u)
                }
        }

        return out.toList()
    }

    /**
     * حلّ رابط سيرفر: إما تمريره إلى loadExtractor، أو فتح صفحة داخلية والبحث عن iframe،
     * أو إرسال رابط مباشر (m3u8/mp4) كرابط تشغيل.
     */
    private suspend fun resolveEmbedUrl(
        embedUrl: String,
        watchUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val target = makeAbsoluteUrl(embedUrl) ?: return false
        val targetHost = runCatching { URI(target).host }.getOrNull()
        val mainHost = runCatching { URI(mainUrl).host }.getOrNull()

        if (targetHost != null && mainHost != null && targetHost.equals(mainHost, ignoreCase = true)) {
            return runCatching {
                val page = httpGet(target, referer = watchUrl)
                val iframe = page.selectFirst("iframe[src]")
                if (iframe != null) {
                    val src = iframe.absUrl("src").ifBlank { makeAbsoluteUrl(iframe.attr("src")) }
                    if (!src.isNullOrBlank()) {
                        return@runCatching resolveEmbedUrl(src, watchUrl, subtitleCallback, callback)
                    }
                }
                val innerLinks = parseEmbedUrls(page.outerHtml())
                for (inner in innerLinks) {
                    if (resolveEmbedUrl(inner, watchUrl, subtitleCallback, callback)) return@runCatching true
                }
                false
            }.getOrDefault(false)
        }

        val lower = target.lowercase()
        val isM3u8 = lower.endsWith(".m3u8") || lower.contains(".m3u8?") || lower.contains("/hls/")
        val isVideo = lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
        if (isM3u8 || isVideo) {
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "مباشر",
                    url = target,
                ) {
                    this.referer = watchUrl
                    this.quality = -1
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                }
            )
            return true
        }

        return loadExtractor(target, watchUrl, subtitleCallback, callback)
    }
}
