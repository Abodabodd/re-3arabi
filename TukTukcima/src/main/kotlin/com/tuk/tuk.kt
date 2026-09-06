package com.tuk

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class TukTukHd : MainAPI() {
    override var mainUrl = "https://tuktukhd.com"
    override var name = "TukTukcima"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val mainPage = mainPageOf(
        "$mainUrl/recent/page/" to "المضاف حديثاً",
        "$mainUrl/category/movies-2/page/" to "أحدث الأفلام",
        "$mainUrl/category/series-1/page/" to "أحدث الحلقات",
        "$mainUrl/category/movies-2/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d9%85%d8%af%d8%a8%d9%84%d8%ac%d8%a9/page/" to "أفلام مدبلجة"
    )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("li.Small--Box, div.Block--Item").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }
    private fun toSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a") ?: return null
        val title = element.selectFirst(".title")?.text() ?: linkTag.attr("title")
        val href = fixUrl(linkTag.attr("href"))
        val imgTag = element.selectFirst("img")
        val posterUrl = imgTag?.attr("data-src").takeIf { !it.isNullOrEmpty() }
            ?: imgTag?.attr("src")
        val isMovie =
            !title.contains("مسلسل") && !title.contains("حلقة") && !href.contains("series")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }
        override suspend fun search(query: String): List<SearchResponse> {
            return search(query, 1)?.items ?: emptyList()
        }

    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {

        val encoded = URLEncoder.encode(query, "utf-8")
        val page1 = (page - 1) * 2 + 1
        val page2 = page1 + 1

        val urls = listOf(
            "$mainUrl/?s=$encoded&page=$page1",
            "$mainUrl/?s=$encoded&page=$page2"
        )

        val results = urls.map { url ->
            async {
                runCatching {
                    val doc = app.get(url).document
                    doc.select("div.Block--Item, li.Small--Box")
                        .mapNotNull { toSearchResult(it) }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten()
        val cleaned = mergeSimilarResults(results)

        newSearchResponseList(cleaned, cleaned.isNotEmpty())
    }
    private fun mergeSimilarResults(list: List<SearchResponse>): List<SearchResponse> {

        val grouped = mutableMapOf<String, SearchResponse>()

        for (item in list) {

            val title = item.name
            if (title.contains("فيلم", true) ||
                title.contains("فلم", true) ||
                title.contains("movie", true)
            ) {
                grouped[title] = item
                continue
            }
            val normalized = title
                .replace(Regex("""الحلقة\s*\d+"""), "")
                .replace(Regex("""episode\s*\d+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\d+$"""), "")
                .trim()
            if (!grouped.containsKey(normalized)) {
                grouped[normalized] = item
            }
        }

        return grouped.values.toList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val fullTitle = doc.selectFirst("h1.post-title a")?.text() ?: doc.selectFirst("h1")?.text() ?: "Unknown"
        val cleanTitle = fullTitle.replace(Regex("""\s*(الحلقة\s*\d+|مترجم|مدبلج).*"""), "").trim()

        val desc = doc.select(".story p").text()
        val poster = doc.selectFirst(".MainSingle .left .image img")?.attr("src")
        val bgPoster = doc.selectFirst(".homepage__bg")?.attr("style")?.substringAfter("url(")?.substringBefore(")") ?: poster

        val year = doc.select(".RightTaxContent a[href*='release-year']").text().filter { it.isDigit() }.toIntOrNull()
        val ratingText = doc.select(".imdbS strong").text()
        val scoreValue = ratingText.toDoubleOrNull()?.times(1000)?.toInt()
        val isSeries = doc.select(".allepcont, .allseasonss").isNotEmpty()

        if (isSeries) {
            val episodesList = ArrayList<Episode>()
            val seasonElements = doc.select(".allseasonss .Block--Item a")

            if (seasonElements.isNotEmpty()) {
                seasonElements.amap { seasonEl ->
                    val seasonUrl = fixUrl(seasonEl.attr("href"))
                    val seasonName = seasonEl.select("h3").text()
                    val seasonNum = seasonName.filter { it.isDigit() }.toIntOrNull() ?: 1

                    val seasonDoc = app.get(seasonUrl).document
                    seasonDoc.select(".allepcont a").forEach { ep ->
                        val epTitle = ep.select(".ep-info h2").text()
                        val epHref = fixUrl(ep.attr("href"))
                        val epNum = ep.select(".epnum").text().filter { it.isDigit() }.toIntOrNull()
                        val epThumb = ep.select("img").attr("data-src")
                            .ifEmpty { ep.select("img").attr("src") }

                        episodesList.add(
                            newEpisode(epHref) {
                                this.name = epTitle
                                episode = epNum
                                season = seasonNum
                                posterUrl = epThumb
                            }
                        )
                    }
                }
            } else {
                doc.select(".allepcont a").forEach { ep ->
                    val epTitle = ep.select(".ep-info h2").text()
                    val epHref = fixUrl(ep.attr("href"))
                    val epNum = ep.select(".epnum").text().filter { it.isDigit() }.toIntOrNull()
                    val epThumb = ep.select("img").attr("data-src").ifEmpty { ep.select("img").attr("src") }

                    episodesList.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = epThumb
                        }
                    )
                }
            }
            val sortedEpisodes = episodesList.sortedWith(compareBy({ it.season }, { it.episode }))

            return newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = desc
                this.year = year
                this.score = Score.from10(scoreValue)
            }

        } else {
            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = desc
                this.year = year
                this.score = Score.from10(scoreValue)
            }
        }
    }

    private fun extractQuality(resolution: String?): Int {
        val cleanRes = resolution?.lowercase()?.trim() ?: return Qualities.Unknown.value
        if (cleanRes.contains("x")) {
            val height = cleanRes.substringAfter("x").filter { it.isDigit() }.toIntOrNull()
            if (height != null && height > 0) return height
        }
        val digits = cleanRes.filter { it.isDigit() }.toIntOrNull()
        if (digits != null && digits > 0) {
            return digits
        }
        return when {
            cleanRes.contains("4k") || cleanRes.contains("2160") -> 2160
            cleanRes.contains("2k") || cleanRes.contains("1440") -> 1440
            cleanRes.contains("fhd") || cleanRes.contains("1080") -> 1080
            cleanRes.contains("hd") || cleanRes.contains("720") -> 720
            cleanRes.contains("sd") || cleanRes.contains("480") -> 480
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeCrypt = doc.select("iframe#main-video-frame").attr("data-crypt")

        if (iframeCrypt.isEmpty()) return false

        try {
            val playerUrl = String(Base64.decode(iframeCrypt, Base64.DEFAULT))

            val initialResponse = app.get(
                playerUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36",
                    "Referer" to mainUrl
                )
            )

            val cookies = initialResponse.cookies
            val xsrfToken = cookies["XSRF-TOKEN"]?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            val version = Regex(""""version":"([^"]+)"""").find(initialResponse.text)?.groupValues?.get(1)

            if (xsrfToken != null && version != null) {
                val inertiaHeaders = mapOf(
                    "X-XSRF-TOKEN" to xsrfToken,
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to "files/mirror/video",
                    "X-Inertia-Partial-Data" to "streams",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to playerUrl,
                    "Content-Type" to "application/json"
                )

                val inertiaResponse = app.get(
                    playerUrl,
                    headers = inertiaHeaders,
                    cookies = cookies
                ).parsed<InertiaResponse>()

                val streams = inertiaResponse.props.streams?.data ?: emptyList()
                val allMirrorsWithQuality = streams.flatMap { streamItem ->
                    val qualityInt = extractQuality(streamItem.resolution ?: streamItem.label)
                    streamItem.mirrors?.map { mirror ->
                        Pair(mirror, qualityInt)
                    } ?: emptyList()
                }

                allMirrorsWithQuality.amap { (mirror, qualityInt) ->
                    val rawLink = mirror.link ?: return@amap
                    val link = if (rawLink.startsWith("//")) "https:$rawLink" else rawLink
                    val displayName = mirror.symbol ?: mirror.driver ?: "Server"

                    when {
                        mirror.symbol?.equals("TukTukVIP", ignoreCase = true) == true -> {
                            extractTukTukVip(link, qualityInt, displayName, callback)
                        }

                        mirror.driver?.equals("rpmshare", ignoreCase = true) == true -> {
                            SmartPlayer.extract(link, qualityInt, displayName, callback)
                        }

                        else -> {
                            loadExtractor(link, subtitleCallback) { extractorLink ->
                                if (extractorLink.quality == Qualities.Unknown.value) {
                                    extractorLink.quality = qualityInt
                                }
                                callback(extractorLink)
                            }
                        }
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }

    private suspend fun extractTukTukVip(url: String, qualityInt: Int, displayName: String, callback: (ExtractorLink) -> Unit) {
        try {
            val refererUrl = try {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
                url
            }

            val originUrl = refererUrl.removeSuffix("/")

            val response = app.get(url, referer = refererUrl).text
            val unpackedHtml = JsUnpacker(response).unpack() ?: response

            val regex = Regex("""https://[^"']+?/hls\d+/[^"']+?/master\.(?:txt|m3u8)(?:\?[^"']*)?""")
            val matches = regex.findAll(unpackedHtml).toList()

            matches.forEach { match ->
                val link = match.value
                val isM3u8 = link.contains(".m3u8") || link.contains(".txt")

                callback.invoke(
                    newExtractorLink(
                        source = "TukTukVIP",
                        name = displayName, // هنا نضع الاسم الجديد المدمج بالجودة
                        url = link,
                    ) {
                        this.referer = refererUrl
                        this.quality = qualityInt

                        headers = mapOf(
                            "sec-ch-ua-platform" to "\"Android\"",
                            "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
                            "sec-ch-ua-mobile" to "?1",
                            "accept" to "*/*",
                            "origin" to originUrl,
                            "sec-fetch-site" to "cross-site",
                            "sec-fetch-mode" to "cors",
                            "sec-fetch-dest" to "empty"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

        suspend fun extract(playerUrl: String, qualityInt: Int, displayName: String, callback: (ExtractorLink) -> Unit) {
            val MAX_RETRIES = 3

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
                    "Referer" to "https://$domain/",
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
                            val finalM3u8 = getFinalM3u8(masterM3u8, "https://$domain/")

                            callback.invoke(
                                newExtractorLink(
                                    source = "Share VIP",
                                    name = displayName, // هنا نضع الاسم الجديد المدمج بالجودة لـ rpmshare
                                    url = finalM3u8 ?: masterM3u8,
                                ) {
                                    referer = "https://$domain/"
                                    quality = qualityInt
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
}