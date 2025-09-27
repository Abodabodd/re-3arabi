package com.mycima
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
class MyCima : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://mycima.pics"
    override var name = "MyCima"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    companion object {
        const val TAG = "MyCima"
    }

    private fun String.getImageURL(): String? {
        return Regex("""url\((.*?)\)""").find(this)?.groupValues?.get(1)
            ?.trim('\'', '"', ' ')
            ?.ifBlank { null }
    }

    private fun String.getIntFromText(): Int? {
        return Regex("""\d+""").find(this)?.groupValues?.firstOrNull()?.toIntOrNull()
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val url = select("div.Thumb--GridItem a").attr("href")
        if (url.isBlank()) return null

        val posterUrl = select("span.BG--GridItem")?.attr("data-lazy-style")?.getImageURL()
        val title = select("div.Thumb--GridItem strong").text().trim()
        val year = select("span.year")?.text()?.getIntFromText()

        val tvType = when {
            url.contains("/film") || title.contains("فيلم") -> TvType.Movie
            url.contains("/anime") || title.contains("انمي") -> TvType.Anime
            else -> TvType.TvSeries
        }

        return newMovieSearchResponse(title, url, tvType) {
            this.posterUrl = posterUrl
            this.year = year
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl" to "الرئيسية",
        "$mainUrl/movies/" to "الأفلام",
        "$mainUrl/series/" to "المسلسلات",
        "$mainUrl/top-imdb/" to "الأعلى تقييماً"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}/page/$page/" else request.data
        val doc = app.get(url).document
        val list = doc.select("div.Grid--WecimaPosts div.GridItem").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, list)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/filtering/?keywords=$query"
        val document = app.get(url).document
        return document.select("div.GridItem").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.select("div.Title--Content--Single-begin h1")
            .firstOrNull()?.ownText()?.trim() ?: "غير معروف"

        val poster = doc.select("div.Poster--Single-begin span.BG--Poster--Single-begin")
            .attr("data-lazy-style").getImageURL()

        val synopsis = doc.select("div.StoryMovieContent").text().trim()
        val year = doc.select("div.Title--Content--Single-begin h1 a").text().getIntFromText()

        val episodes = mutableListOf<Episode>()

        // ====== استخراج post_id ======
        var postId: String? = null
        doc.select("script").forEach { script ->
            if (script.data().contains("post_id:")) {
                postId = Regex("""post_id:\s*'(\d+)'""")
                    .find(script.data())?.groupValues?.get(1)
                if (postId != null) return@forEach
            }
        }

        // لو فيلم
        val isMovie = doc.select("div.SeasonsList").isEmpty() && !url.contains("/series/")
        if (isMovie || postId.isNullOrBlank()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = synopsis
            }
        }

        // ====== مواسم المسلسل ======
        val seasonElements = doc.select("div.SeasonsList ul li a[data-season]")
        seasonElements.forEachIndexed { index, seasonLink ->
            val seasonId = seasonLink.attr("data-season")
            val seasonName = seasonLink.text()
            val seasonNumber = seasonName.getIntFromText() ?: (index + 1)

            try {
                val ajaxUrl = "$mainUrl/wp-content/themes/mycima/Ajaxt/Single/Episodes.php"
                val requestData = mapOf("season" to seasonId, "post_id" to postId!!)

                val ajaxResponse = app.post(
                    ajaxUrl,
                    data = requestData,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = url
                )

                val seasonDoc = ajaxResponse.document
                val episodeElements = seasonDoc.select("a")

                episodeElements.forEachIndexed { epIndex, epElement ->
                    val epUrl = fixUrl(epElement.attr("href"))
                    val epTitle = epElement.select(".EpisodeTitle").text().trim()
                        .ifBlank { "حلقة ${epIndex + 1}" }
                    val epNum = epTitle.getIntFromText() ?: (epIndex + 1)

                    episodes.add(newEpisode(epUrl) {
                        name = epTitle
                        episode = epNum
                        season = seasonNumber
                        posterUrl = poster
                    })
                }
            } catch (e: Exception) {
                Log.e(TAG, "فشل تحميل حلقات الموسم $seasonName", e)
            }
        }

        val isTvSeries = episodes.isNotEmpty()
        return if (isTvSeries) {
            newTvSeriesLoadResponse(
                title, url, TvType.TvSeries,
                episodes.distinctBy { it.data }.sortedWith(compareBy({ it.season }, { it.episode }))
            ) {
                this.posterUrl = poster
                this.plot = synopsis
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = synopsis
                this.year = year
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        doc.select("ul#watch li").apmap { li ->
            val encodedUrl = li.attr("data-watch")
            if (encodedUrl.contains("/play/")) {
                val base64String = encodedUrl.substringAfter("/play/").trimEnd('/')
                try {
                    val decodedUrl = String(Base64.decode(base64String, Base64.DEFAULT))
                    val watchPageDoc = app.get(decodedUrl, referer = data).document
                    val iframeSrc = watchPageDoc.select("iframe").attr("src")
                    if (iframeSrc.isNotBlank()) {
                        loadExtractor(iframeSrc, decodedUrl, subtitleCallback, callback)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process watch link: $encodedUrl", e)
                }
            }
        }

        doc.select("div#downloads a").apmap { a ->
            val url = a.attr("href")
            val name = a.text().trim()
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name Download",
                    url = url,
                ) {
                    this.referer = mainUrl
                    this.quality = name.getIntFromText() ?: Qualities.Unknown.value
                }
            )
        }

        return true
    }
}