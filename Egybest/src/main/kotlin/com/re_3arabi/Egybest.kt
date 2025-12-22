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
        val url = this.attr("href") ?: return null
        val title = this.selectFirst("span.title")?.text()?.trim() 
            ?: this.attr("title")?.trim() 
            ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("src")
        )
        val quality = this.selectFirst("div.ribbon span")?.text()
        
        return newMovieSearchResponse(title, fixUrl(url), TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(quality)
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "الرئيسية",
        "$mainUrl/trending/" to "الأفلام الرائجة",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/series/" to "مسلسلات",
        "$mainUrl/anime/" to "أنمي"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("div.movies a, a.movie").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/explore/?q=${query}"
        val document = app.get(url).document
        return document.select("div.movies a, a.movie").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.title, div.movie_title h1")?.text()?.trim() ?: ""
        val posterUrl = fixUrlNull(
            document.selectFirst("div.movie_img img, img.poster")?.attr("data-src")
            ?: document.selectFirst("div.movie_img img, img.poster")?.attr("src")
        )
        val year = document.selectFirst("div.movie_title a, span.year")?.text()?.toIntOrNull()
        val description = document.selectFirst("div.mbox p, div.story, p.story")?.text()?.trim()
        val rating = document.selectFirst("div.rating strong, span.rating")?.text()
            ?.replace(",", ".")?.toDoubleOrNull()?.times(1000)?.toInt()
        val tags = document.select("div.movie_title a[href*='/tags/'], a.tag, span.tag").map { it.text() }
        
        val recommendations = document.select("div.movies a, a.movie").mapNotNull {
            it.toSearchResult()
        }

        // تحديد إذا كان مسلسل أو فيلم
        val isMovie = document.select("div.movies_small.auto a, ul.episodes").isEmpty()

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
            
            // استخراج الحلقات من صفحة المسلسل
            document.select("div.movies_small.auto a").forEachIndexed { index, ep ->
                val epName = ep.selectFirst("span.title")?.text() ?: "الحلقة ${index + 1}"
                val epHref = fixUrl(ep.attr("href"))
                val epPoster = fixUrlNull(ep.selectFirst("img")?.attr("data-src") ?: ep.selectFirst("img")?.attr("src"))
                
                // استخراج رقم الحلقة والموسم من النص
                val seasonNum = epName.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let {
                    if (epName.contains("الموسم")) it.toIntOrNull() else null
                } ?: 1
                
                val epNum = epName.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.let {
                    if (epName.contains("الحلقة")) it.toIntOrNull() else null
                } ?: (index + 1)
                
                episodes.add(
                    Episode(
                        data = epHref,
                        name = epName,
                        season = seasonNum,
                        episode = epNum,
                        posterUrl = epPoster
                    )
                )
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
        
        // استخراج روابط المشاهدة من الأزرار
        document.select("a.btn.butt, a.watch_btn").forEach { watchBtn ->
            val watchUrl = watchBtn.attr("href")
            if (watchUrl.isNotEmpty() && !watchUrl.contains("#")) {
                try {
                    val watchDoc = app.get(fixUrl(watchUrl)).document
                    
                    // استخراج روابط السيرفرات
                    watchDoc.select("ul#playerTable li, div.servers li").forEach { server ->
                        val serverUrl = server.attr("data-url").ifEmpty { 
                            server.selectFirst("a")?.attr("href") ?: ""
                        }
                        
                        if (serverUrl.isNotEmpty()) {
                            loadExtractor(fixUrl(serverUrl), data, subtitleCallback, callback)
                        }
                    }
                    
                    // استخراج iframe
                    watchDoc.select("iframe").forEach { iframe ->
                        val iframeUrl = iframe.attr("src")
                        if (iframeUrl.isNotEmpty()) {
                            loadExtractor(fixUrl(iframeUrl), data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // تجاهل الأخطاء والمتابعة
                }
            }
        }
        
        // استخراج روابط التحميل المباشرة
        document.select("a.download_btn, table.table_dl a").forEach { link ->
            val downloadUrl = link.attr("href")
            val quality = link.text()
            
            if (downloadUrl.isNotEmpty() && !downloadUrl.contains("#")) {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        quality.ifEmpty { "تحميل" },
                        fixUrl(downloadUrl),
                        referer = mainUrl,
                        quality = getQualityFromString(quality),
                        isM3u8 = downloadUrl.contains(".m3u8")
                    )
                )
            }
        }

        return true
    }
}
