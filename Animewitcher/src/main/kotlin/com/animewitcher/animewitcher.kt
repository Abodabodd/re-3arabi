package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*
import kotlin.collections.HashMap

class AnimeWitcherProvider : MainAPI() {
    override var mainUrl = "https://animewitcher.com"
    override var name = "AnimeWitcher"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true
    @Volatile
    private var algoliaAppId = "D8LH9I7ZL7"
    @Volatile
    private var algoliaApiKey = "b56c01ef52540ef334bcdbaa00ded9e4"
    @Volatile
    private var buAuthKey = "6f1ec205-bbe8-45ce-97eb-1bc25a94bedf"
    private val firebaseApiKey = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"

    private fun getAlgoliaHeaders(): Map<String, String> {
        return mapOf(
            "X-Algolia-Application-Id" to algoliaAppId,
            "X-Algolia-API-Key" to algoliaApiKey,
            "User-Agent" to "Algolia for Android (3.27.0); Android (16)",
            "Content-Type" to "application/json; charset=UTF-8",
        )
    }

    private val FIREBASE_PROJECT_ID = "animewitcher-1c66d"
    private val serverWordsCache = HashMap<String, ServerWords>()

    data class EpisodeInfo(
        val id: String,
        val name: String?,
        val number: Int,
        val imageUrl: String? = null
    )
    data class ServerModel(val name: String?, val link: String?, val quality: String?, val originalLink: String?, val openBrowser: Boolean)
    data class ServerWords(val name: String, val word1: String?, val word2: String?, val word3: String?, val word4: String?)

    private fun logd(msg: String) {
        println("[AnimeWitcherLog] $msg")
    }

    private fun algoliaUrl(index: String) = "https://${algoliaAppId}-dsn.algolia.net/1/indexes/$index/query"

    private fun getQualityAsInt(quality: String?): Int {
        return quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    }
    private suspend fun postAlgoliaQuery(index: String, body: okhttp3.RequestBody): String {
        val startTime = System.currentTimeMillis()
        return try {
            val response = app.post(algoliaUrl(index), requestBody = body, headers = getAlgoliaHeaders())
            if (response.code != 200) throw Exception("رمز الاستجابة ${response.code}")
            val duration = System.currentTimeMillis() - startTime
            logd("⚡ [Algolia POST Network] Succeeded in $duration ms.")
            response.text
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("⚠️ [Algolia POST Network] Failed after $duration ms: ${e.message}. Attempting keys refresh...")
            refreshAlgoliaKeys()

            val retryStartTime = System.currentTimeMillis()
            val response = app.post(algoliaUrl(index), requestBody = body, headers = getAlgoliaHeaders())
            if (response.code != 200) throw Exception("رمز الاستجابة بعد التجديد ${response.code}")
            logd("⚡ [Algolia POST Network] Retry Succeeded in ${System.currentTimeMillis() - retryStartTime} ms.")
            response.text
        }
    }
    private suspend fun getAlgoliaObjectByPost(index: String, objectId: String): JSONObject? {
        val startTime = System.currentTimeMillis()
        logd("⏱️ [Algolia Object] Starting POST query for ObjectID: '$objectId'")

        val escapedId = objectId.replace("\"", "\\\"")
        val payload = JSONObject().put("params", "filters=objectID:\"$escapedId\"")
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        return try {
            val res = postAlgoliaQuery(index, body)
            val hits = JSONObject(res).optJSONArray("hits")
            val duration = System.currentTimeMillis() - startTime
            if (hits != null && hits.length() > 0) {
                logd("✅ [Algolia Object] Found matching object. Time: $duration ms.")
                hits.getJSONObject(0)
            } else {
                logd("⚠️ [Algolia Object] Query returned empty hits. Time: $duration ms.")
                null
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("❌ [Algolia Object] Query failed after $duration ms. Error: ${e.message}")
            null
        }
    }
    private suspend fun refreshAlgoliaKeys() {
        try {
            val url = firestoreDocUrl("Settings/constants")
            val res = app.get(url).text
            val json = JSONObject(res)
            val fields = json.optJSONObject("fields") ?: return

            if (fields.has("search_settings")) {
                val searchSettings = fields.getJSONObject("search_settings")
                    .optJSONObject("mapValue")
                    ?.optJSONObject("fields")

                if (searchSettings != null) {
                    val newAppId = searchSettings.optJSONObject("app_id_v3")?.optString("stringValue")
                        ?: searchSettings.optJSONObject("app_id")?.optString("stringValue")
                        ?: searchSettings.optJSONObject("app_id_v2")?.optString("stringValue")
                    val newApiKey = searchSettings.optJSONObject("api_key")?.optString("stringValue")

                    if (!newAppId.isNullOrEmpty() && !newApiKey.isNullOrEmpty()) {
                        algoliaAppId = newAppId
                        algoliaApiKey = newApiKey
                        logd("✅ [Firestore Keys] Algolia AppId Updated: $algoliaAppId")
                    }
                }
            }

            if (fields.has("BU")) {
                val buSettings = fields.getJSONObject("BU")
                    .optJSONObject("mapValue")
                    ?.optJSONObject("fields")
                if (buSettings != null) {
                    val newAuthKey = buSettings.optJSONObject("auth_key")?.optString("stringValue")
                    if (!newAuthKey.isNullOrEmpty()) {
                        buAuthKey = newAuthKey
                        logd("✅ [Firestore Keys] BU Auth Key Updated: $buAuthKey")
                    }
                }
            }
        } catch (e: Exception) {
            logd("❌ [Firestore Keys] Failed to refresh keys. Error: ${e.message}")
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val indexName = "recent"
        val pageParam = (page - 1).coerceAtLeast(0)

        val attributes = URLEncoder.encode(
            "[\"name\",\"date\",\"doc_ref\",\"episode_id\",\"anime_id\",\"episode_name\",\"title\",\"poster_uri\",\"poster_url_aniList\",\"type\",\"tags\",\"thumb_uri\",\"comments_closed\",\"filler\",\"note\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=30&page=$pageParam&query="

        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = postAlgoliaQuery(indexName, body)

        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("anime_id", obj.optString("objectID"))
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            list.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext newHomePageResponse("أحدث الحلقات", list)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val indexName = "series"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val attributes = URLEncoder.encode(
            "[\"objectID\",\"name\",\"poster_uri\",\"type\",\"details\",\"tags\",\"story\",\"english_title\",\"poster\",\"rating\",\"dubbed\",\"path\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=50&page=0&query=$encodedQuery"
        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = postAlgoliaQuery(indexName, body)

        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val results = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("objectID")
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            results.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext results
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val totalStartTime = System.currentTimeMillis()
        logd("====== [LOAD] PROCESS STARTED ======")
        val animeId = URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "utf-8")
        logd("[LOAD] Extracted animeId: '$animeId'")
        val animeJson = getAlgoliaObjectByPost("series", animeId) ?: try {
            logd("⚠️ Algolia query failed or returned empty. Falling back to URL data parameter...")
            val encodedData = url.substringAfter("?data=", "")
            if (encodedData.isNotEmpty()) {
                val decodeStartTime = System.currentTimeMillis()
                val parsed = JSONObject(URLDecoder.decode(encodedData, "utf-8"))
                logd("✅ [LOAD] Decoded local fallback data in ${System.currentTimeMillis() - decodeStartTime} ms.")
                parsed
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            logd("❌ [LOAD] Failed to parse fallback URL data: ${e.message}")
            JSONObject()
        }

        val docRef = animeJson.optString("doc_ref", animeJson.optString("path", "anime_list/$animeId"))
        val cleanPath = docRef.substringAfter("anime_list/").removePrefix("/")
        val episodes = fetchEpisodes(cleanPath)

        val poster = animeJson.optString("poster_uri", animeJson.optJSONObject("poster")?.optString("medium"))

        val epList = episodes.map { info ->
            val dataStr = "$cleanPath|${info.id}"
            newEpisode(data = dataStr) {
                name = info.name ?: "الحلقة ${info.number}"
                episode = info.number
                posterUrl = info.imageUrl ?: poster
            }
        }

        val title = animeJson.optString("name", animeId)
        val details = animeJson.optJSONObject("details") ?: JSONObject()
        val plot = animeJson.optString("story").ifEmpty {
            animeJson.optJSONObject("_highlightResult")?.optJSONObject("story")?.optString("value")?.replace(Regex("</?em>"), "")
        } ?: ""

        val year = details.optString("year").toIntOrNull()
        val status = if (details.optString("state") == "مكتمل") ShowStatus.Completed else ShowStatus.Ongoing
        val tagsArray = animeJson.optJSONArray("tags")
        val tags = if (tagsArray != null) (0 until tagsArray.length()).map { tagsArray.getString(it) } else emptyList()

        logd("====== [LOAD] PROCESS ENDED - Total Duration: ${System.currentTimeMillis() - totalStartTime} ms ======")

        return@withContext newTvSeriesLoadResponse(title, url, TvType.Anime, epList) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags
        }
    }
    private suspend fun fetchEpisodes(animeId: String): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8").replace("+", "%20")
        val docUrl = firestoreDocUrl("anime_list/$encodedAnimeId/episodes_summery/summery")
        val list = ArrayList<EpisodeInfo>()

        logd("⏱ [Firestore Episodes] Fetching starting for URL: $docUrl")

        try {
            val res = app.get(docUrl).text
            val json = JSONObject(res)
            val fields = json.optJSONObject("fields") ?: return@withContext emptyList()
            val episodesObj = fields.optJSONObject("episodes") ?: return@withContext emptyList()
            val arrayValue = episodesObj.optJSONObject("arrayValue") ?: return@withContext emptyList()
            val values = arrayValue.optJSONArray("values") ?: JSONArray()

            for (i in 0 until values.length()) {
                val valueItem = values.getJSONObject(i)
                val mapValue = valueItem.optJSONObject("mapValue") ?: continue
                val epFields = mapValue.optJSONObject("fields") ?: continue

                val docId = epFields.optJSONObject("doc_id")?.optString("stringValue") ?: continue
                val titleTranslated = epFields.optJSONObject("title_translated")
                    ?.optJSONObject("mapValue")
                    ?.optJSONObject("fields")

                val arName = titleTranslated?.optJSONObject("ar")?.optString("stringValue")
                val epName = arName ?: epFields.optJSONObject("name")?.optString("stringValue") ?: "الحلقة $docId"

                val numberValObj = epFields.optJSONObject("number")
                val numberStr = numberValObj?.optString("integerValue") ?: numberValObj?.optString("stringValue")
                val number = numberStr?.toIntOrNull() ?: (i + 1)

                val image = epFields.optJSONObject("thumb_uri")?.optString("stringValue")

                list.add(EpisodeInfo(docId, epName, number, image))
            }

            list.sortBy { it.number }
            val duration = System.currentTimeMillis() - startTime
            logd("✅ [Firestore Episodes] Succeeded in $duration ms. Found ${list.size} episodes.")
            return@withContext list
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("❌ [Firestore Episodes] Failed after $duration ms. Error: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun firestoreDocUrl(path: String): String =
        "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$path?key=$firebaseApiKey"
    private suspend fun fetchServersForEpisode(animeId: String, episodeId: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8").replace("+", "%20")
        val encodedEpisodeId = URLEncoder.encode(episodeId, "utf-8").replace("+", "%20")

        try {
            val docPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers2/all_servers"
            val res = app.get(firestoreDocUrl(docPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val fields = json.optJSONObject("fields")
            if (fields != null && fields.has("servers")) {
                val arr = fields.getJSONObject("servers").optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
                val list = ArrayList<ServerModel>()
                for (i in 0 until arr.length()) {
                    val map = arr.getJSONObject(i).getJSONObject("mapValue").getJSONObject("fields")
                    val name = map.optJSONObject("name")?.optString("stringValue")
                    val quality = map.optJSONObject("quality")?.optString("stringValue")
                    val link = map.optJSONObject("link")?.optString("stringValue")
                    val orig = map.optJSONObject("original_link")?.optString("stringValue")
                    val openBrowser = map.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                    if (!name.isNullOrEmpty() && !link.isNullOrEmpty()) {
                        list.add(ServerModel(name, link, quality, orig, openBrowser))
                    }
                }
                if (list.isNotEmpty()) {
                    list.sortByDescending { getQualityAsInt(it.quality) }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            logd("fetchServersForEpisode: servers2 doc failed: ${e.message}")
        }

        try {
            val collPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers"
            val res = app.get(firestoreDocUrl(collPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val docs = json.optJSONArray("documents") ?: JSONArray()
            val list = ArrayList<ServerModel>()
            for (i in 0 until docs.length()) {
                val fields = docs.getJSONObject(i).optJSONObject("fields") ?: JSONObject()
                val name = fields.optJSONObject("name")?.optString("stringValue")
                val quality = fields.optJSONObject("quality")?.optString("stringValue")
                val link = fields.optJSONObject("link")?.optString("stringValue")
                val orig = fields.optJSONObject("original_link")?.optString("stringValue")
                val openBrowser = fields.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                val visible = fields.optJSONObject("visible")?.optBoolean("booleanValue") ?: true
                if (!name.isNullOrEmpty() && !link.isNullOrEmpty() && visible) {
                    list.add(ServerModel(name, link, quality, orig, openBrowser))
                }
            }
            list.sortByDescending { getQualityAsInt(it.quality) }
            return@withContext list
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private suspend fun getServerWords(serverName: String): ServerWords? = withContext(Dispatchers.IO) {
        if (serverWordsCache.containsKey(serverName)) return@withContext serverWordsCache[serverName]
        try {
            val path = "Settings/servers/servers/${URLEncoder.encode(serverName, "utf-8").replace("+", "%20")}"
            val res = app.get(firestoreDocUrl(path)).text
            val f = JSONObject(res).optJSONObject("fields") ?: JSONObject()
            val sw = ServerWords(
                name = serverName,
                word1 = f.optJSONObject("word1")?.optString("stringValue"),
                word2 = f.optJSONObject("word2")?.optString("stringValue"),
                word3 = f.optJSONObject("word3")?.optString("stringValue"),
                word4 = f.optJSONObject("word4")?.optString("stringValue")
            )
            serverWordsCache[serverName] = sw
            return@withContext sw
        } catch (e: Exception) {
            return@withContext null
        }
    }

    private suspend fun getFinalRedirectUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = true).url
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getFinalDownloadUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = false).headers["Location"]
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun resolveServerModel(server: ServerModel): String? = withContext(Dispatchers.IO) {
        val name = server.name ?: return@withContext null
        val link = server.link ?: return@withContext null
        val words = getServerWords(name)

        try {
            when (name.uppercase(Locale.getDefault())) {
                "MF", "ST", "MG" -> {
                    return@withContext link
                }
                "KF" -> {
                    if (words?.word1 == null || words.word2 == null) return@withContext null
                    val res = try { app.get(link).text } catch (e: Exception) { "" }
                    if (res.isBlank()) return@withContext null
                    return@withContext "https://${res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")}"
                }
                "PD" -> {
                    val html = try { app.get(link).text } catch (e: Exception) { "" }
                    val og = Regex("""<meta property="og:video" content="([^"]+)""").find(html)?.groupValues?.get(1)
                    if (!og.isNullOrEmpty()) return@withContext og.replace("&amp;", "&")
                    val m = Regex("""href="(/u/[A-Za-z0-9_-]+)"[^>]*>\s*(?:Download|تحميل|download)""", RegexOption.IGNORE_CASE).find(html)
                    if (m != null) {
                        val rel = m.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val m2 = Regex("(/u/[A-Za-z0-9_-]+)").find(html)
                    if (m2 != null) {
                        val rel = m2.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val abs = Regex("""https?://pixeldrain\.com/u/[A-Za-z0-9_-]+""").find(html)?.value
                    if (!abs.isNullOrEmpty()) return@withContext abs
                    return@withContext link
                }
                "VT" -> {
                    if (words?.word1 == null || words.word2 == null || words.word3 == null || words.word4 == null) return@withContext null
                    val res1 = try { app.get(link).text } catch (e: Exception) { "" }
                    val part1 = res1.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("\">", "").trim()
                    val newLink = "https://vidtube.one$part1"
                    val res2 = try { app.get(newLink).text } catch (e: Exception) { "" }
                    return@withContext "https://${res2.substringAfter(words.word3!!).substringBefore(words.word4!!)}"
                }
                "AR" -> {
                    return@withContext getFinalRedirectUrl(link)
                }
                "WC" -> {
                    return@withContext getFinalDownloadUrl(link)
                }
                "BU" -> {
                    return@withContext link
                }
                "QI" -> {
                    return@withContext server.originalLink ?: link
                }
                else -> {
                    if (words?.word1 == null || words.word2 == null) return@withContext null
                    val res = try { app.get(link).text } catch (e: Exception) { "" }
                    if (res.isBlank()) return@withContext null
                    return@withContext res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        logd("====== LOADLINKS START ======")
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            var rawAnimeId = parts[0].trim()
            val episodeId = parts[1].trim()

            if (rawAnimeId.startsWith("http", ignoreCase = true) || rawAnimeId.contains(mainUrl)) {
                try {
                    val pathPart = rawAnimeId.substringAfterLast('/').substringBefore('?')
                    rawAnimeId = URLDecoder.decode(pathPart, "utf-8").trim()
                } catch (e: Exception) {
                    logd("Failed to normalize animeId: ${e.message}")
                }
            }

            val animeId = rawAnimeId
            val servers = fetchServersForEpisode(animeId, episodeId)

            val sortedServers = servers.sortedWith(compareByDescending<ServerModel> { getQualityAsInt(it.quality) }
                .thenBy {
                    when(it.name?.uppercase(Locale.getDefault())) {
                        "PD" -> 0
                        "PD EU TEST" -> 1
                        else -> 2
                    }
                }
            )

            logd("Sorted ${sortedServers.size} servers for resolution.")

            val resolvedList = mutableListOf<Pair<ServerModel, String>>()
            for (server in sortedServers) {
                try {
                    val resolvedUrl = resolveServerModel(server)
                    if (!resolvedUrl.isNullOrBlank()) {
                        resolvedList.add(Pair(server, resolvedUrl))
                    }
                } catch (e: Exception) {
                    logd("Exception resolving ${server.name}: ${e.message}")
                }
            }

            for ((server, finalUrl) in resolvedList) {
                if (finalUrl.trim().startsWith("<")) continue

                val serverName = server.name ?: "Server"

                if (serverName.equals("PD", ignoreCase = true)) {

                    try {
                        loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logd("Error invoking loadExtractor for PD: ${e.message}")
                    }
                    continue
                }

                if (serverName.uppercase(Locale.getDefault()) == "KF") {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = serverName,
                            url = finalUrl,
                        ) {
                            referer = "https://krakenfiles.com/"
                            quality = getQualityFromName(server.quality)
                        }
                    )
                } else {
                    try {
                        loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                    } catch (e: Exception) {
                        logd("Error invoking loadExtractor for $serverName: ${e.message}")
                    }
                }
            }

            logd("====== LOADLINKS END ======")
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}