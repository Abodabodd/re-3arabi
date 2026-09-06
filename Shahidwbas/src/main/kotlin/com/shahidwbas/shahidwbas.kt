package com.shahidwbas

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.Jsoup

class ShahidWBasProvider : MainAPI() {
    override var name = "ShahidWBas"
    override var mainUrl = "https://w30.shahidwbas.tv"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/")) return "$mainUrl$url"
        return url
    }
    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.select("a").firstOrNull { it.attr("href").contains("watch.php") } ?: return null
        val href = fixUrl(a.attr("href"))
        val title = (a.attr("title").ifBlank { this.selectFirst(".caption h3 a")?.text() } ?: a.text()).trim()

        if (title.isEmpty()) return null

        val posterUrl = this.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?: this.selectFirst(".postImgBg")?.attr("style")?.substringAfter("url('")?.substringBefore("')")?.let { fixUrl(it) }

        val isTv = title.contains("حلقة") || title.contains("الموسم") || this.selectFirst(".ep") != null

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/ajax-search.php",
            data = mapOf("queryString" to query),
            headers = mapOf("Referer" to "$mainUrl/", "X-Requested-With" to "XMLHttpRequest")
        ).text
        return Jsoup.parseBodyFragment(response).select("li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/index.php").document
        val sections = mutableListOf<HomePageList>()
        val latest = document.select("ul.pm-ul-browse-videos li").mapNotNull { it.toSearchResult() }
        if (latest.isNotEmpty()) sections.add(HomePageList("جديد الموقع", latest))
        document.select("div.row.pm-featured-cat-row").forEach { row ->
            val catName = row.selectFirst("h2 a")?.text()?.trim() ?: return@forEach
            val items = row.select("li").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) sections.add(HomePageList(catName, items))
        }
        return newHomePageResponse(sections)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }
        val plot = document.selectFirst("div[itemprop=description]")?.text()?.trim()
        val seasonsBox = document.selectFirst("div.SeasonsBox")

        return if (seasonsBox != null) {
            val episodes = mutableListOf<Episode>()
            document.select("div.SeasonsBoxUL button.tablinks").forEachIndexed { index, tab ->
                val seasonNum = tab.text().filter { it.isDigit() }.toIntOrNull() ?: (index + 1)
                document.select("#Season$index a[href*='watch.php']").reversed().forEach { epLink ->
                    val epName = epLink.text().trim()
                    val vid = epLink.attr("href").substringAfter("vid=").substringBefore("&")
                    episodes.add(newEpisode("$mainUrl/play.php?vid=$vid") {
                        this.name = epName
                        this.season = seasonNum
                        this.episode = epName.filter { it.isDigit() }.toIntOrNull()
                        this.posterUrl = poster
                    })
                }
            }
            newTvSeriesLoadResponse(rawTitle, url, TvType.TvSeries, episodes) { this.posterUrl = poster; this.plot = plot }
        } else {
            val vid = url.substringAfter("vid=").substringBefore("&")
            newMovieLoadResponse(rawTitle, url, TvType.Movie, "$mainUrl/play.php?vid=$vid") { this.posterUrl = poster; this.plot = plot }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val referer = data.replace("play.php", "watch.php")
        val document = app.get(data, referer = referer).document

        document.select("ul.list_servers li").forEach { li ->
            val serverName = li.select("strong").text().trim()
            val embedHtml = li.attr("data-embed")

            if (embedHtml.isNotEmpty()) {
                val iframeSrc = Jsoup.parseBodyFragment(embedHtml).selectFirst("iframe")?.attr("src") ?: return@forEach
                val finalIframeUrl = fixUrl(iframeSrc)

                if (finalIframeUrl.contains("liiivideo.com")) {
                    try {
                        val iframePage = app.get(finalIframeUrl, referer = data).text
                        val masterM3u8 = Regex("""file\s*:\s*"(.*?)"""").find(iframePage)?.groupValues?.get(1)

                        if (masterM3u8 != null) {
                            val m3u8Content = app.get(masterM3u8, referer = finalIframeUrl).text
                            val lines = m3u8Content.split("\n")
                            var currentRes = ""

                            lines.forEach { line ->
                                if (line.contains("RESOLUTION=")) {
                                    currentRes = line.substringAfter("RESOLUTION=").substringAfter("x").substringBefore(",")
                                } else if (line.contains(".m3u8")) {
                                    val absoluteUrl = if (line.startsWith("http")) line
                                    else masterM3u8.substringBeforeLast("/") + "/" + line

                                    callback.invoke(
                                        newExtractorLink(
                                            name = "VipServer $currentRes" + "p",
                                            source = "VipServer",
                                            url = absoluteUrl,
                                        ) {
                                            this.referer = finalIframeUrl
                                            quality =
                                                currentRes.toIntOrNull() ?: Qualities.Unknown.value
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {}
                } else {
                    loadExtractor(finalIframeUrl, data, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}