package com.tuniflix
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.delay
import java.net.URI

class Tuniflix : MainAPI() {
    override var mainUrl = "https://tuniflix.site"
    override var name = "Tuni flix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/movies/page/" to "أفلام",
        "$mainUrl/series/page/" to "مسلسلات",
        "$mainUrl/tg/tunisian-movies/page/" to "أفلام تونسية",
        "$mainUrl/tg/arabic-movies/page/" to "أفلام عربية",
        "$mainUrl/tg/turkish-series/page/" to "مسلسلات تركية"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        val home = document.select("article.TPost.B").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        val title = this.selectFirst(".Title")?.text() ?: return null

        var posterUrl = this.selectFirst(".Image img")?.let { img ->
            img.attr("data-src") ?: img.attr("src")
        }
        if (posterUrl?.startsWith("//") == true) {
            posterUrl = "https:$posterUrl"
        }

        return if (href.contains("/serie/") || this.select(".TpTv").text().contains("Serie", true)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("article.TPost.B").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.Title")?.text()?.trim() ?: "Unknown"
        val desc = document.selectFirst(".Description p")?.text()?.trim()

        var poster = document.selectFirst(".Image img.TPostBg")?.attr("src")
            ?: document.selectFirst(".Image img")?.attr("src")
        if (poster?.startsWith("//") == true) poster = "https:$poster"

        val year = document.selectFirst(".Date")?.text()?.toIntOrNull()
        val tags = document.select(".Tags a").map { it.text() }

        val isSeries = url.contains("/serie/") || document.select(".SeasonBx").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonLinks = document.select(".SeasonBx .Title a").map { it.attr("href") }

            seasonLinks.amap { seasonUrl ->
                try {
                    val seasonDoc = app.get(seasonUrl).document
                    val seasonTitle = seasonDoc.selectFirst("h1.Title")?.text() ?: ""
                    val seasonNum = Regex("Season\\s*(\\d+)").find(seasonTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                    seasonDoc.select(".TPTblCn table tr").forEach { tr ->
                        val epLink = tr.selectFirst("a")?.attr("href") ?: return@forEach
                        val epName = tr.selectFirst(".MvTbTtl a")?.text() ?: "Episode"
                        val epNum = tr.selectFirst(".Num")?.text()?.toIntOrNull()

                        episodes.add(newEpisode(epLink) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = poster
                        })
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = desc
                this.tags = tags
            }
        }
    }
    private suspend fun deepSearchIframes(url: String, depth: Int = 0, visited: MutableSet<String> = mutableSetOf()): List<String> {
        if (depth > 3 || !visited.add(url)) return emptyList()
        val foundLinks = mutableListOf<String>()

        try {
            val document = app.get(url, referer = mainUrl).document
            document.select("iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src") ?: iframe.attr("data-src"))
                if (src.isBlank()) return@forEach
                if (src.contains(mainUrl) || src.contains("trembed") || src.contains("trid")) {
                    foundLinks.addAll(deepSearchIframes(src, depth + 1, visited))
                } else {
                    foundLinks.add(src)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return foundLinks.distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val allIframes = deepSearchIframes(data)

        allIframes.forEach { playerUrl ->
            if (playerUrl.contains("#") || playerUrl.contains("id=")) {
                SmartPlayer.extract(playerUrl, callback)
            } else {
                loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return mainUrl + url
        return url
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
                        val data = AppUtils.parseJson<StrpResponse>(decryptedText)
                        if (!data.source.isNullOrBlank()) return data.source
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

        suspend fun extract(playerUrl: String, callback: (ExtractorLink) -> Unit) {
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
                                    "Tuniflix",
                                    "Tuniflix",
                                    finalM3u8 ?: masterM3u8,
                                ) {
                                    referer = "https://$domain/"
                                    quality = Qualities.Unknown.value
                                }
                            )
                            return // نجح الاستخراج، نخرج من الدالة
                        }
                    }
                    delay(1000) // توقف لثانية قبل إعادة المحاولة
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
}