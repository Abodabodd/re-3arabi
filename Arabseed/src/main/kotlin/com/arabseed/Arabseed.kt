package com.arabseed
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.serialization.Serializable
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink
class Arabseed : MainAPI() {
    override var mainUrl = "https://a.asd.homes"
    override var name = "Arabseed"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    private fun String.toAbsolute(): String {
        if (this.isBlank()) return ""
        return when {
            this.startsWith("http") -> this
            this.startsWith("//") -> "https:$this"
            else -> mainUrl.trimEnd('/') + this
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/find/?word=${query.trim().replace(" ", "+")}"
        val document = app.get(url).document
        return document.select("ul.blocks__ul > li").amap {
            val a = it.selectFirst("a.movie__block") ?: return@amap null
            val href = a.attr("href").toAbsolute()
            val title = a.attr("title").ifBlank { a.selectFirst("h3")?.text() } ?: return@amap null
            val posterUrl = a.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            val isMovie = href.contains("/%d9%81%d9%8a%d9%84%d9%85-")
            val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

            newMovieSearchResponse(title, href, tvType) {
                this.posterUrl = posterUrl
            }
        }.filterNotNull()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/main0/" to "الرئيسية",
        "$mainUrl/recently/" to "مضاف حديثا",
        "$mainUrl/movies/" to "أفلام",
        "$mainUrl/main0/" to "المسلسلات",
        "$mainUrl/category/افلام-انيميشن/" to "افلام انيميشن",
        "$mainUrl/category/cartoon-series/" to "مسلسلات كرتون",
        "$mainUrl/category/arabic-series-2/" to "مسلسلات عربي",
        "$mainUrl/category/arabic-movies-6/" to "افلام عربي",

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}page/$page/" else request.data
        val document = app.get(url).document
        val items = document.select(".movie__block").amap {
            val title = it.selectFirst("h3")?.text() ?: return@amap null
            val href = it.attr("href").toAbsolute()
            val posterUrl = it.selectFirst("img")?.let { img ->
                (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
            }
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }.filterNotNull()
        return newHomePageResponse(request.name, items)
    }

    @Serializable
    data class AjaxResponse(
        val html: String?,
        val hasmore: Boolean?
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.post__name")?.text()?.trim() ?: "غير معروف"
        val poster = doc.selectFirst(".poster__side img, .single__cover img, .post__poster img")?.let { img ->
            (img.attr("data-src").ifBlank { img.attr("src") }).toAbsolute()
        }
        val synopsis = doc.selectFirst(".post__story > p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()

        doc.select("ul.episodes__list li a").forEach { epEl ->
            val epHref = epEl.attr("href").toAbsolute()
            val epTitle = epEl.selectFirst(".epi__num")?.text()?.trim() ?: epEl.text().trim()
            val epNum = epTitle.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }
            episodes.add(newEpisode(epHref) {
                name = epTitle
                episode = epNum
                posterUrl = poster
            })
        }

        doc.selectFirst("div.load__more__episodes")?.let { loadMoreButton ->
            val seasonId = loadMoreButton.attr("data-id")
            val csrfToken = doc.select("script").html()
                .let { Regex("""'csrf__token':\s*"([^"]+)""").find(it)?.groupValues?.get(1) }

            if (seasonId.isNotBlank() && !csrfToken.isNullOrBlank()) {
                var hasMore = true
                while (hasMore) {
                    try {
                        val response = app.post(
                            "$mainUrl/season__episodes/",
                            data = mapOf(
                                "season_id" to seasonId,
                                "offset" to episodes.size.toString(),
                                "csrf_token" to csrfToken
                            ),
                            referer = url,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).parsedSafe<AjaxResponse>()

                        if (response?.html.isNullOrBlank() || response?.hasmore != true) {
                            hasMore = false
                        } else {
                            val newEpisodesDoc = Jsoup.parse(response.html)
                            val newEpisodeElements = newEpisodesDoc.select("li a")

                            if (newEpisodeElements.isEmpty()) {
                                hasMore = false
                            } else {
                                newEpisodeElements.forEach { epEl ->
                                    val epHref = epEl.attr("href").toAbsolute()
                                    val epTitle = epEl.selectFirst(".epi__num")?.text()?.trim()
                                        ?: epEl.text().trim()
                                    val epNum =
                                        epTitle.let { Regex("""\d+""").find(it)?.value?.toIntOrNull() }

                                    episodes.add(newEpisode(epHref) {
                                        name = epTitle
                                        episode = epNum
                                        posterUrl = poster
                                    })
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "AJAX load more error", e)
                        hasMore = false
                    }
                }
            }
        }

        val isTvSeries = episodes.isNotEmpty() || url.contains("/selary/")

        return if (isTvSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinct().reversed()) {
                this.posterUrl = poster
                this.plot = synopsis
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = synopsis
            }
        }
    }

    @Serializable
    data class ServerResponse(val server: String?)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks initiated with data: $data")

        val episodePageDoc = app.get(data).document
        val watchUrl = episodePageDoc.selectFirst("a.btton.watch__btn")?.attr("href")?.toAbsolute()
            ?: run {
                Log.e(name, "Failed to find the watch button URL on: $data")
                return false
            }
        Log.d(name, "Found watch URL: $watchUrl")

        val watchPageDoc = app.get(watchUrl, referer = data).document

        val csrfToken = watchPageDoc.select("script").html()
            .let { Regex("""'csrf__token':\s*"([^"]+)""").find(it)?.groupValues?.get(1) }
            ?: return true
        val postId = watchPageDoc.selectFirst(".servers__list li")?.attr("data-post") ?: return true

        watchPageDoc.select(".quality__swither ul.qualities__list li").amap { qualityElement ->
            val quality = qualityElement.attr("data-quality")
            Log.i(name, "Processing AJAX quality: '$quality'")

            app.post(
                "$mainUrl/get__quality__servers/",
                data = mapOf("post_id" to postId, "quality" to quality, "csrf_token" to csrfToken),
                referer = watchUrl,
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).parsedSafe<AjaxResponse>()?.html?.let { html ->
                Jsoup.parse(html).select("li").amap { serverElement ->
                    val serverId = serverElement.attr("data-server")
                    val postData = mapOf("post_id" to postId, "quality" to quality, "server" to serverId, "csrf_token" to csrfToken)

                    try {
                        if (serverId == "0") {
                            Log.d(name, "->>>>>> [Server 0] Attempting to fetch. Quality: '$quality'. Data: $postData")
                        }

                        val response = app.post(
                            "$mainUrl/get__watch__server/",
                            data = postData,
                            referer = watchUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        )

                        if (serverId == "0") {
                            Log.d(name, "<<<<<<- [Server 0] Raw response text: ${response.text}")
                        }

                        val serverResponse = response.parsedSafe<ServerResponse>()

                        if (serverId == "0") {
                            Log.d(name, "[Server 0] Parsed response object: $serverResponse")
                        }


                        serverResponse?.server?.let { iframeUrl ->
                            if(iframeUrl.isNotBlank()) {
                                if (serverId == "0") {
                                    Log.d(name, "[Server 0] Successfully extracted iframe URL: $iframeUrl")
                                }
                                loadExtractor(iframeUrl, watchUrl, subtitleCallback, callback)
                            } else {
                                if (serverId == "0") {
                                    Log.w(name, "[Server 0] Iframe URL is blank in the response.")
                                }
                            }
                        } ?: run {
                            if (serverId == "0") {
                                Log.w(name, "[Server 0] Failed to parse a valid ServerResponse object or the 'server' field was null.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Exception on AJAX server ID '$serverId'", e)
                    }
                }
            }
        }

        Log.i(name, "loadLinks has finished processing all servers.")
        return true
    }
}
