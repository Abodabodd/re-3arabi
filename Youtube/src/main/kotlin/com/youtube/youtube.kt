package com.lagradost.cloudstream3.ar.youtube

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import com.lagradost.cloudstream3.CloudStreamApp
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider.Config.SLEEP_BETWEEN
import kotlin.collections.remove
import kotlin.text.get

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {



    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true // المتغير الجديد لحالة التفعيل
    )
    object Config {
        const val SLEEP_BETWEEN = 1
    }
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)

    companion object {

        private val homeShorts = mutableListOf<Episode>()
        private val searchShorts = mutableListOf<Episode>()
    }

    private var isSearchContext = false


    class YouTubeInterceptor(private val prefs: SharedPreferences?) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val requestBuilder = chain.request().newBuilder()
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
                )

            val cookieBuilder = StringBuilder()
            val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)

            if (!visitor.isNullOrBlank()) {
                cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
            } else {
                cookieBuilder.append("VISITOR_INFO1_LIVE=_Mk3UVhY40g; ")
            }

            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                }
            }
            cookieBuilder.append("PREF=f6=40000000&hl=en; CONSENT=YES+fx.456722336;")

            requestBuilder.addHeader("Cookie", cookieBuilder.toString())
            return chain.proceed(requestBuilder.build())
        }
    }

    private val ytInterceptor = YouTubeInterceptor(sharedPref)
    private val safariUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"

    private var savedContinuationToken: String? = null
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

    @Suppress("PropertyName")
    private data class PlayerResponse(@JsonProperty("streamingData") val streamingData: StreamingData?)
    private data class StreamingData(@JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?)




    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>

    private fun Map<*, *>.getString(key: String): String? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? String


    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: obj.getListKey("runs")?.joinToString("") { run ->
                    when (run) {
                        is String -> run
                        is Map<*, *> -> run.getString("text")
                            ?: run.getString("simpleText")
                            ?: ""

                        else -> ""
                    }
                }.orEmpty()
                ?: obj.getMapKey("text")?.let { getText(it) }.orEmpty()
        }
        return ""
    }

    private fun extractLockupMetadata(lockup: Map<*, *>): Pair<String, String> {
        var channel = ""
        var views = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            rows?.forEach { row ->
                val parts = row.getListKey("metadataParts")
                parts?.forEach { part ->
                    val text = getText(part.getMapKey("text")) ?: ""
                    if (text.isNotBlank()) {

                        if (text.matches(Regex(".*(\\d+[KMBkmb]|views|مشاهدة).*"))) {
                            views = formatViews(text)
                        }

                        else if (!text.matches(Regex(".*(\\d+:\\d+|ago|قبل).*")) && text.length > 1 && !text.contains(
                                "•"
                            )
                        ) {
                            channel = text
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (!direct.isNullOrBlank()) views = formatViews(direct)
        }

        return Pair(channel, views)
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        val text = viewText.toString()
        if (text.any { it in listOf('K', 'M', 'B', 'k', 'm', 'b') } && text.length < 15) {
            return text.split("view")[0].split("مشاهدة")[0].trim()
        }
        val digits = text.filter { it.isDigit() }
        if (digits.isBlank()) return text
        return try {
            val v = digits.toLong()
            when {
                v < 1000 -> v.toString()
                v < 1_000_000 -> String.format("%.1fK", v / 1000.0).replace(".0K", "K")
                v < 1_000_000_000 -> String.format("%.1fM", v / 1_000_000.0).replace(".0M", "M")
                else -> String.format("%.1fB", v / 1_000_000_000.0).replace(".0B", "B")
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun getRawText(map: Map<*, *>?, key: String): String? {
        val obj = map?.getMapKey(key) ?: return null
        return obj.getString("simpleText")
            ?: obj.getListKey("runs")?.firstOrNull()?.getString("text")
    }

    private fun getBestThumbnail(thumbData: Any?): String? {
        return try {
            val thumbs = when (thumbData) {
                is Map<*, *> -> (thumbData["thumbnails"] as? List<*>)
                    ?: (thumbData["sources"] as? List<*>)

                is List<*> -> thumbData
                else -> null
            }
            val lastThumb = thumbs?.lastOrNull() as? Map<*, *>
            var url = lastThumb?.get("url") as? String
            if (url?.startsWith("//") == true) url = "https:$url"
            url
        } catch (e: Exception) {
            null
        }
    }

    private fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }




    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
    ): SearchResponse? {
        if (renderer == null) return null

        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")

        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("viewCountText"))
                    ?: getText(videoData.getMapKey("shortViewCountText"))
                val channel = getText(videoData.getMapKey("ownerText"))
                    ?: getText(videoData.getMapKey("shortBylineText")) ?: ""
                val views = formatViews(viewText)
                val finalTitle =
                    if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                var poster = getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                return newMovieSearchResponse(
                    finalTitle,
                    "$mainUrl/watch?v=$videoId",
                    TvType.Movie
                ) { this.posterUrl = poster }
            }
            return null
        }

        val richContent = renderer.getMapKey("richItemRenderer")?.getMapKey("content")
        val shortsData = renderer.getMapKey("reelItemRenderer")
            ?: renderer.getMapKey("shortsLockupViewModel")
            ?: richContent?.getMapKey("shortsLockupViewModel")

        if (shortsData != null) {
            val onTap = shortsData.getMapKey("onTap")
            val videoId = onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")
                ?.getString("videoId")
                ?: shortsData.getString("videoId")
                ?: shortsData.getString("entityId")?.replace("shorts-shelf-item-", "")

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val overlay = shortsData.getMapKey("overlayMetadata")
                val accessibilityText = shortsData.getString("accessibilityText") ?: ""

                var title = overlay?.getMapKey("primaryText")?.getString("content")
                if (title.isNullOrBlank()) title = getText(shortsData.getMapKey("headline"))
                if (title.isNullOrBlank()) title =
                    overlay?.getMapKey("primaryText")?.getString("simpleText")
                if (title.isNullOrBlank() && accessibilityText.contains(",")) title =
                    accessibilityText.substringBefore(",").trim()
                if (title.isNullOrBlank()) title = "Shorts Clip"

                var viewRaw = overlay?.getMapKey("secondaryText")?.getString("content")
                if (viewRaw.isNullOrBlank()) viewRaw =
                    getText(shortsData.getMapKey("viewCountText"))
                if (viewRaw.isNullOrBlank() && accessibilityText.isNotBlank()) {
                    val match = Regex(",\\s*(.*?)\\s*-").find(accessibilityText)
                    if (match != null) viewRaw = match.groupValues[1].trim()
                }
                val views = formatViews(viewRaw)

                var poster =
                    shortsData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()
                        ?.getString("url")
                if (poster.isNullOrBlank()) poster =
                    shortsData.getMapKey("thumbnailViewModel")?.getMapKey("thumbnailViewModel")
                        ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = currentList.size + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                if (currentList.none { it.data == finalUrl }) {
                    currentList.add(newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = poster
                        this.episode = episodeNum
                    })
                }

                val finalTitle = "#$episodeNum {$views} $title"

                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            if (lockup.getString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val playlistId = lockup.getString("contentId")
                if (!playlistId.isNullOrBlank() && seenIds.add(playlistId)) {
                    val title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")?.getString("content") ?: "Playlist"
                    val episodeCount =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getListKey("overlays")?.firstOrNull()
                            ?.getMapKey("thumbnailOverlayBadgeViewModel")
                            ?.getListKey("thumbnailBadges")?.firstOrNull()
                            ?.getMapKey("thumbnailBadgeViewModel")?.getString("text") ?: ""
                    val poster =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()
                            ?.getString("url")

                    val finalTitle =
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title
                    return newTvSeriesSearchResponse(
                        finalTitle,
                        "$mainUrl/playlist?list=$playlistId",
                        TvType.TvSeries
                    ) { this.posterUrl = poster }
                }
            }

            val videoId = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                var title = getText(
                    lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")
                )
                if (title.isEmpty()) title = "YouTube Video"
                var (channel, views) = extractLockupMetadata(lockup)
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match =
                        Regex("(?:by|من|عبر|قناة)\\s+(.*?)\\s+(?:\\d|view|مشاهدة)").find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = currentList.size + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    if (currentList.none { it.data == finalUrl }) {
                        currentList.add(newEpisode(finalUrl) {
                            this.name = title
                            this.posterUrl = poster
                            this.episode = episodeNum
                        })
                    }
                    finalTitle = "#$episodeNum [Shorts] {$views} $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle =
                        if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    poster = getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) { this.posterUrl = poster }
                }
            }
        }

        val channelData = renderer.getMapKey("channelRenderer")
        if (channelData != null) {
            val id = channelData.getString("channelId")
            if (!id.isNullOrBlank() && seenIds.add(id)) {
                val title = getText(channelData.getMapKey("title")) ?: "Channel"
                val stats = (getText(channelData.getMapKey("videoCountText"))
                    ?: getText(channelData.getMapKey("subscriberCountText"))) ?: ""
                val poster = getBestThumbnail(channelData.getMapKey("thumbnail"))
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) { this.posterUrl = poster }
            }
        }
        return null
    }


    private fun processRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>,
        playlistMode: Boolean
    ) {
        if (data is Map<*, *>) {

            val extracted = collectFromRenderer(data, seenIds)
            if (extracted != null) {
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            val keysToCheck = listOf(
                "contents",
                "items",
                "gridShelfViewModel",
                "verticalListRenderer",
                "horizontalListRenderer",
                "shelfRenderer",
                "itemSectionRenderer",
                "richShelfRenderer",
                "reelShelfRenderer",
                "appendContinuationItemsAction",
                "onResponseReceivedCommands"
            )
            var foundContainer = false
            for (key in keysToCheck) {
                if (data.containsKey(key)) {
                    processRecursive(data[key], outList, seenIds, playlistMode)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode)
            }
        }
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        val regex = Regex(
            """(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*\});""",
            RegexOption.DOT_MATCHES_ALL
        )
        val match = try {
            regex.find(html)
        } catch (e: Exception) {
            null
        }
        return match?.groupValues?.getOrNull(1)?.let {
            try {
                parseJson<Map<String, Any>>(it)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun findConfig(html: String, key: String): String? {
        return try {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)
            m?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            if (data.containsKey("continuationCommand")) return (data["continuationCommand"] as? Map<*, *>)?.get(
                "token"
            ) as? String
            for (v in data.values) {
                val t = findTokenRecursive(v); if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i); if (t != null) return t
            }
        }
        return null
    }

    override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val isEn = lang == "en"
            if (sharedPref?.getBoolean("show_trending_home", true) == true) {
                list.add(MainPageData(if (isEn) "Trending" else "الرئيسية (Trending)", "Home"))
            }

            val customSections = getCustomHomepages()

            customSections.filter { it.isEnabled }.forEach { section ->
                var title = section.name
                if (title.isBlank()) title = extractNameFromUrl(section.url)
                list.add(MainPageData(title, section.url))
            }

            return list
        }
    private fun getCustomHomepages(): List<CustomSection> {
        val json = sharedPref?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            AppUtils.parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(5)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }
    private fun resetContinuation() {
        savedContinuationToken = null
    }
    private val continuationTokens = mutableMapOf<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null

        val requestData = request.data
        val isPlaylist = if (page == 1) requestData.contains("list=") else requestData.startsWith("playlist_")
        fun extractPlaylistVideos(items: List<*>) {
            items.forEach { item ->
                val videoMap = item as? Map<*, *>
                val renderer = videoMap?.get("playlistVideoRenderer") as? Map<*, *>
                if (renderer != null) {
                    val vId = renderer["videoId"] as? String
                    if (vId != null && seenIds.add(vId)) {
                        val vidTitle = extractTitle(renderer["title"] as? Map<*, *>) ?: "Video"
                        val thumb = getBestThumbnail(renderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                        val vidUrl = "$mainUrl/watch?v=$vId"
                        val durationText = extractTitle(safeGet(renderer, "lengthText") as? Map<*, *>)
                        val finalTitle = if (durationText != null) "{$durationText} $vidTitle" else vidTitle

                        results.add(
                            newMovieSearchResponse(finalTitle, vidUrl, TvType.Movie) {
                                this.posterUrl = thumb
                            }
                        )
                    }
                }
            }
        }

        fun findPlaylistToken(items: List<*>?): String? {
            if (items == null) return null
            for (it in items) {
                val m = it as? Map<*, *> ?: continue
                val token = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
                if (!token.isNullOrBlank()) return token
            }
            return null
        }
        try {
            if (page == 1) {
                continuationTokens.remove(requestData)
                if (requestData == "Home") homeShorts.clear()

                var cleanUrl = requestData
                if (cleanUrl.contains("?")) cleanUrl = cleanUrl.substringBefore("?")

                val targetUrl = when {
                    requestData.startsWith("http") && !isPlaylist && !cleanUrl.endsWith("/videos") -> "$cleanUrl/videos"
                    requestData.startsWith("http") -> requestData
                    else -> mainUrl
                }

                val html = app.get(targetUrl, interceptor = ytInterceptor).text

                savedApiKey = findConfig(html, "INNERTUBE_API_KEY")
                savedClientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                savedVisitorData = findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    if (isPlaylist) {
                        val contents = safeGet(
                            initialData, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
                            "tabRenderer", "content", "sectionListRenderer", "contents",
                            0, "itemSectionRenderer", "contents", 0,
                            "playlistVideoListRenderer", "contents"
                        ) as? List<*>

                        if (contents != null) {
                            extractPlaylistVideos(contents)
                            nextContinuation = findPlaylistToken(contents)
                        }
                        if (nextContinuation.isNullOrBlank()) {
                            val conts = findContinuationItemsRecursive(initialData)
                            nextContinuation = findPlaylistToken(conts)
                        }

                    } else {
                        processRecursive(initialData, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(initialData)
                    }
                }
            } else {
                val tokenToUse = continuationTokens[requestData]
                if (!tokenToUse.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val decodedToken = java.net.URLDecoder.decode(tokenToUse, "UTF-8")

                    val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf("client" to mapOf(
                            "visitorData" to (savedVisitorData ?: ""), "clientName" to "WEB",
                            "clientVersion" to (savedClientVersion ?: "2.20240725.01.00"),
                            "platform" to "DESKTOP"
                        )),
                        "continuation" to decodedToken
                    )
                    val headers = mapOf(
                        "X-Youtube-Client-Name" to "WEB",
                        "X-Youtube-Client-Version" to (savedClientVersion ?: ""),
                        "Origin" to mainUrl, "Referer" to mainUrl
                    )

                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        if (isPlaylist) {
                            val continuationItems = findContinuationItemsRecursive(response)
                            if (continuationItems != null) {
                                extractPlaylistVideos(continuationItems)
                                nextContinuation = findPlaylistToken(continuationItems)
                            }
                        } else {
                            val actions = response["onResponseReceivedActions"] ?: response["onResponseReceivedCommands"] ?: response["continuationContents"] ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (!nextContinuation.isNullOrBlank()) {
            continuationTokens[requestData] = nextContinuation
        } else {
            continuationTokens.remove(requestData)
        }

        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }

    fun findContinuationItemsRecursive(obj: Any?): List<*>? {
        when (obj) {
            is Map<*, *> -> {
                if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>

                val keysToTry = listOf(
                    "onResponseReceivedActions",
                    "onResponseReceivedCommands",
                    "onResponseReceivedEndpoints",
                    "continuationContents"
                )
                for (k in keysToTry) {
                    val v = obj[k]
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }

                for (v in obj.values) {
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }
            }

            is List<*> -> {
                for (i in obj) {
                    val r = findContinuationItemsRecursive(i)
                    if (r != null) return r
                }
            }
        }
        return null
    }



    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val results = mutableListOf<SearchResponse>()

        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }


        val seenIds = mutableSetOf<String>()

        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = sharedPref?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }

        try {
            if (page == 1) {
                savedContinuationToken = null
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                val html = app.get(url, interceptor = ytInterceptor).text

                val regexKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
                savedApiKey = regexKey.find(html)?.groupValues?.get(1)
                savedVisitorData = findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    savedContinuationToken = findTokenRecursive(initialData)
                }
            } else {
                if (!savedContinuationToken.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val apiUrl = "$mainUrl/youtubei/v1/search?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB",
                                "clientVersion" to "2.20240101.00",
                                "visitorData" to (savedVisitorData ?: "")
                            )
                        ),
                        "continuation" to savedContinuationToken
                    )

                    val response = app.post(apiUrl, json = payload, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedCommands"] ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        savedContinuationToken = findTokenRecursive(response)
                    }
                }
            }
            return newSearchResponseList(results, !savedContinuationToken.isNullOrBlank())
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
    }

    private val collectedShorts = mutableListOf<Episode>()



    private fun safeGet(data: Any?, vararg keys: Any): Any? {
        var current = data
        for (key in keys) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }



    override suspend fun load(url: String): LoadResponse {
        Log.d(name, "load called for url=$url")



        if (url.contains("/shorts/")) {
            val videoId = url.extractYoutubeId() ?: "video"
            val useSearchList = url.contains("&ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            val targetEpisodes = sourceList.toMutableList()
            var currentEp = targetEpisodes.find { it.data.extractYoutubeId() == videoId }

            if (currentEp == null) {
                val fallbackEp = newEpisode(url) {
                    this.name = "Shorts Video"
                    this.posterUrl = buildThumbnailFromId(videoId)
                    this.episode = targetEpisodes.size + 1
                }
                targetEpisodes.add(0, fallbackEp)
                currentEp = fallbackEp
            }

            val poster = currentEp?.posterUrl ?: buildThumbnailFromId(videoId)

            return newTvSeriesLoadResponse("Shorts Feed", url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = "قائمة تشغيل تلقائية من الشورتس (${targetEpisodes.size} فيديو)"
                this.tags = listOf("Shorts", "Feed")
            }
        }



        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            try {
                val channelUrl = if (url.endsWith("/videos")) url else "$url/videos"
                val response = app.get(channelUrl, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val apiKey = findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                val visitorData = findConfig(html, "VISITOR_DATA")

                val header = safeGet(data, "header", "c4TabbedHeaderRenderer")
                    ?: safeGet(data, "header", "pageHeaderRenderer")

                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: extractTitle(safeGet(header, "pageTitle") as? Map<*, *>)
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "YouTube Channel"

                val poster = getBestThumbnail(safeGet(header, "avatar"))
                    ?: getBestThumbnail(safeGet(header, "content", "pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                val subscriberCount = extractTitle(safeGet(header, "subscriberCountText") as? Map<*, *>)
                    ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows", 1, "metadataParts", 0, "text", "content") as? String

                val allEpisodes = mutableListOf<Episode>()

                fun findContinuationItemsRecursive(obj: Any?): List<*>? {
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints", "continuationContents", "onResponseReceivedResults")
                            for (k in keysToTry) {
                                val v = obj[k]
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                            for (v in obj.values) {
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                        }
                        is List<*> -> {
                            for (i in obj) {
                                val r = findContinuationItemsRecursive(i)
                                if (r != null) return r
                            }
                        }
                    }
                    return null
                }

                fun findContinuationTokenFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
                        if (!token.isNullOrBlank()) return token
                        val token2 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "browseContinuationEndpoint", "token") as? String
                        if (!token2.isNullOrBlank()) return token2
                        val token3 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "token") as? String
                        if (!token3.isNullOrBlank()) return token3
                    }
                    return null
                }

                fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>) {
                    items.forEach { item ->
                        val map = item as? Map<*, *> ?: return@forEach
                        val videoRenderer = when {
                            map.containsKey("videoRenderer") -> map["videoRenderer"] as? Map<*, *>
                            map.containsKey("gridVideoRenderer") -> map["gridVideoRenderer"] as? Map<*, *>
                            map.containsKey("compactVideoRenderer") -> map["compactVideoRenderer"] as? Map<*, *>
                            map.containsKey("shortsVideoRenderer") -> map["shortsVideoRenderer"] as? Map<*, *>
                            map.containsKey("reelItemRenderer") -> {
                                val content = safeGet(map, "reelItemRenderer", "content") as? Map<*, *>
                                content?.get("reelItemRenderer") as? Map<*, *>
                            }
                            map.containsKey("richItemRenderer") -> {
                                val content = safeGet(map, "richItemRenderer", "content") as? Map<*, *>
                                (content?.get("videoRenderer") ?: content?.get("gridVideoRenderer") ?: content?.get("shortsLockupViewModel")) as? Map<*, *>
                            }
                            else -> null
                        }

                        if (videoRenderer != null) {
                            val vId = videoRenderer["videoId"] as? String ?: return@forEach
                            val vidTitle = extractTitle(videoRenderer["title"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["headline"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["shortBylineText"] as? Map<*, *>)
                                ?: "Video"
                            val thumb = getBestThumbnail(videoRenderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            val viewCount = formatViews(safeGet(videoRenderer, "viewCountText", "simpleText") as? String)
                            val publishedTime = extractTitle(safeGet(videoRenderer, "publishedTimeText") as? Map<*, *>)

                            collectTo.add(newEpisode(vidUrl) {
                                this.name = vidTitle
                                this.posterUrl = thumb
                                this.description = listOfNotNull(viewCount, publishedTime).joinToString(" • ")
                            })
                        }
                    }
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                if (tabs != null) {
                    for (tab in tabs) {
                        val tabMap = tab as? Map<*, *>
                        val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                        val content = tabRenderer?.get("content") as? Map<*, *>
                        if (content?.containsKey("richGridRenderer") == true) {
                            initialItems = safeGet(content, "richGridRenderer", "contents") as? List<*>
                            break
                        }
                        if (content?.containsKey("gridRenderer") == true) {
                            initialItems = safeGet(content, "gridRenderer", "items") as? List<*>
                            break
                        }
                    }
                }
                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                var currentToken: String? = findContinuationTokenFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationTokenFromItems(conts)
                }

                var pagesFetchedLocal = 1
                val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPages && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf("X-Youtube-Client-Name" to "WEB", "X-Youtube-Client-Version" to clientVersion)
                        val jsonResponse = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>() ?: break
                        val continuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                        extractVideosFromItems(continuationItems, allEpisodes)
                        currentToken = findContinuationTokenFromItems(continuationItems)
                        kotlinx.coroutines.delay((SLEEP_BETWEEN * 10).toLong())
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Channel: $title\nSubscribers: ${subscriberCount ?: "N/A"}\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }

            } catch (e: Exception) {
                Log.e(name, "Error parsing channel, falling back", e)
            }
        }



        if (url.contains("list=")) {
            try {
                val response = app.get(url, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract playlist data")

                val header = safeGet(data, "header", "playlistHeaderRenderer") as? Map<*, *>
                val title = extractTitle(safeGet(header, "title") as? Map<*, *>) ?: "YouTube Playlist"
                val ownerObj = safeGet(header, "ownerText") as? Map<*, *>
                val author = extractTitle(ownerObj) ?: "Unknown Channel"
                val description = extractTitle(safeGet(header, "description") as? Map<*, *>)

                val episodes = mutableListOf<Episode>()
                val contents = safeGet(
                    data, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
                    "tabRenderer", "content", "sectionListRenderer", "contents",
                    0, "itemSectionRenderer", "contents", 0,
                    "playlistVideoListRenderer", "contents"
                ) as? List<*>

                contents?.forEachIndexed { index, item ->
                    val videoMap = item as? Map<*, *>
                    val renderer = videoMap?.get("playlistVideoRenderer") as? Map<*, *>
                    if (renderer != null) {
                        val vId = renderer["videoId"] as? String
                        if (vId != null) {
                            val vidTitle = extractTitle(renderer["title"] as? Map<*, *>) ?: "Episode ${index + 1}"
                            val thumb = getBestThumbnail(renderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            val durationText = extractTitle(safeGet(renderer, "lengthText") as? Map<*, *>)
                            episodes.add(newEpisode(vidUrl) {
                                this.name = vidTitle
                                this.episode = index + 1
                                this.posterUrl = thumb
                                this.description = if (durationText != null) "Duration: $durationText" else null
                            })
                        }
                    }
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    val finalDescription = if (description.isNullOrBlank()) "Channel: $author" else "Channel: $author\n\n$description"
                    this.plot = finalDescription
                    this.tags = listOf(author)
                }
            } catch (e: Exception) {
                Log.e(name, "Error parsing playlist", e)
            }
        }



        val videoId = url.extractYoutubeId() ?: throw ErrorLoadingException("Invalid YouTube URL")

        val response = app.get(url, interceptor = ytInterceptor)
        val html = response.text
        val data = extractYtInitialData(html)

        var title = "YouTube Video"
        var plot = ""
        var poster = buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()

        if (data != null) {

            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t

                    val dateText = extractTitle(primary["dateText"] as? Map<*, *>)
                    if (!dateText.isNullOrBlank()) plot += "$dateText\n\n"
                }

                val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                if (secondary != null) {

                    val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                    if (owner != null) {
                        channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                        channelAvatar = getBestThumbnail(owner["thumbnail"]) ?: ""
                        channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                        if (channelId.isEmpty()) {

                            val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                            if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                        }
                    }

                    val descObj = secondary["attributedDescription"] as? Map<*, *>
                        ?: secondary["description"] as? Map<*, *>

                    val fullDesc = getText(descObj)// استخدام دالة getText الموحدة
                    if (fullDesc.isNotBlank()) {
                        plot += fullDesc
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false)
            }

        } else {

            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: plot
        }


        if (channelName.isNotBlank() && channelId.isNotBlank()) {
            val channelUrlFull = if (channelId.startsWith("UC") || channelId.startsWith("@")) "$mainUrl/channel/$channelId" else "$mainUrl/$channelId"

            val channelCard = newMovieSearchResponse(
                "Channel: $channelName",
                channelUrlFull,
                TvType.Live
            ) {
                this.posterUrl = channelAvatar


            }

            recommendations.add(0, channelCard)
        }

        val filteredRecs = recommendations.filter { !it.url.contains("/shorts/") }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.plot = plot

            if (channelName.isNotBlank()) {
                this.tags = listOf(channelName)
            }

            this.recommendations = filteredRecs
        }
    }

    private fun sha1(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getSapisidHash(
        sapisid: String,
        origin: String = "https://www.youtube.com"
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val msg = "$timestamp $sapisid $origin"
        val hash = sha1(msg)
        return "SAPISIDHASH ${timestamp}_$hash"
    }
    private fun extractYoutubeIdSafe(input: String): String {
        return when {
            input.contains("youtu.be/") ->
                input.substringAfter("youtu.be/").substringBefore("?")

            input.contains("watch?v=") ->
                input.substringAfter("watch?v=").substringBefore("&")

            input.contains("/shorts/") ->
                input.substringAfter("/shorts/").substringBefore("?")

            else -> input
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data.extractYoutubeId() ?: data
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"
        val context = CloudStreamApp.context
        val playerType = if (context != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.getString("youtube_player_type", "advanced")
        } else {
            "advanced"
        }
        if (playerType == "classic") {
            loadExtractor(fullUrl, subtitleCallback, callback)
        } else {
            com.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, callback)
        }
        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB",
                        "clientVersion" to "2.20240725.01.00",
                        "hl" to "en",
                        "gl" to "US"
                    )
                ),
                "videoId" to videoId
            )

            val responseText = app.post(apiUrl, json = payload, interceptor = ytInterceptor).text

            if (responseText.isNotBlank()) {
                val root = JSONObject(responseText)
                val captions = root.optJSONObject("captions")
                val tracklist = captions?.optJSONObject("playerCaptionsTracklistRenderer")
                val captionTracks = tracklist?.optJSONArray("captionTracks")

                if (captionTracks != null && captionTracks.length() > 0) {
                    val seenSubs = mutableSetOf<String>()

                    var baseTrack = captionTracks.optJSONObject(0)
                    var baseLangCode = ""

                    for (i in 0 until captionTracks.length()) {
                        val t = captionTracks.optJSONObject(i)
                        val lang = t.optString("languageCode")
                        if (lang == "en") {
                            baseTrack = t
                            break
                        }
                    }

                    val baseUrl = baseTrack.optString("baseUrl", "")
                    baseLangCode = baseTrack.optString("languageCode", "original").lowercase()
                    for (i in 0 until captionTracks.length()) {
                        val track = captionTracks.optJSONObject(i)
                        val name = track.optJSONObject("name")?.optString("simpleText") ?: ""
                        val lang = track.optString("languageCode")
                        val url = track.optString("baseUrl")

                        val vttUrl = "$url&fmt=vtt"
                        if (!seenSubs.contains(vttUrl)) {
                            seenSubs.add(vttUrl)
                            val displayTitle = "$name ($lang)"
                            subtitleCallback(SubtitleFile(displayTitle, vttUrl))
                        }
                    }
                    if (baseUrl.isNotEmpty()) {
                        val autoLangs = listOf(
                            "aa","ab","af","ak","am","ar","as","ay","az","ba","be","bg","bho","bn","bo","br","bs","ca","ceb","co","crs",
                            "cs","cy","da","de","dv","dz","ee","el","en","eo","es","et","eu","fa","fi","fil","fj","fo","fr","fy","ga",
                            "gaa","gd","gl","gn","gu","gv","ha","haw","he","hi","hmn","hr","ht","hu","hy","id","ig","is","it","iu","iw",
                            "ja","jv","ka","kha","kk","kl","km","kn","ko","kri","ku","ky","la","lb","lg","ln","lo","lt","lua","luo","lv",
                            "mfe","mg","mi","mk","ml","mn","mr","ms","mt","my","ne","new","nl","no","nso","ny","oc","om","or","os","pa",
                            "pam","pl","ps","pt","pt-BR","pt-PT","qu","rn","ro","ru","rw","sa","sd","sg","si","sk","sl","sm","sn","so","sq",
                            "sr","ss","st","su","sv","sw","ta","te","tg","th","ti","tk","tn","to","tr","ts","tt","tum","ug","uk","ur","uz",
                            "ve","vi","war","wo","xh","yi","yo","zh-Hans","zh-Hant","zu"
                        )

                        for (targetLang in autoLangs) {
                            if (targetLang.equals(baseLangCode, true)) continue

                            val autoUrl = "$baseUrl&fmt=vtt&tlang=$targetLang"

                            if (!seenSubs.contains(autoUrl)) {
                                seenSubs.add(autoUrl)
                                val displayName = "$baseLangCode → $targetLang"
                                subtitleCallback(SubtitleFile(displayName, autoUrl))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
        }

        return true
    }

    private fun parseM3u8Tag(tag: String, key: String): String? {

        val regex = Regex("""$key=("([^"]*)"|([^,]*))""")
        val match = regex.find(tag)
        return match?.groupValues?.get(2)?.ifBlank { null }
            ?: match?.groupValues?.get(3)?.ifBlank { null }
    }

    private fun String.extractYoutubeId(): String? {
        val regex = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }
}
