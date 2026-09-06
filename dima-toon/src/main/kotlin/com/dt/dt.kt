package com.dima

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DimaToonProvider : MainAPI() {
    override var mainUrl = "https://www.dima-toon.com"
    override var name = "Dima Toon"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Cartoon)

    override val mainPage = mainPageOf(
        "series" to "المسلسلات المضافة حديثًا",
        "episodes" to "الحلقات المضافة حديثًا"
    )
    private var cachedSearchQuery: String? = null
    private var cachedLoadData: LoadMoreData? = null

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())

        val document = app.get(mainUrl).document
        val home = when (request.data) {
            "series" -> {
                document.select("div#cartoon-list div.cartoon-item a").mapNotNull {
                    it.toSearchResponse()
                }
            }
            "episodes" -> {
                document.select("div#cartoon-episodes-container div.episode-card a").mapNotNull {
                    it.toSearchResponse()
                }
            }
            else -> emptyList()
        }

        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        if (this.tagName() == "article" && this.hasClass("eael-grid-post")) {
            val link = this.selectFirst("a.eael-grid-post-link") ?: return null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
            val title = link.text().trim()

            val img = this.selectFirst("img")
            val posterUrl = img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")

            return buildSearchResponse(title, href, posterUrl)
        }
        val href = this.attr("href")
        if (href.isBlank()) return null

        val title = this.selectFirst("p, .episode-title")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")

        return buildSearchResponse(title, href, posterUrl)
    }

    private fun buildSearchResponse(title: String, href: String, posterUrl: String?): SearchResponse {
        return if (href.contains("/cartoon-episode/")) {
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
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        if (page == 1) {
            val response = app.get(
                searchUrl,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            )

            val html = response.text
            val document = response.document

            val results = document.select("article.eael-grid-post").mapNotNull {
                it.toSearchResponse()
            }
            val button = document.selectFirst(".eael-load-more-button")
            val nonceRegex = Regex("""var localize\s*=\s*.*?"nonce":"([^"]+)"""")
            val nonce = nonceRegex.find(html)?.groupValues?.get(1)

            if (button != null && nonce != null) {
                val templateStr = button.attr("data-template")
                val templateInfo = try {
                    AppUtils.parseJson<TemplateInfo>(templateStr)
                } catch (e: Exception) { null }

                cachedSearchQuery = query
                cachedLoadData = LoadMoreData(
                    widgetId = button.attr("data-widget-id"),
                    pageId = button.attr("data-page-id"),
                    args = button.attr("data-args"),
                    clazz = button.attr("data-class"),
                    maxPage = button.attr("data-max-page").toIntOrNull() ?: 1,
                    nonce = nonce,
                    templateInfo = templateInfo
                )
            } else {
                cachedLoadData = null
            }
            val hasNextPage = (cachedLoadData?.maxPage ?: 1) > 1
            return newSearchResponseList(results, hasNextPage)
        }
        else {
            val loadData = cachedLoadData
            if (loadData == null || query != cachedSearchQuery || page > loadData.maxPage) {
                return newSearchResponseList(emptyList(), hasNext = false)
            }

            val postData = mapOf(
                "action" to "load_more",
                "class" to loadData.clazz,
                "args" to loadData.args,
                "page" to page.toString(),
                "page_id" to loadData.pageId,
                "widget_id" to loadData.widgetId,
                "nonce" to loadData.nonce,
                "template_info[dir]" to (loadData.templateInfo?.dir ?: ""),
                "template_info[file_name]" to (loadData.templateInfo?.fileName ?: ""),
                "template_info[name]" to (loadData.templateInfo?.name ?: "")
            )

            val ajaxResponse = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "Referer" to searchUrl,
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl
                ),
                data = postData
            )

            var htmlSegment = ajaxResponse.text
            try {
                htmlSegment = AppUtils.parseJson<String>(htmlSegment)
            } catch (e: Exception) {
                if (htmlSegment.startsWith("\"") && htmlSegment.endsWith("\"")) {
                    htmlSegment = htmlSegment.substring(1, htmlSegment.length - 1)
                        .replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/")
                }
            }

            val results = Jsoup.parse(htmlSegment)
                .select("article.eael-grid-post")
                .mapNotNull { it.toSearchResponse() }
            val hasNextPage = page < loadData.maxPage
            return newSearchResponseList(results, hasNextPage)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("/cartoon-episode/")) {
            val doc = app.get(url).document
            val title = doc.selectFirst("h1.xpro-post-title")?.text()?.trim()
                ?: doc.selectFirst("title")?.text()?.substringBefore("|")?.trim() ?: return null
            val poster = doc.selectFirst("div.elementor-element-e7ee95b img")?.attr("src")
            val plot = doc.selectFirst("div.elementor-element-024e1d8")?.text()?.trim()

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val doc = app.get(url).document

        val title = doc.selectFirst("h1.anime-title")?.text()?.trim() ?: return null
        val poster = doc.selectFirst("div.cartoon-image img")?.attr("src")
        val plot = doc.selectFirst("div.brief-story p")?.text()?.trim()

        val episodes = doc.select("div.episodes-grid div.episode-box a").mapNotNull { el ->
            val href = el.attr("href")
            val name = el.text()
            val episodeNumber = Regex("""\s(\d+)$""").find(name)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(href) {
                this.name = name
                this.episode = episodeNumber
            }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val doc = app.get(data).document
        val videoSource = doc.selectFirst("video.easy-video-player > source")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: return false

        if (videoSource.endsWith(".mp4") || videoSource.endsWith(".m3u8")) {
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = videoSource,
                ) {
                    referer = mainUrl
                    quality = Qualities.Unknown.value
                }
            )
        } else {
            loadExtractor(videoSource, data, subtitleCallback, callback)
        }

        return true
    }
}
data class TemplateInfo(
    @JsonProperty("dir") val dir: String? = null,
    @JsonProperty("file_name") val fileName: String? = null,
    @JsonProperty("name") val name: String? = null
)

data class LoadMoreData(
    val widgetId: String,
    val pageId: String,
    val args: String,
    val clazz: String,
    val maxPage: Int,
    val nonce: String,
    val templateInfo: TemplateInfo?
)