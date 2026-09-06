package com.alooy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

class AlooyTvProvider : MainAPI() {
    override var mainUrl = "https://fitnur.com/alooytv"
    override var name = "AlooyTv"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.Anime)
    private var actualDomain: String? = null

    private val homepageSections = listOf(
        "/tv-series.html" to "أحدث الحلقات",
        "/genre/ramadan-kleeji-2026.html" to "رمضان خليجي",
        "/genre/ramadan-arabi-2026.html" to "رمضان عربي",
        "/genre/turki.html" to "مسلسلات تركية",
        "/genre/farisi.html" to "مسلسلات فارسية"
    )
    private suspend fun getActualDomain(): String {
        actualDomain?.let { return it }

        return try {
            val doc = app.get(mainUrl).document
            val link = doc.select("a[href*='alooytv'][href*='.xyz']").firstOrNull()?.attr("href")

            if (link != null) {
                val uri = java.net.URI(link)
                val domain = "${uri.scheme}://${uri.host}"
                actualDomain = domain
                domain
            } else {
                "https://n.alooytv14.xyz"
            }
        } catch (e: Exception) {
            "https://n.alooytv14.xyz"
        }
    }

    private fun Element.toSearchResult(domain: String): SearchResponse? {
        val a = this.selectFirst(".movie-title h3 a") ?: return null
        val title = a.text().trim()
        val href = a.attr("href")
        val url = if (href.startsWith("http")) href else "$domain/${href.trimStart('/')}"

        val posterUrl = this.selectFirst("img.lazy")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) throw ErrorLoadingException("لا يوجد صفحات إضافية")
        val domain = getActualDomain()
        val items = ArrayList<HomePageList>()
        val parallelResults = kotlinx.coroutines.coroutineScope {
            homepageSections.map { (path, title) ->
                async {
                    try {
                        val fullUrl = "$domain/${path.trimStart('/')}"
                        val doc = app.get(fullUrl).document
                        val list = doc.select(".movie-container > div").mapNotNull { it.toSearchResult(domain) }
                        if (list.isNotEmpty()) HomePageList(title, list) else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        items.addAll(parallelResults)
        return newHomePageResponse(items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val domain = getActualDomain()
        val url = "$domain/search?q=$query"
        return app.get(url).document.select(".movie-container > div").mapNotNull {
            it.toSearchResult(domain)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val domain = getActualDomain()
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst(".tab-content .col-md-3 img")?.attr("abs:src")
        val description = document.selectFirst(".tab-content .col-md-9 p")?.text()?.trim()

        val episodes = document.select(".season a.btn-ep").map { element ->
            val epName = element.text()
            val epUrl = element.attr("abs:href")
            newEpisode(epUrl) {
                this.name = epName
                this.episode = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                this.posterUrl = poster
            }
        }.ifEmpty {
            listOf(newEpisode(url) { name = "مشاهدة الفيلم" })
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val domain = getActualDomain()
        val document = app.get(data).document

        document.select("video source").forEach { source ->
            val videoUrl = source.attr("abs:src").ifBlank { source.attr("src") }
            if (videoUrl.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(this.name, "AlooyTv Server", videoUrl) {
                        referer = "$domain/"
                        quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }
}
