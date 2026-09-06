package com.syrialive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import android.util.Base64
import org.jsoup.nodes.Element

class SyriaLiveProvider : MainAPI() {
    override var mainUrl = "https://www.syrlive.com"
    override var name = "SyriaLive"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie)

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (!url.startsWith("http")) return "$mainUrl$url"
        return url
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("https://d.syrlive.com/").document
        val homePageList = mutableListOf<HomePageList>()
        val matches = doc.select(".match-container").mapNotNull { element ->
            val rightTeam = element.selectFirst(".right-team .team-name")?.text() ?: return@mapNotNull null
            val leftTeam = element.selectFirst(".left-team .team-name")?.text() ?: return@mapNotNull null
            val time = element.selectFirst(".match-time")?.text() ?: ""
            val result = element.selectFirst(".result")?.text() ?: "VS"
            val status = element.selectFirst(".date")?.text() ?: "" // "جارية الآن" أو "انتهت"

            val title = "$rightTeam $result $leftTeam"
            val href = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val poster = element.selectFirst(".right-team img")?.attr("data-src")
                ?: element.selectFirst(".right-team img")?.attr("src")

            newLiveSearchResponse(title, fixUrl(href), TvType.Live) {
                this.posterUrl = fixUrl(poster ?: "")
            }
        }

        if (matches.isNotEmpty()) {
            homePageList.add(HomePageList("مباريات اليوم", matches, isHorizontalImages = true))
        }
        val news = doc.select(".AY-PItem").mapNotNull { element ->
            val titleEl = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
            val title = titleEl.text()
            val href = titleEl.attr("href")
            val poster = element.selectFirst("img")?.attr("data-src")
                ?: element.selectFirst("img")?.attr("src")

            newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
                this.posterUrl = fixUrl(poster ?: "")
            }
        }

        if (news.isNotEmpty()) {
            homePageList.add(HomePageList("آخر الأخبار", news))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document

        return doc.select(".AY-PItem").mapNotNull { element ->
            val titleEl = element.selectFirst(".AY-PostTitle a") ?: return@mapNotNull null
            newMovieSearchResponse(titleEl.text(), fixUrl(titleEl.attr("href")), TvType.Movie) {
                this.posterUrl = fixUrl(element.selectFirst("img")?.attr("data-src") ?: "")
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst(".EntryTitle")?.text() ?: "No Title"
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".teamlogo")?.attr("data-src")
        val descBuilder = StringBuilder()
        val tableInfo = doc.select(".AY-MatchInfo table tr")
        if (tableInfo.isNotEmpty()) {
            tableInfo.forEach { row ->
                val key = row.select("th").text()
                val value = row.select("td").text()
                if (key.isNotBlank() && value.isNotBlank()) {
                    descBuilder.append("$key: $value\n")
                }
            }
        } else {
            descBuilder.append(doc.select(".entry-content p").text())
        }

        val type = if (url.contains("/matches/") || tableInfo.isNotEmpty()) TvType.Live else TvType.Movie

        return newMovieLoadResponse(title, url, type, url) {
            this.posterUrl = fixUrl(poster ?: "")
            this.plot = descBuilder.toString()
            this.tags = tableInfo.map { it.select("td").text() } // إضافة معلومات المباراة كوسوم
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        try {
            val browserUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            val mainHeaders = mapOf(
                "User-Agent" to browserUserAgent,
                "Referer" to "https://www.google.com/"
            )
            val doc = app.get(data, headers = mainHeaders).document
            val iframeElement = doc.selectFirst(".entry-content iframe")
            val iframeSrc = iframeElement?.attr("src")

            if (!iframeSrc.isNullOrBlank()) {
                val playerUrl = fixUrl(iframeSrc)
                val playerHeaders = mapOf(
                    "User-Agent" to browserUserAgent,
                    "Referer" to data
                )
                val playerResponse = app.get(playerUrl, headers = playerHeaders).text
                val albaRegex = """AlbaPlayerControl\('([^']+)'""".toRegex()
                val albaMatch = albaRegex.find(playerResponse)

                if (albaMatch != null) {
                    val encodedString = albaMatch.groupValues[1]
                    try {
                        val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
                        val streamUrl = String(decodedBytes)
                        val streamHeaders = mapOf(
                            "User-Agent" to browserUserAgent,
                            "Referer" to playerUrl,
                            "Origin" to "https://player.syria-player.live",
                            "Accept" to "*/*"
                        )

                        M3u8Helper.generateM3u8(
                            name,
                            streamUrl,
                            referer = playerUrl,
                            headers = streamHeaders
                        ).forEach { link ->
                            callback(link)
                            foundLinks = true
                        }
                    } catch (e: Exception) { e.printStackTrace() }
                }
                else if (playerResponse.contains("Clappr.Player")) {
                    val clapprRegex = """source\s*:\s*"([^"]+)"""".toRegex()
                    val clapprMatch = clapprRegex.find(playerResponse)

                    if (clapprMatch != null) {
                        val streamUrl = clapprMatch.groupValues[1] // الرابط المباشر
                        M3u8Helper.generateM3u8(
                            name,
                            streamUrl,
                            referer = playerUrl,
                            headers = mapOf("User-Agent" to browserUserAgent)
                        ).forEach { link ->
                            callback(link)
                            foundLinks = true
                        }
                    }
                }
            }
            doc.select(".video-serv a").forEach { btn ->
                val href = btn.attr("href")
                if (href.isNotBlank()) {
                    loadExtractor(fixUrl(href), data, subtitleCallback, callback)
                    foundLinks = true
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return foundLinks
    }
}