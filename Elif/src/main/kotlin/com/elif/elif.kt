package com.elif

import android.annotation.SuppressLint
import android.app.Activity
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URL

class ElifNewsProvider : MainAPI() {
    override var mainUrl = "https://n.elif.news"
    override var name = "Elif"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val appUserAgent = CloudflareSolver.EXACT_USER_AGENT
    private val solverMutex = Mutex()

    override val mainPage = mainPageOf(
        "$mainUrl/home12" to "الصفحة الرئيسية",
        "$mainUrl/category.php?cat=movies2" to "أفلام",
        "$mainUrl/category.php?cat=turkish-series9-3sk" to "مسلسلات تركية",
        "$mainUrl/category.php?cat=arabic-series12" to "مسلسلات عربية",
        "$mainUrl/category.php?cat=indian-serie" to "مسلسلات هندية",
        "$mainUrl/category.php?cat=anime-series" to "مسلسلات أنمي",
        "$mainUrl/category.php?cat=english-series3" to "مسلسلات أجنبية مترجمة"
    )

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
            Log.e(name, "Failed to get current Activity: ${e.message}")
        }
        return null
    }

    private fun isCloudflareBlock(code: Int, text: String): Boolean {
        if (code in listOf(403, 404, 409, 503, 429)) return true
        val lowerText = text.lowercase()
        return (lowerText.contains("cloudflare") && lowerText.contains("checking your browser")) ||
                lowerText.contains("just a moment") ||
                lowerText.contains("cf-browser-verification")
    }

    private val browserHeaders = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar-EG,ar;q=0.9",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun applyCookiesAndAgent(url: String, originalHeaders: Map<String, String>): Map<String, String> {
        val newHeaders = mutableMapOf<String, String>()
        newHeaders.putAll(browserHeaders)
        newHeaders.putAll(originalHeaders)
        newHeaders["User-Agent"] = appUserAgent
        val isMainDomain = url.contains("elif.news") || url.contains(mainUrl)
        if (isMainDomain) {
            val allCookies = CookieManager.getInstance().getCookie(url)
            if (!allCookies.isNullOrBlank()) {
                newHeaders["Cookie"] = allCookies
            }
        }
        return newHeaders
    }

    private suspend fun safeGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): NiceResponse {
        var currentHeaders = applyCookiesAndAgent(url, headers)
        var response = app.get(url, referer = referer, headers = currentHeaders, allowRedirects = true)

        if (isCloudflareBlock(response.code, response.text)) {
            Log.w(name, "Cloudflare/Block detected on GET: $url (Code: ${response.code}). Waiting for lock...")

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
                }
            }
        }
        return response
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = safeGet(request.data).document
        val homeItems = document.select("ul.pm-ul-browse-videos li, ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst(".caption h3 a") ?: return null
        val title = titleEl.text().trim()
        val href = titleEl.attr("href") ?: return null
        val url = fixUrl(href)
        val thumb = this.selectFirst(".pm-video-thumb img")?.attr("src") ?: ""
        val isTv = url.contains("series") || title.contains("الحلقة") || title.contains("الموسم")

        return if (isTv) {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = fixUrl(thumb)
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = fixUrl(thumb)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=$query"
        val document = safeGet(url).document
        return document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = safeGet(url).document
        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=\"og:image\"]")?.attr("content") ?: ""
        val description = document.selectFirst("meta[property=\"og:description\"]")?.attr("content")

        val isSeries = document.selectFirst(".serie_eps") != null || document.selectFirst("#Season0") != null

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val rawEpisodeElements = document.select("#Season0 a, .tabcontent a")
            val reversedElements = rawEpisodeElements.reversed()

            reversedElements.forEachIndexed { index, element ->
                val epHref = element.attr("href") ?: return@forEachIndexed
                val epTitle = element.attr("title")?.trim() ?: "الحلقة ${index + 1}"

                val episodeObj = newEpisode(fixUrl(epHref)) {
                    this.name = epTitle
                    this.episode = index + 1
                    this.posterUrl = fixUrl(poster)
                }
                episodes.add(episodeObj)
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster)
                this.plot = description
            }
        }
    }

    private fun deobfuscatePacker(packed: String): String {
        try {
            val packerRegex = """eval\(function\(p,a,c,k,e,[rd]\)\{.*?\}\((['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\(['"]\|['"]\)""".toRegex()
            val match = packerRegex.find(packed) ?: return ""
            val (_, p, aStr, cStr, _, kStr) = match.destructured
            val a = aStr.toIntOrNull() ?: return ""
            val c = cStr.toIntOrNull() ?: return ""
            val k = kStr.split('|')

            fun getBaseNString(num: Int, base: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                if (base <= 36) return num.toString(base)
                var n = num
                var res = ""
                while (n > 0) {
                    res = chars[n % base] + res
                    n /= base
                }
                return if (res.isEmpty()) "0" else res
            }

            val dict = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val key = getBaseNString(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                dict[key] = value
            }

            val wordRegex = """\b\w+\b""".toRegex()
            return wordRegex.replace(p) { matchResult ->
                val word = matchResult.value
                dict[word] ?: word
            }
        } catch (e: Exception) {
            return ""
        }
    }
    private fun extractLogic(htmlText: String): String {
        try {
            val startMarker = "eval(function(p,a,c,k,e,d)"
            val startIdx = htmlText.indexOf(startMarker)
            if (startIdx != -1) {
                val endMarker = ".split('|')))"
                val endIdx = htmlText.indexOf(endMarker, startIdx)
                if (endIdx != -1) {
                    val packedJs = htmlText.substring(startIdx, endIdx + endMarker.length)
                    val unpacked = deobfuscatePacker(packedJs)
                    if (unpacked.isNotEmpty()) {
                        return unpacked
                    }
                }
            }
        } catch (e: Exception) {
        }
        return htmlText
    }
    private fun findM3u8(text: String): String? {
        val fileRegex = """file\s*:\s*["']([^"']+)["']""".toRegex()
        val fileMatch = fileRegex.find(text)
        if (fileMatch != null) return fileMatch.groupValues[1]

        val m3u8Regex = """(https?://[^\s"\'<>]+?\.m3u8[^\s"\'<>?]*(?:\?[^\s"\'<>]*)?)""".toRegex()
        val m3u8Match = m3u8Regex.find(text)
        return m3u8Match?.groupValues?.get(1)
    }

    override suspend fun loadLinks(
        data: String,
        isCaster: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val embedUrl = document.selectFirst("link[itemprop=embedUrl]")?.attr("href")
        if (!embedUrl.isNullOrEmpty()) {
            loadExtractor(embedUrl, data, subtitleCallback, callback)
        }
        val xtgoLink = document.selectFirst("#BiBplayer a.xtgo")?.attr("href")
        if (!xtgoLink.isNullOrEmpty()) {
            val resolvedXtgoUrl = fixUrl(xtgoLink)

            val playerDoc = app.get(
                url = resolvedXtgoUrl,
                headers = mapOf("Referer" to data)
            ).document

            val servers = playerDoc.select(".embeding ul li")

            coroutineScope {
                servers.map { server ->
                    async {
                        val embedSource = server.attr("data-embed") ?: return@async
                        if (embedSource.isEmpty()) return@async

                        try {
                            val serverUrl = fixUrl(embedSource)
                            loadExtractor(serverUrl, resolvedXtgoUrl, subtitleCallback, callback)

                            val uri = URL(serverUrl)
                            val domain = "${uri.protocol}://${uri.host}/"
                            val serverName = uri.host.replace("www.", "").lowercase()
                            val serverResponse = app.get(serverUrl, headers = mapOf("Referer" to resolvedXtgoUrl))
                            if (serverResponse.code == 200) {
                                val content = extractLogic(serverResponse.text)
                                val videoLink = findM3u8(content)

                                if (!videoLink.isNullOrEmpty()) {
                                    val finalVideoUrl = fixUrl(videoLink)
                                    val isVidspeed = serverName.contains("vidspeed")
                                    val verifyHeaders = if (isVidspeed) {
                                        mapOf(
                                            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36",
                                            "Accept" to "*/*",
                                            "Origin" to domain.removeSuffix("/"),
                                            "Referer" to domain,
                                            "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
                                        )
                                    } else {
                                        mapOf(
                                            "Referer" to domain,
                                            "Origin" to domain.removeSuffix("/")
                                        )
                                    }

                                    val check = app.get(
                                        url = finalVideoUrl,
                                        headers = verifyHeaders,
                                        timeout = 10
                                    )

                                    if (check.code == 200) {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = serverName,
                                                url = finalVideoUrl,
                                            ) {
                                                referer = domain
                                                quality = Qualities.Unknown.value
                                                if (isVidspeed) {
                                                    headers = mapOf(
                                                        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/151.0.0.0 Mobile Safari/537.36",
                                                        "Accept" to "*/*",
                                                        "Origin" to domain.removeSuffix("/"),
                                                        "Referer" to domain,
                                                        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7"
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }.awaitAll()
            }
        }

        return true
    }
}