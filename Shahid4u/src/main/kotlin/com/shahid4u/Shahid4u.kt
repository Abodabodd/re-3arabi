package com.shahid4u

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import java.net.URI
import java.net.URLEncoder

class Shahid4u : MainAPI() {
    override var mainUrl = "https://shhahhid4u.com/"
    override var name = "Shahid4u"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val logTag = "Shahid4uProvider"
    private var resolvedReferer: String? = null

    private data class Server(
        @JsonProperty("name") val name: String,
        @JsonProperty("url") val url: String
    )

    private data class PlayerResponse(
        @JsonProperty("player_url") val playerUrl: String?
    )
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
        val poster = document.selectFirst("div.poster-side img")?.attr("src")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        val plot = document.selectFirst("span.description")?.text()?.trim()
        val tags = document.select("div.qualities span.q-tag a").map { it.text() }

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
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.posterHeaders = posterheader()
                this.plot = plot
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
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
        val watchUrl = data.replace("/film/", "/watch/").replace("/episode/", "/watch/")
        val safeReferer = encodeUri(watchUrl) // الرابط الآمن لمنع انهيار التطبيق
        val fixedUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Mobile Safari/537.36"
        val fixedAcceptLanguage = "en-US,en;q=0.9"
        val watchResponse = app.get(
            watchUrl,
            headers = mapOf(
                "User-Agent" to fixedUserAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to fixedAcceptLanguage,
                "Upgrade-Insecure-Requests" to "1"
            ),
            interceptor = cfInterceptor
        )

        val htmlContent = watchResponse.text
        val watchDocument = watchResponse.document
        val cookieMap = mutableMapOf<String, String>()
        watchResponse.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }.forEach { header ->
            val cookiePart = header.second.substringBefore(";")
            val parts = cookiePart.split("=", limit = 2)
            if (parts.size == 2) {
                cookieMap[parts[0].trim()] = parts[1].trim()
            }
        }
        val dynamicCookie = cookieMap.map { "${it.key}=${it.value}" }.joinToString("; ")
        val pageToken = Regex("""const pageToken\s*=\s*"([^"]+)"""").find(htmlContent)?.groupValues?.get(1) ?: ""
        val csrfToken = Regex("""const csrfToken\s*=\s*"([^"]+)"""").find(htmlContent)?.groupValues?.get(1) ?: ""
        val issueUrlEscaped = Regex("""const issueUrl\s*=\s*"([^"]+)"""").find(htmlContent)?.groupValues?.get(1) ?: ""
        val issueUrl = issueUrlEscaped.replace("\\/", "/")

        if (pageToken.isNotEmpty() && csrfToken.isNotEmpty() && issueUrl.isNotEmpty()) {
            val serverButtons = watchDocument.select("button.btn-server")
            serverButtons.amap { button ->
                val serverKey = button.attr("data-server-key")

                if (serverKey.isNotBlank()) {
                    try {
                        val postHeaders = mapOf(
                            "User-Agent" to fixedUserAgent,
                            "Accept" to "application/json",
                            "Accept-Language" to fixedAcceptLanguage,
                            "X-CSRF-TOKEN" to csrfToken, // متغير
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to safeReferer,
                            "Cookie" to dynamicCookie // الكوكيز المتغيرة التي تم تجميعها
                        )
                        val apiResponse = app.post(
                            issueUrl,
                            headers = postHeaders,
                            json = mapOf(
                                "page_token" to pageToken,
                                "server_key" to serverKey
                            ),
                            interceptor = cfInterceptor
                        )

                        if (apiResponse.isSuccessful) {
                            val playerUrl = parseJson<PlayerResponse>(apiResponse.text).playerUrl

                            if (!playerUrl.isNullOrBlank()) {
                                val playerRes = app.get(
                                    playerUrl,
                                    headers = mapOf(
                                        "User-Agent" to fixedUserAgent,
                                        "Referer" to safeReferer,
                                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                                        "sec-fetch-dest" to "iframe",
                                        "sec-fetch-mode" to "navigate",
                                        "sec-fetch-site" to "same-origin"
                                    ),
                                    interceptor = cfInterceptor
                                )

                                var finalIframeSrc = playerRes.url
                                if (finalIframeSrc.contains(URI(mainUrl).host ?: "shahie4u")) {
                                    val iframe =
                                        playerRes.document.selectFirst("iframe")?.attr("src")
                                    if (!iframe.isNullOrBlank()) {
                                        finalIframeSrc =
                                            if (iframe.startsWith("//")) "https:$iframe" else iframe
                                    }
                                }

                                if (finalIframeSrc.isNotBlank()) {
                                    loadExtractor(
                                        finalIframeSrc,
                                        watchUrl,
                                        subtitleCallback,
                                        callback
                                    )
                                }
                            }
                        } else {
                            Log.e(
                                logTag,
                                "Server $serverKey rejected POST with code: ${apiResponse.code}"
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(logTag, "Error resolving server $serverKey: ${e.message}")
                    }
                }
            }
        } else {
            Log.e(logTag, "Tokens or Issue URL not found in page source.")
        }

        return true
    }
}