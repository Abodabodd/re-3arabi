package com.cee

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.lagradost.cloudstream3.Actor       // <--- تأكد من هذا الاستيراد
import com.lagradost.cloudstream3.ActorData // <--- وتأكد من هذا الاستيراد


class CeeProvider : MainAPI() {
    override var name = "cee (\uD83C\uDDEE\uD83C\uDDF6)"
    override var mainUrl = "https://cee.buzz"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    private val apiV2 = "$mainUrl/api/android"
    override val mainPage = mainPageOf(
        "$apiV2/newlyVideosItems/level/0/offset/12/page/" to "أحدث الإضافات",

        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=desc" to "أفلام - تاريخ الرفع - الأحدث",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=asc" to "أفلام - تاريخ الرفع - الأقدم",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=ar_title_asc" to "أفلام - أبجديًا (أ-ي)",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=ar_title_desc" to "أفلام - أبجديًا (ب-أ)",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc" to "أفلام - أبجديًا (Z-A)",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc" to "أفلام - أبجديًا (A-Z)",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_desc" to "أفلام - الأكثر مشاهدة",
        "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_desc" to "أفلام - أعلى تقييم IMDb",

        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=desc" to "مسلسلات - تاريخ الرفع - الأحدث",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=asc" to "مسلسلات - تاريخ الرفع - الأقدم",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc" to "مسلسلات - أبجديًا (أ-ي)",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc" to "مسلسلات - أبجديًا (ي-أ)",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc" to "مسلسلات - أبجديًا (Z-A)",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc" to "مسلسلات - أبجديًا (A-Z)",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_desc" to "مسلسلات - الأكثر مشاهدة",
        "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_desc" to "مسلسلات - أعلى تقييم IMDb",
    )


    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()
        var hasMore = false

        Log.d(name, "getMainPage called -> page=$page, request.name='${request.name}', request.data='${request.data}'")

        val requestData = request.data ?: ""
        if (requestData.isNotBlank()) {
            Log.d(name, "Detected section pagination request. Fetching only for request.data: $requestData , page=$page")

            val fetchUrl = when {
                requestData.contains("/page/") -> {
                    if (requestData.endsWith("/page/"))
                        "$requestData$page/"
                    else
                        requestData.replace(Regex("/page/\\d+/?$"), "/page/$page/")
                }

                requestData.contains("pageNumber=") -> {
                    val zeroBasedPage = page - 1
                    val replaced = requestData.replace(
                        Regex("pageNumber=\\d*"),
                        "pageNumber=$zeroBasedPage"
                    )

                    if (replaced == requestData) {
                        if (requestData.contains("?"))
                            "$requestData&pageNumber=$zeroBasedPage"
                        else
                            "$requestData?pageNumber=$zeroBasedPage"
                    } else replaced
                }

                else -> {
                    if (requestData.endsWith("/"))
                        "$requestData$page/"
                    else
                        "$requestData/$page/"
                }
            }



            Log.d(name, "Section fetch URL resolved to: $fetchUrl")
            val resp = runCatching { app.get(fetchUrl).parsedSafe<List<Map<String, Any>>>() }.getOrNull()
            val parsed = resp?.mapNotNull { it.toCinemanaItem().toSearchResponse() } ?: emptyList()

            Log.d(name, "Section parsed items count: ${parsed.size} (raw=${resp?.size ?: 0})")
            val listTitle = request.name ?: "القسم"
            items.add(HomePageList(listTitle, parsed))

            val rawSize = resp?.size ?: parsed.size
            hasMore = rawSize >= 24 || rawSize >= 30 || rawSize >= 12

            Log.d(name, "Returning only section results for '${listTitle}' with hasNext=$hasMore")
            return newHomePageResponse(items, hasNext = hasMore)
        }

        val newlyVideosUrl = "$apiV2/newlyVideosItems/level/0/offset/12/page/$page/"
        Log.d(name, "Fetching newly added videos for main page from: $newlyVideosUrl")
        val newlyResp = runCatching { app.get(newlyVideosUrl).parsedSafe<List<Map<String, Any>>>() }.getOrNull()
        newlyResp?.let { response ->
            if (response.isNotEmpty()) {
                val newlyVideos = response.mapNotNull { it.toCinemanaItem().toSearchResponse() }
                if (newlyVideos.isNotEmpty()) {
                    items.add(HomePageList("أحدث الإضافات", newlyVideos))
                    if (response.size >= 12) hasMore = true
                }
            }
        }

        try {
            @Suppress("UNCHECKED_CAST")
            val mainEntries = this.mainPage as? List<Pair<String, String>>
            if (!mainEntries.isNullOrEmpty()) {
                Log.d(name, "Building sections from mainPageOf entries (count=${mainEntries.size})")
                for ((baseTemplate, title) in mainEntries) {

                    val firstUrl = when {
                        baseTemplate.contains("/page/") && baseTemplate.endsWith("/page/") -> "$baseTemplate/0".replace("//0", "/0").replace("/page//0", "/page/0")
                        baseTemplate.contains("/page/") -> baseTemplate.replace(Regex("/page/\\d+/?$"), "/page/0/")
                        baseTemplate.contains("page=") && baseTemplate.endsWith("page=") -> "${baseTemplate}0"
                        baseTemplate.contains("page=") -> baseTemplate.replace(Regex("page=\\d*"), "page=0")
                        else -> baseTemplate
                    }.replace(":/", "://").replace(Regex("([^:])/+"), "$1/") // normalize

                    Log.d(name, "mainPage entry -> title='$title', base='$baseTemplate', first='$firstUrl'")

                    val pageResp = runCatching { app.get(firstUrl).parsedSafe<List<Map<String, Any>>>() }.getOrNull()
                    val parsedList = pageResp?.mapNotNull { it.toCinemanaItem().toSearchResponse() } ?: emptyList()

                    if (parsedList.isNotEmpty()) {
                        val hp = HomePageList(title, parsedList)

                        val candidateFieldNames = listOf("data", "requestData", "request", "pageUrl", "url", "extra", "nextPage", "params", "metadata")
                        var attached: String? = null
                        for (fName in candidateFieldNames) {
                            try {
                                val f = hp.javaClass.getDeclaredField(fName)
                                f.isAccessible = true
                                f.set(hp, baseTemplate)
                                attached = fName
                                Log.d(name, "Attached baseTemplate to HomePageList via field '$fName' for '$title'")
                                break
                            } catch (_: NoSuchFieldException) {
                            } catch (e: Exception) {
                                Log.w(name, "Reflection attach error on '$fName' for '$title': ${e.message}")
                            }
                        }
                        if (attached == null) {
                            val titleWithMeta = "$title ||PAGE_BASE::$baseTemplate"
                            items.add(HomePageList(titleWithMeta, parsedList))
                            Log.w(name, "Could not attach baseTemplate to HomePageList for '$title' — added fallback titleWithMeta")
                        } else {
                            items.add(hp)
                        }
                    } else {
                        Log.d(name, "First page for mainPage entry '$title' returned empty — skipping")
                    }
                }
            } else {
                Log.d(name, "mainPage property is empty or not a List<Pair<String,String>>; nothing added from it.")
            }
        } catch (e: Exception) {
            Log.w(name, "Error reading mainPage entries: ${e.message}")
        }

        if (items.isEmpty() && page == 1) {
            Log.d(name, "No sections built from mainPageOf — falling back to dynamic videoGroups from API.")
            val videoGroupsUrl = "$apiV2/videoGroups/lang/ar/level/0"
            val groups = runCatching { app.get(videoGroupsUrl).parsedSafe<List<VideoGroup>>() }.getOrNull()
            groups?.forEach { group ->
                val gid = group.id ?: return@forEach
                val gTitle = group.title ?: "مجموعة غير معروفة"
                val basePage = "$apiV2/videoListPagination/groupID/$gid/level/0/itemsPerPage/24/page/"
                val first = if (basePage.endsWith("/page/")) "$basePage/0".replace("//0", "/0").replace("/page//0", "/page/0") else "$basePage/0"
                val normalizedFirst = first.replace(":/", "://").replace("//", "/").replace(":/", "://")

                Log.d(name, "Fetching first page for dynamic group '$gTitle' from: $normalizedFirst")
                val grpResp = runCatching { app.get(normalizedFirst).parsedSafe<List<Map<String, Any>>>() }.getOrNull()
                val grpParsed = grpResp?.mapNotNull { it.toCinemanaItem().toSearchResponse() } ?: emptyList()
                if (grpParsed.isNotEmpty()) {
                    val list = HomePageList(gTitle, grpParsed)
                    var attachedFieldName: String? = null
                    val candidateFieldNames = listOf("data", "requestData", "request", "pageUrl", "url", "extra", "nextPage", "params", "metadata")
                    for (fieldName in candidateFieldNames) {
                        try {
                            val f = list.javaClass.getDeclaredField(fieldName)
                            f.isAccessible = true
                            f.set(list, basePage)
                            attachedFieldName = fieldName
                            Log.d(name, "Attached basePage to HomePageList via field '$fieldName' for group '$gTitle'")
                            break
                        } catch (_: NoSuchFieldException) {
                        } catch (e: Exception) {
                            Log.w(name, "Reflection error on field '$fieldName': ${e.message}")
                        }
                    }
                    if (attachedFieldName == null) {
                        val titleWithMeta = "$gTitle ||PAGE_BASE::$basePage"
                        items.add(HomePageList(titleWithMeta, grpParsed))
                        Log.w(name, "Reflection failed for dynamic group — added fallback titleWithMeta for '$gTitle'")
                    } else {
                        items.add(list)
                    }
                }
            }
        }

        Log.d(name, "getMainPage finished -> totalSections=${items.size}, hasNext=$hasMore")
        return newHomePageResponse(items, hasNext = hasMore)
    }




    override suspend fun search(query: String): List<SearchResponse>? {
        Log.d(name, "🔍 search() first page query='$query' (fallback to paged)")
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {
        val encoded = URLEncoder.encode(query, "utf-8")
        val itemsPerPageSearch = 30 // هذا لا يستخدم مباشرة لتحديد hasMore الآن
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val yearRange = "1900,$currentYear"

        val pageParam_0_indexed = (page - 1).coerceAtLeast(0) // API uses 0-indexed pages

        Log.d(
            name,
            "🔎 [SEARCH_PAGINATION] Initiating search for query: '$query', requested page: $page"
        )
        Log.d(name, "  - Calculated API page parameter (0-indexed): $pageParam_0_indexed")
        Log.d(name, "  - Items requested per API call: $itemsPerPageSearch")

        val moviesUrl =
            "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$yearRange&page=$pageParam_0_indexed&type=movies&itemsPerPage=$itemsPerPageSearch"
        val seriesUrl =
            "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$yearRange&page=$pageParam_0_indexed&type=series&itemsPerPage=$itemsPerPageSearch"

        Log.d(name, "  - Movies API URL: $moviesUrl")
        Log.d(name, "  - Series API URL: $seriesUrl")

        val (moviesRawAndParsed, seriesRawAndParsed) = listOf(moviesUrl, seriesUrl).map { url ->
            async(Dispatchers.IO) {
                runCatching {
                    Log.d(name, "📡 [SEARCH_PAGINATION] Requesting data from: $url")
                    val rawResp = app.get(url).parsedSafe<List<Map<String, Any>>>()
                    val rawSize = rawResp?.size ?: 0
                    Log.d(name, "📥 [SEARCH_PAGINATION] Received RAW ${rawSize} items from $url")

                    val parsedItems = rawResp?.mapNotNull { itemMap ->
                        val cinemanaItem = itemMap.toCinemanaItem()
                        if (cinemanaItem.nb == null) {
                            Log.w(
                                name,
                                "⚠️ [SEARCH_PAGINATION_PARSE_WARN] CinemanaItem.nb is NULL for item from $url. Raw Map: $itemMap"
                            )
                        }
                        val searchResponse = cinemanaItem.toSearchResponse()
                        if (searchResponse == null) {
                            Log.w(
                                name,
                                "⚠️ [SEARCH_PAGINATION_PARSE_WARN] toSearchResponse returned NULL for item from $url. CinemanaItem: $cinemanaItem"
                            )
                        }
                        searchResponse
                    } ?: emptyList()

                    Log.d(
                        name,
                        "✨ [SEARCH_PAGINATION] PARSED ${parsedItems.size} valid items from $url (after filtering null IDs/responses)."
                    )
                    Pair(rawSize, parsedItems)
                }.getOrDefault(Pair(0, emptyList()))
            }
        }.awaitAll()

        val moviesRawCount = moviesRawAndParsed.first
        val movies = moviesRawAndParsed.second

        val seriesRawCount = seriesRawAndParsed.first
        val series = seriesRawAndParsed.second

        Log.d(
            name,
            "🎬 [SEARCH_PAGINATION] Movies: RAW=${moviesRawCount}, PARSED=${movies.size} for page $page."
        )
        Log.d(
            name,
            "📺 [SEARCH_PAGINATION] Series: RAW=${seriesRawCount}, PARSED=${series.size} for page $page."
        )

        val maxSize = maxOf(movies.size, series.size)
        val interleaved = ArrayList<SearchResponse>(movies.size + series.size)
        for (i in 0 until maxSize) {
            if (i < movies.size) interleaved.add(movies[i])
            if (i < series.size) interleaved.add(series[i])
        }

        Log.d(
            name,
            "🔄 [SEARCH_PAGINATION] Interleaved ${interleaved.size} items in total for page $page."
        )

        fun scoreMatch(title: String?, q: String): Int {
            if (title.isNullOrBlank()) return 0
            val t = title.lowercase()
            val ql = q.lowercase().trim()
            if (t == ql) return 100
            if (t.startsWith(ql)) return 80
            if (t.contains(ql)) return 60
            val tokens = ql.split(Regex("\\s+")).filter { it.isNotBlank() }
            val tokenMatches = tokens.count { t.contains(it) }
            return 40 + tokenMatches
        }

        val sorted = interleaved
            .mapIndexed { idx, item ->
                val titleCandidate = item.name ?: item.url ?: ""
                val score = scoreMatch(titleCandidate, query)
                Triple(item, score, idx)
            }
            .sortedWith(
                compareByDescending<Triple<SearchResponse, Int, Int>> { it.second }
                    .thenBy { it.third }
            )
            .map { it.first }

        val finalResults = sorted.distinctBy { "${it.url ?: ""}-${it.name ?: ""}" }

        Log.d(name, "✅ Sorted & deduped results = ${finalResults.size}")

        var hasMore = interleaved.isNotEmpty()

        Log.d(
            name,
            "🤔 [SEARCH_PAGINATION] Determining 'hasMore' using interleaved.isNotEmpty() logic."
        )
        Log.d(name, "  - Total interleaved items for page $page: ${interleaved.size}")
        Log.d(name, "  - Result: hasMore = $hasMore. (If true, UI will request page ${page + 1})")

        if (finalResults.isEmpty() && page == 1) {
            val fallbackYearRange = "1900,2024"
            Log.d(name, "🔁 No results on primary yearRange ($yearRange). Trying fallback yearRange = $fallbackYearRange")

            val moviesUrlFb =
                "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$fallbackYearRange&page=$pageParam_0_indexed&type=movies&itemsPerPage=$itemsPerPageSearch"
            val seriesUrlFb =
                "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$fallbackYearRange&page=$pageParam_0_indexed&type=series&itemsPerPage=$itemsPerPageSearch"

            val (moviesFbRawAndParsed, seriesFbRawAndParsed) = listOf(moviesUrlFb, seriesUrlFb).map { url ->
                async(Dispatchers.IO) {
                    runCatching {
                        Log.d(name, "📡 [SEARCH_FALLBACK] Requesting data from: $url")
                        val rawResp = app.get(url).parsedSafe<List<Map<String, Any>>>()
                        val rawSize = rawResp?.size ?: 0
                        Log.d(name, "📥 [SEARCH_FALLBACK] Received RAW ${rawSize} items from $url")

                        val parsedItems = rawResp?.mapNotNull { itemMap ->
                            val cinemanaItem = itemMap.toCinemanaItem()
                            val searchResponse = cinemanaItem.toSearchResponse()
                            searchResponse
                        } ?: emptyList()

                        Log.d(name, "✨ [SEARCH_FALLBACK] PARSED ${parsedItems.size} valid items from $url.")
                        Pair(rawSize, parsedItems)
                    }.getOrDefault(Pair(0, emptyList()))
                }
            }.awaitAll()

            val moviesFb = moviesFbRawAndParsed.second
            val seriesFb = seriesFbRawAndParsed.second

            val maxFb = maxOf(moviesFb.size, seriesFb.size)
            val interleavedFb = ArrayList<SearchResponse>(moviesFb.size + seriesFb.size)
            for (i in 0 until maxFb) {
                if (i < moviesFb.size) interleavedFb.add(moviesFb[i])
                if (i < seriesFb.size) interleavedFb.add(seriesFb[i])
            }

            val sortedFb = interleavedFb
                .mapIndexed { idx, item ->
                    val titleCandidate = item.name ?: item.url ?: ""
                    val score = scoreMatch(titleCandidate, query)
                    Triple(item, score, idx)
                }
                .sortedWith(
                    compareByDescending<Triple<SearchResponse, Int, Int>> { it.second }
                        .thenBy { it.third }
                )
                .map { it.first }
                .distinctBy { "${it.url ?: ""}-${it.name ?: ""}" }

            if (sortedFb.isNotEmpty()) {
                Log.d(name, "✅ Fallback returned ${sortedFb.size} results (yearRange=$fallbackYearRange).")
                hasMore = interleavedFb.isNotEmpty()
                return@coroutineScope newSearchResponseList(sortedFb, hasMore)
            } else {
                Log.d(name, "❌ Fallback also returned 0 results (yearRange=$fallbackYearRange). Returning empty list.")
            }
        }

        Log.d(
            name,
            "✅ [SEARCH_PAGINATION] Search for query: '$query', page: $page completed. Returning ${finalResults.size} items with hasMore: $hasMore."
        )
        newSearchResponseList(finalResults, hasMore)
    }


    override suspend fun load(url: String): LoadResponse? {
        val extractedId = url.substringAfterLast("/")

        val detailsUrl = "$mainUrl/api/android/allVideoInfo/id/$extractedId"
        Log.d(
            name,
            "Loading details for URL: $detailsUrl (Using extracted ID: $extractedId from input URL: $url)"
        )
        val detailsMap = app.get(detailsUrl).parsedSafe<Map<String, Any>>()
        if (detailsMap == null) {
            Log.e(
                name,
                "Failed to parse details from: $detailsUrl. Response might be empty or malformed."
            )
            return null
        }
        val details = detailsMap.toCinemanaItem()

        val title = details.enTitle ?: run {
            Log.e(name, "Title is null for item from URL: $detailsUrl")
            return null
        }
        val posterUrl = details.imgObjUrl
        val plot = details.enContent
        val year = details.year?.toIntOrNull()

        val ratingFloatPrimary = details.stars?.toFloatOrNull()

        val finalRatingScore: Score? = ratingFloatPrimary?.let { Score.from10(it) } ?: run {
            val altCandidates = listOf("rate", "filmRating", "seriesRating")
            altCandidates.mapNotNull { k ->
                val raw = detailsMap[k]
                val asFloat = when (raw) {
                    is Number -> raw.toDouble().toFloat()
                    is String -> raw.toFloatOrNull()
                    else -> null
                }
                asFloat?.let { Score.from10(it) }
            }.firstOrNull()
        }

        Log.d(name, "Parsed rating: raw='${details.stars}', finalScore=$finalRatingScore")

        val genresList = details.categories
            ?.mapNotNull { cat ->
                cat.en_title?.takeIf { it.isNotBlank() } ?: cat.ar_title?.takeIf { it.isNotBlank() }
            }
            ?.distinct()
            ?: emptyList()

        val actorsList: List<ActorData> = details.actorsInfo?.mapNotNull { actorInfoItem ->
            val actorName =
                actorInfoItem.name?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val actorImageUrl = actorInfoItem.staff_img_thumb ?: actorInfoItem.staff_img
            ?: "defaultImages/not_available.jpg"

            ActorData(
                actor = Actor(
                    name = actorName, // فقط الاسم
                    image = actorImageUrl
                ),
                roleString = null // تجاهل رقم الدور
            )
        } ?: emptyList()

        return if (details.kind == 2) {

            val seasonsAndEpisodesUrl = "$mainUrl/api/android/videoSeason/id/$extractedId"
            Log.d(name, "Fetching seasons and episodes from: $seasonsAndEpisodesUrl")

            val episodesResponse =
                app.get(seasonsAndEpisodesUrl).parsedSafe<List<Map<String, Any>>>()
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
                    Log.w(
                        name,
                        "Skipping malformed episode item: $episodeMap for series ID: $extractedId"
                    )
                }
            }

            val sortedSeasonNumbers = seasonsMap.keys.sorted()
            sortedSeasonNumbers.forEach { sNum ->
                val seasonEpisodes = seasonsMap[sNum]
                seasonEpisodes?.sortBy { it.episode }
                if (seasonEpisodes != null) episodes.addAll(seasonEpisodes)
            }

            newTvSeriesLoadResponse(title, extractedId, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.score = finalRatingScore
                if (genresList.isNotEmpty()) this.tags = genresList
                if (actorsList.isNotEmpty()) this.actors = actorsList
            }
        } else {

            newMovieLoadResponse(title, extractedId, TvType.Movie, extractedId) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.year = year
                this.score = finalRatingScore
                if (genresList.isNotEmpty()) this.tags = genresList
                if (actorsList.isNotEmpty()) this.actors = actorsList
            }
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val extractedId = data.substringAfterLast("/")

        val videosUrl = "$apiV2/transcoddedFiles/id/$extractedId"
        Log.d(name, "Fetching video links from: $videosUrl")
        val videoResponse = app.get(videosUrl).parsedSafe<List<Map<String, Any>>>()

        if (videoResponse.isNullOrEmpty()) {
            Log.e(
                name,
                "Failed to get video links from $videosUrl or response was empty for ID: $extractedId"
            )
            return false
        }

        Log.d(
            name,
            "Received ${videoResponse.size} links. Reversing order to show highest quality first."
        )

        videoResponse.reversed().forEach { videoMap ->
            val videoUrl = videoMap["videoUrl"] as? String
            val resolution = videoMap["resolution"] as? String
            val linkName = resolution ?: "Default"

            if (videoUrl != null) {
                val headers = mapOf("Referer" to mainUrl)
                callback(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = videoUrl
                    ) {
                        getQualityFromName(resolution)
                    }
                )
            } else {
                Log.w(name, "videoUrl is null for a video map in ID: $extractedId, Map: $videoMap")
            }
        }

        val detailsUrl = "$apiV2/allVideoInfo/id/$extractedId"
        app.get(detailsUrl).parsedSafe<Map<String, Any>>()?.let { detailsMap ->
            (detailsMap["translations"] as? List<Map<String, Any>>)?.forEach { sub ->
                val file = sub["file"] as? String
                val lang = sub["name"] as? String
                if (file != null && lang != null) {
                    subtitleCallback(SubtitleFile(lang, file))
                }
            }
        }

        return true
    }

    @Serializable
    data class Category(
        val en_title: String? = null,
        val ar_title: String? = null
    )

    @Serializable
    data class ActorInfo(
        val nb: String? = null,
        val name: String? = null,
        val role: String? = null,
        val staff_img: String? = null,
        val staff_img_thumb: String? = null,
        val staff_img_medium_thumb: String? = null
    )

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
        @SerialName("episodeNummer") val episodeNummer: String? = null,
        val season: String? = null,
        val categories: List<Category>? = null,
        @SerialName("actorsInfo") val actorsInfo: List<ActorInfo>? = null
    )

    @Serializable
    data class SeasonNumberItem(
        val season: String? = null
    )

    @Serializable
    data class VideoGroup(
        val id: String? = null,
        val title: String? = null,
    )

    private fun Map<String, Any>.toCinemanaItem(): CinemanaItem {
        val parsedNb = when (val nbValue = this["nb"]) {
            is String -> nbValue
            is Int -> nbValue.toString()
            is Double -> nbValue.toLong().toString()
            else -> null
        }

        val categoriesParsed = (this["categories"] as? List<*>)?.mapNotNull { c ->
            (c as? Map<*, *>)?.let { m ->
                Category(
                    en_title = m["en_title"] as? String,
                    ar_title = m["ar_title"] as? String
                )
            }
        }

        val actorsParsed = (this["actorsInfo"] as? List<*>)?.mapNotNull { a ->
            (a as? Map<*, *>)?.let { m ->
                ActorInfo(
                    nb = (m["nb"] as? String) ?: (m["nb"] as? Int)?.toString(),
                    name = m["name"] as? String,
                    role = m["role"] as? String,
                    staff_img = m["staff_img"] as? String,
                    staff_img_thumb = m["staff_img_thumb"] as? String,
                    staff_img_medium_thumb = m["staff_img_medium_thumb"] as? String
                )
            }
        }

        return CinemanaItem(
            nb = parsedNb,
            enTitle = this["en_title"] as? String,
            imgObjUrl = this["imgObjUrl"] as? String ?: this["img"] as? String,
            year = this["year"] as? String,
            enContent = this["ar_content"] as? String,
            stars = this["stars"] as? String,
            kind = (this["kind"] as? String)?.toIntOrNull() ?: (this["kind"] as? Int),
            fileFile = this["fileFile"] as? String,
            episodeNummer = this["episodeNummer"] as? String,
            season = this["season"] as? String,
            categories = categoriesParsed,
            actorsInfo = actorsParsed
        )
    }
    private fun CinemanaItem.toSearchResponse(): SearchResponse? {
        val validNb = nb ?: run {
            Log.e(name, "🚫 CinemanaItem.nb is null for title: $enTitle")
            return null
        }

        val rating = this.stars?.toFloatOrNull()
        val scoreObject = rating?.let { Score.from10(it) }

        return if (kind == 2) {
            newTvSeriesSearchResponse(
                name = enTitle ?: "No Title",
                url = validNb,
                type = TvType.TvSeries
            ) {
                this.posterUrl = imgObjUrl
                this.score = scoreObject
            }
        } else {
            newMovieSearchResponse(
                name = enTitle ?: "No Title",
                url = validNb,
                type = TvType.Movie
            ) {
                this.posterUrl = imgObjUrl
                this.score = scoreObject
            }
        }
    }
}