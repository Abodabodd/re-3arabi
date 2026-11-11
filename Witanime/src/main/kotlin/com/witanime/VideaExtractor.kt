package com.witanime

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class GoogleDriveExtractor : ExtractorApi() {
    override val name = "GoogleDrive"
    override val mainUrl = "https://drive.google.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""(?:/file/d/|[?&]id=)([0-9A-Za-z_-]{15,})""").find(url)?.groupValues?.get(1) ?: return
        val directUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "${this.name} (Direct)",
                url = directUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "https://drive.google.com/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}
class MegaExtractor : ExtractorApi() {
    override val name: String = "Mega"
    override val mainUrl: String = "https://mega.nz"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("MEGA_EXTRACTOR", "▶️ getUrl start for: $url")
        try {
            val megaUrl = url.trim()
            if (megaUrl.isBlank() || (!megaUrl.contains("#") && !megaUrl.contains("!"))) {
                Log.w("MEGA_EXTRACTOR", "Invalid Mega URL: missing key token")
                return
            }

            val localStreamUrl = WitAnimeMegaServer.registerMegaStream(megaUrl)
            if (localStreamUrl.isNullOrBlank()) {
                Log.e("MEGA_EXTRACTOR", "Failed to resolve Mega node")
                return
            }

            withContext(Dispatchers.Main) {
                callback.invoke(
                    newExtractorLink(
                        source = this@MegaExtractor.name,
                        name = "${this@MegaExtractor.name} (Direct Stream)",
                        url = localStreamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("MEGA_EXTRACTOR", "Mega getUrl error", e)
        }
    }
}
class GoFileExtractor : ExtractorApi() {
    override val name: String = "GoFile"
    override val mainUrl: String = "https://gofile.io"
    override val requiresReferer: Boolean = false

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun generateWebsiteToken(accountToken: String, lang: String = "ar"): String {
        val bucket = (System.currentTimeMillis() / 1000L) / 14_400L
        val payload = "$userAgent::$lang::$accountToken::$bucket::12af056dacea0b"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("GOFILE_EXTRACTOR", "▶️ getUrl start for GoFile: $url")
        withContext(Dispatchers.IO) {
            try {
                val contentId = Regex("""gofile\.io/(?:d/|contents/)?([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1)
                    ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')

                if (contentId.isBlank()) return@withContext

                val accReq = Request.Builder()
                    .url("https://api.gofile.io/accounts")
                    .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("User-Agent", userAgent)
                    .header("Origin", "https://gofile.io")
                    .header("Referer", "https://gofile.io/")
                    .build()

                val accRaw = app.baseClient.newCall(accReq).execute().use { it.body?.string().orEmpty() }
                val token = runCatching {
                    JSONObject(accRaw).optJSONObject("data")?.optString("token").orEmpty()
                }.getOrDefault("")

                if (token.isBlank()) return@withContext

                val wt = generateWebsiteToken(token, "ar")

                val contentsUrl = "https://api.gofile.io/contents/$contentId?page=1&pageSize=100&sortField=name&sortDirection=1"
                val contentReq = Request.Builder()
                    .url(contentsUrl)
                    .header("User-Agent", userAgent)
                    .header("Authorization", "Bearer $token")
                    .header("X-Website-Token", wt)
                    .header("X-BL", "ar")
                    .header("Origin", "https://gofile.io")
                    .header("Referer", "https://gofile.io/")
                    .get()
                    .build()

                val contentRaw = app.baseClient.newCall(contentReq).execute().use { it.body?.string().orEmpty() }
                val dataObj = runCatching {
                    JSONObject(contentRaw).optJSONObject("data")
                }.getOrNull() ?: return@withContext

                val fileEntries = mutableListOf<JSONObject>()
                if (dataObj.optString("type") == "file") {
                    fileEntries.add(dataObj)
                } else {
                    dataObj.optJSONObject("children")?.let { ch ->
                        val keys = ch.keys()
                        while (keys.hasNext()) {
                            ch.optJSONObject(keys.next())?.let { fileEntries.add(it) }
                        }
                    }
                    dataObj.optJSONArray("children")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let { fileEntries.add(it) }
                        }
                    }
                }

                val cookieHeader = "accountToken=$token"

                for (child in fileEntries) {
                    val fileName = child.optString("name").lowercase()
                    val directLink = child.optString("link").trim()
                    val mime = child.optString("mimetype").lowercase()

                    if (directLink.isEmpty()) continue
                    if (fileName.endsWith(".zip") || fileName.endsWith(".rar") || fileName.endsWith(".7z") ||
                        mime.contains("zip") || mime.contains("rar")
                    ) {
                        continue
                    }

                    val qVal = getQualityFromName(fileName).takeIf { it > 0 } ?: Qualities.P1080.value

                    withContext(Dispatchers.Main) {
                        callback.invoke(
                            newExtractorLink(
                                source = this@GoFileExtractor.name,
                                name = "${this@GoFileExtractor.name} [DL]",
                                url = directLink,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://gofile.io/"
                                this.quality = qVal
                                this.headers = mapOf("Cookie" to cookieHeader)
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("GOFILE_EXTRACTOR", "GoFile getUrl error", e)
            }
        }
    }
}
class VideaExtractor : ExtractorApi() {
    override val name: String = "Videa"
    override val mainUrl: String = "https://videa.hu"
    override val requiresReferer: Boolean = false

    private val stupidKey = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("VIDEA_EXTRACTOR", "▶️ getUrl start for: $url")
        try {
            val iframeSrc = url.trim()
            if (iframeSrc.isBlank()) return

            val pageResp = app.get(iframeSrc).text
            val nonce = Regex("_xt\\s*=\\s*\"([^\"]+)\"").find(pageResp)?.groupValues?.get(1) ?: return

            val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
            val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""

            val resultBuilder = StringBuilder()
            for (i in 0 until 32) {
                val ch = paramL.getOrNull(i) ?: 'a'
                val idxInStupid = stupidKey.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInStupid - 31)
                val safeIndex = when {
                    paramSPart.isEmpty() -> 0
                    index < 0 -> 0
                    index >= paramSPart.length -> paramSPart.length - 1
                    else -> index
                }
                resultBuilder.append(paramSPart.getOrNull(safeIndex) ?: 'a')
            }
            val result = resultBuilder.toString()

            val seed = (1..8).map { "abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)] }.joinToString("")
            val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
            val rc4KeyPart = if (result.length > 16) result.substring(16) else ""

            val videoId = Uri.parse(iframeSrc).getQueryParameter("v")
                ?: Regex("[?&]v=([^&]+)").find(iframeSrc)?.groupValues?.get(1)
                ?: return

            val xmlUrl = "https://videa.hu/player/xml?platform=desktop"
            val queryParams: Map<String, String> = mapOf(
                "_s" to seed,
                "_t" to paramT,
                "v" to videoId
            )
            val reqHeaders: Map<String, String> = mapOf(
                "Referer" to iframeSrc,
                "Origin" to "https://videa.hu"
            )

            val xmlResponse = app.get(xmlUrl, params = queryParams, headers = reqHeaders)
            val body = xmlResponse.text
            val xVideaXsHeader = xmlResponse.headers["x-videa-xs"] ?: ""

            val finalDoc = if (body.trimStart().startsWith("<?xml")) {
                val parsed = Jsoup.parse(body, "", Parser.xmlParser())
                if (parsed.selectFirst("error") != null) return
                parsed
            } else {
                try {
                    val decoded = Base64.decode(body.trim(), Base64.DEFAULT)
                    val decrypted = rc4Decrypt(decoded, rc4KeyPart + seed + xVideaXsHeader)
                    Jsoup.parse(decrypted, "", Parser.xmlParser())
                } catch (e: Exception) {
                    return
                }
            }

            val videoSources = finalDoc.select("video_source")
            val collected = mutableListOf<Triple<Int, String, String>>()

            for (src in videoSources) {
                val name = src.attr("name")
                val videoUrlPart = src.text().trim()
                val exp = src.attr("exp")
                val hashElem = finalDoc.getElementsByTag("hash_value_$name").first()
                val md5 = hashElem?.text()?.trim() ?: continue

                val finalUrl = if (videoUrlPart.startsWith("http")) {
                    "$videoUrlPart?md5=$md5&expires=$exp"
                } else {
                    "https:$videoUrlPart?md5=$md5&expires=$exp"
                }
                val q = getQualityFromName(name).takeIf { it > 0 } ?: Qualities.Unknown.value
                collected.add(Triple(q, name, finalUrl))
            }

            val sorted = collected.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }
                .thenByDescending {
                    Regex("(\\d{3,4})").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                })

            withContext(Dispatchers.Main) {
                for ((qualityVal, name, finalUrl) in sorted) {
                    callback.invoke(
                        newExtractorLink(
                            source = this@VideaExtractor.name,
                            name = "$name (Videa)",
                            url = finalUrl,
                            type = if (finalUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer ?: iframeSrc
                            this.quality = qualityVal
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("VIDEA_EXTRACTOR", "Videa getUrl error", e)
        }
    }

    private fun rc4Decrypt(data: ByteArray, key: String): String {
        return try {
            val spec = SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), "RC4")
            val cipher = Cipher.getInstance("RC4")
            cipher.init(Cipher.DECRYPT_MODE, spec)
            val out = cipher.update(data) ?: cipher.doFinal(data)
            String(out, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
class MailruExtractor : ExtractorApi() {
    override val name = "MailRu"
    override val mainUrl = "https://my.mail.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: url
        val vidId = url.substringAfter("video/embed/").substringBefore("?").trim()
        if (vidId.isBlank()) return

        val videoReq = try {
            app.get("${mainUrl}/+/video/meta/${vidId}", referer = extRef)
        } catch (e: Exception) {
            Log.e("MAILRU_EXTRACTOR", "Failed to fetch meta: ${e.message}")
            return
        }

        val videoKey = videoReq.cookies["video_key"]?.toString().orEmpty()
        val videoData = AppUtils.tryParseJson<MailRuData>(videoReq.text) ?: return

        for (video in videoData.videos ?: emptyList()) {
            var videoUrl = if (video.url.startsWith("//")) "https:${video.url}" else video.url
            if (videoKey.isNotBlank()) {
                videoUrl = if (videoUrl.contains("?")) "$videoUrl&video_key=$videoKey" else "$videoUrl?video_key=$videoKey"
            }

            try {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = extRef
                        this.quality = getQualityFromName(video.key)
                    }
                )
            } catch (e: Exception) {
                Log.e("MAILRU_EXTRACTOR", "Failed to create extractor link for $videoUrl", e)
            }
        }
    }

    data class MailRuData(
        @JsonProperty("provider") val provider: String? = null,
        @JsonProperty("videos") val videos: List<MailRuVideoData>? = null
    )

    data class MailRuVideoData(
        @JsonProperty("url") val url: String,
        @JsonProperty("key") val key: String
    )
}
class FourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = Regex("""4shared\.com/(?:web/embed/file|embed|video|file|s)/([A-Za-z0-9_-]+)""", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1) ?: return

            val normalizedEmbed = "https://www.4shared.com/web/embed/file/$fileId"
            val pageHtml = app.get(normalizedEmbed, referer = "https://www.4shared.com/").text

            val doc = Jsoup.parse(pageHtml, normalizedEmbed)
            val mp4Url = doc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
                ?: Regex("""https?://dc\d+\.4shared\.com/[^\s"'<>]+\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                    .find(pageHtml)?.value
                ?: return

            val finalDirectUrl = if (mp4Url.startsWith("//")) "https:$mp4Url" else mp4Url

            withContext(Dispatchers.Main) {
                callback.invoke(
                    newExtractorLink(
                        source = this@FourSharedExtractor.name,
                        name = "${this@FourSharedExtractor.name} (Direct)",
                        url = finalDirectUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.4shared.com/"
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("4SHARED_EXTRACTOR", "4Shared getUrl error", e)
        }
    }
}
class YonaPlayExtractor : ExtractorApi() {
    override val name = "YonaPlay"
    override val mainUrl = "https://mid.yonaplay.net"
    override val requiresReferer = true

    companion object {
        private const val TAG = "YONAPLAY_EXTRACTOR"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private class YonaCookieJar : CookieJar {
            private val store = ConcurrentHashMap<String, Cookie>()

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                for (cookie in cookies) {
                    store["${url.host}|${cookie.name}"] = cookie
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return store.values.filter { it.matches(url) }
            }
        }

        private fun buildClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(YonaCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }
    }

    private fun decryptAesGcm(b64Data: String, pageKey: String): String? {
        return try {
            val keyBytes = MessageDigest.getInstance("SHA-256")
                .digest(pageKey.toByteArray(StandardCharsets.UTF_8))
            val raw = Base64.decode(b64Data.trim(), Base64.DEFAULT)
            if (raw.size <= 28) return null

            val iv = raw.copyOfRange(0, 12)
            val tag = raw.copyOfRange(12, 28)
            val ciphertext = raw.copyOfRange(28, raw.size)

            val ctWithTag = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, ctWithTag, 0, ciphertext.size)
            System.arraycopy(tag, 0, ctWithTag, ciphertext.size, tag.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ctWithTag), StandardCharsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.e(TAG, "Decryption error: ${e.message}")
            null
        }
    }

    private suspend fun resolveDotPlay(
        client: OkHttpClient,
        dotPlayUrl: String,
        referer: String,
        label: String,
        quality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val req = Request.Builder()
                .url(dotPlayUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .build()

            val pageResp = client.newCall(req).execute().use { it.body?.string().orEmpty() }
            val embedCode = Regex("""window\.embedCode\s*=\s*["']([^"']+)["']""").find(pageResp)?.groupValues?.get(1)
                ?: dotPlayUrl.substringAfterLast('/').substringBefore('?')
            val embedToken = Regex("""window\.embedToken\s*=\s*["']([^"']+)["']""").find(pageResp)?.groupValues?.get(1).orEmpty()

            val apiUrl = "https://dotplay.net/api.php?code=$embedCode"
            val apiReqBuilder = Request.Builder()
                .url(apiUrl)
                .header("Accept", "application/json")
                .header("Referer", dotPlayUrl)
                .header("User-Agent", USER_AGENT)

            if (embedToken.isNotBlank()) {
                apiReqBuilder.header("X-Embed-Token", embedToken)
            }

            val apiRaw = client.newCall(apiReqBuilder.build()).execute().use { it.body?.string().orEmpty() }
            val obj = JSONObject(apiRaw)
            val encodedVideo = obj.optString("video_url").trim()
            if (encodedVideo.isEmpty()) return

            val decoded = String(Base64.decode(encodedVideo, Base64.DEFAULT), StandardCharsets.UTF_8)
                .substringBefore('|').trim()

            if (decoded.startsWith("http")) {
                val isHls = decoded.contains(".m3u8", ignoreCase = true)
                callback.invoke(
                    newExtractorLink(
                        source = this@YonaPlayExtractor.name,
                        name = label,
                        url = decoded,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://dotplay.net/"
                        this.quality = quality
                    }
                )
                loadExtractor(decoded, "https://dotplay.net/", subtitleCallback, callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "DotPlay resolve error: ${e.message}")
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "▶️ getUrl start for: $url")
        withContext(Dispatchers.IO) {
            try {
                val client = buildClient()
                val origin = if (url.startsWith("http")) Uri.parse(url).let { "${it.scheme}://${it.host}" } else "https://mid.yonaplay.net"

                val pageReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer ?: "https://witanime.site/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()

                val pageHtml = client.newCall(pageReq).execute().use { it.body?.string().orEmpty() }

                var code = ""
                var embedToken = ""
                var pageKey = ""

                Regex("""window\._E\s*=\s*(\{[^;]+\})""").find(pageHtml)?.groupValues?.get(1)?.let { rawObj ->
                    runCatching { JSONObject(rawObj) }.getOrNull()?.let { obj ->
                        code = obj.optString("c").trim()
                        embedToken = obj.optString("t").trim()
                        pageKey = obj.optString("k").trim()
                    }
                }

                val apiHeaders = mutableMapOf(
                    "Origin" to origin,
                    "Referer" to url,
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json"
                )

                if (code.isEmpty() || pageKey.isEmpty()) {
                    val initReq = Request.Builder()
                        .url("$origin/api/init-session.php")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .apply { apiHeaders.forEach { (k, v) -> header(k, v) } }
                        .build()

                    val initRaw = client.newCall(initReq).execute().use { it.body?.string().orEmpty() }
                    val initObj = runCatching { JSONObject(initRaw) }.getOrNull() ?: return@withContext
                    code = initObj.optString("c").trim()
                    embedToken = initObj.optString("t").trim()
                    pageKey = initObj.optString("k").trim()
                }

                if (code.isEmpty() || pageKey.isEmpty()) return@withContext
                if (embedToken.isNotEmpty()) {
                    apiHeaders["X-Embed-Token"] = embedToken
                }

                val srcPayload = JSONObject().put("code", code).toString()
                val srcReq = Request.Builder()
                    .url("$origin/api/sources.php")
                    .post(srcPayload.toRequestBody("application/json".toMediaType()))
                    .apply { apiHeaders.forEach { (k, v) -> header(k, v) } }
                    .build()

                val srcRaw = client.newCall(srcReq).execute().use { it.body?.string().orEmpty() }
                val qualitiesObj = JSONObject(srcRaw).optJSONObject("qualities") ?: return@withContext

                for (qKey in listOf("4k", "fhd", "hd", "sd")) {
                    val qGroup = qualitiesObj.optJSONObject(qKey) ?: continue
                    val servers = qGroup.optJSONArray("servers") ?: continue
                    val qVal = getQualityFromName(qKey).takeIf { it > 0 } ?: Qualities.P1080.value

                    for (i in 0 until servers.length()) {
                        val srv = servers.optJSONObject(i) ?: continue
                        val srvName = srv.optString("name", "Server").trim()
                        val srvToken = srv.optString("token").trim()
                        if (srvToken.isEmpty()) continue

                        val apiPayload = JSONObject()
                            .put("code", code)
                            .put("token", srvToken)
                            .put("key", pageKey)
                            .toString()

                        val aReq = Request.Builder()
                            .url("$origin/api/api.php")
                            .post(apiPayload.toRequestBody("application/json".toMediaType()))
                            .apply { apiHeaders.forEach { (k, v) -> header(k, v) } }
                            .build()

                        val aRaw = client.newCall(aReq).execute().use { it.body?.string().orEmpty() }
                        val aObj = runCatching { JSONObject(aRaw) }.getOrNull() ?: continue
                        val encD = aObj.optString("d").trim()
                        if (encD.isEmpty()) continue

                        val subUrl = decryptAesGcm(encD, pageKey)?.takeIf { it.startsWith("http") } ?: continue
                        Log.d(TAG, "🟢 [YonaPlay] تم فك تشفير سيرفر: [$srvName] - الجودة: [${qKey.uppercase()}]")
                        Log.d(TAG, "🔗 الرابط المفكوك: $subUrl")
                        val sourceLabel = "${this@YonaPlayExtractor.name} ($srvName - ${qKey.uppercase()})"

                        if (subUrl.contains("dotplay.", ignoreCase = true)) {
                            resolveDotPlay(
                                client = client,
                                dotPlayUrl = subUrl,
                                referer = "$origin/",
                                label = sourceLabel,
                                quality = qVal,
                                subtitleCallback = subtitleCallback,
                                callback = callback
                            )
                        } else {
                            if (subUrl.contains(".m3u8", ignoreCase = true) || subUrl.contains(".mp4", ignoreCase = true)) {
                                val isHls = subUrl.contains(".m3u8", ignoreCase = true)
                                callback.invoke(
                                    newExtractorLink(
                                        source = this@YonaPlayExtractor.name,
                                        name = sourceLabel,
                                        url = subUrl,
                                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$origin/"
                                        this.quality = qVal
                                    }
                                )
                            }
                            Log.e(TAG, "🔄 جاري تمرير الرابط إلى loadExtractor: $subUrl")
                            loadExtractor(subUrl, "$origin/", subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "YonaPlay getUrl error: ${e.message}")
            }
        }
    }
}
open class VideasExtractor : ExtractorApi() {
    override val name: String = "Videas"
    override val mainUrl: String = "https://app.videas.com"
    override val requiresReferer: Boolean = false

    companion object {
        private const val TAG = "VIDEAS_EXTRACTOR"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "▶️ getUrl start for: $url")
        withContext(Dispatchers.IO) {
            try {
                val origin = if (url.contains("videas.fr")) "https://app.videas.fr" else "https://app.videas.com"
                val normalizedUrl = url.replace("/embed/media/", "/embed/")
                val pageReq = Request.Builder()
                    .url(normalizedUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", referer ?: "https://witanime.site/")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .build()

                val pageHtml = app.baseClient.newCall(pageReq).execute().use { it.body?.string().orEmpty() }
                val doc = Jsoup.parse(pageHtml, normalizedUrl)

                var embedId = ""
                var opening = ""

                val cfgRaw = doc.selectFirst("script#videas-embed-config")?.data().orEmpty()
                if (cfgRaw.isNotBlank()) {
                    runCatching { JSONObject(cfgRaw) }.getOrNull()?.let { cfg ->
                        embedId = cfg.optString("embedId").trim()
                        opening = cfg.optString("opening").trim()
                    }
                }

                if (embedId.isEmpty()) {
                    embedId = Regex("""/embed/(?:media/)?([0-9a-fA-F-]{20,})""").find(url)?.groupValues?.get(1).orEmpty()
                }

                if (embedId.isEmpty()) {
                    Log.w(TAG, "لم يتم العثور على embedId في Videas")
                    return@withContext
                }
                val viewerId = UUID.randomUUID().toString()
                val visReqBuilder = Request.Builder()
                    .url("$origin/api/visitor/embed/$embedId/?viewer_id=$viewerId")
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Origin", origin)
                    .header("Referer", normalizedUrl)

                if (opening.isNotEmpty()) {
                    visReqBuilder.header("X-Embed-Opening", opening)
                }

                val visRaw = app.baseClient.newCall(visReqBuilder.build()).execute().use { it.body?.string().orEmpty() }
                val visObj = runCatching { JSONObject(visRaw) }.getOrNull() ?: return@withContext

                val playbackToken = visObj.optString("playback_token").trim()
                val sessionId = visObj.optString("session_id").trim()
                val items = visObj.optJSONArray("items") ?: JSONArray()
                for (idx in 0 until items.length()) {
                    val itemObj = items.optJSONObject(idx) ?: continue
                    val assetObj = itemObj.optJSONObject("asset") ?: continue
                    val assetUid = assetObj.optString("uid").trim()
                    if (assetUid.isEmpty()) continue
                    assetObj.optJSONArray("subtitles")?.let { subs ->
                        for (s in 0 until subs.length()) {
                            val sub = subs.optJSONObject(s) ?: continue
                            val subUrl = sub.optString("url").trim()
                            val subLang = sub.optString("label", sub.optString("language", "Arabic")).trim()
                            if (subUrl.isNotEmpty()) {
                                val fullSubUrl = if (subUrl.startsWith("http")) subUrl else "$origin$subUrl"
                                subtitleCallback(newSubtitleFile(subLang, fullSubUrl))
                            }
                        }
                    }
                    val reqJson = JSONObject()
                        .put("asset_id", assetUid)
                        .put("playback_token", playbackToken)
                        .put("session_id", sessionId)
                        .toString()

                    val pbReq = Request.Builder()
                        .url("$origin/api/visitor/playback/info/")
                        .post(reqJson.toRequestBody("application/json".toMediaType()))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .header("Origin", origin)
                        .header("Referer", normalizedUrl)
                        .build()

                    val pbRaw = app.baseClient.newCall(pbReq).execute().use { it.body?.string().orEmpty() }
                    val pbObj = runCatching { JSONObject(pbRaw) }.getOrNull() ?: continue

                    val hlsRaw = pbObj.optJSONObject("hls_url")?.optString("url")
                        ?: pbObj.optString("hls_url").takeIf { it.startsWith("/") || it.startsWith("http") }
                    if (!hlsRaw.isNullOrBlank()) {
                        val fullHls = if (hlsRaw.startsWith("http")) hlsRaw else "$origin$hlsRaw"
                        callback.invoke(
                            newExtractorLink(
                                source = this@VideasExtractor.name,
                                name = "${this@VideasExtractor.name} (HLS)",
                                url = fullHls,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$origin/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                    val mp4Raw = pbObj.optJSONObject("source_url")?.optString("url")
                        ?: pbObj.optString("source_url").takeIf { it.startsWith("/") || it.startsWith("http") }

                    if (!mp4Raw.isNullOrBlank()) {
                        val fullMp4 = if (mp4Raw.startsWith("http")) mp4Raw else "$origin$mp4Raw"
                        callback.invoke(
                            newExtractorLink(
                                source = this@VideasExtractor.name,
                                name = "${this@VideasExtractor.name} (MP4)",
                                url = fullMp4,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$origin/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Videas getUrl error: ${e.message}")
            }
        }
    }
}
class VideasFrExtractor : VideasExtractor() {
    override val name: String = "VideasFr"
    override val mainUrl: String = "https://app.videas.fr"
}
