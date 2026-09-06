package com.phoenix

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import com.lagradost.cloudstream3.utils.newExtractorLink

class AnimePhoenixProvider : MainAPI() {
    override var mainUrl = "https://anime-phoenix.com"
    override var name = "anime-phoenix"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        val url = if (page == 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(url, headers = customHeaders).document

        val homePageRows = mutableListOf<HomePageList>()
        val latestSection = document.selectFirst("main.FJ-Phoenix-Anastasia-Latest")
        if (latestSection != null) {
            val sectionTitle = latestSection.selectFirst("h2.FJ-Phoenix-Anastasia-Title")?.text() ?: "آخر الحلقات المضافة"
            val latestItems = latestSection.select("a.FJ-Phoenix-Anastasia-EpCard").mapNotNull { card ->
                val cardName = card.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")?.text() ?: return@mapNotNull null
                val epMeta = card.selectFirst(".FJ-Phoenix-Anastasia-EpCard-MetaModern")?.text() ?: ""
                val cleanTitle = if (epMeta.isNotEmpty()) "$cardName - $epMeta" else cardName
                val href = card.attr("href") ?: return@mapNotNull null
                val cleanHref = if (href.startsWith("http")) href else "$mainUrl$href"
                val poster = card.selectFirst("img.FJ-Phoenix-Anastasia-EpCard-Img")?.attr("src")

                newAnimeSearchResponse(cleanTitle, cleanHref, TvType.Anime).apply {
                    this.posterUrl = poster
                }
            }
            if (latestItems.isNotEmpty()) {
                homePageRows.add(HomePageList(sectionTitle, latestItems.map { it as SearchResponse }))
            }
        }
        val columns = document.select("section.home-cols div.home-cols-col")
        columns.forEach { col ->
            val colTitle = col.selectFirst("h2.home-cols-title")?.text() ?: ""
            val colItems = col.select("a.home-cols-card").mapNotNull { card ->
                val title = card.selectFirst("h3.home-cols-name")?.text() ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null
                val cleanHref = if (href.startsWith("http")) href else "$mainUrl$href"
                val poster = card.selectFirst("img.home-cols-thumb")?.attr("src")
                val isMovie = href.contains("/movies/")

                newAnimeSearchResponse(title, cleanHref, if (isMovie) TvType.AnimeMovie else TvType.Anime).apply {
                    this.posterUrl = poster
                }
            }
            if (colTitle.isNotEmpty() && colItems.isNotEmpty()) {
                homePageRows.add(HomePageList(colTitle, colItems.map { it as SearchResponse }))
            }
        }
        val movieSections = document.select("section.FJ-Phoenix-Anastasia-Movies")
        movieSections.forEach { section ->
            val sectionTitle = section.selectFirst(".FJ-Phoenix-Anastasia-Title")?.text() ?: ""
            val items = section.select("a.FJ-Phoenix-Anastasia-EpCard").mapNotNull { card ->
                val title = card.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")?.text() ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null
                val cleanHref = if (href.startsWith("http")) href else "$mainUrl$href"
                val poster = card.selectFirst("img.FJ-Phoenix-Anastasia-EpCard-Img")?.attr("src")
                val isMovie = href.contains("/movies/")

                newAnimeSearchResponse(title, cleanHref, if (isMovie) TvType.AnimeMovie else TvType.Anime).apply {
                    this.posterUrl = poster
                }
            }
            if (sectionTitle.isNotEmpty() && items.isNotEmpty()) {
                homePageRows.add(HomePageList(sectionTitle, items.map { it as SearchResponse }))
            }
        }
        return newHomePageResponse(homePageRows, hasNext = true)
    }
    private suspend fun getNonce(query: String): String {
        return try {
            val searchLandingUrl = "$mainUrl/search/?q=${URLEncoder.encode(query, "UTF-8")}"
            val html = app.get(searchLandingUrl).text
            val nonceRegex = """"nonce"\s*:\s*"([a-f0-9]+)"""".toRegex()
            nonceRegex.find(html)?.groupValues?.get(1) ?: "25ccbcb8fb"
        } catch (e: Exception) {
            "25ccbcb8fb"
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val nonce = getNonce(query)
        val searchResults = mutableListOf<SearchResponse>()

        try {
            val response = app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                ),
                data = mapOf(
                    "action" to "phoenix_search",
                    "nonce" to nonce,
                    "q" to query,
                    "type" to "all",
                    "genre" to "",
                    "status" to "",
                    "year" to "",
                    "season" to "",
                    "sort" to "relevance",
                    "page" to "1",
                    "per_page" to "25",
                    "dropdown" to "0"
                )
            ).text

            val json = JSONObject(response)
            if (json.optBoolean("success")) {
                val data = json.getJSONObject("data")
                val results = data.getJSONArray("results")

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val titleAr = item.optString("title_ar")
                    val titleEn = item.optString("title_en")
                    val title = if (titleAr.isNotEmpty()) titleAr else titleEn
                    val href = item.optString("url")
                    val poster = item.optString("thumbnail_url")
                    val itemType = item.optString("item_type")

                    val tvType = if (itemType == "movie") TvType.AnimeMovie else TvType.Anime

                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        searchResults.add(
                            newAnimeSearchResponse(title, href, tvType).apply {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            try {
                val searchUrl = "$mainUrl/search/?q=${URLEncoder.encode(query, "UTF-8")}"
                val response = app.get(searchUrl).text
                val soup = Jsoup.parse(response)
                soup.select("div.FJ-episode-wrap").forEach { wrap ->
                    val titleEl = wrap.selectFirst("a.FJ-Phoenix-Anastasia-EpCard-Name")
                    val linkEl = wrap.selectFirst("a.FJ-episode-img-box") ?: wrap.selectFirst("a")
                    val title = titleEl?.text() ?: ""
                    val href = linkEl?.attr("href") ?: ""
                    if (title.isNotEmpty() && href.isNotEmpty()) {
                        searchResults.add(
                            newAnimeSearchResponse(title, href, TvType.Anime)
                        )
                    }
                }
            } catch (inner: Exception) {
            }
        }
        return searchResults
    }
    private val customHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
        "Referer" to mainUrl
    )


    override suspend fun load(url: String): LoadResponse? {
        println("AnimePhoenix_DEBUG: Entering load() with url = $url")
        android.util.Log.d("AnimePhoenix", "Entering load() with url = $url")

        val response = try {
            app.get(url, headers = customHeaders)
        } catch (e: Exception) {
            println("AnimePhoenix_DEBUG: Exception during app.get(url): ${e.message}")
            return null
        }

        val finalUrl = response.url
        val htmlText = response.text
        val statusCode = response.code

        println("AnimePhoenix_DEBUG: Initial Request Status Code = $statusCode")
        println("AnimePhoenix_DEBUG: Final Redirected URL = $finalUrl")
        println("AnimePhoenix_DEBUG: HTML Length = ${htmlText.length}")

        val document = response.document

        val title = document.selectFirst("h1.FJ-Phoenix-Hero-Title")?.text()
            ?: document.selectFirst("h1.FJ-CC-Title")?.text()
            ?: ""
        val poster = document.selectFirst(".FJ-Phoenix-Hero-Poster img")?.attr("src")
        val description = document.selectFirst(".FJ-Phoenix-Desc-Full")?.text()
        val isMovie = finalUrl.contains("/movies/")

        println("AnimePhoenix_DEBUG: Extracted Title = $title")
        println("AnimePhoenix_DEBUG: Extracted Poster = $poster")
        println("AnimePhoenix_DEBUG: Is Movie? = $isMovie")

        if (isMovie) {
            val watchBtn = document.selectFirst("a.FJ-Btn-Watch")
            val watchUrl = watchBtn?.attr("href") ?: "$finalUrl/watch"
            println("AnimePhoenix_DEBUG: Movie watchUrl resolved to = $watchUrl")

            return newMovieLoadResponse(title, finalUrl, TvType.AnimeMovie, watchUrl).apply {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val episodesPageUrl = "${finalUrl.removeSuffix("/")}/episodes/"

            try {
                val epResponse = app.get(episodesPageUrl, headers = customHeaders)
                val epDocument = epResponse.document
                val epHtml = epResponse.text
                val firstGroup = epDocument.select("div#episodesGrid a.FJ-episode-wrap").mapNotNull { item ->
                    val titleEl = item.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")
                    val epTitle = titleEl?.text() ?: return@mapNotNull null
                    val epUrl = item.attr("href") ?: return@mapNotNull null
                    val cleanTitle = epTitle.replace("\\s+".toRegex(), " ").trim()
                    val epNum = """(\d+)$""".toRegex().find(cleanTitle)?.groupValues?.get(1)?.toIntOrNull()

                    newEpisode(epUrl) {
                        this.name = cleanTitle
                        this.episode = epNum
                        this.season = 1
                        this.posterUrl = poster // تعيين بوستر المسلسل للحلقة
                    }
                }

                if (firstGroup.isNotEmpty()) {
                    episodes.addAll(firstGroup)
                    val tokenRegex = """"token"\s*:\s*"([^"]+)"""".toRegex()
                    val sigRegex = """"sig"\s*:\s*"([^"]+)"""".toRegex()

                    val token = tokenRegex.find(epHtml)?.groupValues?.get(1)
                    val sig = sigRegex.find(epHtml)?.groupValues?.get(1)
                    val paginationEl = epDocument.selectFirst("#pagination") ?: epDocument.selectFirst(".FJ-Phoenix-Anastasia-Pagination-Wrap")
                    val totalPages = paginationEl?.attr("data-total")?.toIntOrNull() ?: 1

                    if (totalPages > 1 && token != null && sig != null) {
                        val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php?action=fj_get_episodes&token=${URLEncoder.encode(token, "UTF-8")}&sig=$sig&page=$totalPages&sort=oldest&search="
                        val ajaxResponse = app.get(ajaxUrl, headers = customHeaders).text
                        val json = JSONObject(ajaxResponse)

                        if (json.optBoolean("success")) {
                            val data = json.getJSONObject("data")
                            val html = data.optString("html")
                            if (html.isNotEmpty()) {
                                val lastPageDoc = Jsoup.parse(html)
                                val lastGroup = lastPageDoc.select("a.FJ-episode-wrap").mapNotNull { item ->
                                    val titleEl = item.selectFirst(".FJ-Phoenix-Anastasia-EpCard-Name")
                                    val epTitle = titleEl?.text() ?: return@mapNotNull null
                                    val epUrl = item.attr("href") ?: return@mapNotNull null
                                    val cleanTitle = epTitle.replace("\\s+".toRegex(), " ").trim()
                                    val epNum = """(\d+)$""".toRegex().find(cleanTitle)?.groupValues?.get(1)?.toIntOrNull()

                                    newEpisode(epUrl) {
                                        this.name = cleanTitle
                                        this.episode = epNum
                                        this.season = 1
                                        this.posterUrl = poster // تعيين بوستر المسلسل للحلقة
                                    }
                                }

                                if (lastGroup.isNotEmpty()) {
                                    val sampleEp = firstGroup.first()
                                    val sampleTitle = sampleEp.name ?: ""
                                    val sampleUrl = sampleEp.data

                                    val titlePrefix = sampleTitle.replace("""\d+$""".toRegex(), "")
                                    val urlPrefix = sampleUrl.replace("""\d+$""".toRegex(), "")

                                    val startNum = (firstGroup.last().episode ?: firstGroup.size) + 1
                                    val endNum = (lastGroup.first().episode ?: 1000) - 1

                                    val middleEpisodes = mutableListOf<Episode>()
                                    for (num in startNum..endNum) {
                                        middleEpisodes.add(newEpisode("$urlPrefix$num") {
                                            this.name = "$titlePrefix$num"
                                            this.episode = num
                                            this.season = 1
                                            this.posterUrl = poster // تعيين بوستر المسلسل للحلقة المستحدثة
                                        })
                                    }

                                    episodes.addAll(middleEpisodes)
                                    episodes.addAll(lastGroup)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val epPills = document.select("div.FJ-EpsGrid a")
                epPills.reversed().forEachIndexed { index, pill ->
                    val epTitle = pill.attr("title").ifEmpty { "الحلقة" }
                    val epUrl = pill.attr("href")
                    val epNum = """(\d+)$""".toRegex().find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                    if (epUrl.isNotEmpty()) {
                        episodes.add(newEpisode(epUrl) {
                            this.name = epTitle
                            this.episode = epNum
                            this.season = 1
                            this.posterUrl = poster // تعيين بوستر المسلسل في خطة التراجع
                        })
                    }
                }
            }

            println("AnimePhoenix_DEBUG: Total successfully extracted episodes count = ${episodes.size}")
            return newTvSeriesLoadResponse(title, finalUrl, TvType.Anime, episodes).apply {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val serverLinks = document.select("a.server-link")

        serverLinks.forEach { sLink ->
            val rawData = sLink.attr("data-server")
            if (rawData.isNotEmpty()) {
                try {
                    val decodedBytes = Base64.decode(rawData, Base64.DEFAULT)
                    val decodedStr = String(decodedBytes, Charsets.UTF_8)
                    val unquotedJson = URLDecoder.decode(decodedStr, "UTF-8")

                    val serverInfo = JSONObject(unquotedJson)
                    val name = serverInfo.optString("name", "Phoenix Server")
                    val linkType = serverInfo.optString("type")
                    val videoUrl = serverInfo.optString("link")

                    if (videoUrl.isNotEmpty()) {
                        val finalUrl = if (linkType == "iframe" && videoUrl.contains("<iframe")) {
                            Jsoup.parse(videoUrl).selectFirst("iframe")?.attr("src") ?: ""
                        } else {
                            videoUrl
                        }

                        if (finalUrl.isNotEmpty()) {
                            if (finalUrl.contains("drive.google.com", ignoreCase = true)) {
                                val fileId = """/file/d/([0-9A-Za-z_-]{10,})""".toRegex().find(finalUrl)?.groupValues?.get(1)
                                    ?: """[?&]id=([0-9A-Za-z_-]{10,})""".toRegex().find(finalUrl)?.groupValues?.get(1)

                                if (!fileId.isNullOrBlank()) {
                                    val directDriveUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this.name,
                                            name = "$name (GDrive Direct)",
                                            url = directDriveUrl,
                                        ) {
                                            referer = "https://drive.google.com/"
                                            quality = Qualities.Unknown.value
                                        }
                                    )
                                } else {
                                    loadExtractor(finalUrl, subtitleCallback, callback)
                                }
                            }
                            else if (linkType == "direct") {
                                callback.invoke(
                                    newExtractorLink(
                                        this.name,
                                        name,
                                        finalUrl,
                                    ) {
                                        referer = mainUrl
                                        quality = Qualities.Unknown.value
                                    }
                                )
                            }
                            else {
                                loadExtractor(finalUrl, subtitleCallback, callback)
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
        }
        return true
    }
}