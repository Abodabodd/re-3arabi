package com.faselhd
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.text.toIntOrNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.requestCreator
import com.lagradost.cloudstream3.utils.M3u8Helper
import java.math.BigInteger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import com.lagradost.cloudstream3.utils.getQualityFromName


class FASELHD(private val context: Context) : MainAPI() {
    override var name = "FASELHD"
    override var mainUrl = "https://www.faselhds.life"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        mainUrl to "الرئيسية",
        "$mainUrl/movies" to "أفلام أجنبي",
        "$mainUrl/series" to "مسلسلات أجنبية",
        "$mainUrl/hindi" to "أفلام هندي",
        "$mainUrl/asian-movies" to "أفلام آسيوية",
        "$mainUrl/anime" to "أنمي",
        "$mainUrl/anime-movies" to "أفلام أنمي"
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.selectFirst("a")?.attr("href")?.trim() ?: return null
        val title = this.selectFirst(".h1, .h4, .h5")?.text()?.trim() ?: return null
        val posterUrl = this.selectFirst("img")
            ?.let { it.attr("data-src").ifBlank { it.attr("src") } }
            ?.trim()

        if (href.isBlank() || title.isBlank()) return null

        val type = when {
            href.contains("/movies/") || href.contains("/hindi/") ||
                    href.contains("/asian-movies/") || href.contains("/anime-movies/") -> TvType.Movie

            href.contains("/series/") || href.contains("/asian-series/") ||
                    href.contains("/anime/") || href.contains("/seasons/") -> TvType.TvSeries

            else -> null
        } ?: return null

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, type) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && request.data != mainUrl) {
            if (request.data.contains("all_movies"))
                "${request.data.removeSuffix("/")}/page/$page"
            else
                "${request.data}/page/$page"
        } else {
            request.data
        }

        val document = app.get(url).document

        if (request.data == mainUrl) {
            val lists = mutableListOf<HomePageList>()

            val sliderItems = document.select("#homeSlide .swiper-slide").mapNotNull {
                val slideHref = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val slideTitle = it.selectFirst(".h1 a")?.text()?.trim() ?: return@mapNotNull null
                val slidePoster = it.selectFirst(".poster img")?.attr("src")
                newMovieSearchResponse(slideTitle, slideHref, TvType.Movie) {
                    this.posterUrl = slidePoster
                }
            }
            if (sliderItems.isNotEmpty()) {
                lists.add(HomePageList("أحدث الإضافات", sliderItems, isHorizontalImages = true))
            }

            document.select("div.slider")
                .firstOrNull { it.selectFirst(".h4")?.text()?.contains("مشاهدة") == true }
                ?.let { mostWatchedBlock ->
                    val title =
                        mostWatchedBlock.selectFirst(".h4")?.text() ?: "الأفلام الأكثر مشاهدة"
                    val items = mostWatchedBlock.select(".itemviews .postDiv")
                        .mapNotNull { it.toSearchResult() }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList(title, items, isHorizontalImages = true))
                    }
                }

            document.select("section#blockList").forEach { block ->
                val title = block.selectFirst(".blockHead .h3")?.text() ?: return@forEach
                if (!title.contains("آخر الأفلام المضافة")) {
                    val items = block.select(".blockMovie, .postDiv, .epDivHome")
                        .mapNotNull { it.toSearchResult() }
                    if (items.isNotEmpty()) {
                        lists.add(HomePageList(title, items))
                    }
                }
            }
            return HomePageResponse(lists.filter { it.list.isNotEmpty() }, hasNext = false)
        } else {
            val items = document.select(".postDiv, .blockMovie").mapNotNull { it.toSearchResult() }
            val hasNext = document.select("ul.pagination a[href*='/page/${page + 1}']").isNotEmpty()
            return newHomePageResponse(request.name, items, hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document
        return document.select("div#postList div.postDiv").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst(".singleInfo .title.h1")?.ownText()?.trim() ?: return null

        val poster = fixUrlNull(
            doc.selectFirst("meta[itemprop=image]")?.attr("content")
                ?: doc.selectFirst(".posterImg img.poster")?.attr("src")
        )

        val plot = doc.selectFirst(".singleDesc p, .story p")?.text()?.trim()

        val backgroundPoster = doc.selectFirst("div.singlePage")?.attr("style")
            ?.let { Regex("""url\(['"]?(.*?)['"]?\)""").find(it)?.groupValues?.get(1) }
            ?.let { fixUrlNull(it) }

        var year: Int? = null
        val tags = mutableListOf<String>()
        doc.select("#singleList > div").forEach {
            val text = it.text()
            when {
                text.contains("سنة الإنتاج") -> year = it.selectFirst("a")?.text()?.toIntOrNull()
                text.contains("تصنيف") -> tags.addAll(it.select("a").map { tagEl -> tagEl.text() })
            }
        }

        val recommendations = doc.select(".seasonDiv").mapNotNull { seasonEl ->
            val onclickAttr = seasonEl.attr("onclick")
            val seasonUrl = Regex("""window\.location\.href = '/\?p=\d+'""")
                .find(onclickAttr)?.value?.substringAfter("= '")?.removeSuffix("'")
                ?: return@mapNotNull null
            val seasonTitle = seasonEl.selectFirst(".title")?.text() ?: "موسم"
            val seasonPoster = seasonEl.selectFirst("img")?.attr("data-src")
            newTvSeriesSearchResponse(
                seasonTitle,
                fixUrl(seasonUrl),
                TvType.TvSeries
            ) { this.posterUrl = seasonPoster }
        }

        val episodes = doc.select("div#epAll a").mapNotNull { el ->
            val epUrl = el.attr("href").trim()
            if (epUrl.isBlank()) return@mapNotNull null
            val epName = el.ownText().ifBlank { el.text() }.trim()
            newEpisode(epUrl) {
                this.name = epName
                this.posterUrl = poster
                this.description = null
            }
        }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backgroundPoster
                this.year = year
                this.plot = plot
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
        val tag = "FASELHD_FINAL_DEBUG"
        Log.d(tag, "▶️ loadLinks start for: $data")
        try {
            var doc = app.get(data).document
            if (doc.select("title").text().contains("Just a moment")) {
                doc = app.get(data).document
            }

            val downloadHref = doc.select(".downloadLinks a").attr("href")
            if (downloadHref.isNotBlank()) {
                Log.d(tag, "Found download href: $downloadHref")
                val playerDoc = app.post(downloadHref, referer = mainUrl, timeout = 120).document
                val dlLink = playerDoc.select("div.dl-link a").attr("href")
                if (dlLink.isNotBlank()) {
                    Log.d(tag, "Found final download link: $dlLink")
                    val ex = newExtractorLink(
                        source = name,
                        name = "$name Download Source",
                        url = dlLink,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                    callback.invoke(ex)
                    return true
                } else {
                    Log.w(tag, "download block present but no dlLink found")
                }
            } else {
                Log.d(tag, "No download link found on page")
            }

            val iframeSrc = doc.select("iframe[name=\"player_iframe\"]").attr("src")
            if (iframeSrc.isNotBlank()) {
                Log.d(tag, "Found iframe src: $iframeSrc")
                val resolver = WebViewResolver(
                    interceptUrl = Regex("""(?i)\.m3u8"""),
                    script = """
                    (function(){
                        const btn = document.querySelector('.play-button, .btn-play, button.play, .play-btn, a.play');
                        if (btn) {
                            btn.click();
                            return 'clicked';
                        }
                        return document.documentElement.outerHTML;
                    })();
                """.trimIndent(),
                    scriptCallback = { jsRes ->
                        Log.d(tag, "WebView JS callback (HTML full content): $jsRes")
                        val m3u8Regex = """https?:\/\/[^\s"'<>]+\.m3u8""".toRegex()
                        val match = m3u8Regex.find(jsRes)
                        if (match != null) {
                            val finalUrl = match.value
                            Log.d(tag, "✅ Extracted m3u8 URL: $finalUrl")
                            GlobalScope.launch(Dispatchers.Main) {
                                val ex = newExtractorLink(
                                    source = name,
                                    name = "$name (Web m3u8)",
                                    url = finalUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = iframeSrc
                                    this.quality = Qualities.Unknown.value
                                }
                                callback.invoke(ex)
                            }
                        } else {
                            Log.w(tag, "No m3u8 URL found in HTML")
                        }
                    },
                    timeout = 30_000L
                )
                resolver.resolveUsingWebView(iframeSrc, referer = data)
                return true
            } else {
                Log.w(tag, "No iframe[name=\"player_iframe\"] found in page")
            }
        } catch (e: Exception) {
            Log.e(tag, "loadLinks fatal error", e)
        }
        Log.d(tag, "❌ loadLinks finished (no links)")
        return false
    }
}
