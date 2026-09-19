package com.yacin

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class YacineTVProvider : MainAPI() {
    companion object {
        private const val FB_PROJECT_ID = "ycntv-7a08e"
        private const val FB_PROJECT_NUMBER = "692330584196"
        private const val FB_APP_ID = "1:692330584196:android:68ea9f0c920aa17904cad1"
        private const val FB_API_KEY = "AIzaSyDRKL14PPiXzk7qNUNLgV2IsjasxNpWLeU"
        private const val FB_PKG = "ver3.ycntivi.off"
        private const val FB_CERT = "E404353443FB03A54702D53E2C7563D791D92559"

        @Volatile private var cachedUrl: String = "https://def11.ycnapi.com/api"
        @Volatile private var cachedEtag: String? = null
        @Volatile private var cachedFid: String? = null
        @Volatile private var cachedToken: String? = null
    }

    override var mainUrl = "https://def11.ycnapi.com/api"
    private val fallbackUrl = "https://deft.yacinelive.com/api"

    override var name = "Yacine TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val baseKey = "c!xZj+N9&G@Ev@vw"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class LinkData(
        val id: String,
        val name: String,
        val poster: String?
    )

    private fun generateFid(): String {
        val randomBytes = ByteArray(17)
        SecureRandom().nextBytes(randomBytes)
        randomBytes[0] = ((randomBytes[0].toInt() and 0x0F) or 0x70).toByte()
        return Base64.encodeToString(randomBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).take(22)
    }

    private fun getFirebaseToken(fid: String): String? {
        val url = "https://firebaseinstallations.googleapis.com/v1/projects/$FB_PROJECT_ID/installations"
        val jsonBody = """
            {"fid":"$fid","appId":"$FB_APP_ID","authVersion":"FIS_v2","sdkVersion":"a:18.0.0"}
        """.trimIndent()

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("X-Android-Package", FB_PKG)
            .header("X-Android-Cert", FB_CERT)
            .header("x-goog-api-key", FB_API_KEY)
            .header("x-firebase-client", "H4sIAAAAAAAA_6tWykhNLCpJSk0sKVayio7VUSpLLSrOzM9TslIyUqoFAFyivEQfAAAA")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; RMX5061 Build/BP2A.250605.015)")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    parseJson<FirebaseInstallationResponse>(body).authToken?.token
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun syncDynamicApiUrl(): String = withContext(Dispatchers.IO) {
        var fid = cachedFid
        var token = cachedToken

        if (fid.isNullOrEmpty() || token.isNullOrEmpty()) {
            fid = generateFid()
            token = getFirebaseToken(fid)
            if (token != null) {
                cachedFid = fid
                cachedToken = token
            }
        }

        if (token.isNullOrEmpty() || fid.isNullOrEmpty()) return@withContext cachedUrl

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val currentTimeIso = isoFormat.format(Date())

        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/$FB_PROJECT_NUMBER/namespaces/firebase:fetch"
        val jsonPayload = """
            {"appVersion":"3.1","firstOpenTime":"$currentTimeIso","timeZone":"Asia/Baghdad","appInstanceIdToken":"$token","languageCode":"ar-IQ","appBuild":"4","appInstanceId":"$fid","countryCode":"IQ","analyticsUserProperties":{},"appId":"$FB_APP_ID","platformVersion":"36","sdkVersion":"22.0.0","packageName":"$FB_PKG"}
        """.trimIndent()

        val reqBuilder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", FB_API_KEY)
            .header("X-Android-Package", FB_PKG)
            .header("X-Android-Cert", FB_CERT)
            .header("X-Goog-Firebase-Installations-Auth", token)
            .header("X-Firebase-RC-Fetch-Type", "BASE/1")
            .header("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 16; RMX5061 Build/BP2A.250605.015)")
            .post(jsonPayload.toRequestBody("application/json".toMediaType()))

        if (!cachedEtag.isNullOrEmpty()) {
            reqBuilder.header("If-None-Match", cachedEtag!!)
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { res ->
                val newEtag = res.header("ETag")
                if (newEtag != null) cachedEtag = newEtag

                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val config = parseJson<RemoteConfigResponse>(body)

                    if (config.state == "NO_CHANGE") {
                        return@withContext cachedUrl
                    } else if (config.state == "UPDATE") {
                        val newDomain = config.entries?.get("defaults")
                        if (!newDomain.isNullOrEmpty()) {
                            cachedUrl = "https://$newDomain/api"
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        cachedUrl
    }

    private fun decrypt(encryptedText: String, tHeader: String): String {
        return try {
            val fullKey = (baseKey + tHeader).toByteArray(Charsets.UTF_8)
            val decodedBytes = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor fullKey[i % fullKey.size].toInt()).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? = withContext(Dispatchers.IO) {
        val dynamicUrl = syncDynamicApiUrl()
        val endpoints = listOf(dynamicUrl, fallbackUrl)

        for (baseUrl in endpoints) {
            val cleanBase = baseUrl.trimEnd('/')
            val cleanPath = path.trimStart('/')
            val fullUrl = "$cleanBase/$cleanPath"

            try {
                val request = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", "okhttp/4.12.0")
                    .header("Accept", "application/json")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val tHeader = response.header("t") ?: ""

                        if (body.isEmpty()) return@use null

                        val decryptedJson = decrypt(body, tHeader)
                        if (decryptedJson.isNotEmpty()) {
                            return@withContext parseJson<YacineResponse>(decryptedJson)
                        }
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }
        null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val categories = fetchYacine("categories")?.data ?: emptyList()

        val homePageLists = categories.map { cat ->
            async {
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                if (channels.isEmpty()) return@async null

                val channelItems = channels.map { chan ->
                    val data = LinkData(chan.id ?: "", chan.name ?: "Unknown", chan.logo).toJson()
                    newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
                HomePageList(cat.name ?: "Category", channelItems)
            }
        }.awaitAll().filterNotNull()

        newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val categories = fetchYacine("categories")?.data ?: emptyList()
        val deferredList = categories.map { cat ->
            async {
                val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
                channels.filter { it.name?.contains(query, ignoreCase = true) == true }.map { chan ->
                    val data = LinkData(chan.id ?: "", chan.name ?: "Unknown", chan.logo).toJson()
                    newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                        this.posterUrl = chan.logo
                    }
                }
            }
        }
        deferredList.awaitAll().flatten()
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        return newMovieLoadResponse(
            data.name,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = data.poster
            this.plot = "شاهد بث مباشر لقناة ${data.name}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val linkData = try {
            parseJson<LinkData>(data)
        } catch (e: Exception) {
            return@withContext false
        }

        val responseData = fetchYacine("channel/${linkData.id}")
        val streams = responseData?.data ?: return@withContext false

        streams.forEachIndexed { index, stream ->
            val finalUrl = stream.url?.replace("www.elahmad.coo", "www.elahmad.com") ?: ""
            if (finalUrl.isNotEmpty()) {
                val streamHeaders = mutableMapOf<String, String>()
                stream.headers?.forEach { (key, value) ->
                    if (value is String) streamHeaders[key] = value
                }
                if (!streamHeaders.containsKey("User-Agent")) {
                    streamHeaders["User-Agent"] = "okhttp/4.12.0"
                }

                callback.invoke(
                    newExtractorLink(
                        this@YacineTVProvider.name,
                        stream.name ?: "Server ${index + 1}",
                        finalUrl
                    ) {
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                        this.referer = streamHeaders["Referer"] ?: ""
                    }
                )
            }
        }
        true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class FirebaseInstallationResponse(
        @JsonProperty("authToken") val authToken: AuthToken? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class AuthToken(
            @JsonProperty("token") val token: String? = null
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class RemoteConfigResponse(
        @JsonProperty("state") val state: String? = null,
        @JsonProperty("entries") val entries: Map<String, String>? = null,
        @JsonProperty("templateVersion") val templateVersion: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YacineResponse(
        @JsonProperty("data") val data: List<YacineData>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class YacineData(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("headers") val headers: Map<String, Any>? = null
    )
}
