package com.aflaam

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element

class Aflaam : MainAPI() {
    override var mainUrl = "https://aflaam.com"
    override var name = "Aflaam"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/search?section=2" to "أفلام أجنبية",
        "$mainUrl/search?section=1" to "أفلام عربية",
        "$mainUrl/search?section=3" to "أفلام هندية",
        "$mainUrl/series" to "مسلسلات"
    )

    private fun parseSearchResult(element: Element): SearchResponse? {
        val linkTag = element.selectFirst("a.box") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = element.selectFirst("h3.entry-title")?.text()?.trim() ?: return null
        val posterUrl = element.selectFirst("picture > img")?.attr("src")?.let { fixUrl(it) }

        return if (href.contains("/movie/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else if (href.contains("/series/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            null
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page > 1) "${request.data}&page=$page" else request.data
        val document = app.get(url).document
        val home = document.select("div.item").mapNotNull {
            parseSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query").document
        return document.select("div.item").mapNotNull {
            parseSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
    val document = app.get(url).document

    val title = document.selectFirst("h1.font-size-44")?.text()?.trim() ?: return null
    val posterUrl = document.selectFirst("a.movie-poster > img")?.attr("src")?.let { fixUrl(it) }
    val plot = document.selectFirst("div#movie-tab-2 div.widget-body > p")?.text()?.trim()

    val yearText = document.select("div.movie-container > div.d-flex")
        .find { it.text().contains("سنة الإنتاج") }?.text()
    val year = yearText?.substringAfter(":")?.trim()?.toIntOrNull()

    val tags = document.select("a.movie-category").map { it.text() }
    val scoreValue = document.selectFirst("span.font-size-24")?.text()?.toDoubleOrNull()
    val cast = document.select("div.entry-box-2 a").mapNotNull {
        val name = it.selectFirst("h3.entry-name")?.text() ?: return@mapNotNull null
        val image = it.selectFirst("img")?.attr("src")?.let { src -> fixUrl(src) }
        Actor(name, image)
    }

    val trailerUrl = document.selectFirst("div#movie-tab-3 iframe")?.attr("src")

    if (url.contains("/series/")) {
        val episodes = document.select("div#movie-tab-1 div.entry-box-3").mapNotNull {
            val epLink = it.selectFirst("a")?.attr("href")?.let { href -> fixUrl(href) } ?: return@mapNotNull null
            val epTitleFull = it.selectFirst("h3.entry-title")?.text()
            val epNum = it.selectFirst("span.font-size-50")?.text()?.toIntOrNull()
            val epName = epTitleFull?.replaceFirst(Regex("^\\d+"), "")?.trim()
            val epThumb = it.selectFirst("img")?.attr("src")?.let { src -> fixUrl(src) }

            newEpisode(epLink) {
                this.name = epName
                this.episode = epNum
                this.posterUrl = epThumb
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = Score.from10(scoreValue)
            addActors(cast) // استخدام الدالة المساعدة
            addTrailer(trailerUrl)
        }
    } else {
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = Score.from10(scoreValue)
            addActors(cast) // استخدام الدالة المساعدة
            addTrailer(trailerUrl)
        }
    }
}

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    val watchPageLinks = document.select("div.qualities a.link-show").map {
        fixUrl(it.attr("href"))
    }

    var linksLoaded = false
    watchPageLinks.amap { watchUrl ->
        try {
            val watchPageDoc = app.get(watchUrl).document
            watchPageDoc.select("video#player source").forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank()) {
                    val qualityName = source.attr("size").ifEmpty { "720" }
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} - ${qualityName}p",
                            url = src,
                        ) {
                            referer = mainUrl
                            quality = qualityName.toIntOrNull() ?: Qualities.Unknown.value
                        }
                    )
                    linksLoaded = true
                }
            }
        } catch (e: Exception) {
        }
    }
    return linksLoaded
}
}
