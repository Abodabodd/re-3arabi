package com.cimalight

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URL
import java.net.URLDecoder

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://r.cimalight.co"
    override var name = "CimaLight"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val TAG = "CimaLightLog"

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    private val streamWishPreferredDomains = listOf(
        "vibuxer.com",
        "hanerix.com",
        "niramirus.com",
        "hglamioz.com",
        "playnixes.com",
        "medixiru.com"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/main16" to "الرئيسية",
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/episodes.php" to "أحدث الحلقات",
        "$mainUrl/all-series.php" to "أحدث المسلسلات"
    )

    private val customHeaders = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-User" to "?1",
        "Sec-Fetch-Dest" to "document",
        "Accept-Language" to "ar-EG,ar;q=0.9",
        "Pragma" to "no-cache"
    )

    private var savedCookies: String? = null

    private suspend fun getDocOrSolve(url: String): Document? {
        Log.d(TAG, "جاري طلب الرابط: $url")
        val headersWithCookies = customHeaders.toMutableMap().apply {
            savedCookies?.let { put("Cookie", it) }
        }

        val response = app.get(url, headers = headersWithCookies)
        val statusCode = response.code

        Log.d(TAG, "Status Code: $statusCode")
        if (statusCode in 200..299) {
            return response.document
        }
        if (statusCode == 403 || statusCode == 503 || statusCode == 429) {
            Log.e(TAG, "تم اكتشاف حماية Cloudflare (كود $statusCode) → جاري تشغيل Solver")

            val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity

            val result = CloudflareSolver.solve(
                activity,
                url,
                userAgent
            )
            val cookies = CookieManager.getInstance().getCookie(url)

            if (!cookies.isNullOrEmpty()) {
                Log.d(TAG, "تم الحصول على cookies بنجاح: $cookies")
                savedCookies = cookies
                val retryHeaders = customHeaders.toMutableMap().apply {
                    put("Cookie", cookies)
                }

                val retryResponse = app.get(url, headers = retryHeaders)
                Log.d(TAG, "Retry Status: ${retryResponse.code}")

                if (retryResponse.code in 200..299) {
                    return retryResponse.document
                }
            }
            return result
        }
        return response.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDocOrSolve(request.data) ?: return newHomePageResponse(emptyList())
        val homePageList = ArrayList<HomePageList>()
        val addedSectionNames = mutableSetOf<String>()

        if (request.data.contains("main16")) {
            val featured = document.select("ul[id*=pm-carousel_featured] > li, div.sSlide.feat li.block-post")
            if (featured.isNotEmpty()) {
                val items = featured.mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList("أعمال مميزة", items))
                    addedSectionNames.add("أعمال مميزة")
                }
            }
        }

        val sections = document.select("div.pm-section-head, h2.pm-section-title")
        sections.forEach { head ->
            val title = head.selectFirst("h2 a")?.text()?.trim()
                ?: head.selectFirst("h2")?.text()?.trim()
                ?: head.text().trim()

            if (title.isEmpty() || title.contains("جديد الموقع") || title.contains("أحدث الأفلام") || addedSectionNames.contains(title)) {
                return@forEach
            }

            var parent = head.parent()
            var items = parent?.select("ul[class*='pm-ul-'] > li, div.block-series")?.mapNotNull { it.toSearchResult() }

            if (items.isNullOrEmpty() && parent != null) {
                parent = parent.parent()
                items = parent?.select("ul[class*='pm-ul-'] > li, div.block-series")?.mapNotNull { it.toSearchResult() }
            }

            if (!items.isNullOrEmpty()) {
                homePageList.add(HomePageList(title, items))
                addedSectionNames.add(title)
            }
        }

        if (!addedSectionNames.contains("مسلسلات مميزة")) {
            val sidebarSeries = document.select("div.block-series").mapNotNull { it.toSearchResult() }
            if (sidebarSeries.isNotEmpty()) {
                homePageList.add(HomePageList("مسلسلات مميزة", sidebarSeries))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a:not(.pm-watch-later-add)") ?: return null
        val href = fixUrl(linkElement.attr("href"))

        val title = this.selectFirst("h3 a")?.text()?.trim()
            ?: this.selectFirst("div.title")?.text()?.trim()
            ?: linkElement.attr("title").trim()

        if (title.isEmpty() || href.contains("#")) return null

        val posterUrl = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("div[style*=background-image]")?.attr("style")?.substringAfter("url('")?.substringBefore("')")

        val isTvSeries = href.contains("/episode/") || href.contains("/series/") || title.contains("مسلسل") || this.selectFirst("span.ep, div.ribbon") != null

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=$query"
        val document = getDocOrSolve(url) ?: return emptyList()
        return document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocOrSolve(url) ?: return null

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.video-bibplayer-poster")?.attr("style")?.substringAfter("url(")?.substringBefore(")"))
        val description = document.selectFirst("div[itemprop=description] p")?.text()?.trim()
        val isTvSeries = document.select("div.SeasonsBox").isNotEmpty() || title.contains("مسلسل")

        if (isTvSeries) {
            val episodes = ArrayList<Episode>()
            val seasonElements = document.select("div.SeasonsEpisodesMain div.tabcontent")

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonElement ->
                    val seasonId = seasonElement.attr("id")
                    val seasonName = document.selectFirst("button[onclick*='$seasonId']")?.text()?.trim()
                    val seasonNum = seasonName?.filter { it.isDigit() }?.toIntOrNull() ?: 1

                    seasonElement.select("ul a").forEach { epElement ->
                        val epHref = fixUrl(epElement.attr("href"))
                        val epName = epElement.text().trim()
                        val epNum = epName.filter { it.isDigit() }.toIntOrNull()

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } else {
                episodes.add(newEpisode(url) {
                    this.name = "الحلقة المحددة"
                    this.posterUrl = poster
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
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
        Log.d(TAG, "🔗 loadLinks initiated for: $data")
        val mainPageDoc = getDocOrSolve(data) ?: return false
        val intermediateUrl = mainPageDoc.selectFirst("a.xtgo")?.attr("href") ?: return false

        val headersWithReferer = customHeaders
            .filterKeys { it != "Host" }
            .toMutableMap()
            .apply {
                put("Referer", mainUrl)
                savedCookies?.let { put("Cookie", it) }
            }

        val intermediateDoc = app.get(intermediateUrl, headers = headersWithReferer).document
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
        intermediateDoc.select("div.embeding ul li").forEach { serverElement ->
            val serverUrl = serverElement.attr("data-embed")
            val serverName = serverElement.text().trim()
            addLink(serverUrl, serverName)
        }
        intermediateDoc.select("div.downloads a, ul.downloads li a").forEach { dlElement ->
            val dlUrl = dlElement.attr("href")
            val dlName = dlElement.text().trim()
            addLink(dlUrl, dlName)
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
                    Log.d(TAG, "🔄 Replaced StreamWish/hgcloud: $targetUrl -> $resolved")
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
