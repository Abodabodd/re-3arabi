package com.eseek

import android.util.Base64
import java.net.URLDecoder
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.forEach
import com.fasterxml.jackson.annotation.JsonProperty // استيراد Jackson لتأمين فك البيانات

class GessehProvider : MainAPI() {
    override var mainUrl = "https://qeseh.net"
    override var name = "عشق 2"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
    )

    data class GessehServer(
        val name: String? = null,
        val id: String? = null
    )

    data class GessehPayload(
        val codeDaily: String? = null,
        val servers: List<GessehServer>? = null,
        val backUrl: String? = null
    )
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/all-series/page/" to "جميع المسلسلات",
        "$mainUrl/category/الأفلام-التركية/page/" to "افلام تركية",
        "$mainUrl/page/" to "آخر الحلقات",
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst("a") ?: return null
        val href = linkTag.attr("href")
        val title = linkTag.selectFirst("div.title")?.text()?.trim() ?: return null

        var posterUrl = linkTag.selectFirst("div.imgBg, div.imgSer")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")
            ?.replace("\"", "")?.replace("'", "")?.trim()

        if (posterUrl.isNullOrBlank()) {
            val imgTag = linkTag.selectFirst("img")
            posterUrl = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: imgTag?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                        ?: imgTag?.attr("src")
        }

        return when {
            href.contains("/movies/") -> {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(posterUrl)
                }
            }
            else -> {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(posterUrl)
                }
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page, headers = defaultHeaders).document

        val home = document.select("article.post, article.postEp").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }
    private suspend fun performSearch(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", headers = defaultHeaders).document
        return document.select("article.post").mapNotNull {
            it.toSearchResponse()
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val initialResults = performSearch(query)
        if (initialResults.isEmpty() && query.trim().equals("over", ignoreCase = true)) {
            return performSearch("مسلسل")
        }
        return initialResults
    }
    private fun resolveRealUrl(url: String): String {
        var currentUrl = url
        val urlMatch = Regex("[?&]url=([^&]+)").find(currentUrl)
        if (urlMatch != null) {
            try {
                var extractedUrl = URLDecoder.decode(urlMatch.groupValues[1], "UTF-8")
                if (!extractedUrl.startsWith("http")) {
                    val decodedBytes = Base64.decode(extractedUrl, Base64.DEFAULT)
                    extractedUrl = String(decodedBytes, Charsets.UTF_8).trim()
                }
                if (extractedUrl.startsWith("http")) {
                    currentUrl = extractedUrl
                }
            } catch (e: Exception) {}
        }
        val postMatch = Regex("[?&]post=([^&]+)").find(currentUrl)
        if (postMatch != null) {
            try {
                val encodedPost = URLDecoder.decode(postMatch.groupValues[1], "UTF-8")
                val jsonStr = String(Base64.decode(encodedPost, Base64.DEFAULT), Charsets.UTF_8)
                val backUrlMatch = Regex("\"backUrl\"\\s*:\\s*\"([^\"]+)\"").find(jsonStr)
                if (backUrlMatch != null) {
                    val backUrl = backUrlMatch.groupValues[1].replace("\\/", "/")
                    if (backUrl.startsWith("http")) {
                        currentUrl = backUrl
                    }
                }
            } catch (e: Exception) {}
        }

        return currentUrl
    }

    override suspend fun load(url: String): LoadResponse {
        val realUrl = resolveRealUrl(url)

        val document = app.get(realUrl, headers = defaultHeaders).document

        val seriesUrl = document.selectFirst("div.singleSeries div.info h1 a")?.attr("href")
        if (seriesUrl != null) {
            return load(seriesUrl)
        }

        val title = document.selectFirst("div.info h1")?.text()?.trim() ?: ""

        var poster = document.selectFirst("div.cover div.img")?.attr("style")
            ?.substringAfter("url(")?.substringBefore(")")
            ?.replace("\"", "")?.replace("'", "")?.trim()

        if (poster.isNullOrBlank()) {
            val imgTag = document.selectFirst("div.cover img")
            poster = imgTag?.attr("data-src") ?: imgTag?.attr("src")
        }

        val description = document.selectFirst("div.story")?.text()?.trim()

        if (realUrl.contains("/movies/")) {
            return newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        } else {
            val episodes = document.select("article.postEp").mapNotNull {
                val epUrl = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val epTitle = it.selectFirst("div.title")?.text()?.trim()
                val epNum = it.selectFirst("div.episodeNum span:last-child")?.text()?.toIntOrNull()

                var epPoster = it.selectFirst("div.imgSer")?.attr("style")
                    ?.substringAfter("url(")?.substringBefore(")")
                    ?.replace("\"", "")?.replace("'", "")?.trim()

                if (epPoster.isNullOrBlank()) {
                    val imgTag = it.selectFirst("img")
                    epPoster = imgTag?.attr("data-src") ?: imgTag?.attr("src")
                }

                newEpisode(epUrl) {
                    name = epTitle
                    episode = epNum
                    posterUrl = fixUrlNull(epPoster)
                }
            }.reversed()

            return newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = description
            }
        }
    }

    private suspend fun extractLinkFromObfuscatedPage(
        url: String,
        referer: String,
        logCallback: (String) -> Unit
    ): String? {
        val pageText = try {
            logCallback("Custom Extractor: Fetching page $url with referer $referer")
            app.get(url,).text
        } catch (e: Exception) {
            logCallback("Custom Extractor ERROR: Failed to fetch page $url. Exception: ${e.message}")
            return null
        }
        val evalRegex =
            Regex("eval\\s*\\(\\s*function\\s*\\(.*?\\)\\s*\\{.*?\\}\\s*\\((.*)\\)\\s*\\)")
        val evalMatch = evalRegex.find(pageText)
        if (evalMatch == null) {
            logCallback("Custom Extractor ERROR: evalRegex did not find a match.")
            return null
        }

        val paramsString = evalMatch.groupValues.getOrNull(1) ?: return null
        val paramsRegex =
            Regex("['\"](.*?)['\"]\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*['\"](.*?)['\"]\\.split\\s*\\(['\"]\\|['\"]\\)")
        val paramMatch = paramsRegex.find(paramsString)
        if (paramMatch == null) {
            logCallback("Custom Extractor ERROR: paramsRegex failed on: '${paramsString.take(100)}...'")
            return null
        }

        val (packedCode, baseStr, countStr, dictionaryStr) = paramMatch.destructured
        val base = baseStr.toInt()
        val count = countStr.toInt()
        val keywords = dictionaryStr.split('|')

        fun toBase(num: Int, radix: Int): String {
            val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % radix])
                n /= radix
            }
            return sb.reverse().toString()
        }

        fun deobfuscate(p: String, a: Int, c: Int, k: List<String>): String {
            val replaceMap = mutableMapOf<String, String>()
            for (i in 0 until c) {
                val keyword = k.getOrNull(i)
                if (!keyword.isNullOrEmpty()) {
                    replaceMap[toBase(i, a)] = keyword
                }
            }

            return Regex("\\b\\w+\\b").replace(p) { matchResult ->
                replaceMap[matchResult.value] ?: matchResult.value
            }
        }

        val deobfuscatedJs = deobfuscate(packedCode, base, count, keywords)
        logCallback("Custom Extractor: Deobfuscated JS start: ${deobfuscatedJs.take(120)}")

        val fileRegex = Regex("[\"']?file[\"']?\\s*:\\s*[\"']([^\"']+)[\"']")
        val fileMatch = fileRegex.find(deobfuscatedJs)
        if (fileMatch == null) {
            logCallback("Custom Extractor ERROR: fileRegex did not find a match.")
            return null
        }

        val finalUrl = fileMatch.groupValues[1]
        val cleanUrl = finalUrl.replace("\\/", "/")
        logCallback("Custom Extractor: Success! Found URL: $cleanUrl")
        return cleanUrl
    }

    private fun normalizeUrl(u: String?, base: String? = null): String? {
        val s = u?.trim().takeIf { !it.isNullOrEmpty() } ?: return null
        return when {
            s.startsWith("//") -> "https:$s"
            s.startsWith("http://") || s.startsWith("https://") -> s
            base != null -> try {
                java.net.URL(java.net.URL(base), s).toString()
            } catch (e: Exception) {
                s
            }

            else -> s
        }
    }


    private fun extractUrlFromCodeHtml(codeHtml: String?, base: String? = null): String? {
        if (codeHtml.isNullOrBlank()) return null
        val doc = Jsoup.parse(codeHtml, base ?: "")
        doc.selectFirst("iframe[src]")?.attr("abs:src")?.let { return normalizeUrl(it, base) }
        doc.selectFirst("source[src]")?.attr("abs:src")?.let { return normalizeUrl(it, base) }
        doc.selectFirst("a[href]")?.attr("abs:href")?.let { return normalizeUrl(it, base) }
        Regex("https?:\\/\\/[^\\s\"']+").find(codeHtml)?.value?.let {
            return normalizeUrl(
                it,
                base
            )
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = resolveRealUrl(data)
        val mainPage = try {
            app.get(pageUrl, headers = defaultHeaders).document
        } catch (e: Exception) {
            println("[GessehProvider] failed to load page: $pageUrl -> ${e.message}")
            return false
        }

        val rawExtractorHref = mainPage.selectFirst("a.fullscreen-clickable")?.attr("href")?.trim()
        if (rawExtractorHref.isNullOrBlank()) {
            println("[GessehProvider] extractor link not found on page")
            return false
        }

        var targetUrl = rawExtractorHref
        var playerReferer = "https://fashny.net/"
        val urlMatch = Regex("[?&]url=([^&]+)").find(rawExtractorHref)
        if (urlMatch != null) {
            try {
                var extracted = java.net.URLDecoder.decode(urlMatch.groupValues[1], "UTF-8")
                if (!extracted.startsWith("http")) {
                    val decodedBytes = android.util.Base64.decode(extracted, android.util.Base64.DEFAULT)
                    extracted = String(decodedBytes, Charsets.UTF_8).trim()
                }
                if (extracted.startsWith("http")) {
                    targetUrl = extracted
                    val uri = java.net.URI(rawExtractorHref)
                    playerReferer = "${uri.scheme}://${uri.host}/"
                }
            } catch (e: Exception) {}
        }

        val customHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36",
            "Referer" to playerReferer
        )

        data class ServerData(val name: String, val embedUrl: String)
        val serversToProcess = mutableSetOf<ServerData>()
        val postMatch = Regex("[?&]post=([^&]+)").find(targetUrl)
        if (postMatch != null) {
            try {
                val encodedJson = java.net.URLDecoder.decode(postMatch.groupValues[1], "UTF-8")
                val decodedBytes = android.util.Base64.decode(encodedJson, android.util.Base64.DEFAULT)
                val jsonString = String(decodedBytes, Charsets.UTF_8)
                val payload = parseJson<GessehPayload>(jsonString)

                println("[GessehProvider] --- Start JSON ID Extraction ---")
                payload.servers?.forEach { server ->
                    val sName = server.name ?: return@forEach
                    val sId = server.id ?: return@forEach

                    println("[GessehProvider] JSON Found -> Server: $sName, ID: $sId")

                    buildEmbedUrl(sName, sId)?.let { url ->
                        serversToProcess.add(ServerData(sName, url))
                    }
                }
                println("[GessehProvider] --- End JSON ID Extraction ---")
            } catch (e: Exception) {
                println("[GessehProvider] Failed to parse JSON: ${e.message}")
            }
        }
        try {
            val htmlPage = app.get(targetUrl, headers = customHeaders).document

            println("[GessehProvider] --- Start HTML ID Extraction ---")
            htmlPage.select("ul.serversList li").forEach { li ->
                val serverName = li.attr("data-name").trim().takeIf { it.isNotEmpty() } ?: li.text().trim()
                val serverId = li.attr("data-server").trim()
                var embedUrl: String? = null

                if (serverId.isEmpty()) {
                    val codeHtml = li.selectFirst("code")?.html()
                    embedUrl = extractUrlFromCodeHtml(codeHtml, targetUrl)

                    if (embedUrl.isNullOrBlank()) {
                        val a = li.selectFirst("a")
                        embedUrl = a?.attr("abs:href")?.takeIf { it.isNotBlank() } ?: a?.attr("href")
                    }
                } else {
                    embedUrl = buildEmbedUrl(serverName, serverId)
                }

                embedUrl = normalizeUrl(embedUrl)?.trim()
                if (!embedUrl.isNullOrBlank() && serversToProcess.none { it.embedUrl == embedUrl }) {
                    serversToProcess.add(ServerData(serverName, embedUrl))
                }
            }
            println("[GessehProvider] --- End HTML ID Extraction ---")
        } catch (e: Exception) {
            println("[GessehProvider] HTML fetching failed: ${e.message}")
        }
        coroutineScope {
            serversToProcess.map { server ->
                async {
                    processLink(
                        embedUrl = server.embedUrl,
                        serverName = server.name,
                        serverNameLower = server.name.lowercase(),
                        playerReferer = targetUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback
                    )
                }
            }.awaitAll()
        }

        return true
    }

    private fun buildEmbedUrl(serverName: String, serverId: String): String? {
        val lower = serverName.lowercase()
        return when {
            lower.contains("youtube_in") || lower.contains("youtube-in") -> "https://www.youtube.com/embed/$serverId"
            lower.contains("youtube") -> "https://www.youtube.com/embed/$serverId"
            lower == "express" -> serverId
            lower.contains("daily") || lower.contains("dailymotion") -> "https://www.dailymotion.com/embed/video/$serverId"
            lower.contains("facebook") -> "https://app.videas.fr/embed/media/$serverId"
            lower.contains("estream") -> "https://arabveturk.com/embed-$serverId.html"
            lower.contains("arab hd") || lower.contains("arabhd") -> "https://v.turkvearab.com/embed-$serverId.html"
            lower.contains("red hd") || lower.contains("redhd") -> "https://iplayerhls.com/e/$serverId"
            lower.contains("pro hd") || lower.contains("prohd") -> "https://w.larhu.com/play.php?id=$serverId"
            lower == "pro" -> "https://mdna.upns.online/#$serverId"
            lower.contains("ok") -> "https://ok.ru/videoembed/$serverId"
            lower.contains("box") -> "https://youdboox.com/embed-$serverId.html"
            lower.contains("now") -> "https://extreamnow.org/embed-$serverId.html"
            else -> null
        }
    }

    private suspend fun processLink(
        embedUrl: String,
        serverName: String,
        serverNameLower: String,
        playerReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val low = embedUrl.lowercase()
        if (low.contains("dailymotion.com")) {
            try {
                println("[GessehProvider] Sending to Custom Dailymotion Extractor: $embedUrl")
                QesehDailymotionExtractor().getUrl(embedUrl, null, subtitleCallback, callback)
            } catch (e: Exception) {
                println("[GessehProvider] Custom Dailymotion extractor failed: ${e.message}")
            }
            return
        }
        when {
            low.contains(".m3u8") || low.endsWith(".mp4") || low.contains(".mp4") -> {
                callback(newExtractorLink(this.name, serverName, embedUrl) {
                    this.referer = playerReferer
                    this.quality = getQualityFromName(embedUrl)
                    this.type = ExtractorLinkType.VIDEO
                })
            }
            embedUrl.contains("youtube.com") || embedUrl.contains("youtu.be") -> {
                callback(newExtractorLink(this.name, "YouTube", embedUrl) {
                    this.referer = playerReferer
                    this.quality = getQualityFromName(embedUrl)
                    this.type = ExtractorLinkType.VIDEO
                })
            }
            serverNameLower.contains("arab") || serverNameLower.contains("arabhd") || serverNameLower.contains("estream") || serverNameLower.contains("turk") || serverNameLower.contains("prohd") -> {
                try {
                    val headers = mapOf(
                        "Referer" to embedUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                    )
                    val extracted = extractLinkFromObfuscatedPage(embedUrl, playerReferer) { s -> println(s) }
                    if (!extracted.isNullOrBlank()) {
                        callback(newExtractorLink(source = this.name, name = serverName, url = extracted) {
                            this.headers = headers
                            this.quality = getQualityFromName(extracted)
                        })
                        return
                    }
                } catch (e: Exception) {}
                try { loadExtractor(embedUrl, playerReferer, subtitleCallback, callback) }
                catch (e: Exception) {
                    callback(newExtractorLink(this.name, serverName, embedUrl) {
                        this.referer = playerReferer
                        this.quality = getQualityFromName(embedUrl)
                    })
                }
            }
            else -> {
                try {
                    loadExtractor(embedUrl, playerReferer, subtitleCallback, callback)
                } catch (e: Exception) {
                    callback(newExtractorLink(this.name, serverName, embedUrl) {
                        this.referer = playerReferer
                        this.quality = getQualityFromName(embedUrl)
                    })
                }
            }
        }
    }

    class QesehDailymotionExtractor : ExtractorApi() {
        override val mainUrl = "https://www.dailymotion.com"
        override val name = "Dailymotion (Qeseh)"
        override val requiresReferer = true
        private val baseUrl = "https://www.dailymotion.com"
        private val videoIdRegex = "^[kx][a-zA-Z0-9]+$".toRegex()

        override suspend fun getUrl(
            url: String,
            referer: String?,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            val embedUrl = getEmbedUrl(url) ?: return
            val id = getVideoId(embedUrl) ?: return
            val metaDataUrl = "$baseUrl/player/metadata/video/$id"

            val response = app.get(metaDataUrl, referer = "https://qeseh.net/").text
            val meta = parseJson<MetaData>(response)

            meta.qualities?.get("auto")?.forEach { quality ->
                val videoUrl = quality.url
                if (!videoUrl.isNullOrEmpty() && videoUrl.contains(".m3u8")) {
                    generateM3u8(this.name, videoUrl, mainUrl).forEach(callback)
                }
            }

            meta.subtitles?.data?.forEach { (_, subData) ->
                subData.urls.forEach { subUrl ->
                    subtitleCallback(
                        newSubtitleFile(subData.label, subUrl)
                    )
                }
            }
        }

        private fun getEmbedUrl(url: String): String? {
            if (url.contains("/embed/") || url.contains("/video/")) return url.substringBefore("?")
            if (url.contains("geo.dailymotion.com")) {
                val videoId = url.substringAfter("video=")
                return "$baseUrl/embed/video/$videoId"
            }
            return null
        }

        private fun getVideoId(url: String): String? {
            val path = URI(url).path
            val id = path.substringAfter("/video/")
            return if (id.matches(videoIdRegex)) id else null
        }

        data class MetaData(
            @JsonProperty("qualities") val qualities: Map<String, List<Quality>>?,
            @JsonProperty("subtitles") val subtitles: SubtitlesWrapper?
        )
        data class Quality(
            @JsonProperty("type") val type: String?,
            @JsonProperty("url") val url: String?
        )
        data class SubtitlesWrapper(
            @JsonProperty("enable") val enable: Boolean,
            @JsonProperty("data") val data: Map<String, SubtitleData>?
        )
        data class SubtitleData(
            @JsonProperty("label") val label: String,
            @JsonProperty("urls") val urls: List<String>
        )
    }
}
