package com.yacin



import android.content.Context
import android.content.Intent
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager


@CloudstreamPlugin
class YacineTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YacineTVProvider())
        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                YacineSettings().show(activity.supportFragmentManager, "YacineSettings")
            }
        }
    }
}




class YacineSettings : BottomSheetDialogFragment() {

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181926")) // خلفية داكنة حديثة
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(), // زاوية أعلى يسار
                    dp(24).toFloat(), dp(24).toFloat(), // زاوية أعلى يمين
                    0f, 0f, 0f, 0f
                )
            }
        }
        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#45475A"))
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        }
        root.addView(handle)
        val title = TextView(ctx).apply {
            text = "مجتمع ودعم الإضافة"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CAD3F5"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "تابع التحديثات وانضم لمجتمع النقاش والمطورين"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8087A2"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        root.addView(subtitle)
        fun createPlatformCard(
            badgeIcon: String,
            badgeColor: String,
            titleText: String,
            descText: String,
            targetUrl: String
        ): View {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val shape = GradientDrawable().apply {
                    setColor(Color.parseColor("#24273A"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#363A4F"))
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#3B4252")),
                    shape,
                    shape
                )

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                    } catch (e: Exception) { }
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }
            val iconBadge = TextView(ctx).apply {
                text = badgeIcon
                textSize = 18f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(badgeColor))
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(14)
                }
            }
            card.addView(iconBadge)
            val textContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val cardTitle = TextView(ctx).apply {
                text = titleText
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#CAD3F5"))
            }
            textContainer.addView(cardTitle)

            val cardDesc = TextView(ctx).apply {
                text = descText
                textSize = 12f
                setTextColor(Color.parseColor("#A5ADCB"))
                setPadding(0, dp(2), 0, 0)
            }
            textContainer.addView(cardDesc)

            card.addView(textContainer)
            val arrow = TextView(ctx).apply {
                text = "↗"
                textSize = 17f
                setTextColor(Color.parseColor("#6E738D"))
                setTypeface(null, Typeface.BOLD)
            }
            card.addView(arrow)

            return card
        }
        root.addView(
            createPlatformCard(
                badgeIcon = "📧",
                badgeColor = "#229ED9",
                titleText = "قناة التليجرام",
                descText = "أحدث الإصدارات، الروابط والأخبار",
                targetUrl = "https://t.me/cs3ara"
            )
        )

        root.addView(
            createPlatformCard(
                badgeIcon = "☄️",
                badgeColor = "#5865F2",
                titleText = "سيرفر الديسكورد",
                descText = "نقاشات المجتمع، الدعم الفني والاقتراحات",
                targetUrl = "https://discord.gg/xY63qaQRw"
            )
        )

        root.addView(
            createPlatformCard(
                badgeIcon = "🔗",
                badgeColor = "#313244",
                titleText = "مشروع GitHub",
                descText = "المستودع المفتوح، الإبلاغ عن مشاكل والمساهمة",
                targetUrl = "https://github.com/Abodabodd/re-3arabi"
            )
        )

        return root
    }
}


class YacineTVProvider : MainAPI() {
    companion object {
        private const val FB_PROJECT_ID = "ycntv-7a08e"
        private const val FB_PROJECT_NUMBER = "692330584196"
        private const val FB_APP_ID = "1:692330584196:android:68ea9f0c920aa17904cad1"
        private const val FB_API_KEY = "AIzaSyDRKL14PPiXzk7qNUNLgV2IsjasxNpWLeU"
        private const val FB_PKG = "ver3.ycntivi.off"
        private const val FB_CERT = "E404353443FB03A54702D53E2C7563D791D92559"

        private const val PREF_SAVED_DOMAIN_KEY = "YACINE_SAVED_DOMAIN_URL"
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

    private fun getSavedApiUrl(): String {
        return try {
            getKey<String>(PREF_SAVED_DOMAIN_KEY) ?: cachedUrl
        } catch (e: Exception) {
            cachedUrl
        }
    }

    private fun saveApiUrl(url: String) {
        cachedUrl = url
        try {
            setKey(PREF_SAVED_DOMAIN_KEY, url)
        } catch (e: Exception) { }
    }

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

        if (token.isNullOrEmpty() || fid.isNullOrEmpty()) return@withContext getSavedApiUrl()

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

                    if (config.state == "UPDATE") {
                        val newDomain = config.entries?.get("defaults")
                        if (!newDomain.isNullOrEmpty()) {
                            val updatedUrl = "https://$newDomain/api"
                            saveApiUrl(updatedUrl)
                            return@withContext updatedUrl
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        getSavedApiUrl()
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

    private fun executeYacineRequest(baseUrl: String, path: String): YacineResponse? {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        val fullUrl = "$cleanBase/$cleanPath"

        return try {
            val request = Request.Builder()
                .url(fullUrl)
                .header("User-Agent", "okhttp/4.12.0")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    val body = response.body?.string() ?: ""
                    val tHeader = response.header("t") ?: ""

                    if (body.isNotEmpty()) {
                        val decryptedJson = decrypt(body, tHeader)
                        if (decryptedJson.isNotEmpty()) {
                            return parseJson<YacineResponse>(decryptedJson)
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? = withContext(Dispatchers.IO) {
        val currentSavedUrl = getSavedApiUrl()
        val initialEndpoints = listOf(currentSavedUrl, fallbackUrl).distinct()

        for (baseUrl in initialEndpoints) {
            val res = executeYacineRequest(baseUrl, path)
            if (res != null) {
                saveApiUrl(baseUrl)
                return@withContext res
            }
        }

        val newDynamicUrl = syncDynamicApiUrl()

        if (!initialEndpoints.contains(newDynamicUrl)) {
            val res = executeYacineRequest(newDynamicUrl, path)
            if (res != null) {
                saveApiUrl(newDynamicUrl)
                return@withContext res
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

    private fun resolveDirectStreamUrl(rawUrl: String, rawReferer: String, userAgent: String): String {
        if (!rawUrl.contains("varie-redirect.com")) return rawUrl

        return try {
            val uri = URI(rawUrl)
            val host = uri.host ?: return rawUrl
            val isHttps = rawUrl.startsWith("https://", ignoreCase = true)
            val port = if (uri.port != -1) uri.port else (if (isHttps) 443 else 80)
            val path = (if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath) +
                    (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")

            val socket = if (isHttps) {
                HttpsURLConnection.getDefaultSSLSocketFactory().createSocket(host, port)
            } else {
                Socket(host, port)
            }
            socket.soTimeout = 8000

            val rawRequest = "GET $path HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: $userAgent\r\n" +
                    "Referer: $rawReferer\r\n" +
                    "sec-ch-ua: \"Chromium\";v=\"91\", \" Not;A Brand\";v=\"99\"\r\n" +
                    "sec-ch-ua-mobile: ?0\r\n" +
                    "sec-fetch-mode: navigate\r\n" +
                    "sec-fetch-user: ?1\r\n" +
                    "accept: */*\r\n" +
                    "sec-fetch-dest: video\r\n" +
                    "Connection: close\r\n\r\n"

            socket.getOutputStream().write(rawRequest.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()

            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            var line: String?
            var location: String? = null

            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                if (line!!.startsWith("Location:", ignoreCase = true)) {
                    location = line!!.substring(9).trim()
                    break
                }
            }
            socket.close()

            location ?: rawUrl
        } catch (e: Exception) {
            rawUrl
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
            val rawUrl = stream.url?.replace("www.elahmad.coo", "www.elahmad.com") ?: ""
            if (rawUrl.isNotEmpty()) {
                val streamHeaders = mutableMapOf<String, String>()

                stream.headers?.forEach { (key, value) ->
                    if (value is String) streamHeaders[key] = value
                }

                val userAgent = streamHeaders["User-Agent"]
                    ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
                val referer = streamHeaders["Referer"] ?: "http://yacinneeee.live⁠‍⁠ㅤ‌⁠‍⁠"

                val finalStreamUrl = resolveDirectStreamUrl(rawUrl, referer, userAgent)

                callback.invoke(
                    newExtractorLink(
                        this@YacineTVProvider.name,
                        stream.name ?: "Server ${index + 1}",
                        finalStreamUrl
                    ) {
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                        this.referer = referer
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
