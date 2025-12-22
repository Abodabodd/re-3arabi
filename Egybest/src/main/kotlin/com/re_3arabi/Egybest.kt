package com.re_3arabi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class EgybestProvider : MainAPI() {
    override var mainUrl = "https://i-egybest.info"
    override var name = "Egybest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val quality = this.selectFirst(".ribbon span")?.text()
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/trending/" to "الأفلام الرائجة",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/anime/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + "page:$page").document
        val home = document.select("div.movies a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/explore/?q=${query}"
        val document = app.get(url).document
        return document.select("div.movies a").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("div.movie_title h1, h1.title")?.text()?.trim() ?: ""
        val posterUrl = fixUrlNull(document.selectFirst("div.movie_img img, img.poster")?.attr("src"))
        val year = document.selectFirst("div.movie_title a, span.year")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.mbox, div.story")?.text()?.trim()
        val rating = document.selectFirst("div.rating strong")?.text()?.toDoubleOrNull()?.times(1000)?.toInt()
        val tags = document.select("div.movie_title a[href*='/tags/'], a.tag").map { it.text() }
        
        val recommendations = document.select("div.movies_small a, div.related a").mapNotNull {
            it.toSearchResult()
        }

        val isMovie = document.select("div.season, ul.episodes").isEmpty()

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            val episodes = ArrayList<Episode>()
            
            document.select("div.season").forEachIndexed { seasonIndex, seasonDiv ->
                val seasonNum = seasonDiv.selectFirst("div.title, h3")?.text()
                    ?.filter { it.isDigit() }?.toIntOrNull() ?: (seasonIndex + 1)
                
                seasonDiv.select("ul.episodes li a, li.episode a").forEach { ep ->
                    val epName = ep.text()
                    val epHref = fixUrl(ep.attr("href"))
                    val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: 0
                    
                    episodes.add(
                        Episode(
                            data = epHref,
                            name = epName,
                            season = seasonNum,
                            episode = epNum
                        )
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = description
                this.rating = rating
                this.tags = tags
                this.recommendations = recommendations
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
        
        // استخراج روابط السيرفرات
        document.select("ul.download_btn li a, ul#playerTable li").forEach { server ->
            val serverName = server.text()
            val serverUrl = server.attr("data-url").ifEmpty { 
                server.attr("href") 
            }
            
            if (serverUrl.isNotEmpty()) {
                val fullUrl = fixUrl(serverUrl)
                loadExtractor(fullUrl, subtitleCallback, callback)
            }
        }

        // استخراج روابط iframe
        document.select("iframe").forEach { iframe ->
            val iframeUrl = iframe.attr("src")
            if (iframeUrl.isNotEmpty()) {
                loadExtractor(fixUrl(iframeUrl), subtitleCallback, callback)
            }
        }

        // استخراج روابط التحميل المباشرة
        document.select("a.download_btn, a[download]").forEach { link ->
            val downloadUrl = link.attr("href")
            val quality = link.text()
            
            if (downloadUrl.isNotEmpty()) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        quality.ifEmpty { "تحميل" },
                        fixUrl(downloadUrl),
                        referer = mainUrl,
                        quality = getQualityFromString(quality),
                        isM3u8 = false
                    )
                )
            }
        }

        return true
    }
}
