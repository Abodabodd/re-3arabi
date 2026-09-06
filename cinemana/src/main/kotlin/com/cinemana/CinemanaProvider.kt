package com.cinemana

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.net.URLEncoder
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.utils.getQualityFromName


class Cinemana(val context: Context) : MainAPI() {
    override var name = "Shabakaty Cinemana (\uD83C\uDDEE\uD83C\uDDF6)"
    override var mainUrl = "https://cinemana.shabakaty.cc"
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val hasMainPage = true
    private val apiV2 = "$mainUrl/api/android"

    private data class Section(
        val title: String,
        val url: String,
        val prefKey: String,
        val defaultEnabled: Boolean = false
    )

    private val staticCategories = listOf(
        Section(
            "أحدث الإضافات",
            "$apiV2/newlyVideosItems/level/0/offset/12/page/",
            "cine_newly_added",
            false
        ),
        Section(
            "أفلام - تاريخ الرفع - الأحدث",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=desc",
            "cine_mov_upload_desc", false
        ),
        Section(
            "أفلام - تاريخ الرفع - الأقدم",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=asc",
            "cine_mov_upload_asc", false
        ),
        Section(
            "أفلام - تاريخ الإصدار - الأحدث",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_desc",
            "cine_mov_release_desc", false
        ),
        Section(
            "أفلام - تاريخ الإصدار - الأقدم",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_asc",
            "cine_mov_release_asc", false
        ),
        Section(
            "أفلام - الأكثر مشاهدة",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_desc",
            "cine_mov_views_desc",
            false
        ),
        Section(
            "أفلام - الأقل مشاهدة",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_asc",
            "cine_mov_views_asc",
            false
        ),
        Section(
            "أفلام - أعلى تقييم IMDb",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_desc",
            "cine_mov_stars_desc",
            false
        ),
        Section(
            "أفلام - أقل تقييم IMDb",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_asc",
            "cine_mov_stars_asc",
            false
        ),
        Section(
            "أفلام - أبجديًا (أ-ي)",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=ar_title_asc",
            "cine_mov_ar_asc",
            false
        ),
        Section(
            "أفلام - أبجديًا (ب-أ)",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=ar_title_desc",
            "cine_mov_ar_desc",
            false
        ),
        Section(
            "أفلام - أبجديًا (A-Z)",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc",
            "cine_mov_en_asc",
            false
        ),
        Section(
            "أفلام - أبجديًا (Z-A)",
            "$mainUrl/api/android/video/V/2?videoKind=1&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc",
            "cine_mov_en_desc",
            false
        ),
        Section(
            "مسلسلات - تاريخ الرفع - الأحدث",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=desc",
            "cine_ser_upload_desc",
            false
        ),
        Section(
            "مسلسلات - تاريخ الرفع - الأقدم",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=asc",
            "cine_ser_upload_asc",
            false
        ),
        Section(
            "مسلسلات - تاريخ الإصدار - الأحدث",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_desc",
            "cine_ser_release_desc",
            false
        ),
        Section(
            "مسلسلات - تاريخ الإصدار - الأقدم",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=r_asc",
            "cine_ser_release_asc",
            false
        ),
        Section(
            "مسلسلات - الأكثر مشاهدة",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_desc",
            "cine_ser_views_desc",
            false
        ),
        Section(
            "مسلسلات - الأقل مشاهدة",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=views_asc",
            "cine_ser_views_asc",
            false
        ),
        Section(
            "مسلسلات - أعلى تقييم IMDb",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_desc",
            "cine_ser_stars_desc",
            false
        ),
        Section(
            "مسلسلات - أقل تقييم IMDb",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=stars_asc",
            "cine_ser_stars_asc",
            false
        ),
        Section(
            "مسلسلات - أبجديًا (أ-ي)",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc",
            "cine_ser_ar_asc",
            false
        ),
        Section(
            "مسلسلات - أبجديًا (ي-أ)",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc",
            "cine_ser_ar_desc",
            false
        ),
        Section(
            "مسلسلات - أبجديًا (A-Z)",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_asc",
            "cine_ser_en_asc",
            false
        ),
        Section(
            "مسلسلات - أبجديًا (Z-A)",
            "$mainUrl/api/android/video/V/2?videoKind=2&langNb=&itemsPerPage=30&pageNumber=&level=0&sortParam=en_title_desc",
            "cine_ser_en_desc",
            false
        )
    )

    private var cachedMainPage: List<MainPageData>? = null

    companion object {
        private val dynamicUrlCache = mutableMapOf<String, String>()
    }

    init {
        try {
            PreferenceManager.getDefaultSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener { _, _ ->
                    Log.d(
                        name,
                        "⚙️ Settings changed! Clearing mainPage cache to reflect updates instantly."
                    )
                    cachedMainPage = null
                }
        } catch (_: Exception) {
        }
    }

    private fun isCalledFromSearch(): Boolean {
        return Thread.currentThread().stackTrace.any {
            it.className.contains("SearchViewModel") ||
                    it.className.contains("SearchFragment") ||
                    it.className.contains("SearchHelper")
        }
    }

    private fun saveCachedGroups(groups: List<Pair<String, String>>) {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val serialized = groups.joinToString(",,,") { "${it.first}|||${it.second}" }
            prefs.edit().putString("cine_cached_groups_v2", serialized).apply()
        } catch (_: Exception) {
        }
    }

    private fun getSavedGroups(): List<Pair<String, String>> {
        try {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val raw = prefs.getString("cine_cached_groups_v2", "") ?: ""
            if (raw.isBlank()) return emptyList()
            return raw.split(",,,").mapNotNull {
                val parts = it.split("|||")
                if (parts.size == 2) parts[0] to parts[1] else null
            }
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override val mainPage: List<MainPageData>
        get() {
            cachedMainPage?.let { return it }

            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val generatedPages = mutableListOf<MainPageData>()
            if (prefs.getBoolean("cine_banner", true)) {
                generatedPages.add(mainPageOf("$apiV2/banner/level/0" to "المميز").first())
            }
            if (prefs.getBoolean("cine_dynamic_home", true)) {
                val savedGroups = getSavedGroups()
                if (savedGroups.isNotEmpty()) {
                    savedGroups.forEach { (title, url) ->
                        generatedPages.add(mainPageOf(url to title).first())
                        dynamicUrlCache[title] = url
                    }
                } else {
                    generatedPages.add(mainPageOf("CINE_DYNAMIC_HOME" to "الصفحة الرئيسية").first())
                }

                if (!isCalledFromSearch()) {
                    ioSafe {
                        try {
                            Log.d(name, "📡 [BACKGROUND] Checking for dynamic categories updates...")
                            val responseMap = app.get("$apiV2/videoGroups/lang/ar/level/0")
                                .parsedSafe<Map<String, Any>>()
                            val groupsArray =
                                responseMap?.get("groups") as? List<*> ?: emptyList<Any>()
                            val newGroupsList = mutableListOf<Pair<String, String>>()

                            for (groupRaw in groupsArray) {
                                val group = groupRaw as? Map<*, *> ?: continue
                                val title = group["title"] as? String ?: continue
                                val groupId = group["groupsID"]?.toString()
                                    ?: (group["analytics"] as? Map<*, *>)?.get("eventInt")
                                        ?.toString()
                                    ?: group["list_id"]?.toString() ?: continue

                                val paginationUrl =
                                    "$apiV2/videoListPagination/groupID/$groupId/level/0/itemsPerPage/12/page/"
                                newGroupsList.add(title to paginationUrl)
                            }

                            if (newGroupsList.isNotEmpty() && newGroupsList != savedGroups) {
                                Log.d(
                                    name,
                                    "🔄 [BACKGROUND] Categories updated! Refreshing persistent cache."
                                )
                                saveCachedGroups(newGroupsList)
                                cachedMainPage = null
                            }
                        } catch (e: Exception) {
                            Log.e(name, "Background fetch error: ${e.message}")
                        }
                    }
                }
            }
            staticCategories.forEach { section ->
                if (prefs.getBoolean(section.prefKey, section.defaultEnabled)) {
                    generatedPages.add(mainPageOf(section.url to section.title).first())
                }
            }
            if (!isCalledFromSearch()) {
                cachedMainPage = generatedPages
            }

            return generatedPages
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse =
        coroutineScope {
            val requestData = request.data ?: ""
            val requestName = request.name ?: "القسم"
            val items = mutableListOf<HomePageList>()

            if (requestData.isBlank() || requestData == "SEARCH_DUMMY") {
                return@coroutineScope newHomePageResponse(emptyList(), hasNext = false)
            }
            if (requestData == "$apiV2/banner/level/0") {
                if (page == 1) {
                    try {
                        val resp = app.get(requestData).parsedSafe<List<Map<String, Any>>>()
                        val parsedBanner =
                            resp?.mapNotNull { it.toCinemanaItem().toSearchResponse() }
                                ?.distinctBy { it.url } ?: emptyList()

                        if (parsedBanner.isNotEmpty()) {
                            items.add(
                                HomePageList(
                                    requestName,
                                    parsedBanner,
                                    isHorizontalImages = true
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(name, "Banner Error: ${e.message}")
                    }
                    return@coroutineScope newHomePageResponse(items, hasNext = false)
                } else {
                    return@coroutineScope newHomePageResponse(emptyList(), hasNext = false)
                }
            }
            if (requestData == "CINE_DYNAMIC_HOME" && page == 1) {
                try {
                    val responseMap =
                        app.get("$apiV2/videoGroups/lang/ar/level/0").parsedSafe<Map<String, Any>>()
                    val groupsArray = responseMap?.get("groups") as? List<*> ?: emptyList<Any>()
                    val newGroupsList = mutableListOf<Pair<String, String>>()

                    for (groupRaw in groupsArray) {
                        val group = groupRaw as? Map<*, *> ?: continue
                        val title = group["title"] as? String ?: continue
                        val groupId = group["groupsID"]?.toString()
                            ?: (group["analytics"] as? Map<*, *>)?.get("eventInt")?.toString()
                            ?: group["list_id"]?.toString() ?: continue

                        val paginationUrl =
                            "$apiV2/videoListPagination/groupID/$groupId/level/0/itemsPerPage/12/page/"
                        dynamicUrlCache[title] = paginationUrl
                        newGroupsList.add(title to paginationUrl)

                        val contentArray = group["content"] as? List<*> ?: emptyList<Any>()
                        val parsedContent = contentArray.mapNotNull { itemRaw ->
                            (itemRaw as? Map<String, Any>)?.toCinemanaItem()?.toSearchResponse()
                        }?.distinctBy { it.url } ?: emptyList()

                        if (parsedContent.isNotEmpty()) {
                            val hpList = HomePageList(title, parsedContent)
                            injectUrlToHomePageList(hpList, paginationUrl, title)
                            items.add(hpList)
                        }
                    }
                    saveCachedGroups(newGroupsList)
                } catch (e: Exception) {
                    Log.e(name, "Dynamic Groups Error: ${e.message}")
                }

                return@coroutineScope newHomePageResponse(items, hasNext = false)
            }
            var actualRequestUrl = requestData

            if (requestData == "CINE_DYNAMIC_HOME" && page > 1) {
                val cachedUrl = dynamicUrlCache[requestName]
                if (cachedUrl != null) {
                    actualRequestUrl = cachedUrl
                } else {
                    return@coroutineScope newHomePageResponse(emptyList(), hasNext = false)
                }
            }

            val apiPage = (page - 1).coerceAtLeast(0)

            val fetchUrl = when {
                actualRequestUrl.contains("/page/") -> {
                    if (actualRequestUrl.endsWith("/page/")) "$actualRequestUrl$apiPage/"
                    else actualRequestUrl.replace(Regex("/page/\\d+/?$"), "/page/$apiPage/")
                }

                actualRequestUrl.contains("pageNumber=") -> {
                    val replaced =
                        actualRequestUrl.replace(Regex("pageNumber=\\d*"), "pageNumber=$apiPage")
                    if (replaced == actualRequestUrl) {
                        if (actualRequestUrl.contains("?")) "$actualRequestUrl&pageNumber=$apiPage"
                        else "$actualRequestUrl?pageNumber=$apiPage"
                    } else replaced
                }

                else -> {
                    if (actualRequestUrl.endsWith("/")) "$actualRequestUrl$apiPage/"
                    else "$actualRequestUrl/$apiPage/"
                }
            }

            Log.d(name, "🚀 [NETWORK] Fetching Pagination Page $page -> URL: $fetchUrl")

            try {
                val resp = app.get(fetchUrl).parsedSafe<List<Map<String, Any>>>()
                val parsed = resp?.mapNotNull { it.toCinemanaItem().toSearchResponse() }
                    ?.distinctBy { it.url } ?: emptyList()

                if (parsed.isNotEmpty()) {
                    val hpList = HomePageList(requestName, parsed, isHorizontalImages = false)
                    injectUrlToHomePageList(hpList, actualRequestUrl, requestName)
                    items.add(hpList)
                }

                val hasMore = parsed.size >= 12
                return@coroutineScope newHomePageResponse(items, hasNext = hasMore)
            } catch (e: Exception) {
                Log.e(name, "Error fetching pagination: ${e.message}")
                return@coroutineScope newHomePageResponse(emptyList(), hasNext = false)
            }
        }

    private fun injectUrlToHomePageList(hp: HomePageList, url: String, title: String) {
        val candidateFieldNames = listOf(
            "data",
            "requestData",
            "request",
            "pageUrl",
            "url",
            "extra",
            "nextPage",
            "params",
            "metadata"
        )
        for (fName in candidateFieldNames) {
            try {
                val f = hp.javaClass.getDeclaredField(fName)
                f.isAccessible = true
                f.set(hp, url)
                return
            } catch (_: Exception) {
            }
        }
    }



    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {
        val encoded = URLEncoder.encode(query, "utf-8")
        val itemsPerPageSearch = 12
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val yearRange = "1900,$currentYear"

        val pageParam_0_indexed = (page - 1).coerceAtLeast(0)

        val moviesUrl =
            "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$yearRange&page=$pageParam_0_indexed&type=movies&itemsPerPage=$itemsPerPageSearch"
        val seriesUrl =
            "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$yearRange&page=$pageParam_0_indexed&type=series&itemsPerPage=$itemsPerPageSearch"

        val (moviesRawAndParsed, seriesRawAndParsed) = listOf(moviesUrl, seriesUrl).map { url ->
            async(Dispatchers.IO) {
                runCatching {
                    val rawResp = app.get(url).parsedSafe<List<Map<String, Any>>>()
                    val rawSize = rawResp?.size ?: 0
                    val parsedItems = rawResp?.mapNotNull { itemMap ->
                        val cinemanaItem = itemMap.toCinemanaItem()
                        val searchResponse = cinemanaItem.toSearchResponse()
                        if (searchResponse != null) {
                            Pair(searchResponse, cinemanaItem)
                        } else null
                    } ?: emptyList()

                    Pair(rawSize, parsedItems)
                }.getOrDefault(Pair(0, emptyList()))
            }
        }.awaitAll()

        val movies = moviesRawAndParsed.second
        val series = seriesRawAndParsed.second

        val maxSize = maxOf(movies.size, series.size)
        val interleaved = ArrayList<Pair<SearchResponse, CinemanaItem>>(movies.size + series.size)
        for (i in 0 until maxSize) {
            if (i < movies.size) interleaved.add(movies[i])
            if (i < series.size) interleaved.add(series[i])
        }
        fun getScore(t: String?, ql: String): Int {
            if (t.isNullOrBlank()) return 0
            val tl = t.lowercase().trim()
            if (tl == ql) return 100
            if (tl.startsWith(ql)) return 80
            if (tl.contains(ql)) return 60
            val tokens = ql.split(Regex("\\s+")).filter { it.isNotBlank() }
            val tokenMatches = tokens.count { tl.contains(it) }
            return 40 + tokenMatches
        }

        val ql = query.lowercase().trim()

        val sorted = interleaved
            .mapIndexed { idx, pair ->
                val (response, cinemanaItem) = pair
                val arScore = getScore(cinemanaItem.arTitle, ql)
                val enScore = getScore(cinemanaItem.enTitle, ql)
                val maxScore = maxOf(arScore, enScore)

                Triple(response, maxScore, idx)
            }
            .sortedWith(
                compareByDescending<Triple<SearchResponse, Int, Int>> { it.second } // الترتيب حسب أعلى تقييم
                    .thenBy { it.third } // الحفاظ على الترتيب الأصلي للنتائج المتشابهة
            )
            .map { it.first }

        val finalResults = sorted.distinctBy { "${it.url ?: ""}-${it.name ?: ""}" }

        var hasMore = interleaved.isNotEmpty()
        if (finalResults.isEmpty() && page == 1) {
            val fallbackYearRange = "1900,2024"

            val moviesUrlFb =
                "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$fallbackYearRange&page=$pageParam_0_indexed&type=movies&itemsPerPage=$itemsPerPageSearch"
            val seriesUrlFb =
                "$apiV2/AdvancedSearch?level=0&videoTitle=$encoded&staffTitle=$encoded&year=$fallbackYearRange&page=$pageParam_0_indexed&type=series&itemsPerPage=$itemsPerPageSearch"

            val (moviesFbRawAndParsed, seriesFbRawAndParsed) = listOf(
                moviesUrlFb,
                seriesUrlFb
            ).map { url ->
                async(Dispatchers.IO) {
                    runCatching {
                        val rawResp = app.get(url).parsedSafe<List<Map<String, Any>>>()
                        val rawSize = rawResp?.size ?: 0

                        val parsedItems = rawResp?.mapNotNull { itemMap ->
                            val cinemanaItem = itemMap.toCinemanaItem()
                            val searchResponse = cinemanaItem.toSearchResponse()
                            if (searchResponse != null) {
                                Pair(searchResponse, cinemanaItem)
                            } else null
                        } ?: emptyList()

                        Pair(rawSize, parsedItems)
                    }.getOrDefault(Pair(0, emptyList()))
                }
            }.awaitAll()

            val moviesFb = moviesFbRawAndParsed.second
            val seriesFb = seriesFbRawAndParsed.second

            val maxFb = maxOf(moviesFb.size, seriesFb.size)
            val interleavedFb = ArrayList<Pair<SearchResponse, CinemanaItem>>(moviesFb.size + seriesFb.size)
            for (i in 0 until maxFb) {
                if (i < moviesFb.size) interleavedFb.add(moviesFb[i])
                if (i < seriesFb.size) interleavedFb.add(seriesFb[i])
            }

            val sortedFb = interleavedFb
                .mapIndexed { idx, pair ->
                    val (response, cinemanaItem) = pair
                    val arScore = getScore(cinemanaItem.arTitle, ql)
                    val enScore = getScore(cinemanaItem.enTitle, ql)
                    val maxScore = maxOf(arScore, enScore)
                    Triple(response, maxScore, idx)
                }
                .sortedWith(
                    compareByDescending<Triple<SearchResponse, Int, Int>> { it.second }
                        .thenBy { it.third }
                )
                .map { it.first }
                .distinctBy { "${it.url ?: ""}-${it.name ?: ""}" }

            if (sortedFb.isNotEmpty()) {
                hasMore = interleavedFb.isNotEmpty()
                return@coroutineScope newSearchResponseList(sortedFb, hasMore)
            }
        }

        newSearchResponseList(finalResults, hasMore)
    }

    override suspend fun load(url: String): LoadResponse? {
        val extractedId = url.substringAfterLast("/")
        val detailsUrl = "$mainUrl/api/android/allVideoInfo/id/$extractedId"

        val detailsMap = try {
            app.get(detailsUrl).parsedSafe<Map<String, Any>>()
        } catch (e: Exception) {
            null
        } ?: return null

        val details = detailsMap.toCinemanaItem()

        val title = details.arTitle?.takeIf { it.isNotBlank() } ?: details.enTitle ?: return null
        val posterUrl = details.imgObjUrl
        val plot = details.arContent?.takeIf { it.isNotBlank() } ?: details.enContent
        val year = details.year?.toIntOrNull()

        val ratingFloatPrimary = details.stars?.toFloatOrNull()
        val finalRatingScore: Score? = ratingFloatPrimary?.let { Score.from10(it) } ?: run {
            listOf("rate", "filmRating", "seriesRating").mapNotNull { k ->
                val raw = detailsMap[k]
                when (raw) {
                    is Number -> raw.toDouble().toFloat()
                    is String -> raw.toFloatOrNull()
                    else -> null
                }?.let { Score.from10(it) }
            }.firstOrNull()
        }

        val genresList = details.categories?.mapNotNull { cat ->
            cat.ar_title?.takeIf { it.isNotBlank() } ?: cat.en_title?.takeIf { it.isNotBlank() }
        }?.distinct() ?: emptyList()

        val actorsList: List<ActorData> = details.actorsInfo?.mapNotNull {
            val name = it.name?.trim()?.takeIf { n -> n.isNotEmpty() } ?: return@mapNotNull null
            ActorData(
                Actor(
                    name = name,
                    image = it.staff_img_thumb ?: it.staff_img ?: "defaultImages/not_available.jpg"
                ), roleString = null
            )
        } ?: emptyList()
        val writersList = detailsMap["writersInfo"] as? List<*>
        val writersMapped = writersList?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString()?.trim() ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val image = map["staff_img_thumb"]?.toString()
                ?: map["staff_img"]?.toString()
                ?: "defaultImages/not_available.jpg"
            ActorData(Actor(name = name, image = image), roleString = "الكاتب")
        } ?: emptyList()
        val directorsList = detailsMap["directorsInfo"] as? List<*>
        val directorsMapped = directorsList?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString()?.trim() ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            val image = map["staff_img_thumb"]?.toString()
                ?: map["staff_img"]?.toString()
                ?: "defaultImages/not_available.jpg"
            ActorData(Actor(name = name, image = image), roleString = "المخرج")
        } ?: emptyList()
        val combinedActors = actorsList + writersMapped + directorsMapped
        val likes = detailsMap["videoLikesNumber"]?.toString()
            ?: detailsMap["Likes"]?.toString()
            ?: "0"
        val dislikes = detailsMap["videoDisLikesNumber"]?.toString()
            ?: detailsMap["DisLikes"]?.toString()
            ?: "0"
        val combinedTags = listOf("👍 $likes", "👎 $dislikes") + genresList
        val durationSec = detailsMap["duration"]?.toString()?.toDoubleOrNull()?.toInt()
        val durationInMinutes = durationSec?.let { it / 60 }
        val recsUrl = "https://recommend.shabakaty.com/api/recommendation/recommend/"
        val recsList = try {
            val recsResponse = app.post(
                recsUrl,
                json = mapOf(
                    "MovieId" to extractedId,
                    "MovieName" to (details.enTitle ?: title),
                    "ReProcessIfExpired" to true
                )
            ).parsedSafe<Map<String, Any>>()

            (recsResponse?.get("recommendations") as? List<*>)?.mapNotNull {
                (it as? Map<String, Any>)?.toCinemanaItem()?.toSearchResponse()
            }?.distinctBy { it.url }
        } catch (e: Exception) {
            Log.e(name, "Recommendations Fetch Error: ${e.message}")
            null
        }

        return if (details.kind == 2) {
            val seasonsAndEpisodesUrl = "$mainUrl/api/android/videoSeason/id/$extractedId"

            val episodesResponse = try {
                app.get(seasonsAndEpisodesUrl).parsedSafe<List<Map<String, Any>>>()
            } catch (e: Exception) {
                null
            }

            val episodes = mutableListOf<Episode>()
            val seasonsMap = mutableMapOf<Int, MutableList<Episode>>()

            episodesResponse?.forEach { episodeMap ->
                val epDetails = episodeMap.toCinemanaItem()
                if (epDetails.nb != null && (epDetails.enTitle != null || epDetails.arTitle != null)) {
                    val epNum = (epDetails.episodeNummer as? String)?.toIntOrNull() ?: 1
                    val sNum = (epDetails.season as? String)?.toIntOrNull() ?: 1
                    val epDurationSec =
                        episodeMap["duration"]?.toString()?.toDoubleOrNull()?.toInt()
                    val epDurationInMinutes = epDurationSec?.let { it / 60 }

                    seasonsMap.getOrPut(sNum) { mutableListOf() }.add(
                        newEpisode(epDetails.nb) {
                            this.name = "الموسم $sNum - الحلقة $epNum"
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = epDetails.imgObjUrl ?: posterUrl
                            this.description = epDetails.arContent?.takeIf { it.isNotBlank() }
                                ?: epDetails.enContent
                            this.runTime = epDurationInMinutes
                        }
                    )
                }
            }

            seasonsMap.keys.sorted().forEach { sNum ->
                seasonsMap[sNum]?.sortBy { it.episode }
                seasonsMap[sNum]?.let { episodes.addAll(it) }
            }

            newTvSeriesLoadResponse(title, extractedId, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.score =
                finalRatingScore
                this.tags = combinedTags
                this.actors = combinedActors
                this.recommendations = recsList
            }
        } else {
            newMovieLoadResponse(title, extractedId, TvType.Movie, extractedId) {
                this.posterUrl = posterUrl; this.plot = plot; this.year = year; this.score =
                finalRatingScore
                this.tags = combinedTags
                this.actors = combinedActors
                this.recommendations = recsList
                this.duration = durationInMinutes
            }
        }
    }

    private fun extractQuality(resolution: String?): Int {
        if (resolution == null) return Qualities.Unknown.value
        val cleanRes = resolution.lowercase().trim()
        return when {
            cleanRes.contains("2160") || cleanRes.contains("4k") -> Qualities.P2160.value
            cleanRes.contains("1440") -> Qualities.P1440.value
            cleanRes.contains("1080") -> Qualities.P1080.value
            cleanRes.contains("720") -> Qualities.P720.value
            cleanRes.contains("480") -> Qualities.P480.value
            cleanRes.contains("360") -> Qualities.P360.value
            cleanRes.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
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
                callback(
                    newExtractorLink(
                        source = name,
                        name = "",
                        url = videoUrl
                    ) {
                        this.quality = extractQuality(resolution)
                    }
                )
            } else {

            }
        }

        val detailsUrl = "$apiV2/allVideoInfo/id/$extractedId"

        app.get(detailsUrl).parsedSafe<Map<String, Any>>()?.let { detailsMap ->

            val subs = (detailsMap["translations"] as? List<Map<String, Any>>)
                ?.sortedBy { sub ->
                    when ((sub["extention"] as? String)?.lowercase()) {
                        "ass" -> 0
                        "vtt" -> 1
                        "srt" -> 2
                        else -> 3
                    }
                }

            subs?.forEach { sub ->
                val file = sub["file"] as? String
                val ext = sub["extention"] as? String ?: ""
                val originalLang = sub["name"] as? String

                val lang = when (ext.lowercase()) {
                    "ass" -> "arabic"
                    else -> originalLang
                }

                if (file != null && lang != null) {

                    subtitleCallback(
                        SubtitleFile(lang ?: "arabic", file)
                    )
                }
            }
        }

        return true
    }

    @Serializable
    data class Category(val en_title: String? = null, val ar_title: String? = null)
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
    data class WriterInfo(
        val nb: String? = null,
        val name: String? = null,
        val role: String? = null
    ) // 🟢 كلاس الكاتب

    @Serializable
    data class CinemanaItem(
        val nb: String? = null,
        @SerialName("en_title") val enTitle: String? = null,
        @SerialName("ar_title") val arTitle: String? = null,
        val imgObjUrl: String? = null,
        val year: String? = null,
        @SerialName("en_content") val enContent: String? = null,
        @SerialName("ar_content") val arContent: String? = null,
        val stars: String? = null,
        val kind: Int? = null,
        val fileFile: String? = null,
        @SerialName("episodeNummer") val episodeNummer: String? = null,
        val season: String? = null,
        val categories: List<Category>? = null,
        @SerialName("actorsInfo") val actorsInfo: List<ActorInfo>? = null,
        @SerialName("writersInfo") val writersInfo: List<WriterInfo>? = null // 🟢 مصفوفة الكاتب
    )

    private fun Map<String, Any>.toCinemanaItem(): CinemanaItem {
        val parsedNb = when (val nbValue = this["nb"]) {
            is String -> nbValue; is Int -> nbValue.toString(); is Double -> nbValue.toLong()
                .toString(); else -> null
        }
        val parsedKind = when (val k = this["kind"]) {
            is Int -> k; is String -> k.toIntOrNull(); else -> null
        }
        val cats = (this["categories"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.let { m ->
                Category(
                    en_title = m["en_title"] as? String,
                    ar_title = m["ar_title"] as? String
                )
            }
        }
        val actors = (this["actorsInfo"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.let { m ->
                ActorInfo(
                    nb = (m["nb"] as? String) ?: (m["nb"] as? Int)?.toString(),
                    name = m["name"] as? String,
                    role = m["role"] as? String,
                    staff_img = m["staff_img"] as? String,
                    staff_img_thumb = m["staff_img_thumb"] as? String
                )
            }
        }
        val writers = (this["writersInfo"] as? List<*>)?.mapNotNull {
            (it as? Map<*, *>)?.let { m ->
                WriterInfo(
                    nb = (m["nb"] as? String) ?: (m["nb"] as? Int)?.toString(),
                    name = m["name"] as? String,
                    role = m["role"] as? String
                )
            }
        } // 🟢 معالجة بيانات الكاتب

        return CinemanaItem(
            nb = parsedNb,
            enTitle = this["en_title"] as? String,
            arTitle = this["ar_title"] as? String,
            imgObjUrl = this["imgObjUrl"] as? String ?: this["img"] as? String,
            year = this["year"] as? String,
            enContent = this["en_content"] as? String,
            arContent = this["ar_content"] as? String,
            stars = this["stars"] as? String,
            kind = parsedKind,
            fileFile = this["fileFile"] as? String,
            episodeNummer = this["episodeNummer"] as? String,
            season = this["season"] as? String,
            categories = cats,
            actorsInfo = actors,
            writersInfo = writers // 🟢 إسناد الكاتب
        )
    }

    private fun CinemanaItem.toSearchResponse(): SearchResponse? {
        val validNb = nb ?: return null
        val scoreObject = this.stars?.toFloatOrNull()?.let { Score.from10(it) }
        val finalTitle = arTitle?.takeIf { it.isNotBlank() } ?: enTitle ?: "بدون عنوان"
        return if (kind == 2) newTvSeriesSearchResponse(
            name = finalTitle,
            url = validNb,
            type = TvType.TvSeries
        ) {
            this.posterUrl = imgObjUrl
            this.score = scoreObject
        }
        else newMovieSearchResponse(name = finalTitle, url = validNb, type = TvType.Movie) {
            this.posterUrl = imgObjUrl
            this.score = scoreObject
        }
    }
}