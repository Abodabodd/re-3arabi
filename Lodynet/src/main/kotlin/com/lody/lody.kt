package com.lagradost.cloudstream3.plugins

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.delay
private fun encodeUri(url: String): String {
    return try {
        url.toCharArray().joinToString("") { char ->
            if (char.code <= 127) char.toString() else URLEncoder.encode(char.toString(), "UTF-8")
        }
    } catch (e: Exception) {
        ""
    }
}

class LodyNet : MainAPI() {
    override var mainUrl = "https://lodynet.watch"
    override var name = "LodyNet"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val searchApi = "$mainUrl/wp-content/themes/Lodynet2020/Api/RequestSearch.php"

    private fun decodeBase64(input: String): String {
        if (input.isBlank()) return ""
        return try {
            val decodedBytes = android.util.Base64.decode(input, android.util.Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun Element.getPosterUrl(): String? {
        val dataSrc = this.attr("data-src")
        if (!dataSrc.isNullOrBlank()) return dataSrc

        val style = this.attr("style") ?: ""
        return Regex("""url\s*\(\s*['"]?([^'")]*)['"]?\s*\)""").find(style)?.groupValues?.get(1)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        doc.select(".IndexNewlyField").forEach { container ->
            val title = container.select(".IndexFieldTitle a").text().trim()
            val movies = container.select(".ItemNewlyField").mapNotNull { item ->
                val aTag = item.selectFirst("a") ?: return@mapNotNull null
                val name = item.select(".NewlyTitle").text().trim()
                val link = fixUrl(aTag.attr("href"))
                val poster = item.selectFirst(".NewlyCover")?.getPosterUrl()

                newMovieSearchResponse(name, link, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
            if (movies.isNotEmpty()) {
                homePageList.add(HomePageList(title, movies))
            }
        }

        val pinnedMovies = doc.select("#IndexPinned .ItemPinnedField").mapNotNull { item ->
            val aTag = item.selectFirst("a") ?: return@mapNotNull null
            val name = item.select(".NewlyTitle").text().trim()
            val link = fixUrl(aTag.attr("href"))
            val poster = item.selectFirst(".NewlyCover")?.getPosterUrl()

            newMovieSearchResponse(name, link, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        if (pinnedMovies.isNotEmpty()) {
            homePageList.add(0, HomePageList("مثبتات", pinnedMovies))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$searchApi?value=$encodedQuery"
        val response = app.get(url).text

        return try {
            val jsonList = tryParseJson<List<Any>>(response)
            if (jsonList == null || jsonList.size < 2) return emptyList()

            val rawResults = jsonList[1]
            val mapper = com.fasterxml.jackson.databind.ObjectMapper()
            val jsonString = mapper.writeValueAsString(rawResults)
            val results = parseJson<List<SearchResultJson>>(jsonString)

            results.amap { item ->
                val fullLink = fixUrl(item.url)
                val doc = app.get(fullLink).document

                val realPoster = doc.selectFirst("#CoverSingle")?.getPosterUrl()
                    ?: doc.selectFirst(".ItemNewly .NewlyCover")?.getPosterUrl()
                    ?: item.cover
                    ?: ""

                newMovieSearchResponse(item.title ?: "", fullLink, TvType.TvSeries) {
                    this.posterUrl = realPoster
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var doc = app.get(url).document

        val isCategoryPage = doc.select("#ListEpisodes").isEmpty() && doc.select("#AreaNewly .ItemNewly a").isNotEmpty()

        if (isCategoryPage) {
            val firstEpLink = doc.select("#AreaNewly .ItemNewly a").firstOrNull()?.attr("href")
            if (firstEpLink != null) {
                val newDoc = app.get(fixUrl(firstEpLink)).document
                if (newDoc.select("#ListEpisodes").isNotEmpty() || newDoc.select("#AreaWetch").isNotEmpty()) {
                    doc = newDoc
                }
            }
        }

        var title = doc.select("h1#PrimaryTitle").text().trim()
        val episodeTitleRegex = Regex("""\s*[-]*\s*(\d+|\d+\s*|)\s*(الحلقة|حلقة)\s*\d+.*""")
        title = title.replace(episodeTitleRegex, "").trim()

        val description = doc.select("#ContentDetails p").text().trim()

        val poster = doc.selectFirst("#CoverSingle")?.getPosterUrl()
            ?: doc.selectFirst(".ItemNewly .NewlyCover")?.getPosterUrl()
            ?: ""

        val ribbonTags = doc.select(".NewlyRibbon").map { it.text().trim() }
        val categoryTags = doc.select("#ListCategories li a").map { it.text().trim() }
        val tags = (categoryTags + ribbonTags).distinct()

        val year = doc.select("#DateDetails").attr("content").take(4).toIntOrNull()
        val episodes = ArrayList<Episode>()
        val sliderElements = doc.select("#ListEpisodes .ItemEpisode, #ListEpisodes .CurrentEpisode")

        if (sliderElements.isNotEmpty()) {
            sliderElements.forEach { element ->
                val epLink = fixUrl(element.attr("href"))
                val epName = element.text().trim()

                val epNum = element.attr("id").replace("Ep", "").toIntOrNull()
                    ?: Regex("(\\d+)").find(epName)?.groupValues?.get(1)?.toIntOrNull()

                episodes.add(newEpisode(epLink) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = doc.select("#RelatedNewly .ItemNewly").mapNotNull {
                    val recName = it.select(".NewlyTitle").text()
                    val recLink = fixUrl(it.select("a").attr("href"))
                    val recPoster = it.selectFirst(".NewlyCover")?.getPosterUrl()
                    newTvSeriesSearchResponse(recName, recLink, TvType.TvSeries) { this.posterUrl = recPoster }
                }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.tags = tags
                this.recommendations = doc.select("#RelatedNewly .ItemNewly").mapNotNull {
                    val recName = it.select(".NewlyTitle").text()
                    val recLink = fixUrl(it.select("a").attr("href"))
                    val recPoster = it.selectFirst(".NewlyCover")?.getPosterUrl()
                    newMovieSearchResponse(recName, recLink, TvType.Movie) { this.posterUrl = recPoster }
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data).text
        val doc = org.jsoup.Jsoup.parse(pageHtml)
        val safeData = encodeUri(data) // ترميز وحماية الرابط لاستخدامه كـ Referer آمن

        val currentBaseUrl = try {
            val uri = java.net.URI(data)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            mainUrl
        }

        val dynamicEmbedApi = "$currentBaseUrl/wp-content/themes/Lodynet2020/Api/RequestServerEmbed.php"

        val tokenVidloMatch = Regex(""""TokenVidlo"\s*:\s*"([^"]+)"""").find(pageHtml)
        val tokenVidlo = tokenVidloMatch?.groupValues?.get(1) ?: ""

        val scriptContent = doc.select("script").joinToString("\n") { it.data() }
        val postId = Regex("""SeoData\.Id\s*=\s*(\d+)""").find(scriptContent)?.groupValues?.get(1)
            ?: return false

        val serversJsonString = Regex("""ServersWatch\s*:\s*(\[\s*\{.*?\}\s*\])""").find(scriptContent)?.groupValues?.get(1)
        val parsedServers = tryParseJson<List<ServerJson>>(serversJsonString)

        val serversList = ArrayList<ServerJson>()
        if (parsedServers != null && parsedServers.isNotEmpty()) {
            serversList.addAll(parsedServers)
        } else {
            doc.select("#AllServerWatch button").forEach { btn ->
                val serverId = Regex("""SwitchServer\(this,\s*(\d+)""")
                    .find(btn.attr("onclick"))?.groupValues?.get(1)?.toIntOrNull()
                if (serverId != null) {
                    serversList.add(ServerJson(btn.text().trim(), "", serverId, true))
                }
            }
        }

        serversList.amap { server ->
            try {
                var embedUrl = ""

                if (!server.embed.isNullOrBlank()) {
                    embedUrl = decodeBase64(server.embed)
                }
                else if (server.encrypted == true && server.id != null) {
                    val formBody = mapOf(
                        "PostID" to postId,
                        "ServerID" to server.id.toString()
                    )

                    val apiResponse = app.post(
                        dynamicEmbedApi,
                        data = formBody,
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to safeData, // تم التعديل إلى رابط آمن
                            "Origin" to currentBaseUrl,
                            "Accept" to "*/*",
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                        )
                    ).text

                    embedUrl = apiResponse.trim().replace("\"", "").replace("\\/", "/")
                }

                if (embedUrl.startsWith("//")) embedUrl = "https:$embedUrl"
                if (!embedUrl.startsWith("http")) return@amap

                val isVidlo = server.name?.contains("vidlo", ignoreCase = true) == true ||
                        server.name?.contains("vid lo", ignoreCase = true) == true ||
                        embedUrl.contains("vidlo", ignoreCase = true)

                if (isVidlo) {
                    val vidloUrlWithToken = if (tokenVidlo.isNotEmpty()) {
                        if (embedUrl.contains("?")) "$embedUrl&${tokenVidlo.removePrefix("?")}"
                        else "$embedUrl$tokenVidlo"
                    } else {
                        embedUrl
                    }

                    try {
                        val vidloHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36",
                            "Referer" to "$mainUrl/",
                            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                        )

                        val vidloRes = app.get(vidloUrlWithToken, headers = vidloHeaders).text

                        val sourcesMatch = Regex("""sources\s*:\s*\[(.*?)\]""", setOf(RegexOption.DOT_MATCHES_ALL)).find(vidloRes)
                        if (sourcesMatch != null) {
                            val sources = sourcesMatch.groupValues[1]
                            val files = Regex("""file\s*:\s*"([^"]+)"""").findAll(sources).map { it.groupValues[1] }.toList()
                            val labels = Regex("""label\s*:\s*"([^"]+)"""").findAll(sources).map { it.groupValues[1] }.toList()

                            var qualityIndex = 0
                            for (file in files) {
                                if (file.endsWith(".m3u8")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "LodyNet",
                                            name = "Vidlo HLS",
                                            url = file,
                                        ) {
                                            referer = "$mainUrl/"
                                            quality = Qualities.Unknown.value
                                        }
                                    )
                                } else {
                                    val label = if (qualityIndex < labels.size) labels[qualityIndex] else "Unknown"
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "LodyNet",
                                            name = "Vidlo $label",
                                            url = file,
                                        ) {
                                            referer = "$mainUrl/"
                                            quality = getQualityFromName(label)
                                        }
                                    )
                                    qualityIndex++
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return@amap
                }

                if (embedUrl.contains("lodynet") && embedUrl.contains("/embed")) {
                    try {
                        val embedDoc = app.get(embedUrl, headers = mapOf("Referer" to safeData)).document // تم التعديل إلى رابط آمن
                        val realSource = embedDoc.select("iframe").attr("src")
                        if (realSource.isNotEmpty()) embedUrl = realSource
                    } catch (_: Exception) {}
                }

                try {
                    loadExtractor(embedUrl, safeData, subtitleCallback, callback) // تم التعديل إلى رابط آمن
                } catch (_: Exception) {}

                try {
                    SmartPlayer.extract(
                        playerUrl = embedUrl,
                        referer = safeData, // تم التعديل إلى رابط آمن
                        qualityInt = Qualities.Unknown.value,
                        displayName = "LodyNet - ${server.name ?: "SmartPlayer"}",
                        callback = callback
                    )
                } catch (_: Exception) {}

            } catch (_: Exception) {}
        }

        return true
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
                    val decryptedBytes = cipher.doFinal(encryptedBytes)
                    val decryptedText = String(decryptedBytes, Charsets.UTF_8)

                    if (decryptedText.trim().startsWith("{")) {
                        val data = tryParseJson<StrpResponse>(decryptedText)
                        val source = data?.source
                        if (!source.isNullOrBlank()) {
                            val cleanSource = if (source.contains("://") && !source.startsWith("http")) {
                                "https" + source.substring(source.indexOf("://"))
                            } else {
                                source
                            }
                            return cleanSource
                        }
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

        suspend fun extract(playerUrl: String, referer: String, qualityInt: Int, displayName: String, callback: (ExtractorLink) -> Unit) {
            val MAX_RETRIES = 3
            val safeReferer = encodeUri(referer) // تنظيف رابط الـ Referer قبل إرساله في الهيدرز

            try {
                val uri = URI(if (playerUrl.startsWith("//")) "https:$playerUrl" else playerUrl)
                val domain = uri.host ?: return

                val videoId = when {
                    playerUrl.contains("#") -> playerUrl.substringAfterLast("#").substringBefore("&")
                    playerUrl.contains("id=") -> playerUrl.substringAfter("id=").substringBefore("&")
                    else -> return
                }

                val apiUrl = "https://$domain/api/v1/video?id=$videoId"

                val headers = mapOf(
                    "Referer" to safeReferer, // تم الاستبدال بالرابط الآمن
                    "Origin" to "https://$domain",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                    "Accept" to "application/json, text/plain, */*"
                )

                for (attempt in 1..MAX_RETRIES) {
                    val res = app.get(apiUrl, headers = headers)

                    if (res.isSuccessful && res.text.isNotBlank()) {
                        val rawM3u8 = smartDecrypt(res.text, domain, videoId)

                        if (!rawM3u8.isNullOrBlank()) {
                            val masterM3u8 = sanitizeUrl(rawM3u8)
                            val finalM3u8 = getFinalM3u8(masterM3u8, safeReferer) // تمرير الـ Referer الآمن أيضاً هنا

                            callback.invoke(
                                newExtractorLink(
                                    source = "Share VIP",
                                    name = displayName,
                                    url = finalM3u8 ?: masterM3u8,
                                ) {
                                    this.referer = safeReferer // وضع الـ Referer الآمن في الرابط المستخرج
                                    this.quality = qualityInt
                                }
                            )
                            return
                        }
                    }
                    delay(1000)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        private suspend fun getFinalM3u8(masterUrl: String, referer: String): String? {
            val safeReferer = encodeUri(referer)
            return try {
                val playlistContent = app.get(masterUrl, headers = mapOf("Referer" to safeReferer)).text
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

        data class StrpResponse(
            @JsonProperty("source") val source: String?,
            @JsonProperty("cf") val cf: String?
        )
    }

    data class InertiaResponse(
        @JsonProperty("props") val props: InertiaProps
    )
    data class InertiaProps(
        @JsonProperty("streams") val streams: StreamsData?
    )
    data class StreamsData(
        @JsonProperty("data") val data: List<StreamItem>?
    )
    data class StreamItem(
        @JsonProperty("resolution") val resolution: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("mirrors") val mirrors: List<MirrorItem>?
    )
    data class MirrorItem(
        @JsonProperty("link") val link: String?,
        @JsonProperty("symbol") val symbol: String?,
        @JsonProperty("driver") val driver: String?
    )

    data class SearchResultJson(
        @JsonProperty("Title") val title: String?,
        @JsonProperty("Url") val url: String,
        @JsonProperty("Category") val category: String?,
        @JsonProperty("Cover") val cover: String?
    )

    data class ServerJson(
        @JsonProperty("Name") val name: String?,
        @JsonProperty("Embed") val embed: String?,
        @JsonProperty("Id") val id: Int?,
        @JsonProperty("Encrypted") val encrypted: Boolean?
    )
}