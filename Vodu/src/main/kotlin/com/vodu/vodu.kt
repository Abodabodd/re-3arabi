package com.vodu

import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder

class VoduProvider : MainAPI() {
    override var mainUrl = "https://movie.vodu.me"
    override var name = "Vodu"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val siteHeaders = mapOf(
        "Referer" to "https://movie.vodu.me/",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    override val mainPage = mainPageOf(
        "$mainUrl" to "Featured",
        "$mainUrl/movies" to "Latest Movies",
        "$mainUrl/series" to "Latest Series",
        "$mainUrl/category/15" to "Turkish Series",
        "$mainUrl/category/5" to "Asian Drama",
        "$mainUrl/category/2" to "Anime Series",
        "$mainUrl/category/9" to "Anime Movies",
        "$mainUrl/category/4" to "Arabic Series",
        "$mainUrl/category/7" to "Arabic Movies"
    )

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return url.replace("&amp;", "&").trim()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = try {
            app.get(request.data, headers = siteHeaders, timeout = 10).document
        } catch (e: Exception) {
            if (e is java.io.IOException ||        // يشمل Canceled, timeout, ConnectException
                e is java.net.UnknownHostException ||
                e is javax.net.ssl.SSLException
            ) {
                showToast(
                    "يبدو ان شبكتك لا تدعم فودو تاكد من انك تفتحه في العراق 🇮🇶 وعلى شبكة مدعومة",
                    Toast.LENGTH_LONG
                )
                return newHomePageResponse(emptyList())
            }
            throw e
        }

        val homeItems = ArrayList<HomePageList>()
        val sections = document.select("div:has(h2.tv-row-title)")

        if (sections.isNotEmpty()) {
            for (section in sections) {
                val title = section.selectFirst("h2.tv-row-title")?.text()?.trim() ?: continue
                val items = section.select("a[href*='/item/']").mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    homeItems.add(HomePageList(title, items))
                }
            }
        } else {
            val items = document.select("div.grid a[href*='/item/']").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                homeItems.add(HomePageList(request.name, items))
            }
        }

        return newHomePageResponse(homeItems)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = this.attr("href") ?: return null
        val title = this.selectFirst("p.truncate")?.text()?.trim() ?: return null
        val poster = fixImageUrl(this.selectFirst("img")?.attr("src"))
        val chipText = this.selectFirst("span.ui-chip")?.text()?.lowercase() ?: ""
        val type = if (chipText.contains("series") || chipText.contains("مسلسل")) {
            TvType.TvSeries
        } else {
            TvType.Movie
        }

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, link, type) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, link, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encoded = URLEncoder.encode(query, "utf-8")
        val searchUrl = "$mainUrl/search?q=$encoded&sort=newest&sortBy=adddate&sortType=desc&p=$page"
        val document = app.get(searchUrl, headers = siteHeaders).document

        val rawElements = document.select("div.grid a[href*='/item/']")
        if (rawElements.isEmpty()) return null

        val parsedList = rawElements.mapNotNull { it.toSearchResponse() }

        fun getMatchScore(title: String?, qLower: String): Int {
            if (title.isNullOrBlank()) return 0
            val tLower = title.lowercase().trim()
            if (tLower == qLower) return 100
            if (tLower.startsWith(qLower)) return 80
            if (tLower.contains(qLower)) return 60
            val tokens = qLower.split(Regex("\\s+")).filter { it.isNotBlank() }
            val tokenMatches = tokens.count { tLower.contains(it) }
            return if (tokenMatches > 0) 40 + tokenMatches else 0
        }

        val ql = query.lowercase().trim()

        val sortedResults = parsedList
            .mapIndexed { index, item ->
                val score = getMatchScore(item.name, ql)
                Triple(item, score, index)
            }
            .sortedWith(
                compareByDescending<Triple<SearchResponse, Int, Int>> { it.second }
                    .thenBy { it.third }
            )
            .map { it.first }
            .distinctBy { it.url }

        val hasNextPage = document.select("a[rel=next], a:contains(›)").isNotEmpty() ||
                document.select("ul.flex a[href*='p=${page + 1}']").isNotEmpty()

        return newSearchResponseList(sortedResults, hasNextPage)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = siteHeaders).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"

        val poster = fixImageUrl(
            document.selectFirst("div.aspect-\\[2\\/3\\] img, div.w-32 img")?.attr("src")
        )

        val rawBackdrop = fixImageUrl(
            document.selectFirst("div.relative.h-\\[45vh\\] img, div.tv-hero img")?.attr("src")
        )

        val backdrop = if (!rawBackdrop.isNullOrBlank()) {
            try {
                val check = app.head(rawBackdrop, headers = siteHeaders)
                if (check.isSuccessful) rawBackdrop else poster
            } catch (_: Exception) {
                poster
            }
        } else {
            poster
        }

        val plot = document.selectFirst("p.max-w-2xl")?.text()?.trim()

        val ratingText = document.selectFirst("span.text-white.font-bold:has(i.fa-star)")?.text()?.trim()
        val score = ratingText?.replace(Regex("[^0-9.]"), "")?.toDoubleOrNull()?.let {
            Score.from10(it)
        }

        val yearText = document.select("div.flex.items-center span").firstOrNull {
            it.text().matches(Regex(".*\\b(19|20)\\d{2}\\b.*"))
        }?.text()
        val year = yearText?.let { Regex("\\b(19|20)\\d{2}\\b").find(it)?.value?.toIntOrNull() }

        val duration = document.select("div.flex.items-center span").firstOrNull {
            it.text().contains("min")
        }?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        val genres = document.selectFirst("p:contains(Genres:)")?.text()
            ?.replace("Genres:", "")
            ?.split(",")
            ?.map { it.trim() }

        val actors = document.select("div:has(> h3:contains(Cast)) a[href*='/actor/']").mapNotNull {
            val actorName = it.selectFirst("p")?.text()?.trim() ?: return@mapNotNull null
            val actorImg = fixImageUrl(it.selectFirst("img")?.attr("src"))
            Actor(actorName, actorImg)
        }

        val recommendations = document.select("div:has(> h3:contains(More Like This)) a[href*='/item/']").mapNotNull {
            it.toSearchResponse()
        }

        val trailerId = Regex("""const\s+trailerId\s*=\s*"([^"]+)"""").find(document.html())?.groupValues?.get(1)
        val trailerUrl = if (!trailerId.isNullOrBlank()) "https://www.youtube.com/watch?v=$trailerId" else null

        val isSeries = document.select("#seasonTabs, div[id^='seasonPanel_']").isNotEmpty() ||
                document.select("span.ui-chip").any { it.text().contains("Series", true) }

        if (isSeries) {
            val episodes = ArrayList<Episode>()
            val seasonPanels = document.select("div[id^='seasonPanel_']")

            seasonPanels.forEachIndexed { index, panel ->
                val seasonNum = index + 1
                val epElements = panel.select("a[href*='/play/episode/']")

                epElements.forEachIndexed { epIndex, epElement ->
                    val epUrl = epElement.attr("href")
                    val epTitle = epElement.selectFirst("p.font-semibold")?.text()?.trim() ?: "Episode ${epIndex + 1}"
                    val epDesc = epElement.selectFirst("p.text-slate-400")?.text()?.trim()

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epTitle
                            this.season = seasonNum
                            this.episode = epIndex + 1
                            this.description = epDesc
                            this.posterUrl = poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
                addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        } else {
            val playUrl = document.selectFirst("a[href*='/play/']")?.attr("href") ?: url.replace("/item/", "/play/")

            return newMovieLoadResponse(title, url, TvType.Movie, playUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.duration = duration
                this.score = score
                this.tags = genres
                this.recommendations = recommendations
                addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = siteHeaders).document
        val html = document.html()
        val qualitiesMatch = Regex("""const\s+qualitiesRaw\s*=\s*(\{.*?\});""").find(html)?.groupValues?.get(1)
        if (!qualitiesMatch.isNullOrBlank()) {
            val qualitiesJson = tryParseJson<Map<String, QualityItem>>(qualitiesMatch)

            qualitiesJson?.forEach { (label, info) ->
                val videoUrl = info.url?.replace("\\/", "/") ?: return@forEach
                val qualityInt = label.toIntOrNull() ?: Qualities.P720.value

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $label" + "p",
                        url = videoUrl,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityInt
                    }
                )
            }
        } else {
            val fallbackMatch = Regex("""const\s+fallbackUrl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            fallbackMatch?.let { rawUrl ->
                val videoUrl = rawUrl.replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name Standard",
                        url = videoUrl,
                    ) {
                        referer = "$mainUrl/"
                        quality = Qualities.P720.value
                    }
                )
            }
        }

        val subtitlesMatch = Regex("""const\s+subtitlesRaw\s*=\s*(\[\{.*?\}\]);""").find(html)?.groupValues?.get(1)
        if (!subtitlesMatch.isNullOrBlank()) {
            val subsJson = tryParseJson<List<SubtitleItem>>(subtitlesMatch)

            subsJson?.forEach { sub ->
                val subUrl = sub.path?.replace("\\/", "/") ?: return@forEach
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = sub.label ?: "Arabic",
                        url = subUrl
                    )
                )
            }
        }

        return true
    }

    data class QualityItem(
        @JsonProperty("url") val url: String?,
        @JsonProperty("type") val type: String?
    )

    data class SubtitleItem(
        @JsonProperty("lang_code") val langCode: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("format") val format: String?,
        @JsonProperty("path") val path: String?
    )
}