package com.cimaclub

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CimaClub : MainAPI() {
    override var mainUrl = "https://ciimaclub.club"
    override var name = "CimaClub"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("inner--title > h2")?.text()?.trim() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }?.ifBlank { null }

        val isTv = this.hasClass("ser") || href.contains("/series/") || href.contains("/مسلسل-")

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
       
        if (page > 1) return newHomePageResponse(emptyList())

        val document = app.get(mainUrl).document
        val homePageList = mutableListOf<HomePageList>()

        val featured = document.select("div.Slider--Outer li.Slides--Item div.Block--Item")
        if (featured.isNotEmpty()) {
            val featuredList = featured.mapNotNull {
                val title = it.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val posterUrl = it.selectFirst("img")?.let { img ->
                    img.attr("data-src").ifBlank { img.attr("src") }
                }?.ifBlank { null }
                val url = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val isTv = url.contains("/series/") || url.contains("/مسلسل-")

                if (isTv) {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = posterUrl }
                } else {
                    newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = posterUrl }
                }
            }
            homePageList.add(HomePageList("المميزة", featuredList))
        }

        val recentlyAddedItems = document.select("div.holdposts div.BlocksHolder > div.Small--Box")
        val recentlyAddedList = recentlyAddedItems.mapNotNull { it.toSearchResponse() }
        if (recentlyAddedList.isNotEmpty()) {
            homePageList.add(HomePageList("المضاف حديثاً", recentlyAddedList))
        }

        return newHomePageResponse(homePageList)
    }


    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("div.BlocksHolder > div.Small--Box").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val isTvSeries = url.contains("/series/") || url.contains("/مسلسل-") ||
                document.select("section.allepcont .row a").size > 1

        val title = document.selectFirst("h1.PostTitle")?.text()?.trim()
            ?: throw RuntimeException("Title not found on page: $url")
        val poster = document.selectFirst(".MainSingle .left .image img")?.attr("src")?.ifBlank { null }
        val plot = document.selectFirst(".StoryArea p")?.text()?.replace("قصة العرض", "")?.trim()
        val tags = document.select(".TaxContent a[href*='/genre/']").mapNotNull { it.text() }
        val year = document.selectFirst(".TaxContent a[href*='/release-year/']")?.text()?.toIntOrNull()
        val contentRating = document.select(".half-tags li span:contains(التصنيف العمرى)").firstOrNull()
            ?.parent()?.selectFirst("a")?.text()?.trim()

        if (isTvSeries) {
            val episodes = mutableListOf<Episode>()
            val seasons = document.select("section.allseasonss .Small--Box a")

            if (seasons.isNotEmpty()) {
                seasons.apmap { seasonLink ->
                    val seasonUrl = seasonLink.attr("href")
                    val seasonDoc = if (seasonUrl == url) document else app.get(seasonUrl).document
                    val seasonNumText = seasonLink.selectFirst(".epnum span")?.nextSibling()?.toString()?.trim()
                    val seasonNum = seasonNumText?.toIntOrNull()

                    seasonDoc.select("section.allepcont .row a").map { ep ->
                        newEpisode(ep.attr("href")) {
                            this.name = ep.selectFirst(".ep-info h2")?.text()
                            this.episode = ep.selectFirst(".epnum")?.ownText()?.trim()?.toIntOrNull()
                            this.season = seasonNum
                            this.posterUrl = poster 
                        }
                    }
                }.flatten().toCollection(episodes)
            } else {
                document.select("section.allepcont .row a").mapTo(episodes) { ep ->
                    newEpisode(ep.attr("href")) {
                        this.name = ep.selectFirst(".ep-info h2")?.text()
                        this.episode = ep.selectFirst(".epnum")?.ownText()?.trim()?.toIntOrNull()
                        this.posterUrl = poster
                    }
                }
            }

            return newTvSeriesLoadResponse(
                name = title,
                url = url,
                type = TvType.TvSeries,
                episodes = episodes.distinctBy { it.data }
                    .sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.contentRating = contentRating
            }
        } else {
            return newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = "$url/watch/"
            ) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
                this.year = year
                this.contentRating = contentRating
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tag = "CimaClubLinks"
        val watchUrl = if (data.endsWith("/watch/")) data else data.removeSuffix("/") + "/watch/"
        Log.d(tag, "🔗 Loading links for: $watchUrl")

        val document = app.get(watchUrl).document

        // ▶️ روابط المشاهدة (Embed)
        document.select("ul#watch li").apmap {
            val embedUrl = it.attr("data-watch")
            if (embedUrl.isNotBlank()) {
                Log.d(tag, "📺 Embed server: $embedUrl")
                loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
            }
        }

        // ⬇️ روابط التحميل المباشر
        document.select(".ServersList.Download a").apmap { element ->
            val downloadUrl = element.attr("href")?.trim()
            if (downloadUrl.isNullOrBlank()) return@apmap

            val name = element.selectFirst(".text span")?.text()?.trim() ?: "رابط تحميل"
            val linkType = if (downloadUrl.contains(".m3u8", true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }

            Log.d(tag, "⬇️ Direct link found: name=$name, url=$downloadUrl, type=$linkType")

            val extractorLink = newExtractorLink(
                source = name,
                name = "$name - مباشر",
                url = downloadUrl,
                type = linkType
            ) {
                this.referer = watchUrl
                this.quality = Qualities.Unknown.value
            }

            callback(extractorLink)
        }

        Log.d(tag, "✅ Finished extracting links for $watchUrl")
        return true
    }
}
