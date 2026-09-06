package com.arabseed

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import kotlinx.serialization.Serializable
import com.lagradost.nicehttp.NiceResponse // تأكد من استيراد NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Base64
import java.nio.charset.StandardCharsets

class Arabseed : MainAPI() {
    override var mainUrl = "https://arabseed.rocks"
    override var name = "Arabseed"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    private fun getPosterHeaders(): Map<String, String> {
        val cookies = android.webkit.CookieManager.getInstance().getCookie(mainUrl) ?: ""
        return mapOf(
            "Cookie" to cookies,
            "User-Agent" to appUserAgent,
            "Referer" to mainUrl
        )
    }
    @Serializable
    data class SearchAjaxResponse(
        val type: String?,
        val html: String?
    )
    private val appUserAgent = CloudflareSolver.EXACT_USER_AGENT
    private val solverMutex = Mutex()
    private fun String.toAbsolute(): String {
        if (this.isBlank()) return ""
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> mainUrl.trimEnd('/') + this
        }
    }
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
    private fun getCurrentActivity(): Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
            val activitiesField = activityThreadClass.getDeclaredField("mActivities")
            activitiesField.isAccessible = true
            val activities = activitiesField.get(activityThread) as? Map<*, *> ?: return null
            for (activityRecord in activities.values) {
                val activityRecordClass = activityRecord!!.javaClass
                val pausedField = activityRecordClass.getDeclaredField("paused")
                pausedField.isAccessible = true
                if (!pausedField.getBoolean(activityRecord)) {
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    return activityField.get(activityRecord) as Activity
                }
            }
        } catch (e: Exception) {
            Log.e(name, "Failed to get current Activity via reflection: ${e.message}")
        }
        return null
    }

    private fun isCloudflareBlock(code: Int, text: String): Boolean {
        if (code in listOf(403, 503, 429)) return true
        val lowerText = text.lowercase()
        return lowerText.contains("cloudflare") && lowerText.contains("checking your browser") ||
                lowerText.contains("just a moment") ||
                lowerText.contains("cf-browser-verification")
    }
    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "ar-EG,ar;q=0.9",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "referer" to "$mainUrl",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1"
    )

    private suspend fun applyCookiesAndAgent(url: String, originalHeaders: Map<String, String>): Map<String, String> {
        val newHeaders = mutableMapOf<String, String>()
        newHeaders.putAll(browserHeaders)
        newHeaders.putAll(originalHeaders)
        newHeaders["User-Agent"] = appUserAgent
        val cookiesUrl = if (url.contains(mainUrl)) url else mainUrl
        val allCookies = CookieManager.getInstance().getCookie(cookiesUrl)

        if (!allCookies.isNullOrBlank()) {
            newHeaders["Cookie"] = allCookies
        }

        return newHeaders
    }

    private suspend fun safeGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): NiceResponse {
        var currentHeaders = applyCookiesAndAgent(url, headers)
        var response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)

        if (isCloudflareBlock(response.code, response.text)) {
            Log.w(name, "Cloudflare detected on GET: $url (Code: ${response.code}). Waiting for lock...")
            solverMutex.withLock {
                currentHeaders = applyCookiesAndAgent(url, headers)
                response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)
                if (isCloudflareBlock(response.code, response.text)) {
                    Log.w(name, "Still blocked. Triggering Solver for GET...")
                    val activity = getCurrentActivity()
                    if (activity != null) {
                        CloudflareSolver.solve(activity, url)
                        currentHeaders = applyCookiesAndAgent(url, headers)
                        response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)
                    } else {
                        Log.e(name, "Cannot solve Cloudflare: Activity is null")
                    }
                } else {
                    Log.i(name, "Cloudflare already solved by another concurrent request. Continuing GET.")
                }
            }
        }
        return response
    }

    private suspend fun safePost(url: String, data: Map<String, String>, referer: String? = null, headers: Map<String, String> = emptyMap()): NiceResponse {
        var currentHeaders = applyCookiesAndAgent(url, headers)
        var response = app.post(url, data = data, referer = referer, headers = currentHeaders, allowRedirects = true)

        if (isCloudflareBlock(response.code, response.text)) {
            Log.w(name, "Cloudflare detected on POST: $url (Code: ${response.code}). Waiting for lock...")
            solverMutex.withLock {
                currentHeaders = applyCookiesAndAgent(url, headers)
                response = app.post(url, data = data, referer = referer, headers = currentHeaders, allowRedirects = true)
                if (isCloudflareBlock(response.code, response.text)) {
                    Log.w(name, "Still blocked. Triggering Solver for POST...")
                    val activity = getCurrentActivity()
                    if (activity != null) {
                        CloudflareSolver.solve(activity, url)
                        currentHeaders = applyCookiesAndAgent(url, headers)
                        response = app.post(url, data = data, referer = referer, headers = currentHeaders, allowRedirects = true)
                    } else {
                        Log.e(name, "Cannot solve Cloudflare: Activity is null")
                    }
                } else {
                    Log.i(name, "Cloudflare already solved by another concurrent request. Continuing POST.")
                }
            }
        }
        return response
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val homeUrl = "$mainUrl/home/"
        val homeResponse = safeGet(homeUrl)
        val homeDoc = homeResponse.document
        val csrfToken = homeDoc.select("script").html()
            .let { Regex("""['"]csrf__token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

        if (csrfToken.isNullOrBlank()) {
            Log.e(name, "CRITICAL: CSRF token not found during search.")
            return emptyList()
        }
        val searchResponse = safePost(
            "$mainUrl/find__posts/",
            data = mapOf(
                "search" to query.trim(),
                "search_type" to "",
                "csrf_token" to csrfToken
            ),
            referer = homeUrl,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01"
            )
        ).parsedSafe<SearchAjaxResponse>()

        val html = searchResponse?.html
        if (html.isNullOrBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("ul.res__ul > li").amap { li ->
            val a = li.selectFirst("a.search__item") ?: return@amap null
            val href = a.attr("href").toAbsolute()
            val title = a.selectFirst("h3")?.text()?.trim() ?: return@amap null

            val posterUrl = a.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            val tagsText = li.select("ul li").text()
            val isMovie = href.contains("/%d9%81%d9%8a%d9%84%d9%85-") ||
                    href.contains("/film-") ||
                    title.contains("فيلم") ||
                    tagsText.contains("أفلام") ||
                    tagsText.contains("افلام") ||
                    tagsText.contains("فيلم")

            val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
                this.posterHeaders = getPosterHeaders()
            }
        }.filterNotNull()
    }
    override val mainPage = mainPageOf(
        "$mainUrl/home/" to "الرئيسية",
        "$mainUrl/recently/" to "مضاف حديثا",
        "$mainUrl/category/films/" to "أفلام",
        "$mainUrl/category/tv/" to "المسلسلات",
        "$mainUrl/category/anime/anime-movies/" to "افلام انيميشن",

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = safeGet(url).document
        val items = document.select(".movie__block").amap {
            val title = it.selectFirst("h3")?.text() ?: return@amap null
            val href = it.attr("href").toAbsolute()
            val posterUrl = it.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.posterHeaders = getPosterHeaders()
            }
        }.filterNotNull()
        return newHomePageResponse(request.name, items)
    }

    @Serializable
    data class AjaxResponse(
        val html: String?,
        val hasmore: Boolean?
    )

    override suspend fun load(url: String): LoadResponse {
        Log.i(name, "================ LOAD START ================ ")
        Log.d(name, "load() initiated with URL: $url")
        val initialResponse = safeGet(url)
        val doc = initialResponse.document
        var rightLink = mainUrl
        try {
            val urlObj = java.net.URL(initialResponse.url)
            rightLink = "${urlObj.protocol}://${urlObj.host}"
        } catch (e: Exception) {
            Log.d(name, "Failed to parse initial rightLink: ${e.message}")
        }
        val seriesUrl = doc.select(".bread__crumbs li a[href*='/selary/']")
            .lastOrNull {
                val href = it.attr("href")
                !href.contains("/%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-") && !href.contains("/%d8%a7%d9%84%d8%ad%d9%84%d9%8%d8%a9-")
            }
            ?.attr("href")?.toAbsolute()
            ?: url.substringBefore("/%d8%a7%d9%84%d9%85%d9%88%d8%b3%d9%85-").substringBefore("/%d8%a7%d9%84%d8%ad%d9%84%d9%82%d8%a9-").toAbsolute()

        Log.d(name, "Determined base series URL: $seriesUrl")

        val seriesDoc = if (seriesUrl != url && seriesUrl.isNotBlank()) {
            Log.d(name, "Current URL is not the base series URL. Fetching base URL page...")

            val seriesResponse = safeGet(seriesUrl)
            try {
                val urlObj = java.net.URL(seriesResponse.url)
                rightLink = "${urlObj.protocol}://${urlObj.host}"
            } catch (e: Exception) {}

            seriesResponse.document
        } else {
            doc
        }

        Log.d(name, "Final rightLink used for AJAX: $rightLink")

        val title = seriesDoc.selectFirst("h1.post__name")?.text()?.trim()
            ?: doc.selectFirst("h1.post__name")?.text()?.trim()
            ?: "Title Not Found"

        val poster = (seriesDoc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: seriesDoc.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: seriesDoc.selectFirst(".poster__single img, .single__cover > img:not(.rating__box img), .post__poster img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") })
            })?.toAbsolute()

        val synopsis = seriesDoc.selectFirst(".post__story > p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        val seasonsListDiv = seriesDoc.selectFirst("div#seasons__list")
        val episodeElements = doc.select("ul.episodes__list li a")

        val isTvSeries = seasonsListDiv != null || episodeElements.isNotEmpty()

        if (isTvSeries) {
            Log.i(name, "Content identified as TV Series")
            val currentSeasonName = seasonsListDiv?.selectFirst(".filter__bttn b")?.text()?.trim() ?: ""
            val currentSeasonNum = Regex("""\d+""").find(currentSeasonName)?.value?.toIntOrNull() ?: 1

            episodeElements.forEach { epEl ->
                val epHref = epEl.attr("href").toAbsolute()
                val epTitleText = epEl.selectFirst(".epi__num")?.text()?.trim() ?: epEl.text().trim()
                val epNum = Regex("""\d+""").find(epTitleText)?.value?.toIntOrNull()

                episodes.add(newEpisode(epHref) {
                    this.name = epTitleText
                    this.episode = epNum
                    this.season = currentSeasonNum
                    this.posterUrl = poster?.takeIf { it.isNotBlank() }
                })
            }
            val otherSeasonsElements = seasonsListDiv?.select("ul li[data-term]")

            if (!otherSeasonsElements.isNullOrEmpty()) {
                Log.i(name, "Found ${otherSeasonsElements.size} other seasons. Fetching via AJAX...")

                val csrfToken = seriesDoc.select("script").html()
                    .let { Regex("""['"]csrf__token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

                if (!csrfToken.isNullOrBlank()) {
                    val parallelEpisodes = otherSeasonsElements?.amap { seasonEl ->
                        val seasonId = seasonEl.attr("data-term").trim()
                        val seasonName = seasonEl.selectFirst("span")?.text()?.trim() ?: ""
                        val seasonNum = Regex("""\d+""").find(seasonName)?.value?.toIntOrNull()

                        val currentSeasonEpisodes = mutableListOf<Episode>()

                        if (seasonId.isNotBlank()) {
                            var hasMore = true
                            var currentOffset = 0

                            while (hasMore) {
                                try {
                                    val response = safePost(
                                        "$rightLink/season__episodes/",
                                        data = mapOf(
                                            "season_id" to seasonId,
                                            "offset" to currentOffset.toString(),
                                            "csrf_token" to csrfToken
                                        ),
                                        referer = seriesUrl,
                                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                                    ).parsedSafe<AjaxResponse>()

                                    if (response?.html.isNullOrBlank()) {
                                        hasMore = false
                                    } else {
                                        val newEpisodesDoc = Jsoup.parse(response.html)
                                        val newEpisodeElements = newEpisodesDoc.select("li a")

                                        if (newEpisodeElements.isEmpty()) {
                                            hasMore = false
                                        } else {
                                            newEpisodeElements.forEach { epEl ->
                                                val epHref = epEl.attr("href").toAbsolute()
                                                val epTitle = epEl.selectFirst(".epi__num")?.text()?.trim() ?: epEl.text().trim()
                                                val epNum = Regex("""\d+""").find(epTitle)?.value?.toIntOrNull()

                                                currentSeasonEpisodes.add(newEpisode(epHref) {
                                                    this.name = epTitle
                                                    this.episode = epNum
                                                    this.season = seasonNum
                                                    this.posterUrl = poster?.takeIf { it.isNotBlank() }
                                                })
                                            }
                                            currentOffset += newEpisodeElements.size
                                            hasMore = response.hasmore == true
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(name, "AJAX season fetch FAILED for seasonId: $seasonId", e)
                                    hasMore = false
                                }
                            }
                        }
                        currentSeasonEpisodes.reversed()
                    }
                    parallelEpisodes?.flatten()?.let {
                        episodes.addAll(it)
                    }
                } else {
                    Log.e(name, "CRITICAL: CSRF token not found for AJAX seasons request.")
                }
            }

            return newTvSeriesLoadResponse(
                title,
                seriesUrl,
                TvType.TvSeries,
                episodes.distinctBy { it.data }
            ) {
                this.posterHeaders = getPosterHeaders()
                this.posterUrl = poster?.takeIf { it.isNotBlank() }
                this.plot = synopsis
            }

        } else {
            Log.i(name, "Content identified as Movie")
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster?.takeIf { it.isNotBlank() }
                this.plot = synopsis
                this.posterHeaders = getPosterHeaders()
            }
        }
    }

    @Serializable
    data class ServerResponse(val server: String?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "================ LOAD LINKS START ================")
        Log.i(name, "Input data URL: $data")

        try {
            var watchUrl = data
            val mainPageResponse = safeGet(data)
            val mainPageDoc = mainPageResponse.document
            val watchBtnUrl = mainPageDoc.selectFirst("a.btton.watch__btn")?.attr("href")
            val watchPageDoc = if (!watchBtnUrl.isNullOrBlank()) {
                watchUrl = if (watchBtnUrl.startsWith("http")) watchBtnUrl else "$mainUrl$watchBtnUrl"
                Log.i(name, "Navigating to Watch URL: $watchUrl")
                safeGet(watchUrl, referer = data).document
            } else {
                Log.i(name, "Already on Watch Page")
                mainPageDoc
            }

            val finalResolvedWatchUrl = watchUrl
            var rightLink = mainUrl
            try {
                val urlObj = java.net.URL(finalResolvedWatchUrl)
                rightLink = "${urlObj.protocol}://${urlObj.host}"
            } catch (e: Exception) {}
            val csrfToken = watchPageDoc.select("script").html()
                .let { Regex("""['"]csrf__token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }
            val serverElements = watchPageDoc.select(".servers__list li")

            if (serverElements.isEmpty()) {
                Log.e(name, "No servers found in HTML.")
                return false
            }

            Log.i(name, "Found ${serverElements.size} servers. Processing...")
            serverElements.amap { serverEl ->
                val serverName = serverEl.selectFirst("span")?.text()?.trim() ?: "عرب سيد"
                val quality = serverEl.attr("data-qu").ifBlank { "1080" }
                val postId = serverEl.attr("data-post")
                val serverId = serverEl.attr("data-server")
                var playerUrl = serverEl.attr("data-player-url").trim()
                if (playerUrl.isNotBlank()) {
                    Log.i(name, "Direct URL found for server: $serverName")
                    playerUrl = playerUrl.replace("m.arabseed.me", "m.reviewrate.net")
                    val finalUrl = "$playerUrl#quality=$quality"
                    loadExtractor(finalUrl, finalResolvedWatchUrl, subtitleCallback, callback)
                }
                else if (postId.isNotBlank() && serverId.isNotBlank() && !csrfToken.isNullOrBlank()) {
                    Log.i(name, "Fetching AJAX URL for server: $serverName")
                    val watchAjaxUrl = "$rightLink/get__watch__server/"

                    try {
                        val response = safePost(
                            watchAjaxUrl,
                            data = mapOf(
                                "post_id" to postId,
                                "quality" to quality,
                                "server" to serverId,
                                "csrf_token" to csrfToken
                            ),
                            referer = finalResolvedWatchUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        )

                        val serverResponse = response.parsedSafe<ServerResponse>()
                        var ajaxIframeUrl = serverResponse?.server

                        if (!ajaxIframeUrl.isNullOrBlank()) {
                            if (ajaxIframeUrl.contains("/play.php?url=")) {
                                try {
                                    val encodedUrl = ajaxIframeUrl.substringAfter("url=")
                                        .replace("-", "+")
                                        .replace("_", "/")
                                    val decodedBytes = Base64.decode(encodedUrl, Base64.DEFAULT)
                                    ajaxIframeUrl = String(decodedBytes, StandardCharsets.UTF_8)
                                } catch (e: Exception) {
                                    Log.e(name, "Failed to decode base64 URL", e)
                                }
                            }
                            ajaxIframeUrl = ajaxIframeUrl.replace("m.arabseed.me", "m.reviewrate.net")

                            val finalUrl = "$ajaxIframeUrl#quality=$quality"
                            Log.i(name, "SUCCESS: Extracted AJAX iframe -> $finalUrl")
                            loadExtractor(finalUrl, finalResolvedWatchUrl, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Error fetching AJAX iframe for server $serverName", e)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(name, "Error in loadLinks", e)
        }

        Log.i(name, "================ LOAD LINKS FINISHED ================")
        return true
    }
}
