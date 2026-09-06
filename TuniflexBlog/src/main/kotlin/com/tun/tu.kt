package com.tun

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import java.util.regex.Pattern
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

class tunProvider : MainAPI() {
    override var mainUrl = "https://ttunflix.blogspot.com"
    override var name = "TUNFLIX BLOG"
    override val hasMainPage = true
    override var lang = "ar"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private data class BloggerFeed(
        @JsonProperty("feed") val feed: Feed? = null
    )

    private data class Feed(
        @JsonProperty("entry") val entry: List<Entry>? = null
    )

    private data class Entry(
        @JsonProperty("title") val title: Title? = null,
        @JsonProperty("link") val link: List<Link>? = null,
        @JsonProperty("media\$thumbnail") val thumbnail: Thumbnail? = null,
        @JsonProperty("content") val content: Content? = null
    )

    private data class Content(
        @JsonProperty("\$t") val text: String? = null
    )

    private data class Title(
        @JsonProperty("\$t") val text: String? = null
    )

    private data class Link(
        @JsonProperty("rel") val rel: String? = null,
        @JsonProperty("href") val href: String? = null
    )

    private data class Thumbnail(
        @JsonProperty("url") val url: String? = null
    )

    private fun getPosterFromEntry(entry: Entry): String {
        val thumbUrl = entry.thumbnail?.url
        if (thumbUrl != null && thumbUrl.isNotEmpty()) {
            return thumbUrl.replace("/s72-c/", "/w400/")
        }

        val htmlContent = entry.content?.text ?: return ""
        val matcher = Pattern.compile("""<img[^>]+src=["']([^"']+)["']""").matcher(htmlContent)
        if (matcher.find()) {
            val extractedUrl = matcher.group(1) ?: ""
            if (!extractedUrl.contains("ytimg.com") && !extractedUrl.contains("youtube.com")) {
                return extractedUrl
            }
        }
        return ""
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = "$mainUrl/feeds/posts/default?alt=json&max-results=30"
        val response = app.get(url).parsed<BloggerFeed>()
        val entries = response.feed?.entry ?: return null

        val homeItems = entries.mapNotNull { entry ->
            val title = entry.title?.text ?: return@mapNotNull null
            val postUrl = entry.link?.find { it.rel == "alternate" }?.href ?: return@mapNotNull null
            val posterUrl = getPosterFromEntry(entry)

            val isSeries = title.contains("مسلسل", ignoreCase = true) ||
                    title.contains("Season", ignoreCase = true) ||
                    title.contains("Saison", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                newTvSeriesSearchResponse(title, postUrl, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, postUrl, type) {
                    this.posterUrl = posterUrl
                }
            }
        }

        return newHomePageResponse("آخر الإضافات", homeItems)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/feeds/posts/default?alt=json&q=${encodeUrl(query)}&max-results=20"
        val response = app.get(searchUrl).parsed<BloggerFeed>()
        val entries = response.feed?.entry ?: return emptyList()

        return entries.mapNotNull { entry ->
            val title = entry.title?.text ?: return@mapNotNull null
            val postUrl = entry.link?.find { it.rel == "alternate" }?.href ?: return@mapNotNull null
            val posterUrl = getPosterFromEntry(entry)

            val isSeries = title.contains("مسلسل", ignoreCase = true) ||
                    title.contains("Season", ignoreCase = true) ||
                    title.contains("Saison", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                newTvSeriesSearchResponse(title, postUrl, type) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, postUrl, type) {
                    this.posterUrl = posterUrl
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: doc.title()

        var targetPlayerUrl = if (url.contains("/p/")) {
            url
        } else {
            val linkElement = doc.selectFirst("a[href*=\"/p/\"]")
            linkElement?.attr("href") ?: ""
        }

        if (targetPlayerUrl.startsWith("/")) {
            targetPlayerUrl = mainUrl + targetPlayerUrl
        }

        if (targetPlayerUrl.isEmpty()) {
            return null
        }

        val playerDoc = app.get(targetPlayerUrl).document
        val htmlContent = playerDoc.html()

        val poster = doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")

        val episodesList = parseEpisodesFlexible(htmlContent)
        val isSeries = title.contains("مسلسل", ignoreCase = true) ||
                title.contains("Season", ignoreCase = true) ||
                title.contains("Saison", ignoreCase = true) ||
                title.contains(" s ", ignoreCase = true) ||
                title.contains(" s0", ignoreCase = true) ||
                title.contains(" s1", ignoreCase = true) ||
                title.contains(" s2", ignoreCase = true) ||
                title.contains(" s3", ignoreCase = true) ||
                title.contains(" s4", ignoreCase = true) ||
                title.contains(" s5", ignoreCase = true) ||
                episodesList.size > 1 // إذا عثر السكربت على أكثر من حلقة يتم التحويل تلقائياً لمسلسل

        if (episodesList.isNotEmpty()) {
            if (isSeries) {
                val episodeResponses = episodesList.map { ep ->
                    newEpisode(ep.baseURL) {
                        this.name = ep.title
                        this.episode = ep.id
                        this.posterUrl = poster
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeResponses) {
                    this.posterUrl = poster
                }
            } else {
                val firstEp = episodesList.first()
                return newMovieLoadResponse(title, url, TvType.Movie, firstEp.baseURL) {
                    this.posterUrl = poster
                }
            }
        }

        val directVideoUrl = playerDoc.selectFirst("video")?.attr("src") ?: ""
        if (directVideoUrl.isNotEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, directVideoUrl) {
                this.posterUrl = poster
            }
        }

        return null
    }

    private fun parseEpisodesFlexible(htmlContent: String): List<ParsedEpisode> {
        val list = mutableListOf<ParsedEpisode>()

        val blockPattern = Pattern.compile("""\{[^{}]*?(?:"baseURL"|'baseURL'|baseURL)\s*:[^{}]*?\}""", Pattern.DOTALL)
        val blockMatcher = blockPattern.matcher(htmlContent)

        while (blockMatcher.find()) {
            val block = blockMatcher.group()
            val idMatcher = Pattern.compile("""(?:"id"|'id'|id)\s*:\s*(\d+)""").matcher(block)
            val titleMatcher = Pattern.compile("""(?:"title"|'title'|title)\s*:\s*["']([^"']+)["']""").matcher(block)
            val urlMatcher = Pattern.compile("""(?:"baseURL"|'baseURL'|baseURL)\s*:\s*["']([^"']+)["']""").matcher(block)

            if (idMatcher.find() && titleMatcher.find() && urlMatcher.find()) {
                val id = idMatcher.group(1)?.toIntOrNull() ?: continue
                val title = titleMatcher.group(1) ?: ""
                val baseURL = urlMatcher.group(1) ?: ""
                list.add(ParsedEpisode(id, title, baseURL))
            }
        }

        if (list.isEmpty()) {
            val doc = Jsoup.parse(htmlContent)
            val embedElements = doc.select("[data-embed-url]")
            for (element in embedElements) {
                val embedUrl = element.attr("data-embed-url") ?: continue
                if (embedUrl.isNotEmpty()) {
                    val id = element.attr("data-embed-id").toIntOrNull() ?: (list.size + 1)
                    var epTitle = element.text().trim()
                    if (epTitle.isEmpty()) {
                        epTitle = "الحلقة $id"
                    }
                    list.add(ParsedEpisode(id, epTitle, embedUrl))
                }
            }
        }

        if (list.isEmpty()) {
            val wixPattern = Pattern.compile("""https://video\.wixstatic\.com/video/[^\s"\'`>]+""")
            val wixMatcher = wixPattern.matcher(htmlContent)
            val wixUrls = mutableListOf<String>()

            while (wixMatcher.find()) {
                wixUrls.add(wixMatcher.group())
            }

            val uniqueUrls = wixUrls.distinctBy { it.split("?")[0] }

            uniqueUrls.forEachIndexed { index, url ->
                list.add(ParsedEpisode(index + 1, "الحلقة ${index + 1}", url))
            }
        }

        return list
    }

    private data class ParsedEpisode(
        val id: Int,
        val title: String,
        val baseURL: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("wixstatic.com")) {
            var cleanBaseUrl = data
            val regex = "/(1080p|720p|480p|360p)/mp4/file\\.mp4\\??.*".toRegex()
            cleanBaseUrl = cleanBaseUrl.replace(regex, "/")
            if (!cleanBaseUrl.endsWith("/")) {
                cleanBaseUrl += "/"
            }

            val qualities = listOf("1080p", "720p", "480p", "360p")
            for (q in qualities) {
                val videoUrl = "${cleanBaseUrl}${q}/mp4/file.mp4?fileUsed=false"

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} - $q",
                        url = videoUrl,
                    ) {
                        referer = ""
                        quality = getQualityFromName(q)
                    }
                )
            }
        } else {
            val guessedQuality = when {
                data.contains("1080p") -> "1080p"
                data.contains("720p") -> "720p"
                data.contains("480p") -> "480p"
                data.contains("360p") -> "360p"
                else -> "720p"
            }
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} - $guessedQuality",
                    url = data,
                ) {
                    referer = ""
                    quality = getQualityFromName(guessedQuality)
                }
            )
        }
        return true
    }

    private fun encodeUrl(url: String): String {
        return java.net.URLEncoder.encode(url, "UTF-8")
    }
}