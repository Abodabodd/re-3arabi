package com.anime4up

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
class Anime4up : MainAPI() {
    override var mainUrl = "https://w1.anime4up.rest"
    override var name = "Anime4Up"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override var lang = "ar"
    override val hasMainPage = true
    private var savedCookies: String? = null
    private val cfMutex = Mutex()
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    private fun log(tag: String, msg: String) {
        println("Anime4upDebug | [$tag] -> $msg")
    }

    private fun buildHeaders(referer: String? = null, customHeaders: Map<String, String>? = null): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to userAgent,
            "Accept-Language" to "ar,en-US;q=0.9",
            "Upgrade-Insecure-Requests" to "1"
        )
        referer?.let { headers["Referer"] = it }
        savedCookies?.let { headers["Cookie"] = it }
        customHeaders?.let { headers.putAll(it) }
        return headers
    }
    private suspend fun safeGet(
        url: String,
        referer: String? = null,
        customHeaders: Map<String, String>? = null
    ): com.lagradost.nicehttp.NiceResponse {
        var currentRequestUrl = url
        var headers = buildHeaders(referer, customHeaders)

        var res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        if (res.code in listOf(403, 503, 429)) {
            cfMutex.withLock {
                val currentCookies = android.webkit.CookieManager.getInstance().getCookie(currentRequestUrl)
                if (currentCookies != null && currentCookies != savedCookies && currentCookies.contains("cf_clearance")) {
                    log("SAFE-GET", "تم حل كلاودفلير مسبقاً، استخدام الكوكيز الجاهزة.")
                    savedCookies = currentCookies
                } else {
                    log("SAFE-GET", "تم اكتشاف حماية (Code: ${res.code}). تشغيل صياد الكوكيز...")
                    val activity = CommonActivity.activity ?: com.lagradost.cloudstream3.CommonActivity.activity

                    if (activity != null) {
                        val solverResult = CloudflareSolver.solve(activity, currentRequestUrl, userAgent)

                        if (solverResult != null) {
                            if (!solverResult.cookies.isNullOrEmpty()) {
                                savedCookies = solverResult.cookies
                                log("SAFE-GET", "تم حفظ الكوكيز بنجاح.")
                            }
                            if (solverResult.finalUrl != currentRequestUrl) {
                                log("DOMAIN-UPDATE", "تم التوجيه إلى: ${solverResult.finalUrl}")
                                try {
                                    val newHost = java.net.URL(solverResult.finalUrl).host
                                    mainUrl = "https://$newHost"
                                } catch (e: Exception) {}
                                currentRequestUrl = solverResult.finalUrl
                            }
                        }
                    }
                }
            }
            headers = buildHeaders(referer, customHeaders)
            res = app.get(currentRequestUrl, headers = headers, timeout = 30)
        }

        return res
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = safeGet(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        fun getImageUrl(element: Element): String? {
            val img = element.selectFirst("img")
            if (img != null) {
                val src = img.attr("data-image").ifBlank { img.attr("data-src") }.ifBlank { img.attr("src") }
                if (src.isNotBlank()) return src
            }

            val imageContainer = element.selectFirst(".image")
            if (imageContainer != null) {
                val dataSrc = imageContainer.attr("data-src")
                if (dataSrc.isNotBlank()) return dataSrc

                val style = imageContainer.attr("style")
                if (style.contains("url")) {
                    return style.substringAfter("url(").substringBefore(")")
                        .replace("\"", "")
                        .replace("'", "")
                        .replace("&quot;", "")
                }
            }
            return null
        }

        doc.select(".main-widget").forEach { widget ->
            val sectionName = widget.selectFirst(".main-didget-head h3")?.text() ?: "القسم"

            val items = widget.select(".themexblock, .anime-card-container").mapNotNull { element ->
                val title = element.selectFirst("h3")?.text() ?: return@mapNotNull null
                val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null

                newAnimeSearchResponse(title, link) {
                    this.posterUrl = getImageUrl(element)
                    this.posterHeaders = buildHeaders()
                }
            }

            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(sectionName, items))
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val doc = safeGet(url).document

        return doc.select("div.anime-grid div.anime-card-themex").mapNotNull { element ->
            val titleElement = element.selectFirst(".anime-card-title h3 a") ?: return@mapNotNull null
            val title = titleElement.text()
            val href = titleElement.attr("href")

            val img = element.selectFirst("img")
            val posterUrl = img?.attr("data-image")?.ifBlank { img.attr("src") }

            val typeText = element.selectFirst(".anime-card-type")?.text() ?: ""
            val type = if (typeText.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

            newAnimeSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
                this.posterHeaders = buildHeaders()
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var animeUrl = url
        var animeDoc = safeGet(url).document

        if (url.contains("/episode/")) {
            val parentAnimeLink = animeDoc.selectFirst(".anime-page-link a")?.attr("href")
            if (!parentAnimeLink.isNullOrBlank()) {
                animeUrl = parentAnimeLink
                animeDoc = safeGet(animeUrl).document
            }
        }

        val title = animeDoc.selectFirst("h1.anime-details-title")?.text() ?: "Unknown"
        val poster = animeDoc.selectFirst(".anime-thumbnail img")?.let {
            it.attr("data-image").ifBlank { it.attr("data-src") }.ifBlank { it.attr("src") }
        }
        val plot = animeDoc.selectFirst("p.anime-story")?.text()
        val tags = animeDoc.select("ul.anime-genres li a").map { it.text() }
        val year = animeDoc.select(".anime-info").firstOrNull {
            it.text().contains("بداية العرض")
        }?.text()?.filter { it.isDigit() }?.toIntOrNull()

        val typeText = animeDoc.select(".anime-info").text()
        val type = if (typeText.contains("Movie", true) || typeText.contains("فيلم")) TvType.AnimeMovie else TvType.Anime

        val episodes = mutableListOf<Episode>()

        val firstEpLink = animeDoc.selectFirst(".anime-external-links a.anime-first-ep")?.attr("href")
            ?: animeDoc.selectFirst("#episodesList .themexblock a")?.attr("href")

        if (!firstEpLink.isNullOrBlank()) {
            val epDoc = safeGet(firstEpLink).document
            val sidebarEpisodes = epDoc.select("ul.all-episodes-list li a")

            if (sidebarEpisodes.isNotEmpty()) {
                sidebarEpisodes.forEach { element ->
                    val epUrl = element.attr("href")
                    val epName = element.text().trim()
                    val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                    episodes.add(newEpisode(epUrl) {
                        name = epName
                        posterUrl = poster
                        episode = epNum
                    })
                }
            }
        }

        if (episodes.isEmpty()) {
            animeDoc.select("#episodesList .themexblock").forEach { element ->
                val epUrl = element.selectFirst("a")?.attr("href") ?: return@forEach
                val epName = element.selectFirst(".badge.light-soft span")?.text() ?: "Episode"
                val epNum = epName.replace(Regex("[^0-9]"), "").toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    name = epName
                    posterUrl = poster
                    episode = epNum
                })
            }
        }

        val finalEpisodes = episodes.distinctBy { it.data }.sortedBy { it.episode ?: 0 }

        return newTvSeriesLoadResponse(title, animeUrl, type, finalEpisodes) {
            this.posterUrl = poster
            this.posterHeaders = buildHeaders()
            this.plot = plot
            this.tags = tags
            this.year = year
        }
    }

    @Serializable
    data class Share4maxMirror(@SerialName("link") val link: String?, @SerialName("driver") val driver: String?)

    @Serializable
    data class Share4maxQuality(@SerialName("label") val label: String?, @SerialName("mirrors") val mirrors: List<Share4maxMirror>?)

    @Serializable
    data class Share4maxStreamsData(@SerialName("data") val data: List<Share4maxQuality>?)

    @Serializable
    data class Share4maxProps(@SerialName("streams") val streams: Share4maxStreamsData?)

    @Serializable
    data class Share4maxInertiaResponse(@SerialName("props") val props: Share4maxProps?)

    @Serializable
    data class Share4maxInitialPage(@SerialName("version") val version: String?)

    private suspend fun processMegabox(url: String, referer: String): List<String> {
        val extractedIframes = mutableListOf<String>()

        try {
            val targetUrl = url
            val initialResponse = safeGet(targetUrl, referer = referer)
            val soup = initialResponse.document
            val version = soup.selectFirst("script[data-page=app]")?.html()?.let {
                parseJson<Share4maxInitialPage>(it).version
            }

            if (version == null) return emptyList()

            val inertiaHeaders = mapOf(
                "X-Inertia" to "true",
                "X-Inertia-Partial-Component" to "files/mirror/video",
                "X-Inertia-Partial-Data" to "streams",
                "X-Inertia-Version" to version,
                "X-Requested-With" to "XMLHttpRequest"
            )
            val streamResponse = safeGet(targetUrl, referer = referer, customHeaders = inertiaHeaders)
            val streamJson = parseJson<Share4maxInertiaResponse>(streamResponse.text)

            streamJson.props?.streams?.data?.forEach { qualityLevel ->
                qualityLevel.mirrors?.forEach { mirror ->
                    mirror.link?.let { link ->
                        val finalUrl = if (link.startsWith("//")) "https:$link" else link
                        extractedIframes.add(finalUrl)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return extractedIframes
    }



    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = safeGet(data).document
        val seenLinks = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val semaphore = Semaphore(6)
        supervisorScope {
            val watchTasks = doc.select("ul#episode-servers li[data-watch]").map { li ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val serverUrl = li.attr("data-watch")
                            val linksToProcess = if (serverUrl.contains("share4max") || serverUrl.contains("megamax")) {
                                processMegabox(serverUrl, data)
                            } else {
                                listOf(serverUrl)
                            }

                            linksToProcess.forEach { link ->
                                if (link.isNotBlank() && seenLinks.add(link)) {
                                    loadExtractor(link, data, subtitleCallback, callback)
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            val downloadTasks = doc.select("div.download-list table.table tbody tr").map { tr ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val downloadLink = tr.selectFirst("td.td-link a")?.attr("href")

                            if (!downloadLink.isNullOrBlank() && seenLinks.add(downloadLink)) {
                                loadExtractor(downloadLink, data, subtitleCallback, callback)
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            (watchTasks + downloadTasks).awaitAll()
        }

        return true
    }
}
