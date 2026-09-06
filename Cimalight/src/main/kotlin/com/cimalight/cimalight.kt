package com.cimalight
import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class CimaLightProvider : MainAPI() {

    override var mainUrl = "https://r.cimalight.co"
    override var name = "CimaLight"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val TAG = "CimaLightLog"

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/main16" to "الرئيسية",
        "$mainUrl/movies.php" to "أحدث الأفلام",
        "$mainUrl/episodes.php" to "أحدث الحلقات",
        "$mainUrl/all-series.php" to "أحدث المسلسلات"
    )
    private val customHeaders = mapOf(
        "Cache-Control" to "no-cache, no-store, must-revalidate",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"146\", \"Not-A.Brand\";v=\"24\", \"Google Chrome\";v=\"146\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\"",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-User" to "?1",
        "Sec-Fetch-Dest" to "document",
        "Accept-Language" to "ar-EG,ar;q=0.9",
        "Pragma" to "no-cache"
    )

    private var savedCookies: String? = null
    private suspend fun getDocOrSolve(url: String): Document? {
        Log.d(TAG, "جاري طلب الرابط: $url")
        val headersWithCookies = customHeaders.toMutableMap().apply {
            savedCookies?.let { put("Cookie", it) }
        }

        val response = app.get(url, headers = headersWithCookies)
        val statusCode = response.code

        Log.d(TAG, "Status Code: $statusCode")
        if (statusCode in 200..299) {
            return response.document
        }
        if (statusCode == 403 || statusCode == 503 || statusCode == 429) {
            Log.e(TAG, "تم اكتشاف حماية Cloudflare (كود $statusCode) → جاري تشغيل Solver")

            val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity

            val result = CloudflareSolver.solve(
                activity,
                url,
                userAgent
            )
            val cookies = CookieManager.getInstance().getCookie(url)

            if (!cookies.isNullOrEmpty()) {
                Log.d(TAG, "تم الحصول على cookies بنجاح: $cookies")
                savedCookies = cookies
                val retryHeaders = customHeaders.toMutableMap().apply {
                    put("Cookie", cookies)
                }

                val retryResponse = app.get(url, headers = retryHeaders)
                Log.d(TAG, "Retry Status: ${retryResponse.code}")

                if (retryResponse.code in 200..299) {
                    return retryResponse.document
                }
            }
            return result
        }
        return response.document
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = getDocOrSolve(request.data) ?: return newHomePageResponse(emptyList())
        val homePageList = ArrayList<HomePageList>()
        val addedSectionNames = mutableSetOf<String>()

        if (request.data.contains("main16")) {
            val featured = document.select("ul[id*=pm-carousel_featured] > li, div.sSlide.feat li.block-post")
            if (featured.isNotEmpty()) {
                val items = featured.mapNotNull { it.toSearchResult() }
                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList("أعمال مميزة", items))
                    addedSectionNames.add("أعمال مميزة")
                }
            }
        }

        val sections = document.select("div.pm-section-head, h2.pm-section-title")
        sections.forEach { head ->
            val title = head.selectFirst("h2 a")?.text()?.trim()
                ?: head.selectFirst("h2")?.text()?.trim()
                ?: head.text().trim()

            if (title.isEmpty() || title.contains("جديد الموقع") || title.contains("أحدث الأفلام") || addedSectionNames.contains(title)) {
                return@forEach
            }

            var parent = head.parent()
            var items = parent?.select("ul[class*='pm-ul-'] > li, div.block-series")?.mapNotNull { it.toSearchResult() }

            if (items.isNullOrEmpty() && parent != null) {
                parent = parent.parent()
                items = parent?.select("ul[class*='pm-ul-'] > li, div.block-series")?.mapNotNull { it.toSearchResult() }
            }

            if (!items.isNullOrEmpty()) {
                homePageList.add(HomePageList(title, items))
                addedSectionNames.add(title)
            }
        }

        if (!addedSectionNames.contains("مسلسلات مميزة")) {
            val sidebarSeries = document.select("div.block-series").mapNotNull { it.toSearchResult() }
            if (sidebarSeries.isNotEmpty()) {
                homePageList.add(HomePageList("مسلسلات مميزة", sidebarSeries))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a:not(.pm-watch-later-add)") ?: return null
        val href = fixUrl(linkElement.attr("href"))

        val title = this.selectFirst("h3 a")?.text()?.trim()
            ?: this.selectFirst("div.title")?.text()?.trim()
            ?: linkElement.attr("title").trim()

        if (title.isEmpty() || href.contains("#")) return null

        val posterUrl = this.selectFirst("img")?.attr("src")
            ?: this.selectFirst("div[style*=background-image]")?.attr("style")?.substringAfter("url('")?.substringBefore("')")

        val isTvSeries = href.contains("/episode/") || href.contains("/series/") || title.contains("مسلسل") || this.selectFirst("span.ep, div.ribbon") != null

        return if (isTvSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrlNull(posterUrl)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search.php?keywords=$query"
        val document = getDocOrSolve(url) ?: return emptyList()
        return document.select("ul#pm-grid li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = getDocOrSolve(url) ?: return null

        val title = document.selectFirst("h1[itemprop=name]")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.video-bibplayer-poster")?.attr("style")?.substringAfter("url(")?.substringBefore(")"))
        val description = document.selectFirst("div[itemprop=description] p")?.text()?.trim()
        val isTvSeries = document.select("div.SeasonsBox").isNotEmpty() || title.contains("مسلسل")

        if (isTvSeries) {
            val episodes = ArrayList<Episode>()
            val seasonElements = document.select("div.SeasonsEpisodesMain div.tabcontent")

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonElement ->
                    val seasonId = seasonElement.attr("id")
                    val seasonName = document.selectFirst("button[onclick*='$seasonId']")?.text()?.trim()
                    val seasonNum = seasonName?.filter { it.isDigit() }?.toIntOrNull() ?: 1

                    seasonElement.select("ul a").forEach { epElement ->
                        val epHref = fixUrl(epElement.attr("href"))
                        val epName = epElement.text().trim()
                        val epNum = epName.filter { it.isDigit() }.toIntOrNull()

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            } else {
                episodes.add(newEpisode(url) {
                    this.name = "الحلقة المحددة"
                    this.posterUrl = poster
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val mainPageDoc = getDocOrSolve(data) ?: return false
        val intermediateUrl = mainPageDoc.selectFirst("a.xtgo")?.attr("href") ?: return false
        val headersWithReferer = customHeaders
            .filterKeys { it != "Host" }
            .toMutableMap()
            .apply {
                put("Referer", mainUrl)
                savedCookies?.let { put("Cookie", it) } // تمرير الكوكيز إذا وجدت
            }

        val intermediateDoc = app.get(intermediateUrl, headers = headersWithReferer).document
        intermediateDoc.select("div.embeding ul li").amap { serverElement ->
            val serverUrl = serverElement.attr("data-embed")
            if (serverUrl.startsWith("http")) {
                loadExtractor(serverUrl, data, subtitleCallback, callback)
            }
        }

        return true
    }
}