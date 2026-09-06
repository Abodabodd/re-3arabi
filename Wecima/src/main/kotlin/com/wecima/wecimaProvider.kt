package com.lagradost.cloudstream3.ar

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import org.jsoup.Jsoup

class WecimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.ac"
    override var name = "We Cima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val logTag = "WecimaProvider1"

    @Volatile
    private var resolvedSearchBase: String? = null

    private suspend fun resolveSearchBase(): String {
        resolvedSearchBase?.let { return it }

        val resp = app.get(
            "$mainUrl/search",
            allowRedirects = true,
            interceptor = cloudflareKiller
        )

        val finalUrl = resp.url.substringBefore("/search")

        resolvedSearchBase = finalUrl

        Log.d(logTag, "✅ Resolved search base -> $finalUrl")
        return finalUrl
    }

    @Volatile
    private var currentCfCookie: String? = "cf_clearance=ED0NVzmNRSWM57kanLn3lwlIaUBtZI2vPk1ZAP6zyfA-1763125072-1.2.1.1-eWAxOeBZN.zpvnXMgT46AJocpEalzqZ3WXlNZzjhIhJEvbNkRhY1Mz84sv6onyU7zfGBygnr3XbAw955PxJHjnRFUMqcp6o0rbp5wT4hJMMFiUaOSABpAAx.9G7sYlmslO5bpns2BZxvxUlV3onCRMAQY7lUHW.MCZCH10MtRNaPKsDmxvtZo14sQDLEXyu8tEDOAkjElHCHAW3iyseTRNN2j0Zd7ceblYh28.E7PwA;"

    private val cloudflareKiller by lazy { CloudflareKiller() }


    private fun getInterceptor(): Interceptor? {
        return if (currentCfCookie == null) cloudflareKiller else null
    }


    private fun getHeaders(isPost: Boolean = false): Map<String, String> {
        val baseHeaders = if (isPost) postHeaders else standardHeaders
        return currentCfCookie?.let {
            baseHeaders.plus("Cookie" to it)
        } ?: baseHeaders
    }

    private fun parseCfClearance(headers: okhttp3.Headers) {
        headers["Set-Cookie"]?.let { cookieHeader ->
            val newCookie = cookieHeader.split(';').find { it.trim().startsWith("cf_clearance=") }
            if (newCookie != null) {
                Log.d(logTag, "CloudflareKiller obtained a new cookie. Saving it for future requests.")
                currentCfCookie = newCookie
            }
        }
    }


    override val mainPage = mainPageOf(
        "$mainUrl/seriestv" to "أحدث المسلسلات",
        "$mainUrl/movies" to "أحدث الأفلام",
        "$mainUrl/category/arabic-movies" to "أفلام عربية",
        "$mainUrl/category/foreign-movies" to "أفلام أجنبية",
        "$mainUrl/category/arabic-series" to "مسلسلات عربية",
        "$mainUrl/category/foreign-series" to "مسلسلات أجنبية"
    )

    override var sequentialMainPage = true

    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private val postHeaders = standardHeaders.plus(
        mapOf(
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
    )

    private suspend fun httpGet(url: String, referer: String? = null): org.jsoup.nodes.Document {

        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "Attempting GET with current cookie...")
                return app.get(
                    url,
                    referer = referer ?: mainUrl,
                    headers = getHeaders(isPost = false),
                    interceptor = getInterceptor() // Will be null here
                ).document
            } catch (e: Exception) {
                Log.w(logTag, "GET with current cookie failed. It has likely expired. Error: ${e.message}")
                currentCfCookie = null // Invalidate the cookie
            }
        }

        Log.d(logTag, "Executing GET with CloudflareKiller to get a new session...")
        val response = app.get(
            url,
            referer = referer ?: mainUrl,
            headers = getHeaders(isPost = false), // Now without cookie
            interceptor = getInterceptor()       // Now with CloudflareKiller
        )

        parseCfClearance(response.headers)
        return response.document
    }

    private suspend fun httpPost(url: String, data: Map<String, String>, referer: String? = null): String {

        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "Attempting POST with current cookie...")
                return app.post(
                    url,
                    data = data,
                    referer = referer ?: mainUrl,
                    headers = getHeaders(isPost = true),
                    interceptor = getInterceptor() // Will be null
                ).text
            } catch (e: Exception) {
                Log.w(logTag, "POST with current cookie failed. It has likely expired. Error: ${e.message}")
                currentCfCookie = null // Invalidate the cookie
            }
        }
        Log.d(logTag, "Executing POST with CloudflareKiller to get a new session...")
        val response = app.post(
            url,
            data = data,
            referer = referer ?: mainUrl,
            headers = getHeaders(isPost = true), // Now without cookie
            interceptor = getInterceptor()       // Now with CloudflareKiller
        )
        parseCfClearance(response.headers)
        return response.text
    }


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = httpGet(request.data)
        val home = document.select("div.Grid--WecimaPosts div.GridItem").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2, strong") ?: return null
        val title = titleElement.text().trim()
        val anchor = this.selectFirst("a") ?: return null
        val href = anchor.attr("href")

        val posterSpan = this.selectFirst("span.BG--GridItem")
        val posterUrl = posterSpan?.attr("data-src")?.ifBlank { null }
            ?: posterSpan?.attr("style")?.let { style ->
                Regex("url\\((.*?)\\)").find(style)?.groupValues?.get(1)
            }?.let { fixUrl(it) }

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.posterHeaders = imageHeaders() // cf_clearance فقط
        }
    }

    private fun imageHeaders(): Map<String, String> {
        return currentCfCookie?.let {
            mapOf("Cookie" to it)
        } ?: emptyMap()
    }
    data class WecimaSearchItem(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("year") val year: String? = null,
        @JsonProperty("istv") val istv: Int? = null
    )

    data class WecimaSearchResponseJson(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("results") val results: List<WecimaSearchItem>? = null
    )
    private suspend fun httpGetRaw(url: String): com.lagradost.nicehttp.NiceResponse {
        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "⚡ Fast Check: Attempting GET with cached cookie...")
                val response = app.get(
                    url,
                    referer = mainUrl,
                    headers = getHeaders(isPost = false),
                    interceptor = null // نمنع الكيلر هنا عمداً
                )

                if (response.code == 200) {
                    return response
                }
                Log.w(logTag, "Cookie expired or invalid (Code: ${response.code})")
            } catch (e: Exception) {
                Log.w(logTag, "Fast check failed: ${e.message}")
                currentCfCookie = null // الكوكيز تالف، نحذفه
            }
        }
        Log.d(logTag, "🐢 Slow Check: Triggering CloudflareKiller...")
        val response = app.get(
            url,
            referer = mainUrl,
            headers = getHeaders(isPost = false), // بدون كوكيز قديمة
            interceptor = cloudflareKiller       // الآن نسمح بالكيلر
        )
        parseCfClearance(response.headers)
        return response
    }
    private suspend fun getFreshDomainAndCookies(): String {
        return try {
            val response = httpGetRaw(mainUrl)
            val finalUrl = response.url.trimEnd('/')

            if (finalUrl.isNotEmpty() && finalUrl != mainUrl && finalUrl.startsWith("http")) {
                Log.d(logTag, "🚨 Domain Redirect Detected! Updating: $mainUrl -> $finalUrl")
                return finalUrl
            }

            Log.d(logTag, "✅ Domain is valid & Cookies worked.")
            return mainUrl

        } catch (e: Exception) {
            Log.e(logTag, "⚠️ Domain Check Failed: ${e.message}")
            return mainUrl
        }
    }
    private suspend fun performSearch(query: String): List<SearchResponse> {
        val activeUrl = getFreshDomainAndCookies()

        if (mainUrl != activeUrl) {
            mainUrl = activeUrl
        }

        val url = "$activeUrl/search"
        Log.d(logTag, "🚀 Executing Search POST to: $url with query: $query")

        val responseText = httpPost(
            url,
            data = mapOf("q" to query),
            referer = activeUrl
        )

        return try {
            val parsed = AppUtils.parseJson<WecimaSearchResponseJson>(responseText)

            parsed.results?.mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val slug = item.slug ?: return@mapNotNull null
                val posterUrl = item.image

                if (item.istv == 2) return@mapNotNull null

                val type = if (item.istv == 0) TvType.Movie else TvType.TvSeries
                val prefix = if (item.istv == 0) "/watch/" else "/series/"
                val encodedSlug = java.net.URLEncoder.encode(slug, "UTF-8").replace("+", "%20")
                val itemUrl = if (slug.startsWith("http")) slug else "$mainUrl$prefix$encodedSlug"

                Log.d(logTag, "🔍 Search Result: [$title] -> $itemUrl")

                newMovieSearchResponse(title, itemUrl, type) {
                    this.posterUrl = posterUrl
                    this.year = item.year?.toIntOrNull()
                    this.posterHeaders = imageHeaders()
                }
            } ?: emptyList()

        } catch (e: Exception) {
            Log.e(logTag, "Search Parsing Error: ${e.message}")
            emptyList()
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val initialResults = performSearch(query)
        if (initialResults.isEmpty() && query.trim() == "مسلسل") {
            Log.d(logTag, "🔄 Fallback triggered: No results for 'مسلسل', searching for 'game of' instead.")
            return performSearch("game of")
        }
        return initialResults
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(logTag, "load() initiated with URL: $url")
        val document = httpGet(url)

        val isTvSeriesPage = url.contains("/series/") || document.selectFirst(".List--Seasons--Episodes") != null
        val isEpisodePage = url.contains("/watch/") && document.selectFirst(".Breadcrumb--UX a[href*='/series/']") != null

        Log.d(logTag, "isTvSeriesPage: $isTvSeriesPage, isEpisodePage: $isEpisodePage")

        if (isTvSeriesPage || isEpisodePage) {
            val seriesUrl = if (isEpisodePage) {
                document.selectFirst(".Breadcrumb--UX a[href*='/series/']")?.attr("href")
                    ?: throw ErrorLoadingException("Could not find series URL from episode page")
            } else {
                url
            }

            val seriesDocument = if (isEpisodePage) httpGet(seriesUrl) else document

            val title = seriesDocument.selectFirst("div.Title--Content--Single-begin h1")?.text()?.trim() ?: "TV Series Title"
            val posterUrl = seriesDocument.selectFirst("wecima.separated--top")?.attr("style")?.let {
                Regex("--img:url\\((.*?)\\)").find(it)?.groupValues?.get(1)
            }
            val plot = seriesDocument.selectFirst("div.StoryMovieContent")?.text()?.trim()
            val year = seriesDocument.select("ul.Terms--Content--Single-begin li")
                .find { it.selectFirst("span")?.text()?.contains("السنة") == true }
                ?.selectFirst("p")?.text()?.toIntOrNull()

            Log.d(logTag, "Loading TV Series: $title")

            val episodes = mutableListOf<Episode>()
            val seasonElements = seriesDocument.select("div.List--Seasons--Episodes a.SeasonsEpisodes")

            if (seasonElements.isNotEmpty()) {
                Log.d(logTag, "Found ${seasonElements.size} seasons. Processing...")
                seasonElements.amap { seasonEl ->
                    val seasonNum =
                        Regex("الموسم (\\d+)").find(seasonEl.text())?.groupValues?.get(1)
                            ?.toIntOrNull()
                    val dataId = seasonEl.attr("data-id")
                    val dataSeason = seasonEl.attr("data-season")
                    try {
                        val seasonHtml = httpPost(
                            "$mainUrl/ajax/Episode",
                            data = mapOf("post_id" to dataId, "season" to dataSeason),
                            referer = seriesUrl
                        )
                        val seasonDoc = Jsoup.parse(seasonHtml)
                        seasonDoc.select("a.hoverable.activable").forEach { epEl ->
                            episodes.add(
                                newEpisode(epEl.attr("href")) {
                                    name = epEl.selectFirst("episodetitle")?.text()
                                    season = seasonNum
                                    episode =
                                        Regex("الحلقة (\\d+)").find(name ?: "")?.groupValues?.get(1)
                                            ?.toIntOrNull()
                                    this.posterUrl = posterUrl

                                }
                            )
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            } else {
                Log.d(logTag, "No multi-season list found. Fallback to single-season logic.")
                seriesDocument.select(".EpisodesList.Full--Width a").forEach { epEl ->
                    episodes.add(
                        newEpisode(epEl.attr("href")) {
                            name = epEl.selectFirst("episodetitle")?.text()
                            this.season = 1
                            this.episode = Regex("الحلقة (\\d+)").find(name ?: "")?.groupValues?.get(1)?.toIntOrNull()
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            Log.d(logTag, "Finished loading ${episodes.size} episodes.")
            return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, episodes.sortedWith(compareBy({ it.season }, { it.episode }))) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders()
                this.plot = plot
                this.year = year
            }

        } else {
            val title = document.selectFirst("div.Title--Content--Single-begin h1")?.text()?.trim() ?: "Movie Title"
            val posterUrl = document.select("meta[property=og:image]").attr("content").ifBlank {
                document.selectFirst("wecima.separated--top")?.let { element ->
                    val style = element.attr("data-lazy-style").ifBlank { element.attr("style") }
                    Regex("url\\((.*?)\\)").find(style)?.groupValues?.get(1)
                }
            }

            val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()
            val year = document.select("ul.Terms--Content--Single-begin li")
                .find { it.selectFirst("span")?.text()?.contains("السنة") == true }
                ?.selectFirst("p")?.text()?.toIntOrNull()

            Log.d(logTag, "Loading Movie: $title")

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.posterHeaders = imageHeaders()
                this.plot = plot
                this.year = year
            }
        }
    }
    private fun decodeWecimaUrl(encodedStr: String): String? {
        return try {
            if (encodedStr.isBlank()) return null
            val cleanedStr = encodedStr.replace("+", "").trim()
            val finalB64Str = if (!cleanedStr.startsWith("aHR0c")) "aHR0c$cleanedStr" else cleanedStr

            val decodedUrl = String(Base64.decode(finalB64Str, Base64.DEFAULT))
            decodedUrl
        } catch (e: Exception) {
            logError(e)
            null
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(logTag, "🔗 loadLinks initiated for: $data")
        val document = httpGet(data)
        document.select("ul.WatchServersList li btn").amap { serverBtn ->
            val encodedUrl = serverBtn.attr("data-url")
            decodeWecimaUrl(encodedUrl)?.let { decodedUrl ->
                Log.d(logTag, "📺 Decoded Watch URL: $decodedUrl")
                if (decodedUrl.startsWith("http")) {
                    loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        document.select(".openLinkDown").amap { downloadBtn ->
            val encodedUrl = downloadBtn.attr("data-href")
            decodeWecimaUrl(encodedUrl)?.let { decodedUrl ->
                Log.d(logTag, "📥 Decoded Download URL: $decodedUrl")

                val qualityText = downloadBtn.selectFirst("resolution")?.text()?.trim() ?: ""
                val typeText = downloadBtn.selectFirst("quality")?.text()?.trim() ?: "Download"
                val serverName = "$name $typeText" // مثال: We Cima WEB-DL
                if (decodedUrl.startsWith("http")) {
                    loadExtractor(decodedUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}