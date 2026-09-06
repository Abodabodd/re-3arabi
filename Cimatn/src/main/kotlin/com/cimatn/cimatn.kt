package com.cimatn

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
import com.lagradost.cloudstream3.utils.newExtractorLink

class CimaTn : MainAPI() {
    override var mainUrl = "https://www.cimatn.com"
    override var name = "CimaTn"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/search/label/أحدث الإضافات" to "أحدث الإضافات",
        "$mainUrl/search/label/أفلام تونسية" to "أفلام تونسية",
        "$mainUrl/search/label/مسلسلات تونسية" to "مسلسلات تونسية",
        "$mainUrl/search/label/رمضان2025" to "رمضان 2025",
        "$mainUrl/search/label/دراما" to "دراما",
        "$mainUrl/search/label/كوميديا" to "كوميديا",
        "$mainUrl/search/label/أكشن" to "أكشن"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}?max-results=20"
        val doc = app.get(url).document
        val home = doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val title = element.select("#item-name").text().trim()
        val url = element.attr("href")
        var posterUrl = element.select("img").attr("src")
        posterUrl = fixPoster(posterUrl)
        val year = element.select(".entry-label").text().trim().toIntOrNull()

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?q=$query"
        val doc = app.get(url).document
        return doc.select("#holder a.itempost").mapNotNull { toSearchResult(it) }
    }

    override suspend fun load(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?")

        if (cleanUrl.contains("film-")) {
            return loadMovieData(cleanUrl)
        }

        val doc = app.get(cleanUrl).document
        val htmlContent = doc.html()

        if (htmlContent.contains("data-secure-url") && !htmlContent.contains("const watchPageSlug")) {
            return loadMovieData(cleanUrl)
        }

        val title = doc.selectFirst("h1.PostTitle")?.text()?.trim() ?: "Series"
        val poster = doc.selectFirst("#poster img")?.attr("src")
        val description = doc.selectFirst(".StoryArea, .SingleContent")?.text()?.trim()
        val tags = doc.select(".RightTaxContent a").map { it.text() }
        val year = doc.select(".RightTaxContent .fa-calendar").parents().first()?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val episodes = mutableListOf<Episode>()
        val domain = "https://${java.net.URI(cleanUrl).host}"

        val feedMatch = Regex("""const\s+feedURL\s*=\s*"([^"]+)";""").find(htmlContent)
        val seasonsList = mutableListOf<Pair<String, String>>()

        if (feedMatch != null) {
            var feedUrlSuffix = feedMatch.groupValues[1]
            if (feedUrlSuffix.startsWith("/")) feedUrlSuffix = feedUrlSuffix.substring(1)
            val feedUrl = "$domain/$feedUrlSuffix"

            try {
                val jsonString = app.get(feedUrl).text
                val cleanJson = if (jsonString.contains("{"))
                    jsonString.substring(jsonString.indexOf("{"), jsonString.lastIndexOf("}") + 1)
                else jsonString

                val entries = cleanJson.split("\"entry\":[")
                if (entries.size > 1) {
                    val items = entries[1].split("},{")
                    for (item in items) {
                        val tMatch = Regex(""""\$":"(.*?)"""").find(item.substringAfter("\"title\":"))
                        val sTitle = tMatch?.groupValues?.get(1) ?: "Season"

                        val linksPart = item.substringAfter("\"link\":").substringBefore("],")
                        val linkMatch = Regex(""""rel":"alternate".*?"href":"(https:[^"]+)"""").find(linksPart)
                            ?: Regex(""""href":"(https:[^"]+)".*?"rel":"alternate"""").find(linksPart)

                        val sUrl = linkMatch?.groupValues?.get(1)?.replace("\\/", "/")
                        if (sUrl != null) seasonsList.add(Pair(sTitle, sUrl))
                    }
                }
            } catch (e: Exception) {}
        }

        if (seasonsList.isEmpty()) {
            doc.select(".allseasonss .Small--Box.Season a").forEach {
                seasonsList.add(Pair(it.attr("title"), it.attr("href")))
            }
        }

        if (seasonsList.isNotEmpty()) {
            seasonsList.reversed().forEachIndexed { index, (sTitle, sUrl) ->
                val seasonNum = index + 1
                val (epHtml, epUrl) = if (sUrl.contains(cleanUrl.substringAfterLast("/"))) Pair(htmlContent, cleanUrl) else Pair(app.get(sUrl).text, sUrl)

                val foundEps = getSeriesEpisodes(domain, epHtml, epUrl)
                foundEps.forEach { (epName, epLink) ->
                    val epNum = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    episodes.add(newEpisode(epLink) {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epNum
                    })
                }
            }
        } else {
            val foundEps = getSeriesEpisodes(domain, htmlContent, cleanUrl)
            foundEps.forEach { (epName, epLink) ->
                val epNum = Regex("""(\d+)""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epLink) {
                    this.name = epName
                    this.season = 1
                    this.episode = epNum
                })
            }
        }

        return newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    private suspend fun loadMovieData(url: String): LoadResponse {
        val cleanUrl = url.substringBefore("?")
        val filename = cleanUrl.substringAfterLast("/").replace(".html", "")
        val keyword = if (filename.contains("-")) filename.substringAfterLast("-") else filename
        val domain = "https://${java.net.URI(cleanUrl).host}"

        var finalLink: String? = null

        try {
            val feedUrl = "$domain/feeds/posts/default?alt=json&max-results=100"
            val jsonString = app.get(feedUrl).text

            val cleanJson = if (jsonString.contains("{"))
                jsonString.substring(jsonString.indexOf("{"), jsonString.lastIndexOf("}") + 1)
            else jsonString

            val entries = cleanJson.split("\"entry\":[")
            if (entries.size > 1) {
                val items = entries[1].split("},{")
                for (item in items) {
                    val linkMatch = Regex(""""rel":"alternate".*?"href":"(https:[^"]+)"""").find(item)
                        ?: Regex(""""href":"(https:[^"]+)".*?"rel":"alternate"""").find(item)

                    val href = linkMatch?.groupValues?.get(1)?.replace("\\/", "/")

                    if (href != null && href.contains(keyword)) {
                        finalLink = href
                        break
                    }
                }
            }
        } catch (e: Exception) {}

        val resolvedLink = if (finalLink != null) {
            finalLink.replace("www.cimatn.com", "cimatunisa.blogspot.com")
        } else {
            cleanUrl.replace("www.cimatn.com", "cimatunisa.blogspot.com")
        }

        val doc = app.get(cleanUrl).document
        val title = doc.selectFirst("h1.PostTitle")?.text()?.trim() ?: "Movie"
        val poster = doc.selectFirst("#poster img")?.attr("src")
        val description = doc.selectFirst(".StoryArea, .SingleContent")?.text()?.trim()
        val tags = doc.select(".RightTaxContent a").map { it.text() }
        val year = doc.select(".RightTaxContent .fa-calendar").parents().first()?.text()?.filter { it.isDigit() }?.toIntOrNull()

        return newMovieLoadResponse(title, resolvedLink, TvType.Movie, resolvedLink) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.year = year
        }
    }

    private suspend fun getSeriesEpisodes(domain: String, html: String, pageUrl: String): List<Pair<String, String>> {
        val episodes = mutableListOf<Pair<String, String>>()

        val linksMatch = Regex("""const\s+episodeLinks\s*=\s*\[(.*?)\];""", RegexOption.DOT_MATCHES_ALL).find(html)
        if (linksMatch != null) {
            val linksStr = linksMatch.groupValues[1]
            val urlsRegex = Regex("""["'](https?://[^"']+)["']""")
            urlsRegex.findAll(linksStr).forEachIndexed { index, matchResult ->
                episodes.add(Pair("الحلقة ${index + 1}", matchResult.groupValues[1]))
            }
            if (episodes.isNotEmpty()) return episodes
        }

        val slugMatch = Regex("""const\s+watchPageSlug\s*=\s*"([^"]+)";""").find(html)
        val totalEpMatch = Regex("""const\s+totalEpisodes\s*=\s*(\d+);""").find(html)

        if (slugMatch != null && totalEpMatch != null) {
            val watchSlug = slugMatch.groupValues[1]
            val totalEps = totalEpMatch.groupValues[1].toInt()

            val feedUrl = "$domain/feeds/pages/default?alt=json&max-results=150&q=$watchSlug"
            try {
                val jsonString = app.get(feedUrl).text
                val linkRegex = Regex("""\"rel\":\"alternate\".*?\"href\":\"(https:[^\"]+)\"""")
                val allLinks = linkRegex.findAll(jsonString).map { it.groupValues[1].replace("\\/", "/") }
                val watchPageUrl = allLinks.find { it.contains(watchSlug) }

                if (watchPageUrl != null) {
                    for (i in 1..totalEps) {
                        episodes.add(Pair("الحلقة $i", "$watchPageUrl?episode=$i"))
                    }
                    return episodes
                }
            } catch (e: Exception) {}
        }

        if (totalEpMatch != null) {
            val baseLinkMatch = Regex("""const\s+baseLink\s*=\s*"([^"]+)";""").find(html)
            if (baseLinkMatch != null && slugMatch == null) {
                val count = totalEpMatch.groupValues[1].toInt()
                var baseLink = baseLinkMatch.groupValues[1]

                for (i in 1..count) {
                    val fullLink = if (baseLink.startsWith("http")) {
                        "$baseLink$i.html"
                    } else {
                        if (baseLink.startsWith("/")) baseLink = baseLink.substring(1)
                        "$domain/p/$baseLink$i.html"
                    }
                    episodes.add(Pair("الحلقة $i", fullLink))
                }
                return episodes
            }
        }

        val doc = org.jsoup.Jsoup.parse(html)
        val links = doc.select(".allepcont .row a")
        if (links.isNotEmpty()) {
            links.forEach { link ->
                val href = link.attr("href")
                if (href.isNotEmpty() && href != "#") {
                    val title = link.select("h2").text().ifEmpty { "Ep" }
                    episodes.add(Pair(title, href))
                }
            }
            return episodes
        }

        val slug = try {
            pageUrl.substringAfterLast("/").replace(".html", "")
        } catch (e: Exception) { "" }

        if (slug.isNotEmpty()) {
            val feedUrl = "$domain/feeds/pages/default?alt=json&max-results=500"
            try {
                val jsonString = app.get(feedUrl).text
                val cleanJson = if (jsonString.contains("{")) jsonString.substring(jsonString.indexOf("{"), jsonString.lastIndexOf("}") + 1) else jsonString

                val targetSlugClean = slug.lowercase().replace("y", "i").replace("-", "").replace("_", "")

                val entries = cleanJson.split("\"entry\":[")
                if (entries.size > 1) {
                    val items = entries[1].split("},{")
                    for (item in items) {
                        val tMatch = Regex(""""\$":"(.*?)"""").find(item.substringAfter("\"title\":"))
                        val entryTitle = tMatch?.groupValues?.get(1) ?: "Episode"

                        val lMatch = Regex(""""rel":"alternate".*?"href":"(https:[^"]+)"""").find(item)
                            ?: Regex(""""href":"(https:[^"]+)".*?"rel":"alternate"""").find(item)
                        val entryLink = lMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: continue

                        val linkSlug = entryLink.substringAfterLast("/").replace(".html", "")
                        val linkSlugClean = linkSlug.lowercase().replace("y", "i").replace("-", "").replace("_", "")

                        val isMatch = linkSlugClean.contains(targetSlugClean)
                        val isEpisode = entryLink.contains("ep-") || entryLink.contains("-ep") || entryLink.contains("hal9a") || entryLink.contains("episode")

                        if (isMatch && isEpisode) {
                            episodes.add(Pair(entryTitle, entryLink))
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        return episodes.sortedBy {
            Regex("""(\d+)""").find(it.first)?.groupValues?.get(1)?.toIntOrNull() ?: 999
        }
    }

    private fun fixPoster(url: String): String {
        return url.replace(Regex("/s\\d+-c/"), "/w600/")
            .replace(Regex("/w\\d+/"), "/w600/")
            .replace(Regex("/s\\d+/"), "/s1600/")
    }
    private suspend fun deepSearchLinks(url: String, depth: Int = 0, visited: MutableSet<String> = mutableSetOf()): List<String> {
        if (depth > 3 || !visited.add(url)) return emptyList()
        val foundLinks = mutableListOf<String>()

        try {
            val html = app.get(url, referer = mainUrl).text
            val serversRegex = Regex("""url:\s*['"](https?://[^'"]+)['"]""")
            serversRegex.findAll(html).forEach {
                val src = it.groupValues[1].replace("\\", "")
                if (isInternalEmbed(src)) {
                    foundLinks.addAll(deepSearchLinks(src, depth + 1, visited))
                } else {
                    foundLinks.add(src)
                }
            }
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
                if (!src.isNullOrBlank() && !src.contains("facebook.com") && !src.contains("googlesyndication")) {
                    val fixedSrc = if (src.startsWith("//")) "https:$src" else src

                    if (isInternalEmbed(fixedSrc)) {
                        foundLinks.addAll(deepSearchLinks(fixedSrc, depth + 1, visited))
                    } else {
                        foundLinks.add(fixedSrc)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return foundLinks.distinct()
    }

    private fun isInternalEmbed(url: String): Boolean {
        return url.contains(mainUrl) || url.contains("cimatn") || url.contains("cimatunisa") || url.contains("trembed") || url.contains("trid") || url.startsWith("/")
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            loadExtractor(data, "$mainUrl/", subtitleCallback, callback)
            return true
        }
        val allExternalLinks = deepSearchLinks(data)
        var foundAnyLink = false

        allExternalLinks.forEach { playerUrl ->
            val isSmartDecrypted = SmartPlayer.extract(playerUrl, callback)
            if (isSmartDecrypted) {
                foundAnyLink = true
            } else {
                if (!isInternalEmbed(playerUrl)) {
                    loadExtractor(playerUrl, "$mainUrl/", subtitleCallback, callback)
                    foundAnyLink = true
                }
            }
        }

        return foundAnyLink
    }
    object SmartPlayer {
        private const val KEY_STRING = "kiemtienmua911ca"

        private fun String.decodeHex(): ByteArray {
            val cleanHex = this.trim().replace("\"", "").let { if (it.length % 2 != 0) it.dropLast(1) else it }
            return cleanHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun generateIvCandidates(domain: String, videoId: String): List<ByteArray> {
            val candidates = mutableListOf<ByteArray>()

            val dOpts = mutableListOf<Int>(48, 323)
            if (domain.isNotEmpty()) {
                dOpts.add(domain.length * (domain.length + 2))
                val parts = domain.split(".")
                if (parts.size >= 2) {
                    val shortDomain = "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
                    dOpts.add(shortDomain.length * (shortDomain.length + 2))
                }
            }

            val wOpts = mutableListOf<Int>(0, 105, 141, 189, 63)
            if (videoId.isNotEmpty()) {
                wOpts.add(3 * videoId.first().code)
            }

            for (d in dOpts.distinct()) {
                for (w in wOpts.distinct()) {
                    val part1 = (1..9).map { (it + d).toChar() }.joinToString("")
                    val part2 = intArrayOf(d, 111, w, 128, 132, 97, 95).map { it.toChar() }.joinToString("")
                    val ivString = (part1 + part2)
                    candidates.add(ivString.toByteArray(Charsets.UTF_8).copyOfRange(0, 16))
                }
            }
            return candidates
        }

        private fun smartDecrypt(encryptedHex: String, domain: String, videoId: String): String? {
            val encryptedBytes = try { encryptedHex.decodeHex() } catch (e: Exception) { return null }
            val secretKey = SecretKeySpec(KEY_STRING.toByteArray(Charsets.UTF_8), "AES")
            val ivCandidates = generateIvCandidates(domain, videoId)
            for (iv in ivCandidates) {
                try {
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
                    val decryptedText = String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)

                    if (decryptedText.trim().startsWith("{")) {
                        val source = Regex(""""source"\s*:\s*"([^"]+)"""").find(decryptedText)?.groupValues?.get(1)
                        if (source != null) return source.replace("\\/", "/")
                    }
                } catch (e: Exception) { continue }
            }
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(ByteArray(16)))
                val decryptedPadded = cipher.doFinal(encryptedBytes)

                if (decryptedPadded.size > 16) {
                    val validText = String(decryptedPadded.copyOfRange(16, decryptedPadded.size), Charsets.UTF_8)
                    val match = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/[^\s",\\]+\.m3u8)""").find(validText)
                        ?: Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/[^\s",\\]+)""").find(validText)

                    if (match != null) {
                        return "https://" + match.groupValues[1]
                    }
                }
            } catch (e: Exception) { }

            return null
        }

        suspend fun extract(playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
            if (!playerUrl.contains("#") && !playerUrl.contains("id=")) return false

            val MAX_RETRIES = 3

            try {
                val uri = URI(if (playerUrl.startsWith("//")) "https:$playerUrl" else playerUrl)
                val domain = uri.host ?: return false

                val videoId = when {
                    playerUrl.contains("#") -> playerUrl.substringAfterLast("#").substringBefore("&")
                    playerUrl.contains("id=") -> playerUrl.substringAfter("id=").substringBefore("&")
                    else -> return false
                }

                if (videoId.isBlank()) return false

                val apiUrl = "https://$domain/api/v1/video?id=$videoId"
                val headers = mapOf(
                    "Referer" to "https://$domain/",
                    "Origin" to "https://$domain",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
                    "Accept" to "application/json, text/plain, */*"
                )

                for (attempt in 1..MAX_RETRIES) {
                    val res = app.get(apiUrl, headers = headers)

                    if (res.isSuccessful && Regex("^[0-9a-fA-F\"]+$").matches(res.text.trim())) {
                        val rawM3u8 = smartDecrypt(res.text.trim(), domain, videoId)

                        if (!rawM3u8.isNullOrBlank()) {
                            val masterM3u8 = sanitizeUrl(rawM3u8)
                            val finalM3u8 = getFinalM3u8(masterM3u8, "https://$domain/")

                            callback.invoke(
                                newExtractorLink(
                                    "CimaTn",
                                    "CimaTn Server ($domain)",
                                    finalM3u8 ?: masterM3u8,
                                ) {
                                    referer = "https://$domain/"
                                    quality = Qualities.Unknown.value
                                }
                            )
                            return true // تمت عملية الاستخراج بنجاح
                        }
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return false // فشلت الطريقة الذكية
        }

        private suspend fun getFinalM3u8(masterUrl: String, referer: String): String? {
            return try {
                val playlistContent = app.get(masterUrl, headers = mapOf("Referer" to referer)).text
                val qualityLine = playlistContent.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }

                if (qualityLine != null && !qualityLine.contains("://")) {
                    val basePath = masterUrl.substringBeforeLast("/")
                    "$basePath/$qualityLine"
                } else if (qualityLine != null) {
                    qualityLine
                } else {
                    masterUrl
                }
            } catch (e: Exception) {
                masterUrl
            }
        }

        private fun sanitizeUrl(rawLink: String): String {
            val urlRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,10}/.*)""")
            val match = urlRegex.find(rawLink)
            return if (match != null) "https://${match.value}" else rawLink
        }
    }
}