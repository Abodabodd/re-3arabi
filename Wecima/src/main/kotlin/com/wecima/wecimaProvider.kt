package com.lagradost.cloudstream3.ar

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder

class WecimaProvider : MainAPI() {
    override var mainUrl = "https://wecima.lifestyle"
    override var name = "We Cima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val logTag = "WecimaProvider1"

    private val streamWishPreferredDomains = listOf(
        "vibuxer.com",
        "hanerix.com",
        "niramirus.com",
        "hglamioz.com",
        "playnixes.com",
        "medixiru.com"
    )

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
    private var currentCfCookie: String? =
        "cf_clearance=ED0NVzmNRSWM57kanLn3lwlIaUBtZI2vPk1ZAP6zyfA-1763125072-1.2.1.1-eWAxOeBZN.zpvnXMgT46AJocpEalzqZ3WXlNZzjhIhJEvbNkRhY1Mz84sv6onyU7zfGBygnr3XbAw955PxJHjnRFUMqcp6o0rbp5wT4hJMMFiUaOSABpAAx.9G7sYlmslO5bpns2BZxvxUlV3onCRMAQY7lUHW.MCZCH10MtRNaPKsDmxvtZo14sQDLEXyu8tEDOAkjElHCHAW3iyseTRNN2j0Zd7ceblYh28.E7PwA;"

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
        "$mainUrl/" to "جديد ماي سيما"
    )

    override var sequentialMainPage = true

    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"

    private val standardHeaders = mapOf(
        "User-Agent" to userAgent,
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

    private suspend fun httpGet(url: String, referer: String? = null): Document {
        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "Attempting GET with current cookie...")
                return app.get(
                    url,
                    referer = referer ?: mainUrl,
                    headers = getHeaders(isPost = false),
                    interceptor = getInterceptor()
                ).document
            } catch (e: Exception) {
                Log.w(logTag, "GET with current cookie failed. It has likely expired. Error: ${e.message}")
                currentCfCookie = null
            }
        }

        Log.d(logTag, "Executing GET with CloudflareKiller to get a new session...")
        val response = app.get(
            url,
            referer = referer ?: mainUrl,
            headers = getHeaders(isPost = false),
            interceptor = getInterceptor()
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
                    interceptor = getInterceptor()
                ).text
            } catch (e: Exception) {
                Log.w(logTag, "POST with current cookie failed. It has likely expired. Error: ${e.message}")
                currentCfCookie = null
            }
        }

        Log.d(logTag, "Executing POST with CloudflareKiller to get a new session...")
        val response = app.post(
            url,
            data = data,
            referer = referer ?: mainUrl,
            headers = getHeaders(isPost = true),
            interceptor = getInterceptor()
        )
        parseCfClearance(response.headers)
        return response.text
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val baseUrl = mainUrl.trimEnd('/')
        val requestUrl = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"

        val document = try {
            httpGet(requestUrl, referer = "$baseUrl/")
        } catch (e: Exception) {
            logError(e)
            return newHomePageResponse(emptyList())
        }

        val homePageList = ArrayList<HomePageList>()
        val mainItems = document.select("div#MainFiltar div.GridItem").mapNotNull {
            it.toSearchResult()
        }
        if (mainItems.isNotEmpty()) {
            homePageList.add(HomePageList("جديد ماي سيما", mainItems))
        }
        if (page == 1) {
            val sliderItems = document.select("wecimabegin div.Slides--Main div.GridItem").mapNotNull {
                it.toSearchResult()
            }
            if (sliderItems.isNotEmpty()) {
                homePageList.add(HomePageList("مختارات ماي سيما", sliderItems))
            }

            val trendingTitle = document.selectFirst("wecimaactivities title--wecimaactivities")
                ?.text()?.trim()?.ifBlank { null } ?: "شائع فى ماي سيما"

            val trendingItems = document.select("wecimaactivities inner--wecimaactivities a").mapNotNull { a ->
                val href = fixUrl(a.attr("href")).takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val pEl = a.selectFirst("p") ?: return@mapNotNull null
val title = pEl.ownText().trim().ifBlank { null }
    ?: pEl.text().replace(Regex("\\(\\s*\\d{4}\\s*\\)"), "").trim().ifBlank { null }
    ?: return@mapNotNull null

val year = pEl.selectFirst("span.year")?.text()?.let {
    Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
}
                val styleAttr = a.attr("style")
                val rawPoster = Regex("url\\(['\"]?(.*?)['\"]?\\)").find(styleAttr)?.groupValues?.get(1)?.trim()
                val posterUrl = rawPoster?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

                val isSeries = title.contains("مسلسل") ||
                    title.contains("الحلقة") ||
                    title.contains("انمي")

                newMovieSearchResponse(title, href, if (isSeries) TvType.TvSeries else TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.posterHeaders = imageHeaders()
                }
            }
            if (trendingItems.isNotEmpty()) {
                homePageList.add(HomePageList(trendingTitle, trendingItems))
            }
        }

        val hasNext = document.selectFirst("div.pagination a.next") != null
        return newHomePageResponse(homePageList, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a") ?: return null
        val href = fixUrl(anchor.attr("href"))

        val titleElement = this.selectFirst("h2, strong")
        val year = titleElement?.selectFirst("span.year")?.text()?.let {
            Regex("(\\d{4})").find(it)?.groupValues?.get(1)?.toIntOrNull()
        }
        val title = anchor.attr("title").ifBlank {
            titleElement?.text()
        }?.trim() ?: return null

        val posterSpan = this.selectFirst("span.BG--GridItem")
        val styleAttr = posterSpan?.attr("data-lazy-style")?.ifBlank { null }
            ?: posterSpan?.attr("style")?.ifBlank { null }

        val rawPosterUrl = posterSpan?.attr("data-src")?.ifBlank { null }
            ?: styleAttr?.let { style ->
                Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
            }?.trim()?.ifBlank { null }

        val posterUrl = rawPosterUrl?.let { fixUrl(it) }

        val isSeries = this.selectFirst(".Episode--number") != null ||
            title.contains("مسلسل") ||
            title.contains("الحلقة")

        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
            this.year = year
            this.posterHeaders = imageHeaders()
        }
    }

    private fun imageHeaders(): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/"
        )
        currentCfCookie?.let { headers["Cookie"] = it }
        return headers
    }

    private suspend fun httpGetRaw(url: String): com.lagradost.nicehttp.NiceResponse {
        if (currentCfCookie != null) {
            try {
                Log.d(logTag, "⚡ Fast Check: Attempting GET with cached cookie...")
                val response = app.get(
                    url,
                    referer = mainUrl,
                    headers = getHeaders(isPost = false),
                    interceptor = null
                )

                if (response.code == 200) {
                    return response
                }
                Log.w(logTag, "Cookie expired or invalid (Code: ${response.code})")
            } catch (e: Exception) {
                Log.w(logTag, "Fast check failed: ${e.message}")
                currentCfCookie = null
            }
        }
        Log.d(logTag, "🐢 Slow Check: Triggering CloudflareKiller...")
        val response = app.get(
            url,
            referer = mainUrl,
            headers = getHeaders(isPost = false),
            interceptor = cloudflareKiller
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
            mainUrl
        } catch (e: Exception) {
            Log.e(logTag, "⚠️ Domain Check Failed: ${e.message}")
            mainUrl
        }
    }

    private suspend fun performSearch(query: String): List<SearchResponse> {
        val activeUrl = getFreshDomainAndCookies()

        if (mainUrl != activeUrl) {
            mainUrl = activeUrl
        }

        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$activeUrl/filtering/?keywords=$encodedQuery&Rated=&genre=&release-years=&category=&quality=&country=&language="
        Log.d(logTag, "🚀 Executing Search GET to: $url")

        return try {
            val document = httpGet(url, referer = activeUrl)
            document.select("div#MainFiltar div.GridItem").mapNotNull {
                it.toSearchResult()
            }
        } catch (e: Exception) {
            Log.e(logTag, "Search Error: ${e.message}")
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

    private fun parseSeasonNumber(text: String): Int? {
        Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val normalized = text
            .replace(Regex("[\u064B-\u065F\u0670\u0640]"), "") // إزالة التشكيل والتطويل
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()

        val ordinals = listOf(
            "الحادي والثلاثون" to 31, "الحادي والثلاثين" to 31, "الواحد والثلاثون" to 31, "الواحد والثلاثين" to 31,
            "الثاني والثلاثون" to 32, "الثاني والثلاثين" to 32,
            "الثالث والثلاثون" to 33, "الثالث والثلاثين" to 33,
            "الرابع والثلاثون" to 34, "الرابع والثلاثين" to 34,
            "الخامس والثلاثون" to 35, "الخامس والثلاثين" to 35,
            "الثلاثون" to 30, "الثلاثين" to 30,
            "الحادي والعشرون" to 21, "الحادي والعشرين" to 21, "الواحد والعشرون" to 21, "الواحد والعشرين" to 21,
            "الثاني والعشرون" to 22, "الثاني والعشرين" to 22,
            "الثالث والعشرون" to 23, "الثالث والعشرين" to 23,
            "الرابع والعشرون" to 24, "الرابع والعشرين" to 24,
            "الخامس والعشرون" to 25, "الخامس والعشرين" to 25,
            "السادس والعشرون" to 26, "السادس والعشرين" to 26,
            "السابع والعشرون" to 27, "السابع والعشرين" to 27,
            "الثامن والعشرون" to 28, "الثامن والعشرين" to 28,
            "التاسع والعشرون" to 29, "التاسع والعشرين" to 29,
            "العشرون" to 20, "العشرين" to 20,
            "الحادي عشر" to 11, "الواحد عشر" to 11,
            "الثاني عشر" to 12,
            "الثالث عشر" to 13,
            "الرابع عشر" to 14,
            "الخامس عشر" to 15,
            "السادس عشر" to 16,
            "السابع عشر" to 17,
            "الثامن عشر" to 18,
            "التاسع عشر" to 19,
            "العاشر" to 10,
            "الاول" to 1,
            "الثاني" to 2,
            "الثالث" to 3,
            "الرابع" to 4,
            "الخامس" to 5,
            "السادس" to 6,
            "السابع" to 7,
            "الثامن" to 8,
            "التاسع" to 9
        )

        return ordinals.firstOrNull { normalized.contains(it.first) }?.second
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(logTag, "load() initiated with URL: $url")
        val document = httpGet(url)

        val isSeries = url.contains("/series/") ||
    document.selectFirst("div.Seasons--Episodes, div.SeasonsList, div.EpisodesList a, singlesection.Series--Section a") != null

        if (isSeries) {
            val seriesUrl = document.selectFirst("singlesection.Series--Section > a")?.attr("href")
                ?.let { fixUrl(it) }
                ?: url

            val title = document.selectFirst("singlesection.Series--Section h2")?.text()
                ?.replace("حلقات ", "")
                ?.trim()
                ?: document.selectFirst("div.Title--Content--Single-begin h1")?.text()?.trim()
                ?: "TV Series Title"

            val posterUrl = document.selectFirst("wecima.separated--top")?.attr("style")?.let {
                Regex("--img:url\\((.*?)\\)").find(it)?.groupValues?.get(1)
            }?.ifBlank { null }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

            val plot = document.selectFirst("div.StoryMovieContent")?.text()?.trim()

            val year = document.selectFirst("div.Title--Content--Single-begin a[href*='/release-year/']")?.text()
                ?.trim()?.toIntOrNull()
                ?: document.select("ul.Terms--Content--Single-begin li")
                    .find { it.selectFirst("span")?.text()?.contains("السنة") == true }
                    ?.selectFirst("p")?.text()?.trim()?.toIntOrNull()

            val htmlText = document.html()
            val ajaxBase = Regex("""var\s+AjaxtURL\s*=\s*"([^"]+)"""")
                .find(htmlText)?.groupValues?.get(1)
                ?: "$mainUrl/wp-content/themes/mycima/Ajaxt/"

            val postId = Regex("""Single/Episodes\.php.*?post_id:\s*['"](\d+)['"]""", RegexOption.DOT_MATCHES_ALL)
                .find(htmlText)?.groupValues?.get(1)
                ?: document.selectFirst("link[rel=shortlink]")?.attr("href")
                    ?.let { Regex("p=(\\d+)").find(it)?.groupValues?.get(1) }
                ?: ""

            val seasonElements = document.select("div.SeasonsList ul li a")

            fun parseEpisodesFromDoc(
                doc: Document,
                seasonTitle: String,
                seasonNum: Int
            ): List<Episode> {
                return doc.select("a").mapNotNull { epEl ->
                    val epHref = epEl.attr("href").ifBlank { return@mapNotNull null }
                    val epRawName = epEl.selectFirst("episodetitle")?.text()?.trim()
                        ?: epEl.text().trim()
                    val epNum = Regex("(\\d+)").find(epRawName)?.groupValues?.get(1)?.toIntOrNull()

                    val formattedName = if (epNum != null) {
                        "$seasonTitle الحلقة $epNum"
                    } else {
                        "$seasonTitle $epRawName".trim()
                    }

                    newEpisode(fixUrl(epHref)) {
                        this.name = formattedName
                        this.season = seasonNum
                        this.episode = epNum
                        this.posterUrl = posterUrl
                    }
                }
            }

            val episodes: List<Episode> = if (seasonElements.isNotEmpty()) {
                Log.d(logTag, "Found ${seasonElements.size} seasons. Processing...")
                val parsedSeasonNums = seasonElements.map { parseSeasonNumber(it.text()) }
                val usedNumbers = parsedSeasonNums.filterNotNull().toMutableSet()
                var nextMissing = 1
                val resolvedSeasonNums = parsedSeasonNums.map { knownNum ->
                    if (knownNum != null) {
                        knownNum
                    } else {
                        while (usedNumbers.contains(nextMissing)) {
                            nextMissing++
                        }
                        val assigned = nextMissing
                        usedNumbers.add(assigned)
                        nextMissing++
                        assigned
                    }
                }

                seasonElements.mapIndexed { idx, el -> idx to el }.amap { (idx, seasonEl) ->
                    val seasonNum = resolvedSeasonNums[idx]
                    val seasonTitle = seasonEl.text()
                        .replace(Regex("\\s*(مترجم|مدبلج).*"), "")
                        .trim()
                        .ifBlank { "الموسم $seasonNum" }
                    val dataSeason = seasonEl.attr("data-season")

                    try {
                        val seasonHtml = httpPost(
                            "${ajaxBase.trimEnd('/')}/Single/Episodes.php",
                            data = mapOf("season" to dataSeason, "post_id" to postId),
                            referer = url
                        )
                        parseEpisodesFromDoc(Jsoup.parse(seasonHtml), seasonTitle, seasonNum)
                    } catch (e: Exception) {
                        logError(e)
                        if (seasonEl.parent()?.hasClass("active") == true) {
                            document.selectFirst("div.EpisodesList")?.let { listEl ->
                                parseEpisodesFromDoc(Jsoup.parse(listEl.html()), seasonTitle, seasonNum)
                            } ?: emptyList()
                        } else {
                            emptyList()
                        }
                    }
                }.flatten()
            } else {
                document.selectFirst("div.EpisodesList")?.let { listEl ->
                    parseEpisodesFromDoc(Jsoup.parse(listEl.html()), "الموسم الأول", 1)
                } ?: emptyList()
            }

            val sortedEpisodes = episodes
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(title, seriesUrl, TvType.TvSeries, sortedEpisodes) {
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
            val year = document.selectFirst("div.Title--Content--Single-begin a[href*='/release-year/']")?.text()
                ?.trim()?.toIntOrNull()
                ?: document.select("ul.Terms--Content--Single-begin li")
                    .find { it.selectFirst("span")?.text()?.contains("السنة") == true }
                    ?.selectFirst("p")?.text()?.trim()?.toIntOrNull()

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
            String(Base64.decode(finalB64Str, Base64.DEFAULT))
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    private fun customB64Decode(s: String, alphabet: String): String {
        var bc = 0
        var bs = 0
        val out = mutableListOf<Byte>()
        for (ch in s) {
            val idx = alphabet.indexOf(ch)
            if (idx == -1) continue
            bs = if (bc % 4 != 0) (bs * 64 + idx) else idx
            if (bc % 4 != 0) {
                out.add((0xff and (bs shr ((-2 * (bc + 1)) and 6))).toByte())
            }
            bc++
        }
        return try {
            val pct = out.joinToString("") { "%02x".format(it) }
            URLDecoder.decode("%$pct".replace("%", "%25").replace("%25", "%"), "UTF-8")
        } catch (e: Exception) {
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun rc4(s: String, key: String): String {
        val sbox = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + sbox[i] + key[i % key.length].code) % 256
            val temp = sbox[i]; sbox[i] = sbox[j]; sbox[j] = temp
        }
        var i = 0
        j = 0
        val res = StringBuilder()
        for (ch in s) {
            i = (i + 1) % 256
            j = (j + sbox[i]) % 256
            val temp = sbox[i]; sbox[i] = sbox[j]; sbox[j] = temp
            res.append((ch.code xor sbox[(sbox[i] + sbox[j]) % 256]).toChar())
        }
        return res.toString()
    }

    private fun deobfuscateJs(jsCode: String): String {
        val jsId = """[a-zA-Z_$][\w$]*"""
        val decRegex = Regex("""function\s+($jsId)\s*\(\s*($jsId)(?:\s*,\s*$jsId)?\s*\)\s*\{\s*\2\s*=\s*\2\s*([-+])\s*([^;]+);\s*(?:const|let|var)\s+$jsId\s*=\s*($jsId)\s*\(\s*\)\s*;""")
        val mDec = decRegex.find(jsCode) ?: return jsCode

        val decFn = mDec.groupValues[1]
        val op = mDec.groupValues[3]
        val offsetVal = mDec.groupValues[4].trim().toIntOrNull() ?: 0
        val offset = if (op == "+") -offsetVal else offsetVal
        val arrFn = mDec.groupValues[5]

        val arrRegex = Regex("""function\s+${Regex.escape(arrFn)}\s*\(\s*\)\s*\{\s*(?:const|let|var)\s+$jsId\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL)
        val mArr = arrRegex.find(jsCode) ?: return jsCode
        val rawArrStr = mArr.groupValues[1]

        val arr = Regex("""['"]([^'"]+)['"]""").findAll(rawArrStr).map { it.groupValues[1] }.toMutableList()
        val alphabetMatch = Regex("""['"]([A-Za-z0-9+/=]{64,65})['"]""").find(jsCode)
        val alphabet = alphabetMatch?.groupValues?.get(1)
            ?: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/="

        fun decodeItem(idx: Int, key: String): String {
            val realIdx = idx - offset
            if (realIdx !in arr.indices) return ""
            val raw = arr[realIdx]
            return try {
                if (key.isNotEmpty()) {
                    rc4(customB64Decode(raw, alphabet), key)
                } else {
                    customB64Decode(raw, alphabet)
                }
            } catch (e: Exception) {
                raw
            }
        }

        val callRegex = Regex("""\b${Regex.escape(decFn)}\(\s*(0x[0-9a-fA-F]+|\d+)(?:\s*,\s*['"]([^'"]*)['"])?\s*\)""")
        return callRegex.replace(jsCode) { m ->
            val numStr = m.groupValues[1]
            val idx = if (numStr.startsWith("0x")) numStr.substring(2).toInt(16) else numStr.toInt()
            val key = m.groupValues.getOrNull(2) ?: ""
            "\"${decodeItem(idx, key)}\""
        }
    }

    private fun extractDomains(decodedJs: String, currentHost: String): List<String> {
        val domains = mutableListOf<String>()
        val listRegex = Regex("""\b(?:main|dmca)\s*=\s*\[([^\]]+)\]""")
        listRegex.findAll(decodedJs).forEach { match ->
            val content = match.groupValues[1]
            Regex("""['"]([a-zA-Z0-9.-]+\.[a-z]{2,})['"]""").findAll(content).forEach { d ->
                domains.add(d.groupValues[1])
            }
        }
        if (domains.isEmpty()) {
            val domainRegex = Regex("""['"]([a-zA-Z0-9.-]+\.[a-z]{2,})['"]""")
            domainRegex.findAll(decodedJs).forEach { d ->
                val dom = d.groupValues[1]
                if (!dom.contains("google") && !dom.contains("cloudflare") && !dom.endsWith(currentHost)) {
                    domains.add(dom)
                }
            }
        }
        return domains.distinct()
    }

    private suspend fun replaceStreamWishDomain(targetUrl: String, referer: String? = null): String {
        return try {
            val uri = URL(targetUrl)
            val query = if (!uri.query.isNullOrBlank()) "?${uri.query}" else ""
            val path = uri.path

            for (domain in streamWishPreferredDomains) {
                val candidateUrl = "https://$domain$path$query"
                try {
                    val check = app.get(
                        candidateUrl,
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to (referer ?: "https://$domain/")
                        ),
                        timeout = 6
                    )
                    if (check.code == 200 && !check.text.contains("loading", ignoreCase = true)) {
                        return candidateUrl
                    }
                } catch (_: Exception) {}
            }

            "https://${streamWishPreferredDomains.first()}$path$query"
        } catch (e: Exception) {
            targetUrl
        }
    }

    private suspend fun resolveHgCloud(targetUrl: String, referer: String? = null): String? {
        return try {
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to (referer ?: targetUrl)
            )

            val r1 = app.get(targetUrl, headers = headers, timeout = 8)
            if (r1.code != 200) return null

            val html = r1.text
            val jsPathMatch = Regex("""<script[^>]+src=["']([^"']*main\.js[^"']*)["']""").find(html)
                ?: if (html.lowercase().contains("loading")) Regex("""<script[^>]+src=["'](/[^"']+\.js[^"']*)["']""").find(html) else null

            val uri = URL(r1.url)
            val currentHost = uri.host
            val candidates = mutableListOf<String>()
            candidates.addAll(streamWishPreferredDomains)

            if (jsPathMatch != null) {
                val jsRelative = jsPathMatch.groupValues[1]
                val jsUrl = if (jsRelative.startsWith("http")) jsRelative else "${uri.protocol}://${uri.host}$jsRelative"

                val r2 = app.get(jsUrl, headers = headers, timeout = 8)
                if (r2.code == 200) {
                    try {
                        val decodedJs = deobfuscateJs(r2.text)
                        candidates.addAll(extractDomains(decodedJs, currentHost))
                    } catch (e: Exception) {}
                }
            }

            val uniqueCandidates = candidates.distinct().take(8)

            for (dest in uniqueCandidates) {
                if (dest.equals(currentHost, ignoreCase = true)) continue
                val query = if (!uri.query.isNullOrBlank()) "?${uri.query}" else ""
                val testUrl = "https://$dest${uri.path}$query"

                try {
                    val check = app.get(testUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to r1.url), timeout = 5)
                    if (check.code == 200 && !check.text.contains("loading", ignoreCase = true)) {
                        return testUrl
                    }
                } catch (e: Exception) {}
            }
            null
        } catch (e: Exception) {
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
        val collectedLinks = linkedMapOf<String, String?>()

        fun addLink(rawUrl: String?, serverName: String?) {
            val cleanUrl = rawUrl?.trim().orEmpty()
            if (!cleanUrl.startsWith("http")) return
            val cleanName = serverName?.trim()?.takeIf { it.isNotBlank() }
            if (!collectedLinks.containsKey(cleanUrl)) {
                collectedLinks[cleanUrl] = cleanName
            } else if (collectedLinks[cleanUrl].isNullOrBlank() && cleanName != null) {
                collectedLinks[cleanUrl] = cleanName
            }
        }
        document.select("ul#watch li[data-watch]").forEach { serverEl ->
            val watchUrl = serverEl.attr("data-watch")
            val serverName = serverEl.text().trim()
            Log.d(logTag, "📺 Watch URL: $watchUrl ($serverName)")
            addLink(watchUrl, serverName)
        }
        document.select("ul.List--Download--Wecima--Single li a[href]").forEach { downloadEl ->
            val downloadUrl = downloadEl.attr("href")
            val serverName = downloadEl.selectFirst("quality")?.text()?.trim()
                ?: downloadEl.text().trim()
            Log.d(logTag, "📥 Download URL: $downloadUrl ($serverName)")
            addLink(downloadUrl, serverName)
        }
        document.select("ul.WatchServersList li btn[data-url]").forEach { serverBtn ->
            decodeWecimaUrl(serverBtn.attr("data-url"))?.let { decodedUrl ->
                val serverName = serverBtn.text().trim().ifBlank { serverBtn.parent()?.text()?.trim() }
                addLink(decodedUrl, serverName)
            }
        }

        document.select(".openLinkDown[data-href]").forEach { downloadBtn ->
            decodeWecimaUrl(downloadBtn.attr("data-href"))?.let { decodedUrl ->
                val serverName = downloadBtn.selectFirst("quality")?.text()?.trim()
                addLink(decodedUrl, serverName)
            }
        }

        collectedLinks.entries.toList().amap { (link, serverName) ->
            var targetUrl = link
            val isStreamWishOrHg = targetUrl.contains("hgcloud", ignoreCase = true) ||
                targetUrl.contains("streamwish", ignoreCase = true) ||
                serverName?.contains("hgcloud", ignoreCase = true) == true ||
                serverName?.contains("streamwish", ignoreCase = true) == true

            if (isStreamWishOrHg) {
                val resolved = if (targetUrl.contains("hgcloud", ignoreCase = true)) {
                    resolveHgCloud(targetUrl, mainUrl) ?: replaceStreamWishDomain(targetUrl, mainUrl)
                } else {
                    replaceStreamWishDomain(targetUrl, mainUrl)
                }
                if (resolved.isNotBlank()) {
                    Log.d(logTag, "🔄 Replaced StreamWish/hgcloud: $targetUrl -> $resolved")
                    targetUrl = resolved
                }
            }
            var emittedByBuiltIn = false
            val builtInCallback: (ExtractorLink) -> Unit = { extractorLink ->
                emittedByBuiltIn = true
                callback(extractorLink)
            }

            try {
                loadExtractor(targetUrl, mainUrl, subtitleCallback, builtInCallback)
            } catch (e: Exception) {
                logError(e)
            }
            if (!emittedByBuiltIn) {
                try {
                    UniversalExtractor.extract(
                        url = targetUrl,
                        referer = mainUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                        serverName = serverName
                    )
                } catch (e: Exception) {
                    logError(e)
                }
            }

            true
        }

        return collectedLinks.isNotEmpty()
    }
}
