package com.cinemana

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import android.util.Log

// *** تعريف Score (داخل الإضافة) - تم الاحتفاظ به من الكود الثاني ***
@Serializable
data class Score(
    val float: Float,
    val int: Double,
    val text: String? = null
) {
    companion object {
        fun from10(score: Float?): Score? {
            return score?.let { Score(it, 10.0, null) }
        }
    }
}

class CinemanaProvider : MainAPI() {
    override var name = "Shabakaty Cinemana slow (\uD83C\uDDEE\uD83C\uDDF6)" // احتفظت بالاسم من الكود الثاني
    override var mainUrl = "https://cinemana.shabakaty.com"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true

    // تم إزالة تعريف override val mainPage = listOf(...)
    // لأن دالة getMainPage من الكود الأول لا تستخدم هذا النمط.

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        // -----------------------------------------------------
        // 1. "أحدث الإضافات" (التي تستخدم API newlyVideosItems)
        // تم استخدام منطق الكود الأول بالضبط
        val newlyVideosUrl = "$mainUrl/api/android/newlyVideosItems/level/0/offset/12/page/$page/"
        Log.d(name, "Fetching newly added videos for main page from: $newlyVideosUrl")
        val newlyVideosResponse = app.get(newlyVideosUrl).parsedSafe<List<Map<String, Any>>>()
        if (newlyVideosResponse != null && newlyVideosResponse.isNotEmpty()) {
            val newlyVideos = newlyVideosResponse.mapNotNull { it.toCinemanaItem().toSearchResponse() }
            if (newlyVideos.isNotEmpty()) {
                items.add(HomePageList("أحدث الإضافات", newlyVideos))
                Log.d(name, "Added ${newlyVideos.size} newly added videos to main page.")
            } else {
                Log.w(name, "Parsed newlyVideosResponse but got empty list of SearchResponse.")
            }
        } else {
            Log.e(name, "Failed to parse newly added videos response or it was empty from: $newlyVideosUrl")
        }

        // -----------------------------------------------------
        // 2. الفئات / المجموعات الديناميكية (مثل أفلام 4K, أفلام مميزة)
        // تم استخدام منطق الكود الأول بالضبط
        val videoGroupsUrl = "$mainUrl/api/android/videoGroups/lang/ar/level/0"
        Log.d(name, "Fetching video groups from: $videoGroupsUrl")
        val videoGroupsResponse = app.get(videoGroupsUrl).parsedSafe<List<VideoGroup>>()

        videoGroupsResponse?.forEach { group ->
            val groupId = group.id ?: return@forEach
            val groupTitle = group.title ?: "مجموعة غير معروفة"

            // ملاحظة: الكود الأول يستخدم 'page' مباشرة هنا. الكود الثاني استخدم 'pageNumber'. سألتزم بالكود الأول.
            val groupContentUrl = "$mainUrl/api/android/videoListPagination/groupID/$groupId/level/0/itemsPerPage/24/page/$page"
            Log.d(name, "Fetching content for group '$groupTitle' (ID: $groupId) from: $groupContentUrl")
            val groupContentResponse = app.get(groupContentUrl).parsedSafe<List<Map<String, Any>>>()
            if (groupContentResponse != null && groupContentResponse.isNotEmpty()) {
                val groupContent = groupContentResponse.mapNotNull { it.toCinemanaItem().toSearchResponse() }
                if (groupContent.isNotEmpty()) {
                    items.add(HomePageList(groupTitle, groupContent))
                    Log.d(name, "Added ${groupContent.size} items for group '$groupTitle'.")
                } else {
                    Log.w(name, "Parsed group content response for '$groupTitle' but got empty list of SearchResponse.")
                }
            } else {
                Log.e(name, "Failed to parse content for group '$groupTitle' from: $groupContentUrl or response was empty.")
            }
        }
        if (videoGroupsResponse.isNullOrEmpty()) {
            Log.w(name, "No video groups found or failed to parse from: $videoGroupsUrl")
        }

        // -----------------------------------------------------
        // 3. القوائم المصنفة (حسب الإصدار، الأبجدية، المشاهدات، الفئة العمرية، IMDb)
        // تم استخدام منطق الكود الأول بالضبط
        val contentTypes = listOf(
            TvType.Movie to "1",
            TvType.TvSeries to "2"
        )

        val sortedListsConfig = listOf(
            Triple("أحدث إصدار", "desc", "الملفات الحديثة"),
            Triple("أقدم إصدار", "asc", "الملفات القديمة"),
            Triple("سنة الإصدار الأحدث", "r_desc", null),
            Triple("سنة الإصدار الأقدم", "r_asc", null),
            Triple("أبجديًا تنازليًا", "title_desc", null),
            Triple("أبجديًا تصاعديًا", "title_asc", null),
            Triple("الأكثر مشاهدة", "views_desc", null),
            Triple("الأقل مشاهدة", "views_asc", null),
            Triple("الأعلى تصنيفًا عمريًا", "rating_desc", "الفئة العمرية تنازلي"),
            Triple("الأقل تصنيفًا عمريًا", "rating_asc", "الفئة العمرية تصاعدي"),
            Triple("أعلى تقييم IMDb", "stars_desc", null),
            Triple("أقل تقييم IMDb", "stars_asc", null)
        )

        for ((titleSuffix, sortKey, customTitlePart) in sortedListsConfig) {
            for ((tvType, videoKind) in contentTypes) {
                val listTitle = if (customTitlePart != null) {
                    "${tvType.name.replace("Tv", "")} - $customTitlePart"
                } else {
                    "${tvType.name.replace("Tv", "")} - $titleSuffix"
                }

                // ملاحظة: الكود الأول يستخدم 'page' مباشرة هنا. الكود الثاني استخدم 'pageNumber'. سألتزم بالكود الأول.
                val sortedUrl = "$mainUrl/api/android/video/V/2/itemsPerPage/24/level/0/videoKind/$videoKind/sortParam/$sortKey/pageNumber/$page"
                Log.d(name, "Fetching $listTitle from: $sortedUrl")
                val sortedResponse = app.get(sortedUrl).parsedSafe<List<Map<String, Any>>>()

                if (sortedResponse != null && sortedResponse.isNotEmpty()) {
                    val sortedVideos = sortedResponse.mapNotNull { it.toCinemanaItem().toSearchResponse() }
                    if (sortedVideos.isNotEmpty()) {
                        items.add(HomePageList(listTitle, sortedVideos))
                        Log.d(name, "Added ${sortedVideos.size} items for '$listTitle'.")
                    } else {
                        Log.w(name, "Parsed sorted response for '$listTitle' but got empty list of SearchResponse.")
                    }
                } else {
                    Log.e(name, "Failed to parse sorted response for '$listTitle' or it was empty from: $sortedUrl")
                }
            }
        }
        // -----------------------------------------------------

        if (items.isEmpty()) {
            Log.w(name, "getMainPage returned no content after all attempts. Check API responses and item parsing.")
        }

        return newHomePageResponse(items, hasNext = true)
    }

    // *** دالة البحث - تم الاحتفاظ بها من الكود الثاني كما هي ***
    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        val itemsPerPageSearch = 30
        val yearRange = "1900,2025"

        val maxPagesToFetch = 3

        for (pageNumberSearch in 0 until maxPagesToFetch) {
            val commonParams = "level=0&videoTitle=$query&staffTitle=$query&year=$yearRange&page=$pageNumberSearch"

            val moviesSearchUrl = "$mainUrl/api/android/AdvancedSearch?$commonParams&type=movies&itemsPerPage=$itemsPerPageSearch"
            Log.d(name, "Searching movies for '$query' at: $moviesSearchUrl (Page: $pageNumberSearch)")
            val moviesResponse = app.get(moviesSearchUrl).parsedSafe<List<Map<String, Any>>>() ?: emptyList()
            val movies = moviesResponse.mapNotNull { it.toCinemanaItem().toSearchResponse() }
            allResults.addAll(movies)

            val seriesSearchUrl = "$mainUrl/api/android/AdvancedSearch?$commonParams&type=series&itemsPerPage=$itemsPerPageSearch"
            Log.d(name, "Searching series for '$query' at: $seriesSearchUrl (Page: $pageNumberSearch)")
            val seriesResponse = app.get(seriesSearchUrl).parsedSafe<List<Map<String, Any>>>() ?: emptyList()
            val series = seriesResponse.mapNotNull { it.toCinemanaItem().toSearchResponse() }
            allResults.addAll(series)

            if (movies.isEmpty() && series.isEmpty()) {
                Log.d(name, "No more results found after page $pageNumberSearch for query '$query'. Stopping.")
                break
            }
        }

        return allResults
    }

    // *** دالة Load - تم الاحتفاظ بها من الكود الثاني كما هي ***
    override suspend fun load(url: String): LoadResponse? {
        val extractedId = url.substringAfterLast("/")

        val detailsUrl = "$mainUrl/api/android/allVideoInfo/id/$extractedId"
        Log.d(name, "Loading details for URL: $detailsUrl (Using extracted ID: $extractedId from input URL: $url)")
        val detailsMap = app.get(detailsUrl).parsedSafe<Map<String, Any>>()
        if (detailsMap == null) {
            Log.e(name, "Failed to parse details from: $detailsUrl. Response might be empty or malformed.")
            return null
        }
        val details = detailsMap.toCinemanaItem()

        val title = details.enTitle
        if (title == null) {
            Log.e(name, "Title is null for item from URL: $detailsUrl")
            return null
        }
        val posterUrl = details.imgObjUrl
        val plot = details.enContent
        val year = details.year?.toIntOrNull()

        val ratingFloat = details.stars?.toFloatOrNull()
        val scoreObject = ratingFloat?.let { Score.from10(it) }

        return if (details.kind == 2) { // kind = 2 للمسلسلات
            Log.d(name, "Found a TvSeries with ID: $extractedId, Title: $title")

            val seasonsAndEpisodesUrl = "$mainUrl/api/android/videoSeason/id/$extractedId"
            Log.d(name, "Fetching seasons and episodes from: $seasonsAndEpisodesUrl")

            val episodesResponse = app.get(seasonsAndEpisodesUrl).parsedSafe<List<Map<String, Any>>>()
            val episodes = mutableListOf<Episode>()

            val seasonsMap = mutableMapOf<Int, MutableList<Episode>>()

            episodesResponse?.forEach { episodeMap ->
                val episodeDetails = episodeMap.toCinemanaItem()
                if (episodeDetails.nb != null && episodeDetails.enTitle != null) {
                    val episodeNum = (episodeDetails.episodeNummer as? String)?.toIntOrNull() ?: 1
                    val seasonNum = (episodeDetails.season as? String)?.toIntOrNull() ?: 1

                    val episodeTitle = "الموسم $seasonNum - الحلقة $episodeNum"

                    val newEpisode = newEpisode(episodeDetails.nb) {
                        this.name = episodeTitle
                        this.season = seasonNum
                        this.episode = episodeNum
                        this.posterUrl = episodeDetails.imgObjUrl ?: posterUrl
                        this.description = episodeDetails.enContent
                    }
                    seasonsMap.getOrPut(seasonNum) { mutableListOf() }.add(newEpisode)
                } else {
                    Log.w(name, "Skipping malformed episode item from response: $episodeMap for series ID: $extractedId")
                }
            }

            if (episodesResponse.isNullOrEmpty()) {
                Log.e(name, "Episodes API ($seasonsAndEpisodesUrl) response was null or empty for series ID: $extractedId")
            } else if (seasonsMap.isEmpty()) {
                Log.w(name, "Parsed episodes API response, but no valid episodes found for series ID: $extractedId. Raw response might be: $episodesResponse")
            }

            val sortedSeasonNumbers = seasonsMap.keys.sorted()

            sortedSeasonNumbers.forEach { sNum ->
                val seasonEpisodes = seasonsMap[sNum]
                if (seasonEpisodes != null) {
                    seasonEpisodes.sortBy { it.episode }
                    episodes.addAll(seasonEpisodes)
                }
            }

            newTvSeriesLoadResponse(title, extractedId, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        } else { // kind = 1 للأفلام (أو أي قيمة أخرى غير 2)
            Log.d(name, "Returning MovieLoadResponse for: $title (ID: $extractedId)")
            newMovieLoadResponse(title, extractedId, TvType.Movie, extractedId) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
            }
        }
    }

    // *** دالة LoadLinks - تم الاحتفاظ بها من الكود الثاني كما هي ***
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val extractedId = data.substringAfterLast("/")

        val videosUrl = "$mainUrl/api/android/transcoddedFiles/id/$extractedId"
        Log.d(name, "Attempting to fetch video links from: $videosUrl (Using extracted ID: $extractedId from input data: $data)")
        val videoResponse = app.get(videosUrl).parsedSafe<List<Map<String, Any>>>()

        if (videoResponse == null || videoResponse.isEmpty()) {
            Log.e(name, "Failed to get video links from $videosUrl or response was empty for ID: $extractedId")
            return false
        }

        Log.d(name, "Received video response: ${videoResponse.size} links found for ID: $extractedId.")

        videoResponse.forEach { videoMap ->
            val videoUrl = videoMap["videoUrl"] as? String
            val resolution = videoMap["resolution"] as? String
            val linkName = resolution ?: "Default"

            if (videoUrl != null) {
                val headers = mapOf("Referer" to mainUrl)
                Log.d(name, "Creating ExtractorLink: Name='$linkName', URL='$videoUrl', Headers=$headers, Resolution String='$resolution'")
                callback(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = videoUrl
                    ) {
                        this.headers = headers
                        this.quality = getQualityFromName(resolution)
                    }
                )
            } else {
                Log.w(name, "videoUrl is null for a video map in ID: $extractedId, Map: $videoMap")
            }
        }

        val detailsUrl = "$mainUrl/api/android/allVideoInfo/id/$extractedId"
        Log.d(name, "Attempting to fetch subtitle links from: $detailsUrl (Using extracted ID: $extractedId from input data: $data)")
        val detailsMap = app.get(detailsUrl).parsedSafe<Map<String, Any>>()

        if (detailsMap != null) {
            val translations = detailsMap["translations"] as? List<Map<String, Any>>
            if (translations != null) {
                Log.d(name, "Found ${translations.size} subtitle tracks for ID: $extractedId.")
                translations.forEach { sub ->
                    val file = sub["file"] as? String
                    val lang = sub["name"] as? String
                    if (file != null && lang != null) {
                        Log.d(name, "Adding subtitle: Language='$lang', URL='$file'")
                        subtitleCallback(SubtitleFile(lang, file))
                    } else {
                        Log.w(name, "Subtitle file or language is null for sub: $sub")
                    }
                }
            } else {
                Log.d(name, "No 'translations' key found or it's not a list in allVideoInfo for ID: $extractedId")
            }
        } else {
            Log.e(name, "Failed to get allVideoInfo for subtitle fetching for ID: $extractedId")
        }

        return true
    }

    // *** CinemanaItem - من الكود الثاني مع تحليل nb القوي ***
    @Serializable
    data class CinemanaItem(
        val nb: String? = null,
        @SerialName("en_title") val enTitle: String? = null,
        val imgObjUrl: String? = null,
        val year: String? = null,
        @SerialName("en_content") val enContent: String? = null,
        val stars: String? = null,
        val kind: Int? = null,
        val fileFile: String? = null,
        @SerialName("episodeNummer") val episodeNummer: String? = null, // تم الاحتفاظ به لدعم المسلسلات
        val season: String? = null // تم الاحتفاظ به لدعم المسلسلات
    )

    @Serializable
    data class SeasonNumberItem(
        val season: String? = null
    )

    // *** VideoGroup - من الكود الثاني ***
    @Serializable
    data class VideoGroup(
        val id: String? = null,
        val title: String? = null,
    )

    // *** toCinemanaItem - من الكود الثاني مع تحليل nb القوي ***
    private fun Map<String, Any>.toCinemanaItem(): CinemanaItem {
        // تحليل أكثر قوة لـ 'nb' للتعامل مع Int أو Double أيضًا، وهو ما قد يحل مشكلة "الواجهة الرئيسية لا تفتح"
        val parsedNb = when (val nbValue = this["nb"]) {
            is String -> nbValue
            is Int -> nbValue.toString()
            is Double -> nbValue.toLong().toString()
            else -> null
        }

        return CinemanaItem(
            nb = parsedNb,
            enTitle = this["en_title"] as? String,
            imgObjUrl = this["imgObjUrl"] as? String ?: this["img"] as? String,
            year = this["year"] as? String,
            enContent = this["en_content"] as? String,
            stars = this["stars"] as? String,
            kind = (this["kind"] as? String)?.toIntOrNull() ?: (this["kind"] as? Int),
            fileFile = this["fileFile"] as? String,
            episodeNummer = this["episodeNummer"] as? String, // تم الاحتفاظ به لدعم المسلسلات
            season = this["season"] as? String // تم الاحتفاظ به لدعم المسلسلات
        )
    }

    // *** toSearchResponse - من الكود الثاني مع رسائل تسجيل محسنة ***
    private fun CinemanaItem.toSearchResponse(): SearchResponse {
        val validNb = nb ?: run {
            Log.e(name, "CinemanaItem.nb is null, cannot create SearchResponse for title: $enTitle")
            return newMovieSearchResponse("Error", "error", TvType.Movie)
        }

        return if (kind == 2) {
            newTvSeriesSearchResponse(enTitle ?: "No Title", validNb, TvType.TvSeries) {
                this.posterUrl = imgObjUrl
            }
        } else {
            newMovieSearchResponse(enTitle ?: "No Title", validNb, TvType.Movie) {
                this.posterUrl = imgObjUrl
            }
        }
    }
}