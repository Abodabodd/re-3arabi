package com.topcinema

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.component1
import kotlin.collections.component2

class TopCinemaProvider : MainAPI() {
    override var mainUrl = "https://web8.topcinema.cam"
    override var name = "Top Cinema"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val cfInterceptor: Interceptor get() = cloudflareKiller
    private val standardHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )
    private suspend fun httpGet(url: String, referer: String? = null): org.jsoup.nodes.Document {
        return app.get(
            url,
            referer = referer ?: mainUrl,
            headers = standardHeaders,
            interceptor = cfInterceptor
        ).document
    }

    private suspend fun httpPost(
        url: String,
        data: Map<String, String>,
        referer: String? = null
    ): String {
        return app.post(
            url,
            data = data,
            referer = referer ?: mainUrl,
            headers = postHeaders,
            interceptor = cfInterceptor
        ).text
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = httpGet(mainUrl)
        val homePageList = arrayListOf<HomePageList>()

        val mainSlider = document.select(".Slides--Main .Slides--Item")
        if (mainSlider.isNotEmpty()) {
            val featuredList = mainSlider.mapNotNull { toSearchResponse(it) }
            homePageList.add(HomePageList("أبرز العروض", featuredList))
        }

        document.select("section.Two--Items").forEach { section ->
            try {
                val title = section.selectFirst(".Title--Box h3")?.text()?.trim() ?: return@forEach
                val items = section.select(".Posts--List .Small--Box").mapNotNull {
                    toSearchResponse(it)
                }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(title, items))
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
        return newHomePageResponse(homePageList)
    }
    private fun toSearchResponse(element: Element): SearchResponse? {
        val link = element.selectFirst("a") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null
        val title = link.attr("title")
        val posterUrl = link.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        val isMovie = title.contains("فيلم")
        val isSeries = title.contains("مسلسل")

        return when {
            isMovie -> newMovieSearchResponse(title, href, TvType.Movie) {
                this.addPoster(posterUrl, headers = standardHeaders)
            }

            isSeries -> newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.addPoster(posterUrl, headers = standardHeaders)
            }
            else -> {
                if (element.selectFirst(".number, .epnum") != null || href.contains("/series/")) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.addPoster(posterUrl, headers = standardHeaders)
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.addPoster(posterUrl, headers = standardHeaders)
                    }
                }
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?query=$query&type=all"
        val document = httpGet(url)
        return document.select(".Posts--List .Small--Box").mapNotNull {
            toSearchResponse(it)
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = httpGet(url)

        val title = document.selectFirst("h1.post-title a")?.text()?.trim()
            ?: document.selectFirst("h1.post-title")?.text()?.trim()!!
        val poster = document.selectFirst(".MainSingle .left .image img")?.attr("src")
        val plot = document.selectFirst(".story p")?.text()
        val tags = document.select(".RightTaxContent li:contains(نوع) a").map { it.text() }
        val year =
            document.selectFirst(".RightTaxContent li:contains(الصدور) a")?.text()?.toIntOrNull()
        val scoreValue = document.selectFirst(".imdbR span")?.text()?.toDoubleOrNull()
        val actors =
            document.select(".RightTaxContent li.actor a").map { Actor(it.text(), it.attr("href")) }

        val isTvSeries = document.selectFirst("section.tabs") != null

        return if (isTvSeries) {
            var episodes = emptyList<Episode>()
            val seasonsElements = document.select("section.allseasonss .Small--Box.Season a")
            if (seasonsElements.isNotEmpty()) {
                episodes = seasonsElements.amap { seasonLink ->
                    val seasonUrl = seasonLink.attr("href")
                    val seasonPoster = seasonLink.selectFirst("img")?.attr("src")
                    val seasonNum =
                        seasonLink.selectFirst(".epnum")?.text()?.replace("الموسم", "")?.trim()
                            ?.toIntOrNull()

                    val seasonDoc = httpGet(seasonUrl)
                    seasonDoc.select(".allepcont .row > a").map { ep ->
                        val epUrl = ep.attr("href")
                        val data = "$epUrl/watch/||$epUrl/download/"
                        val epTitle = ep.selectFirst("h2")?.text()
                        val episodeNumber =
                            ep.selectFirst(".epnum")?.text()?.replace("الحلقة", "")?.trim()
                                ?.toIntOrNull()
                        val episode = newEpisode(
                            data = data
                        ) {
                            name = epTitle
                            season = seasonNum
                            episode = episodeNumber
                            posterUrl = seasonPoster
                        }
                        episode // Return the modified episode object
                    }.reversed()
                }.flatten()
            }
            if (episodes.isEmpty()) {
                val seasonNumFromTitle = title.let {
                    Regex("""الموسم (\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull()
                } ?: 1
                episodes = document.select(".allepcont .row > a").map { ep ->
                    val epUrl = ep.attr("href")
                    val data = "$epUrl/watch/||$epUrl/download/"
                    val epTitle = ep.selectFirst("h2")?.text()
                    val epThumb = ep.selectFirst("img")?.attr("src")
                    val episodeNumber =
                        ep.selectFirst(".epnum")?.text()?.replace("الحلقة", "")?.trim()
                            ?.toIntOrNull()

                    val episode = newEpisode(
                        data = data,
                    ) {
                        name = epTitle
                        season = seasonNumFromTitle
                        episode = episodeNumber
                        posterUrl = epThumb
                    }
                    episode
                }.reversed()
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(scoreValue)
            }
        } else {
            val data = "$url/watch/||$url/download/"
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
                this.score = Score.from10(scoreValue)
            }
        }
    }
    private val postHeaders = standardHeaders.plus(
        mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "Accept" to "*/*" // <-- مهم لطلبات AJAX
        )
    )






    private fun unpackJs(p: String, a: Int, c: Int, k: List<String>): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        fun intToBase(numInput: Int, base: Int): String {
            if (numInput == 0) return "0"
            var n = numInput
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(digits[n % base])
                n /= base
            }
            return sb.reverse().toString()
        }

        val mapping = mutableMapOf<String, String>()
        for (i in 0 until c) {
            val key = intToBase(i, a)
            val value = k.getOrNull(i)
            if (!value.isNullOrBlank()) mapping[key] = value
        }

        return Regex("([0-9A-Za-z]+)").replace(p) { m -> mapping[m.value] ?: m.value }
    }



    private fun unwrapPlayUrl(url: String): String {
        return try {
            if (url.contains("play.php?to=")) {
                val decoded = java.net.URLDecoder.decode(url.substringAfter("play.php?to="), "UTF-8").trim()
                if (decoded.startsWith("http")) decoded else "https:${decoded.trimStart(':')}"
            } else url
        } catch (e: Exception) { url }
    }
    private fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.authority}"
        } catch (e: Exception) {
            mainUrl // في حال حدوث خطأ، نعود للرابط الأساسي
        }
    }
    private fun getDynamicHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
            "Accept-Language" to "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer
        )
    }

    private fun getDynamicPostHeaders(referer: String): Map<String, String> {
        return getDynamicHeaders(referer).plus(
            mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept" to "*/*"
            )
        )
    }

    private suspend fun extractVidtube(url: String, currentBaseUrl: String, callback: (ExtractorLink) -> Unit) {
        try {
            Log.d("TopCinema", "[Vidtube] Loading: $url")
            val response = app.get(url, headers = getDynamicHeaders(currentBaseUrl), referer = currentBaseUrl).text
            val packerRegex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            var pRaw: String? = null
            var a = 0
            var c = 0
            var kList: List<String> = emptyList()

            val match = packerRegex.find(response)
            if (match != null) {
                try {
                    pRaw = match.groupValues[2]
                    a = match.groupValues[3].toInt()
                    c = match.groupValues[4].toInt()
                    val kStr = match.groupValues[6]
                    kList = if (kStr.isEmpty()) emptyList() else kStr.split("|")
                    Log.d("TopCinema", "[Vidtube] Regex parse OK -> a=$a c=$c k=${kList.size}")
                } catch (e: Exception) {
                    Log.w("TopCinema", "[Vidtube] Regex parse partial failure: ${e.message}")
                }
            }
            if (pRaw == null) {
                try {
                    val evalStart = response.indexOf("eval(function(p,a,c,k,e,d)")
                    if (evalStart >= 0) {
                        val sub = response.substring(evalStart)
                        val lastSplitIdx = sub.lastIndexOf(".split('|')")
                        if (lastSplitIdx > 0) {
                            val beforeSplit = sub.substring(0, lastSplitIdx)
                            val quoteIdx = beforeSplit.lastIndexOf("'")
                            val quoteIdx2 = beforeSplit.lastIndexOf("\"")
                            val idx = maxOf(quoteIdx, quoteIdx2)
                            if (idx >= 0) {
                                val kStr = beforeSplit.substring(idx + 1).trim()
                                kList = if (kStr.isBlank()) emptyList() else kStr.split("|")
                            }

                            val tail = sub.substring(0, lastSplitIdx)
                            val nums = Regex("""\(\s*(['"']).*?['"']\s*,\s*(\d+)\s*,\s*(\d+)\s*,""", RegexOption.DOT_MATCHES_ALL).find(tail)
                            if (nums != null) {
                                a = nums.groupValues[2].toIntOrNull() ?: a
                                c = nums.groupValues[3].toIntOrNull() ?: c
                            }

                            val pCandidateMatch = Regex("""\(\s*(['"])(.*?)\1\s*,\s*$a\s*,\s*$c""", RegexOption.DOT_MATCHES_ALL).find(sub)
                            if (pCandidateMatch != null) {
                                pRaw = pCandidateMatch.groupValues[2]
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("TopCinema", "[Vidtube] fallback parse failed: ${e.message}")
                }
            }

            if (pRaw == null) {
                Log.d("TopCinema", "[Vidtube] Could not extract p/a/c/k from page")
                return
            }
            val p = pRaw.replace("\\'", "'")
            val safeA = if (a <= 0) 62 else minOf(a, 62)
            val safeC = if (c <= 0) kList.size.coerceAtLeast(0) else c

            val unpacked = try {
                unpackJs(p, safeA, safeC, kList)
            } catch (e: Exception) {
                Log.e("TopCinema", "[Vidtube] unpackJs failed: ${e.message}")
                ""
            }
            val fileRegex = Regex("""file\s*:\s*"(https?://[^"]+)"""")
            val labelRegex = Regex("""label\s*:\s*"([^"]+)"""")

            val files = fileRegex.findAll(unpacked).map { it.groupValues[1] }.toList()
            val labels = labelRegex.findAll(unpacked).map { it.groupValues[1] }.toList()

            if (files.isEmpty()) {
                Log.d("TopCinema", "[Vidtube] No files found after unpacking.")
            }

            files.forEachIndexed { index, fileUrl ->
                val label = labels.getOrNull(index) ?: "Auto"
                try {
                    callback(newExtractorLink(
                        source = name,
                        name = "Vidtube - $label",
                        url = fileUrl
                    ) {
                        referer = url
                        quality = getQualityFromName(label)
                    })
                    Log.d("TopCinema", "[Vidtube] Found Link: $label -> $fileUrl")
                } catch (e: Exception) {
                    Log.e("TopCinema", "[Vidtube] callback/newExtractorLink failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("TopCinema", "[Vidtube] Connection/overall error: ${e.message}")
            logError(e)
        }
    }
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val extractedLinks = ConcurrentHashMap<String, String>()

        data.split("||").filter { it.isNotBlank() }.amap { rawUrl ->
            try {
                if (rawUrl.contains("/watch/")) {
                    val response = app.get(
                        rawUrl,
                        headers = getDynamicHeaders(mainUrl),
                        interceptor = cfInterceptor
                    )
                    val finalWatchUrl = response.url
                    val finalBaseUrl = getBaseUrl(finalWatchUrl)
                    val watchDoc = response.document

                    watchDoc.selectFirst(".player--iframe iframe")?.attr("src")?.let {
                        extractedLinks[it] = finalWatchUrl
                    }

                    watchDoc.select(".watch--servers--list li.server--item").amap { server ->
                        val ajaxUrl =
                            "$finalBaseUrl/wp-content/themes/movies2023/Ajaxat/Single/Server.php"
                        val res = app.post(
                            ajaxUrl,
                            data = mapOf(
                                "id" to server.attr("data-id"),
                                "i" to server.attr("data-server")
                            ),
                            headers = getDynamicPostHeaders(finalWatchUrl),
                            interceptor = cfInterceptor
                        ).text

                        Jsoup.parse(res).selectFirst("iframe")?.attr("src")?.let {
                            extractedLinks[it] = finalWatchUrl
                        }
                    }
                } else if (rawUrl.contains("/download/")) {
                    val response = app.get(
                        rawUrl,
                        headers = getDynamicHeaders(mainUrl),
                        interceptor = cfInterceptor
                    )
                    val finalDownloadUrl = response.url
                    response.document.select("a.downloadsLink").forEach { a ->
                        val href = a.attr("href")
                        if (href.isNotBlank()) {
                            extractedLinks[href] = finalDownloadUrl
                        }
                    }
                } else {
                    extractedLinks[rawUrl] = getBaseUrl(rawUrl)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        extractedLinks.entries.toList().amap { (rawLink, referer) ->
            val finalLink = unwrapPlayUrl(rawLink)
            val baseUrlForExtractor = getBaseUrl(referer)

            if (finalLink.contains("vidtube", ignoreCase = true)) {
                extractVidtube(finalLink, baseUrlForExtractor, callback)
            } else {
                loadExtractor(finalLink, referer, subtitleCallback, callback)
            }
        }

        return true
    }
}