package com.animerift

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class AnimeRift : MainAPI() {

    override var mainUrl = "https://gateway.anime-rift.com"
    override var name = "AnimeRift"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    private val JWT_SECRET = "RIFT_JWT_SECRET_PLACEHOLDER"
    private val FIREBASE_API_KEY = "RIFT_FIREBASE_API_KEY_PLACEHOLDER"
    private val FIREBASE_APP_ID = "RIFT_FIREBASE_APP_ID_PLACEHOLDER"
    private val ANDROID_PACKAGE = "com.riftapps.animerift"
    private val ANDROID_CERT = "AF40CE82A52AA4107F311D8B9727D01C8D02250B"
    companion object {
        private var fid: String? = null
        private var firebaseToken: String? = null
        private var gatewayBaseUrl: String? = null
        private var sessionKey: String? = null
        private var deviceId: String? = null
        private var syntheticBuildId: String? = null
        private var syntheticDeviceInfoJson: String? = null
        private var vpnNoticeCount: Int = 0
        private fun getCacheFile(): File {
            val tmp = System.getProperty("java.io.tmpdir") ?: "."
            return File(tmp, "anime_rift_session_store.json")
        }

        private fun saveToDisk(data: Map<String, String>) {
            try {
                val file = getCacheFile()
                val jsonStr = ObjectMapper().writeValueAsString(data)
                file.writeText(jsonStr)
            } catch (_: Exception) {}
        }

        private fun loadFromDisk(): Map<String, String>? {
            return try {
                val file = getCacheFile()
                if (file.exists()) {
                    val text = file.readText()
                    if (text.isNotBlank()) {
                        val node = ObjectMapper().readTree(text)
                        mapOf(
                            "fid" to (node.get("fid")?.asText() ?: ""),
                            "firebaseToken" to (node.get("firebaseToken")?.asText() ?: ""),
                            "gatewayBaseUrl" to (node.get("gatewayBaseUrl")?.asText() ?: ""),
                            "deviceId" to (node.get("deviceId")?.asText() ?: ""),
                            "sessionKey" to (node.get("sessionKey")?.asText() ?: ""),
                            "syntheticBuildId" to (node.get("syntheticBuildId")?.asText() ?: ""),
                            "syntheticDeviceInfoJson" to (node.get("syntheticDeviceInfoJson")?.asText() ?: "")
                        )
                    } else null
                } else null
            } catch (_: Exception) {
                null
            }
        }
    }

    private val mapper = ObjectMapper()
    private fun generateSyntheticDevice(): Pair<String, String> {
        val brands = listOf(
            Triple("google", "Google", "Pixel 8 Pro"),
            Triple("google", "Google", "Pixel 7"),
            Triple("samsung", "samsung", "Galaxy S23"),
            Triple("xiaomi", "Xiaomi", "Xiaomi 13")
        )
        val selected = brands.random()
        val randomNum = "${Random().nextInt(899999) + 100000}.015"
        val buildId = "AP2A.240805.$randomNum"
        val uniqueTag = UUID.randomUUID().toString().substring(0, 8)

        val deviceInfo = mapOf(
            "model" to selected.third,
            "brand" to selected.first,
            "manufacturer" to selected.second,
            "device" to selected.first,
            "hardware" to "qcom",
            "product" to selected.first,
            "androidVersion" to "14",
            "sdkInt" to 34,
            "securityPatch" to "2025-01-01",
            "isPhysicalDevice" to true,
            "supportedAbis" to listOf("arm64-v8a"),
            "tags" to "release-keys",
            "type" to "user",
            "buildId" to buildId,
            "host" to "build-server-$uniqueTag",
            "fingerprint" to "${selected.first}/${selected.third}/${selected.first}:14/$buildId/V.1e9a_$uniqueTag:user/release-keys"
        )
        return Pair(buildId, mapper.writeValueAsString(deviceInfo))
    }

    private fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
    }

    private fun generateIntegrityToken(scope: String): String {
        val now = System.currentTimeMillis() / 1000
        val exp = now + 60
        val header = base64UrlEncode("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = base64UrlEncode("""{"scope":"$scope","exp":$exp,"iat":$now}""".toByteArray())
        val toSign = "$header.$payload"
        val secretKey = SecretKeySpec(JWT_SECRET.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKey)
        val signature = base64UrlEncode(mac.doFinal(toSign.toByteArray()))
        return "$toSign.$signature"
    }

    private fun generateFcmToken(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return "APA91b" + (1..80).map { chars.random() }.joinToString("")
    }

    private fun generateDeviceTimezone(): String {
        val now = Date()
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val nano = Random().nextInt(999999).toString().padStart(6, '0')
        return "${format.format(now)}.$nano"
    }

    private fun gzipCompress(data: String): ByteArray {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(data.toByteArray()) }
        return bos.toByteArray()
    }

    private suspend fun ensureInitialized(forceRefresh: Boolean = false) {
        if (!forceRefresh) {
            if (!fid.isNullOrEmpty() && !gatewayBaseUrl.isNullOrEmpty() && !firebaseToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
                return
            }

            val disk = loadFromDisk()
            if (disk != null) {
                fid = disk["fid"]
                firebaseToken = disk["firebaseToken"]
                gatewayBaseUrl = disk["gatewayBaseUrl"]
                deviceId = disk["deviceId"]
                sessionKey = disk["sessionKey"]
                syntheticBuildId = disk["syntheticBuildId"]
                syntheticDeviceInfoJson = disk["syntheticDeviceInfoJson"]

                if (!fid.isNullOrEmpty() && !gatewayBaseUrl.isNullOrEmpty() && !firebaseToken.isNullOrEmpty() && !deviceId.isNullOrEmpty()) {
                    return
                }
            }
        }

        withContext(Dispatchers.IO) {
            registerFirebaseInstallation()
            fetchRemoteConfig()
            registerDevice()

            val map = mapOf(
                "fid" to (fid ?: ""),
                "firebaseToken" to (firebaseToken ?: ""),
                "gatewayBaseUrl" to (gatewayBaseUrl ?: mainUrl),
                "deviceId" to (deviceId ?: ""),
                "sessionKey" to (sessionKey ?: ""),
                "syntheticBuildId" to (syntheticBuildId ?: "BP2A.250605.015"),
                "syntheticDeviceInfoJson" to (syntheticDeviceInfoJson ?: "{}")
            )
            saveToDisk(map)
        }
    }

    private fun registerFirebaseInstallation() {
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/anime-rift-4142e/installations"
        val payloadStr = """{"fid":"","appId":"$FIREBASE_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:19.1.0"}"""

        val request = okhttp3.Request.Builder()
            .url(url)
            .post(gzipCompress(payloadStr).toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("X-Android-Package", ANDROID_PACKAGE)
            .addHeader("X-Android-Cert", ANDROID_CERT)
            .addHeader("x-goog-api-key", FIREBASE_API_KEY)
            .addHeader("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
            .addHeader("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 14; Pixel 8 Pro Build/AP2A.240805.015)")
            .build()

        val response = app.baseClient.newCall(request).execute()
        val body = response.body?.string() ?: throw Exception("No response from Firebase")
        val json = mapper.readTree(body)
        fid = json.get("fid").asText()
        firebaseToken = json.get("authToken").get("token").asText()
    }

    private suspend fun fetchRemoteConfig() {
        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/536921039715/namespaces/firebase:fetch"
        val payload = mapOf(
            "appVersion" to "3.13.5",
            "appInstanceIdToken" to (firebaseToken ?: ""),
            "appBuild" to "68",
            "appInstanceId" to (fid ?: ""),
            "analyticsUserProperties" to emptyMap<String, String>(),
            "appId" to FIREBASE_APP_ID,
            "platformVersion" to "34",
            "sdkVersion" to "23.0.1",
            "packageName" to ANDROID_PACKAGE
        )

        val headers = mapOf(
            "X-Goog-Api-Key" to FIREBASE_API_KEY,
            "X-Android-Package" to ANDROID_PACKAGE,
            "X-Android-Cert" to ANDROID_CERT,
            "X-Goog-Firebase-Installations-Auth" to (firebaseToken ?: ""),
            "Content-Type" to "application/json"
        )

        val json = app.post(url, headers = headers, json = payload).parsed<JsonNode>()
        gatewayBaseUrl = json.get("entries")?.get("anime_rift_android_gateway_base_url_v4")?.asText()?.replace("\"", "") ?: mainUrl
    }

    private suspend fun registerDevice() {
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val url = "$baseUrl/auth/register/device"
        val fcmToken = generateFcmToken()
        deviceId = "${fid ?: ""}:$fcmToken"
        val (buildId, deviceInfoJson) = generateSyntheticDevice()
        syntheticBuildId = buildId
        syntheticDeviceInfoJson = deviceInfoJson

        val payload = mapOf(
            "deviceId" to deviceId,
            "current_app_version" to "3.13.5",
            "device_os" to "android",
            "device_environment" to "production",
            "device_info" to deviceInfoJson,
            "install_source" to "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER",
            "deviceOsId" to buildId,
            "firebaseInstallationId" to fid,
            "apn_token" to null,
            "install_mode" to 2
        )

        val json = apiCall(url, "USER.AUTH.DEVICE.REGISTER", "POST", payload)
        sessionKey = json.get("sessionKey")?.asText()
    }

    private suspend fun apiCall(url: String, scope: String, method: String = "GET", body: Map<String, Any?>? = null, isRetry: Boolean = false): JsonNode {
        ensureInitialized()
        val integrityToken = generateIntegrityToken(scope)
        val timezone = generateDeviceTimezone()

        val headers = mapOf(
            "x-device-os-id" to (syntheticBuildId ?: "AP2A.240805.015"),
            "user-agent" to "Dart/3.10 (dart:io)",
            "x-device-release-version" to "3.13.5",
            "x-firebase-app-check" to "null",
            "authorization" to "Bearer null",
            "content-type" to "application/json; charset=UTF-8",
            "x-installation-source" to "IS_INSTALLED_FROM_PLAY_PACKAGE_INSTALLER",
            "integrity" to "Bearer $integrityToken",
            "accept" to "application/json",
            "x-firebase-id" to (fid ?: ""),
            "x-device-id" to (deviceId ?: ""),
            "x-device-timezone" to timezone,
            "x-device-language" to "ar",
            "x-platform" to "Mobile",
            "x-os" to "android"
        )

        try {
            val response = when (method.uppercase()) {
                "POST" -> app.post(url, headers = headers, json = body)
                "PUT" -> app.put(url, headers = headers, json = body)
                else -> app.get(url, headers = headers)
            }
            return response.parsed<JsonNode>()
        } catch (e: Exception) {
            if (!isRetry && (e.message?.contains("401") == true || e.message?.contains("403") == true)) {
                ensureInitialized(forceRefresh = true)
                return apiCall(url, scope, method, body, isRetry = true)
            }
            throw e
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (vpnNoticeCount < 3) {
            vpnNoticeCount++
            try {
                com.lagradost.cloudstream3.CommonActivity.showToast(
                    "⚠️ تنبيه: يُرجى استخدام VPN لتجنب حظر عنوان الـ IP الخاص بك",
                    android.widget.Toast.LENGTH_LONG
                )
            } catch (_: Exception) {}
        }

        ensureInitialized()
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val url = "$baseUrl/library/home_content?with_genres=true"
        val json = apiCall(url, "ANIME.LIBRARY.HOME_CONTENT")

        val homeLists = mutableListOf<HomePageList>()
        val sections = json.get("sections")

        sections?.forEach { section ->
            val title = section.get("sectionTitle")?.asText() ?: ""
            val items = section.get("items")
            val list = mutableListOf<SearchResponse>()

            items?.forEach { item ->
                val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                list.add(
                    newAnimeSearchResponse(
                        name = item.get("title")?.asText() ?: "",
                        url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
                    ) {
                        this.posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                        this.year = item.get("release_year")?.asInt()
                        this.score = ratingFloat?.let { Score.from10(it) }
                    }
                )
            }
            if (list.isNotEmpty()) {
                homeLists.add(HomePageList(title, list, isHorizontalImages = true))
            }
        }
        return newHomePageResponse(homeLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        ensureInitialized()
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val apiPage = (page - 1).coerceAtLeast(0)
        val url = "$baseUrl/library/search?page=$apiPage&sort_by=release_year&sort_direction=1&text_direction=jp"

        return try {
            val json = apiCall(url, "ANIME.LIBRARY.SEARCH", method = "POST", body = mapOf("query" to query))
            val items = json.get("items") ?: return null

            val results = mutableListOf<SearchResponse>()
            items.forEach { item ->
                val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()
                results.add(
                    newAnimeSearchResponse(
                        name = item.get("title")?.asText() ?: "",
                        url = "$mainUrl/api/v4/library/details/${item.get("_id")?.asText()}"
                    ) {
                        this.posterUrl = item.get("medium_picture")?.asText() ?: item.get("main_picture")?.asText()
                        this.year = item.get("release_year")?.asInt()
                        this.score = ratingFloat?.let { Score.from10(it) }
                    }
                )
            }

            val hasNext = json.get("hasNext")?.asBoolean() ?: false
            newSearchResponseList(results, hasNext)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override suspend fun load(url: String): LoadResponse {
        ensureInitialized()
        val baseUrl = gatewayBaseUrl ?: mainUrl
        val animeId = url.substringAfterLast("/")
        val detailUrl = "$baseUrl/library/details/$animeId"
        val json = apiCall(detailUrl, "ANIME.LIBRARY.DETAILS")
        val item = json.get("item") ?: throw ErrorLoadingException("Failed to load anime details")

        val allRelated = mutableListOf<AnimeSearchResponse>()
        val others = json.get("others")

        others?.get("recommendations")?.forEach { rec ->
            val recRating = rec.get("myAnimeList_rating")?.asDouble()?.toFloat()
            allRelated.add(
                newAnimeSearchResponse(
                    name = rec.get("title")?.asText() ?: "",
                    url = "$mainUrl/api/v4/library/details/${rec.get("_id")?.asText()}"
                ) {
                    this.posterUrl = rec.get("main_picture")?.asText()
                    this.score = recRating?.let { Score.from10(it) }
                } as AnimeSearchResponse
            )
        }

        val episodesList = mutableListOf<Episode>()
        try {
            val episodeUrl = "$baseUrl/library/episodes/$animeId?sort_by_latest=1&with_arcs=true&with_favorites=true"
            val epJson = apiCall(episodeUrl, "ANIME.LIBRARY.EPISODES.ALL")
            epJson.get("items")?.forEach { ep ->
                val epNumber = ep.get("episode_number")?.asInt() ?: 1
                val epId = ep.get("_id")?.asText() ?: ""
                episodesList.add(
                    newEpisode(data = "$animeId|$epId|$epNumber") {
                        this.name = "الحلقة $epNumber"
                        this.episode = epNumber
                        this.season = 1
                        this.posterUrl = ep.get("thumbnail")?.asText()
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val ratingFloat = item.get("myAnimeList_rating")?.asDouble()?.toFloat()

        return newAnimeLoadResponse(
            name = item.get("title")?.asText() ?: "",
            url = url,
            type = TvType.Anime
        ) {
            this.posterUrl = item.get("main_picture")?.asText()
            this.plot = item.get("synopsis")?.asText()
            this.year = item.get("release_year")?.asInt()
            this.score = ratingFloat?.let { Score.from10(it) }
            this.tags = item.get("genreLabels")?.mapNotNull { it.get("label")?.asText() }
            this.showStatus = when (item.get("release_status")?.asText()) {
                "on_going" -> ShowStatus.Ongoing
                "finished" -> ShowStatus.Completed
                else -> null
            }
            this.recommendations = allRelated
            addEpisodes(DubStatus.Subbed, episodesList)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            val animeId = parts[0]
            val episodeId = parts[1]
            val episodeNumber = parts.getOrNull(2)?.toIntOrNull() ?: 1

            val baseUrl = gatewayBaseUrl ?: mainUrl
            val sourcesUrl = "$baseUrl/library/episode/sources"
            val sourcesBody = mapOf(
                "animeId" to animeId,
                "episodeId" to episodeId,
                "episode_number" to episodeNumber
            )
            val sourcesJson = apiCall(sourcesUrl, "ANIME.LIBRARY.EPISODES.SOURCES.ALL", method = "POST", body = sourcesBody)
            val items = sourcesJson.get("items") ?: return@withContext false

            val priorityProviders = listOf("cr2", "rift-streamer", "streamtape")
            val filteredItems = items.filter { src ->
                val subTitle = src.get("sub_title")?.asText() ?: ""
                subTitle.startsWith("ar_") || subTitle.isEmpty()
            }.ifEmpty { items.toList() }

            val sortedItems = filteredItems.sortedBy { src ->
                val provider = src.get("provider")?.asText() ?: ""
                val index = priorityProviders.indexOf(provider)
                if (index != -1) index else 99
            }.take(6)

            sortedItems.forEach { src ->
                val hostId = src.get("_id")?.asText() ?: return@forEach
                val serverName = src.get("server_name")?.asText() ?: "Server"
                val provider = src.get("provider")?.asText() ?: ""
                val subTitle = src.get("sub_title")?.asText() ?: ""

                val qualitiesNode = src.get("qualities")
                val qualitiesList = if (qualitiesNode != null && qualitiesNode.isArray && qualitiesNode.size() > 0) {
                    qualitiesNode.map { it.asText() }
                } else {
                    listOf("720P")
                }

                for (quality in qualitiesList) {
                    try {
                        val canPlayUrl = "$baseUrl/library/episode/source/can_play"
                        val canPlayBody = mapOf(
                            "episodeId" to episodeId,
                            "hostId" to hostId,
                            "is_download" to false,
                            "event_name" to "play_episode_unlocked"
                        )
                        val canPlayJson = apiCall(canPlayUrl, "ANIME.LIBRARY.EPISODES.SOURCES.CHECK_AVAILABILITY", method = "POST", body = canPlayBody)
                        val sessionId = canPlayJson.get("sessionId")?.asText() ?: ""

                        val claimUrl = "$baseUrl/ads_manager/claim"
                        val claimBody = mapOf(
                            "event_name" to "play_episode_unlocked",
                            "hostId" to hostId,
                            "episodeId" to episodeId,
                            "transactionRef" to "${System.currentTimeMillis()}_${Random().nextInt(99999999)}",
                            "reward_result" to "not_filled",
                            "streamingServerKey" to provider,
                            "is_optional" to false,
                            "is_reward" to true
                        )
                        try {
                            apiCall(claimUrl, "USER.ADS_MANAGER.CLAIMS", method = "PUT", body = claimBody)
                        } catch (_: Exception) {}

                        val directLinkUrl = "$baseUrl/library/episode/source/direct_link"
                        val directLinkBody = mapOf(
                            "id" to hostId,
                            "quality" to quality,
                            "with_internal_player" to "1",
                            "sessionId" to sessionId
                        )
                        val directLinkJson = apiCall(directLinkUrl, "ANIME.LIBRARY.EPISODES.SOURCES.DIRECT_LINK", method = "POST", body = directLinkBody)
                        directLinkJson.get("tracks")?.forEach { track ->
                            val trackUrl = track.get("file")?.asText() ?: track.get("url")?.asText()
                            val trackLang = track.get("label")?.asText() ?: track.get("language")?.asText() ?: "Arabic"
                            if (!trackUrl.isNullOrEmpty()) {
                                subtitleCallback(SubtitleFile(trackLang, trackUrl))
                            }
                        }
                        if (directLinkJson.get("url_response")?.asBoolean() == true) {
                            val videoUrl = directLinkJson.get("videoUrl")?.asText()

                            if (!videoUrl.isNullOrEmpty()) {
                                val customHeaders = mutableMapOf(
                                    "User-Agent" to "libmpv",
                                    "Accept" to "*/*",
                                    "Range" to "bytes=0-",
                                    "Connection" to "close",
                                    "Icy-MetaData" to "1"
                                )
                                val hostFromUrl = try {
                                    java.net.URI(videoUrl).host
                                } catch (_: Exception) {
                                    null
                                }
                                customHeaders["Host"] = hostFromUrl ?: "media-1.rift-content.com"

                                directLinkJson.get("http_headers")?.fields()?.forEach { (k, v) ->
                                    customHeaders[k] = v.asText()
                                }

                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$serverName [$subTitle] - $quality",
                                        url = videoUrl,
                                    ) {
                                        this.quality = extractQuality(quality)
                                        this.headers = customHeaders
                                    }
                                )
                            }
                        }
                        else if (directLinkJson.get("ticket_response")?.asBoolean() == true) {
                            val fileId = directLinkJson.get("fileId")?.asText() ?: ""
                            val ticket = directLinkJson.get("ticket")?.asText() ?: ""
                            val waitTimeSeconds = directLinkJson.get("wait_time")?.asLong() ?: 5L


                            if (fileId.isNotEmpty() && ticket.isNotEmpty()) {
                                kotlinx.coroutines.delay((waitTimeSeconds * 1000) + 500)

                                val tapeApiUrl = "https://api.streamtape.com/file/dl?file=$fileId&ticket=$ticket"

                                var tapeRes = app.get(tapeApiUrl).parsed<JsonNode>()
                                val status = tapeRes.get("status")?.asInt()
                                val msg = tapeRes.get("msg")?.asText()


                                if (status != 200) {
                                    kotlinx.coroutines.delay(2000)
                                    tapeRes = app.get(tapeApiUrl).parsed<JsonNode>()
                                }

                                val tapeDirectUrl = tapeRes.get("result")?.get("url")?.asText()

                                if (!tapeDirectUrl.isNullOrEmpty()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "$serverName [$subTitle] - $quality",
                                            url = tapeDirectUrl,
                                        ) {
                                            this.quality = extractQuality(quality)
                                            this.headers = mapOf(
                                                "User-Agent" to "libmpv",
                                                "Accept" to "*/*"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(name, "❌ [LoadLinks Error] حدث خطأ في السيرفر: ${e.message}")
                    }
                }
            }
            return@withContext true
        } catch (e: Exception) {
            return@withContext false
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
}
