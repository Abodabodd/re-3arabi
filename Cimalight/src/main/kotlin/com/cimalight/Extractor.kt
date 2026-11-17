package com.cimalight

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.random.Random

object ArabicExtractorUtils {
    const val DEFAULT_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

    val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    fun parseQuality(hint: String?, fallback: String = ""): Int {
        val combined = "${hint.orEmpty()} $fallback".uppercase()
        return when {
            combined.contains("2160") || combined.contains("4K") || combined.contains("UHD") -> Qualities.P2160.value
            combined.contains("1440") || combined.contains("2K") -> Qualities.P1440.value
            combined.contains("1080") || combined.contains("FHD") -> Qualities.P1080.value
            combined.contains("720") || combined.contains("HD") -> Qualities.P720.value
            combined.contains("480") || combined.contains("SD") -> Qualities.P480.value
            combined.contains("360") -> Qualities.P360.value
            combined.contains("240") -> Qualities.P240.value
            else -> getQualityFromName(hint.orEmpty()).takeIf { it > 0 } ?: Qualities.Unknown.value
        }
    }

    fun originOf(url: String): String? = runCatching {
        val u = URI(url)
        if (u.host.isNullOrBlank()) null else "${u.scheme}://${u.host}"
    }.getOrNull()

    fun unpackScripts(html: String): String {
        val sb = StringBuilder(html)
        val evalRegex = Regex("""eval\(function\(p,a,c,k,e,[dr]\).*?\)\)\s*;?""", RegexOption.DOT_MATCHES_ALL)
        for (m in evalRegex.findAll(html)) {
            runCatching { JsUnpacker(m.value).unpack() }.getOrNull()?.let { unpacked ->
                sb.append('\n').append(unpacked)
            }
        }
        return sb.toString()
    }

    suspend fun emitHls(
        source: String,
        name: String,
        url: String,
        referer: String,
        headers: Map<String, String> = mapOf("User-Agent" to DEFAULT_UA, "Referer" to referer),
        quality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit
    ) {
        val generated = runCatching {
            M3u8Helper.generateM3u8(
                source = source,
                streamUrl = url,
                referer = referer,
                quality = quality.takeIf { it > 0 },
                headers = headers
            )
        }.getOrDefault(emptyList())

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
        } else {
            callback(
                newExtractorLink(source, name, url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = quality
                    this.headers = headers
                }
            )
        }
    }

    fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim { it <= ' ' || it.code < 0x20 }
    }.getOrNull()

    fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, cipherBytes: ByteArray): String? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(cipherBytes), Charsets.UTF_8).trim()
    }.getOrNull()

    fun b64UrlDecode(s: String): ByteArray {
        var norm = s.trim().replace('-', '+').replace('_', '/')
        val rem = norm.length % 4
        if (rem != 0) norm += "=".repeat(4 - rem)
        return Base64.decode(norm, Base64.DEFAULT)
    }
}

open class ArabicVidstackExtractor(
    override val name: String = "UPNShare",
    override val mainUrl: String = "https://upns.one"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoId = url.substringAfterLast('#', "")
            .substringAfterLast('/')
            .substringBefore('&')
            .substringBefore('?')
            .trim()
            .ifEmpty {
                url.substringBefore('?').substringBefore('#').trimEnd('/')
                    .substringAfterLast('/')
                    .takeIf { it.isNotBlank() && !it.contains('.') }
                    .orEmpty()
            }
        if (videoId.isBlank()) return

        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        val originFromUrl = ArabicExtractorUtils.originOf(url) ?: "https://$host"

        val candidateOrigins = buildList {
            add(originFromUrl)
            if (host.contains("rpmplay")) add("https://megamax.rpmplay.me")
            if (host.contains("uns.bio")) add("https://megamax.uns.bio")
            if (host.contains("strp2p")) add("https://megamax.strp2p.site")
            if (host.contains("upns.one")) add("https://tuktuk.upns.one")
            add(mainUrl)
        }.distinct()

        for (origin in candidateOrigins) {
            val reqHeaders = mapOf(
                "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                "Referer" to "$origin/",
                "Origin" to origin,
                "Accept" to "*/*"
            )

            val rawText = runCatching {
                app.get("$origin/api/v1/video?id=$videoId", headers = reqHeaders).text
            }.getOrNull() ?: continue

            val cleanHex = rawText.trim().trim('"').replace(Regex("""\s+"""), "")
            if (cleanHex.length < 32 || cleanHex.length % 2 != 0 || cleanHex.startsWith("{")) continue

            val cipherBytes = ByteArray(cleanHex.length / 2) { idx ->
                cleanHex.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
            }

            val decrypted = ArabicExtractorUtils.aesCbcDecrypt(KEY, IV, cipherBytes) ?: continue
            val json = runCatching { JSONObject(decrypted) }.getOrNull() ?: continue

            val streamUrls = LinkedHashSet<String>()

            fun collectFromNode(key: String) {
                val node = json.opt(key) ?: return
                when (node) {
                    is String -> {
                        val v = node.trim()
                        if (v.startsWith("http")) streamUrls.add(v)
                        else if (v.startsWith("//")) streamUrls.add("https:$v")
                        else if (v.startsWith("/")) streamUrls.add("$origin$v")
                    }
                    is JSONArray -> {
                        for (i in 0 until node.length()) {
                            val item = node.opt(i)
                            if (item is String && item.startsWith("http")) streamUrls.add(item)
                            else if (item is JSONObject) {
                                val f = item.optString("file").ifBlank { item.optString("url") }
                                if (f.startsWith("http")) streamUrls.add(f)
                            }
                        }
                    }
                    is JSONObject -> {
                        val keys = node.keys()
                        while (keys.hasNext()) {
                            val v = node.optString(keys.next())
                            if (v.startsWith("http")) streamUrls.add(v)
                        }
                    }
                }
            }

            collectFromNode("cfNative")
            collectFromNode("source")
            collectFromNode("cf")
            collectFromNode("streams")

            if (streamUrls.isNotEmpty()) {
                val streamHeaders = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Referer" to "$origin/",
                    "Origin" to origin
                )
                for (hls in streamUrls) {
                    ArabicExtractorUtils.emitHls(
                        source = name,
                        name = name,
                        url = hls,
                        referer = "$origin/",
                        headers = streamHeaders,
                        callback = callback
                    )
                }
                return
            }
        }
    }

    companion object {
        private val KEY = "kiemtienmua911ca".toByteArray(Charsets.UTF_8)
        private val IV = "1234567890oiuytr".toByteArray(Charsets.UTF_8)
    }
}

class UpnShareExtractor : ArabicVidstackExtractor("UPNShare", "https://upns.one")
class UpnShareTukTukExtractor : ArabicVidstackExtractor("UPNShare", "https://tuktuk.upns.one")
class UpnShareBioExtractor : ArabicVidstackExtractor("UPNShare", "https://uns.bio")
class UpnShareMegaMaxExtractor : ArabicVidstackExtractor("UPNShare", "https://megamax.uns.bio")
class StreamP2PExtractor : ArabicVidstackExtractor("StreamP2P", "https://strp2p.site")
class StreamP2PMegaMaxExtractor : ArabicVidstackExtractor("StreamP2P", "https://megamax.strp2p.site")
class P2PStreamVipExtractor : ArabicVidstackExtractor("StreamP2P", "https://p2pstream.vip")
class RpmShareExtractor : ArabicVidstackExtractor("RPMShare", "https://rpmplay.me")
class RpmShareMegaMaxExtractor : ArabicVidstackExtractor("RPMShare", "https://megamax.rpmplay.me")

class TukTukVipExtractor : ExtractorApi() {
    override var name = "TukTukVIP"
    override var mainUrl = "https://lolololu.website"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val selfReferer = try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}/"
        } catch (_: Exception) {
            "https://lolololu.website/"
        }
        val originUrl = selfReferer.removeSuffix("/")
        val response = runCatching {
            app.get(url, referer = selfReferer).text
        }.getOrNull() ?: return
        val unpackedHtml = JsUnpacker(response).unpack() ?: response
        val regex = Regex("""https://[^"']+?/hls\d+/[^"']+?/master\.(?:txt|m3u8)(?:\?[^"']*)?""")
        val matches = regex.findAll(unpackedHtml).toList()
        if (matches.isEmpty()) return
        val playHeaders = mapOf(
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?1",
            "accept" to "*/*",
            "origin" to originUrl,
            "sec-fetch-site" to "cross-site",
            "sec-fetch-mode" to "cors",
            "sec-fetch-dest" to "empty"
        )

        matches.forEach { match ->
            val link = match.value
            val isM3u8 = link.contains(".m3u8") || link.contains(".txt")

            callback(
                newExtractorLink(
                    source = "TukTukVIP",
                    name = "TukTukVIP",
                    url = link,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = selfReferer
                    this.quality = Qualities.P1080.value
                    this.headers = playHeaders
                }
            )
        }
    }
}

open class ArabicMegaMaxExtractor(
    override val name: String = "MegaMax",
    override val mainUrl: String = "https://megamax.me"
) : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val hashId = Regex("""/(?:iframe|d|download|e|f|mirror)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val refUrl = referer ?: "$origin/"
        extractLeechHls(hashId, origin, refUrl, subtitleCallback, callback)
        extractLeechDirectMp4(hashId, origin, refUrl, callback)
    }

    private suspend fun extractLeechHls(
        hashId: String,
        origin: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageRes = runCatching {
            app.get("$origin/e/$hashId", referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val doc = pageRes.document
        val rawPage = doc.selectFirst("script[data-page=app]")?.html()?.trim()
            ?: doc.selectFirst("#app[data-page]")?.attr("data-page")?.let { Parser.unescapeEntities(it, false) }
            ?: return
        val pageJson = runCatching { JSONObject(rawPage) }.getOrNull() ?: return
        val version = pageJson.optString("version")

        val partialRes = runCatching {
            app.get(
                url = "$origin/e/$hashId",
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "text/html, application/xhtml+xml",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-Inertia" to "true",
                    "X-Inertia-Version" to version,
                    "X-Inertia-Partial-Component" to "files/leech/video",
                    "X-Inertia-Partial-Data" to "streams,subtitles",
                    "Referer" to "$origin/e/$hashId"
                )
            ).text
        }.getOrNull() ?: return

        val props = runCatching { JSONObject(partialRes).optJSONObject("props") }.getOrNull() ?: return
        props.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val file = s.optString("file").takeIf { it.startsWith("http") } ?: continue
                val lang = s.optString("label").ifBlank { "Arabic" }
                subtitleCallback(newSubtitleFile(lang, file))
            }
        }

        val dataRaw = props.optJSONObject("streams")?.optString("data").orEmpty()
        if (dataRaw.isBlank()) return
        val plain = (if (dataRaw.contains("\"ct\"")) {
            decryptCryptoJs(dataRaw, LEECH_KEY)
        } else {
            dataRaw
        })?.trim() ?: return

        val streamHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        val sourcesArr = runCatching {
            if (plain.startsWith("[")) JSONArray(plain) else JSONArray().put(JSONObject(plain))
        }.getOrNull() ?: return

        for (i in 0 until sourcesArr.length()) {
            val item = sourcesArr.optJSONObject(i) ?: continue
            val fileUrl = item.optString("file").takeIf { it.startsWith("http") } ?: continue
            val label = item.optString("label")
            val q = ArabicExtractorUtils.parseQuality(label, fileUrl)

            if (fileUrl.contains(".m3u8") || item.optString("type").contains("mpegURL", ignoreCase = true)) {
                ArabicExtractorUtils.emitHls(name, "$name ($label)", fileUrl, "$origin/", streamHeaders, q, callback)
            } else {
                callback(
                    newExtractorLink(name, "$name ($label) [MP4]", fileUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = q
                        this.headers = streamHeaders
                    }
                )
            }
        }
    }

    private suspend fun extractLeechDirectMp4(
        hashId: String,
        origin: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val res = runCatching {
            app.get("$origin/d/$hashId", referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val doc = res.document
        val rawPage = doc.selectFirst("#app[data-page]")?.attr("data-page")?.let { Parser.unescapeEntities(it, false) } ?: return
        val pageJson = runCatching { JSONObject(rawPage) }.getOrNull() ?: return
        val attachments = pageJson.optJSONObject("props")?.optJSONArray("attachments") ?: return
        val version = pageJson.optString("version")

        for (i in 0 until attachments.length()) {
            val att = attachments.optJSONObject(i) ?: continue
            val attachId = att.optString("hashId").takeIf { it.isNotBlank() } ?: continue
            val label = att.optString("label").ifBlank { att.optString("resolution") }
            val q = ArabicExtractorUtils.parseQuality(label)

            val body = JSONObject().put("attach", attachId).toString().toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
            val postRes = runCatching {
                app.post(
                    url = "$origin/d/$hashId",
                    headers = mapOf(
                        "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                        "Accept" to "text/html, application/xhtml+xml",
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "XMLHttpRequest",
                        "X-Inertia" to "true",
                        "X-Inertia-Version" to version,
                        "X-Inertia-Partial-Component" to "files/leech/download",
                        "X-Inertia-Partial-Data" to "download",
                        "Origin" to origin,
                        "Referer" to "$origin/d/$hashId"
                    ),
                    requestBody = body
                ).text
            }.getOrNull() ?: continue

            val directUrl = runCatching {
                JSONObject(postRes).optJSONObject("props")?.optJSONObject("download")?.optJSONObject("data")?.optString("url")
            }.getOrNull()?.takeIf { it.startsWith("http") } ?: continue

            callback(
                newExtractorLink(name, "$name Direct ($label) [MP4]", directUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.quality = q
                    this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)
                }
            )
        }
    }

    private fun decryptCryptoJs(jsonStr: String, passphrase: String): String? = runCatching {
        val obj = JSONObject(jsonStr)
        val ct = Base64.decode(obj.getString("ct"), Base64.DEFAULT)
        val iv = hexToBytes(obj.getString("iv"))
        val salt = hexToBytes(obj.getString("s"))
        val passBytes = passphrase.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var block = ByteArray(0)
        while (derived.size < 48) {
            md5.reset()
            md5.update(block)
            md5.update(passBytes)
            md5.update(salt)
            block = md5.digest()
            derived += block
        }
        val key = derived.copyOfRange(0, 32)
        ArabicExtractorUtils.aesCbcDecrypt(key, iv, ct)
    }.getOrNull()

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        return ByteArray(clean.length / 2) { idx -> clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
    }

    companion object {
        private const val LEECH_KEY = "k7v2m4x9bqwpnj6t"
    }
}

class Share4MaxExtractor : ArabicMegaMaxExtractor("Share4Max", "https://share4max.com")

open class ArabicByseExtractor(
    override val name: String = "Byse",
    override val mainUrl: String = "https://byseqekaho.com"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val code = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?').substringBefore('.')
        if (code.isBlank()) return

        val embedReferer = "$origin/e/$code"
        val apiUrl = "$origin/api/videos/$code"
        val body = runCatching {
            app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "application/json",
                    "Referer" to (referer ?: embedReferer)
                )
            ).text
        }.getOrNull() ?: return

        val playback = runCatching { JSONObject(body).optJSONObject("playback") }.getOrNull() ?: return
        val keyParts = playback.optJSONArray("key_parts") ?: return
        val version = playback.optInt("version", 1)
        val iv = ArabicExtractorUtils.b64UrlDecode(playback.optString("iv"))
        val payload = ArabicExtractorUtils.b64UrlDecode(playback.optString("payload"))
        val total = keyParts.length()
        val candidateKeys = ArrayList<ByteArray>()
        if (version in 1..20 && version <= total && (31 - version) in 1..total) {
            candidateKeys.add(
                ArabicExtractorUtils.b64UrlDecode(keyParts.optString(version - 1)) +
                    ArabicExtractorUtils.b64UrlDecode(keyParts.optString(30 - version))
            )
        }
        if (total >= 2) {
            candidateKeys.add(
                ArabicExtractorUtils.b64UrlDecode(keyParts.optString(0)) +
                    ArabicExtractorUtils.b64UrlDecode(keyParts.optString(1))
            )
        }

        var decryptedJson: String? = null
        for (k in candidateKeys) {
            val dec = ArabicExtractorUtils.aesGcmDecrypt(k, iv, payload)
            if (dec != null && dec.contains("sources")) {
                decryptedJson = dec
                break
            }
        }
        if (decryptedJson == null) return

        val decObj = runCatching { JSONObject(decryptedJson) }.getOrNull() ?: return
        val sources = decObj.optJSONArray("sources") ?: return
        val streamHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            val file = s.optString("url").ifBlank { s.optString("file") }.trim()
            if (!file.startsWith("http")) continue
            val label = s.optString("label").ifBlank { s.optString("quality") }
            val q = ArabicExtractorUtils.parseQuality(label, "${s.optInt("height", 0)}p")

            if (file.contains(".m3u8")) {
                ArabicExtractorUtils.emitHls(name, "$name ($label)", file, "$origin/", streamHeaders, q, callback)
            } else {
                callback(
                    newExtractorLink(name, "$name ($label) [MP4]", file, ExtractorLinkType.VIDEO) {
                        this.referer = "$origin/"
                        this.quality = q
                        this.headers = streamHeaders
                    }
                )
            }
        }
    }
}

class ByseSxExtractor : ArabicByseExtractor("Byse", "https://byse.sx")
class ByseQekahoExtractor : ArabicByseExtractor("ByseQekaho", "https://byseqekaho.com")
class ByseRaguciExtractor : ArabicByseExtractor("ByseRaguci", "https://byseraguci.com")
class ByseZejataosExtractor : ArabicByseExtractor("ByseZejataos", "https://bysezejataos.com")

class ArabicFireStreamExtractor : ExtractorApi() {
    override val name = "FireStream"
    override val mainUrl = "https://firestream.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val slug = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) return

        val initialOrigin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$initialOrigin/e/$slug"
        val res = runCatching {
            app.get(embedUrl, referer = referer ?: "$initialOrigin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val finalUrl = res.url.ifBlank { embedUrl }
        val finalOrigin = ArabicExtractorUtils.originOf(finalUrl) ?: "https://firestream.site"
        val doc = res.document
        doc.selectFirst("script#video-data")?.data()?.let { raw ->
            runCatching {
                val subs = JSONObject(raw).optJSONObject("video")?.optJSONArray("subtitles")
                if (subs != null) {
                    for (i in 0 until subs.length()) {
                        val s = subs.optJSONObject(i) ?: continue
                        val subUrl = s.optString("url").takeIf { it.startsWith("http") } ?: continue
                        val lang = s.optString("label").ifBlank { "Arabic" }
                        subtitleCallback(newSubtitleFile(lang, subUrl))
                    }
                }
            }
        }

        val tokenBlob = doc.selectFirst("script#token-blob")?.data()?.trim().takeIf { !it.isNullOrBlank() } ?: return
        val postBody = JSONObject().put("blob", tokenBlob).toString().toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)

        val resolveRes = runCatching {
            app.post(
                url = "$finalOrigin/api/videos/$slug/resolve",
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "Referer" to finalUrl,
                    "Origin" to finalOrigin
                ),
                requestBody = postBody
            ).text
        }.getOrNull() ?: return

        val resolveJson = runCatching { JSONObject(resolveRes) }.getOrNull() ?: return
        val primary = resolveJson.optString("signedVideoUrl").takeIf { it.startsWith("http") }
        val sd = resolveJson.optString("signedVideoSdUrl").takeIf { it.startsWith("http") }
        val headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$finalOrigin/")

        listOf(primary to Qualities.P1080.value, sd to Qualities.P480.value).forEach { (mUrl, defQ) ->
            if (mUrl != null) {
                if (mUrl.contains(".m3u8")) {
                    ArabicExtractorUtils.emitHls(name, name, mUrl, "$finalOrigin/", headers, defQ, callback)
                } else {
                    callback(
                        newExtractorLink(name, "$name [MP4]", mUrl, ExtractorLinkType.VIDEO) {
                            this.referer = "$finalOrigin/"
                            this.quality = defQ
                            this.headers = headers
                        }
                    )
                }
            }
        }
    }
}

class ArabicVidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidaraa.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val filecode = Regex("""/(?:e|d|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (filecode.isBlank()) return

        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val payload = JSONObject()
            .put("filecode", filecode)
            .put("device", "web")
            .put("codecs", JSONArray())
            .toString()
            .toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)

        val resText = runCatching {
            app.post(
                url = "$origin/api/stream",
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "application/json",
                    "Referer" to "$origin/e/$filecode",
                    "Origin" to origin
                ),
                requestBody = payload
            ).text
        }.getOrNull() ?: return

        val json = runCatching { JSONObject(resText) }.getOrNull() ?: return
        val streamUrl = json.optString("streaming_url").takeIf { it.startsWith("http") } ?: return
        val q = ArabicExtractorUtils.parseQuality(json.optString("title"))

        json.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val subUrl = s.optString("file_path").takeIf { it.startsWith("http") } ?: continue
                val lang = s.optString("label").ifBlank { "Arabic" }
                subtitleCallback(newSubtitleFile(lang, subUrl))
            }
        }

        val headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$origin/")
        if (streamUrl.contains(".m3u8")) {
            ArabicExtractorUtils.emitHls(name, name, streamUrl, "$origin/", headers, q, callback)
        } else {
            callback(
                newExtractorLink(name, "$name [MP4]", streamUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.quality = q
                    this.headers = headers
                }
            )
        }
    }
}

class ArabicYonaPlayExtractor : ExtractorApi() {
    override val name = "YonaPlay"
    override val mainUrl = "https://mid.yonaplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val pageHtml = runCatching {
            app.get(url, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        var code = ""
        var embedToken = ""
        var pageKey = ""

        Regex("""window\._E\s*=\s*(\{[^;]+\})""").find(pageHtml)?.groupValues?.get(1)?.let { raw ->
            runCatching { JSONObject(raw) }.getOrNull()?.let { obj ->
                code = obj.optString("c")
                embedToken = obj.optString("t")
                pageKey = obj.optString("k")
            }
        }

        if (code.isEmpty() || pageKey.isEmpty()) return
        val apiHeaders = mutableMapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Origin" to origin,
            "Referer" to url
        )
        if (embedToken.isNotEmpty()) apiHeaders["X-Embed-Token"] = embedToken

        val srcBody = runCatching {
            val body = JSONObject().put("code", code).toString().toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
            app.post("$origin/api/sources.php", headers = apiHeaders, requestBody = body).text
        }.getOrNull() ?: return

        val qualitiesObj = runCatching { JSONObject(srcBody).optJSONObject("qualities") }.getOrNull() ?: return

        for (qKey in listOf("4k", "fhd", "hd", "sd")) {
            val qGroup = qualitiesObj.optJSONObject(qKey) ?: continue
            val servers = qGroup.optJSONArray("servers") ?: continue

            for (i in 0 until servers.length()) {
                val srv = servers.optJSONObject(i) ?: continue
                val token = srv.optString("token").takeIf { it.isNotBlank() } ?: continue
                val srvName = srv.optString("name", "Server")

                val reqJson = JSONObject().put("code", code).put("token", token).put("key", pageKey).toString()
                    .toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
                val aRes = runCatching {
                    app.post("$origin/api/api.php", headers = apiHeaders, requestBody = reqJson).text
                }.getOrNull() ?: continue

                val encD = runCatching { JSONObject(aRes).optString("d") }.getOrNull() ?: continue
                val subUrl = decryptYonaPlay(encD, pageKey) ?: continue

                if (subUrl.contains("dotplay.")) {
                    extractDotPlay(subUrl, "$origin/", "$name ($srvName)", ArabicExtractorUtils.parseQuality(qKey), callback)
                }
            }
        }
    }

    private fun decryptYonaPlay(b64Data: String, pageKey: String): String? = runCatching {
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(pageKey.toByteArray(Charsets.UTF_8))
        val raw = Base64.decode(b64Data.trim(), Base64.DEFAULT)
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(12, 28)
        val ciphertext = raw.copyOfRange(28, raw.size)
        val ctWithTag = ByteArray(ciphertext.size + tag.size)
        System.arraycopy(ciphertext, 0, ctWithTag, 0, ciphertext.size)
        System.arraycopy(tag, 0, ctWithTag, ciphertext.size, tag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ctWithTag), Charsets.UTF_8).trim()
    }.getOrNull()

    private suspend fun extractDotPlay(
        dotUrl: String,
        referer: String,
        label: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(dotUrl) ?: "https://dotplay.net"
        val html = runCatching {
            app.get(dotUrl, referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val code = Regex("""window\.embedCode\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        val apiJson = runCatching {
            app.get("$origin/api.php?code=$code", referer = dotUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val encoded = runCatching { JSONObject(apiJson).optString("video_url") }.getOrNull() ?: return
        val decoded = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8).substringBefore('|').trim()
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: return

        val isHls = decoded.contains(".m3u8")
        callback(
            newExtractorLink(label, label, decoded, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = quality
            }
        )
    }
}

class DotPlayExtractor : ExtractorApi() {
    override val name = "DotPlay"
    override val mainUrl = "https://dotplay.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        ArabicYonaPlayExtractor().getUrl(url, referer, subtitleCallback, callback)
    }
}

open class ArabicStreamWishExtractor(
    override val name: String = "StreamWish",
    override val mainUrl: String = "https://streamwish.fun"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val pageHtml = runCatching {
            app.get(url, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(pageHtml)
        val hlsCandidates = LinkedHashSet<String>()
        Regex("""var\s+links\s*=\s*(\{[^;]+\})""").find(combined)?.groupValues?.get(1)?.let { rawJson ->
            runCatching {
                val obj = JSONObject(rawJson)
                for (k in listOf("hls4", "hls2", "hls3", "hls", "file")) {
                    val link = obj.optString(k).trim().replace("\\/", "/")
                    if (link.isNotBlank()) resolveUrl(link, origin)?.let { hlsCandidates.add(it) }
                }
            }
        }
        if (hlsCandidates.isEmpty()) {
            Regex("""["'](?:hls4|hls2|hls3|hls|file)["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(combined).forEach { m ->
                resolveUrl(m.groupValues[1].replace("\\/", "/"), origin)?.let { hlsCandidates.add(it) }
            }
        }
        if (hlsCandidates.isEmpty()) {
            Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(combined.replace("\\/", "/")).forEach { m ->
                hlsCandidates.add(m.value)
            }
        }

        val streamHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        for (hls in hlsCandidates) {
            ArabicExtractorUtils.emitHls(name, name, hls, "$origin/", streamHeaders, Qualities.Unknown.value, callback)
            return
        }
    }

    private fun resolveUrl(raw: String, origin: String): String? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$origin$clean"
            else -> "$origin/$clean"
        }
    }
}

class VidHideExtractor : ArabicStreamWishExtractor("VidHide", "https://vidhidepro.com")
class EarnVidsExtractor : ArabicStreamWishExtractor("EarnVids", "https://morencius.com")
class StreamHgExtractor : ArabicStreamWishExtractor("StreamHG", "https://vibuxer.com")
class HanerixExtractor : ArabicStreamWishExtractor("Hanerix", "https://hanerix.com")
class FilelionsExtractor : ArabicStreamWishExtractor("Filelions", "https://filelions.to")

open class ArabicPackerExtractor(
    override val name: String = "LuluStream",
    override val mainUrl: String = "https://lulustream.com"
) : ExtractorApi() {
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val html = runCatching {
            app.get(url, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(html)
        val m3u8 = Regex("""https?://[^"'\\\s]+/hls\d*/[^"'\\\s]+/master\.(?:txt|m3u8)[^"'\\\s]*""").find(combined)?.value
            ?: Regex("""https?://[^"'\\\s]+\.m3u8[^"'\\\s]*""").find(combined)?.value
            ?: Regex("""(?:file|src)\s*[:=]\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(combined)?.groupValues?.get(1)
            ?: return

        val headers = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        if (m3u8.contains(".m3u8") || m3u8.contains(".txt")) {
            ArabicExtractorUtils.emitHls(name, name, m3u8, "$origin/", headers, Qualities.Unknown.value, callback)
        } else {
            callback(
                newExtractorLink(name, "$name [MP4]", m3u8, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.quality = Qualities.P720.value
                    this.headers = headers
                }
            )
        }
    }
}

class VideolandExtractor : ArabicPackerExtractor("Videoland", "https://videoland.sbs")
class UpDownExtractor : ArabicPackerExtractor("UpDown", "https://updown.icu")
class OkStreamExtractor : ArabicPackerExtractor("OkStream", "https://okstream.online")

class LuluStreamExtractor : ExtractorApi() {
    override var name = "LuluStream"
    override var mainUrl = "https://luluvdo.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "$origin/"

        val html = runCatching {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
            ).text
        }.getOrNull() ?: return
        val unpacked = (JsUnpacker(html).unpack() ?: html)
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
        val regex = Regex("""(?i)["']((?:https?:)?//[^\s"'<>\\]+?\.(?:m3u8|mp4|txt)[^\s"'<>\\]*|/(?:[^"'<>\s\\]+?/)+master\.(?:txt|m3u8)[^"'<>\s\\]*)["']""")
        val rawLink = regex.find(unpacked)?.groupValues?.get(1) ?: return
        val fullUrl = when {
            rawLink.startsWith("//") -> "https:$rawLink"
            rawLink.startsWith("http") -> rawLink
            else -> runCatching { URI(url).resolve(rawLink).toString() }.getOrNull() ?: "$origin$rawLink"
        }

        val isHls = fullUrl.contains(".m3u8") || fullUrl.contains(".txt")
        val headers = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Referer" to pageReferer,
            "Origin" to origin
        )
        if (isHls) {
            ArabicExtractorUtils.emitHls(name, name, fullUrl, pageReferer, headers, Qualities.Unknown.value, callback)
        } else {
            callback(
                newExtractorLink(name, "$name [MP4]", fullUrl, ExtractorLinkType.VIDEO) {
                    this.referer = pageReferer
                    this.quality = Qualities.P720.value
                    this.headers = headers
                }
            )
        }
    }
}

open class ArabicDoodExtractor(
    override val name: String = "DoodStream",
    override val mainUrl: String = "https://dsvplay.com"
) : ExtractorApi() {
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/(?:e|d)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$id"

        val res = runCatching {
            app.get(embedUrl, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
        }.getOrNull() ?: return

        val passMd5 = Regex("""/pass_md5/[^'"]+""").find(res.text)?.value ?: return
        val activeOrigin = ArabicExtractorUtils.originOf(res.url) ?: origin

        val md5Base = runCatching {
            app.get(
                url = "$activeOrigin$passMd5",
                referer = res.url,
                headers = mapOf(
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text.trim()
        }.getOrNull() ?: return

        if (!md5Base.startsWith("http")) return
        val token = passMd5.substringAfterLast('/')
        val randomSuffix = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val finalUrl = "$md5Base$randomSuffix?token=$token&expiry=${System.currentTimeMillis()}"

        callback(
            newExtractorLink(name, "$name [MP4]", finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$activeOrigin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$activeOrigin/")
            }
        )
    }
}

class PlaymogoExtractor : ArabicDoodExtractor("Playmogo", "https://playmogo.com")
class MyVidPlayExtractor : ArabicDoodExtractor("MyVidPlay", "https://myvidplay.com")

class ArabicVoeExtractor : ExtractorApi() {
    override val name = "VOE"
    override val mainUrl = "https://voe.sx"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val originFromUrl = ArabicExtractorUtils.originOf(url) ?: mainUrl
        var currentUrl = if (url.contains("/e/")) url else {
            Regex("""voe\.[a-z0-9.-]+/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1)?.let { "$originFromUrl/e/$it" } ?: url
        }

        var html = ""
        repeat(3) {
            val res = runCatching {
                app.get(currentUrl, referer = referer ?: "$originFromUrl/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA))
            }.getOrNull() ?: return

            currentUrl = res.url
            html = res.text

            val redirect = Regex("""window\.location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (redirect != null && redirect != currentUrl) currentUrl = redirect else return@repeat
        }

        val origin = ArabicExtractorUtils.originOf(currentUrl) ?: mainUrl
        val headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$origin/", "Origin" to origin)
        val rawPayload = Regex("""<script[^>]*type=["']application/json["'][^>]*>\s*\[\s*"([^"]+)"\s*\]\s*</script>""").find(html)?.groupValues?.get(1)
        if (!rawPayload.isNullOrBlank()) {
            val decoded = decodeVoe(rawPayload)
            if (decoded != null) {
                val hls = decoded.optString("source").takeIf { it.startsWith("http") }
                val mp4 = decoded.optString("direct_access_url").takeIf { it.startsWith("http") }

                if (hls != null) {
                    ArabicExtractorUtils.emitHls(name, name, hls, "$origin/", headers, Qualities.Unknown.value, callback)
                    return
                }
                if (mp4 != null) {
                    callback(
                        newExtractorLink(name, "$name [MP4]", mp4, ExtractorLinkType.VIDEO) {
                            this.referer = "$origin/"
                            this.quality = Qualities.P720.value
                            this.headers = headers
                        }
                    )
                    return
                }
            }
        }
        Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let { raw ->
            val hls = if (raw.startsWith("http")) raw else runCatching {
                String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
            }.getOrNull()
            if (!hls.isNullOrBlank() && hls.startsWith("http")) {
                ArabicExtractorUtils.emitHls(name, name, hls, "$origin/", headers, Qualities.Unknown.value, callback)
            }
        }
    }

    private fun decodeVoe(input: String): JSONObject? = runCatching {
        val rot = buildString(input.length) {
            for (c in input) {
                when (c) {
                    in 'a'..'z' -> append('a' + (c - 'a' + 13) % 26)
                    in 'A'..'Z' -> append('A' + (c - 'A' + 13) % 26)
                    else -> append(c)
                }
            }
        }
        var cleaned = rot
        for (m in listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&")) cleaned = cleaned.replace(m, "")
        val step1 = String(Base64.decode(cleaned, Base64.DEFAULT), Charsets.ISO_8859_1)
        val shifted = CharArray(step1.length) { i -> (step1[i].code - 3).toChar() }
        val reversed = String(shifted).reversed()
        val jsonStr = String(Base64.decode(reversed, Base64.DEFAULT), Charsets.UTF_8)
        JSONObject(jsonStr)
    }.getOrNull()
}

class ArabicStreamTapeExtractor : ExtractorApi() {
    override val name = "StreamTape"
    override val mainUrl = "https://streamtape.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|v)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val html = runCatching {
            app.get("$origin/e/$code/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val concatMatch = Regex("""getElementById\(['"](?:robot|ideoo)link['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?(?:\.substring\((\d+)\))?""").find(html) ?: return
        val prefix = concatMatch.groupValues[1]
        var tail = concatMatch.groupValues[2]
        concatMatch.groupValues[3].toIntOrNull()?.let { if (it in 0..tail.length) tail = tail.substring(it) }

        var built = prefix + tail
        if (built.startsWith("//")) built = "https:$built"
        val finalUrl = if (built.contains("&stream=")) built else "$built&stream=1"

        callback(
            newExtractorLink(name, "$name [MP4]", finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to "$origin/e/$code/")
            }
        )
    }
}

class ArabicMixdropExtractor : ExtractorApi() {
    override val name = "MixDrop"
    override val mainUrl = "https://mixdrop.ag"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|f)/([A-Za-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedUrl = "$origin/e/$code"
        val html = runCatching {
            app.get(embedUrl, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(html)
        val wurl = Regex("""MDCore\.wurl\s*=\s*["']([^"']+)["']""").find(combined)?.groupValues?.get(1) ?: return
        val videoUrl = when {
            wurl.startsWith("//") -> "https:$wurl"
            wurl.startsWith("http") -> wurl
            else -> "$origin$wurl"
        }

        callback(
            newExtractorLink(name, "$name [MP4]", videoUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)
            }
        )
    }
}

class ArabicMp4UploadExtractor : ExtractorApi() {
    override val name = "Mp4Upload"
    override val mainUrl = "https://www.mp4upload.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val id = Regex("""mp4upload\.[^/]+/(?:embed-)?([A-Za-z0-9]{10,})""").find(url)?.groupValues?.get(1)
        val embedUrl = if (id != null) "$origin/embed-$id.html" else url

        val html = runCatching {
            app.get(embedUrl, referer = referer ?: "$origin/", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val combined = ArabicExtractorUtils.unpackScripts(html)
        val src = Regex("""player\.src\(\s*\{\s*type\s*:\s*["']video/mp4["']\s*,\s*src\s*:\s*["']([^"']+)["']""").find(combined)?.groupValues?.get(1)
            ?: Regex("""https?://[a-z0-9.-]*mp4upload\.[^/]+(?::\d+)?/[^\s"'<>\\]+\.mp4""").find(combined)?.value
            ?: return

        callback(
            newExtractorLink(name, "$name [MP4]", src, ExtractorLinkType.VIDEO) {
                this.referer = embedUrl
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Referer" to embedUrl)
            }
        )
    }
}

class ArabicVideasExtractor : ExtractorApi() {
    override val name = "Videas"
    override val mainUrl = "https://app.videas.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val embedId = Regex("""/embed/(?:media/)?([0-9a-fA-F-]{20,})""").find(url)?.groupValues?.get(1) ?: return

        val visitorHeaders = mapOf(
            "User-Agent" to ArabicExtractorUtils.DEFAULT_UA,
            "Accept" to "application/json",
            "Origin" to origin
        )
        val viewerId = UUID.randomUUID().toString()
        val visRaw = runCatching {
            app.get("$origin/api/visitor/embed/$embedId/?viewer_id=$viewerId", headers = visitorHeaders, referer = url).text
        }.getOrNull() ?: return

        val visObj = runCatching { JSONObject(visRaw) }.getOrNull() ?: return
        val playbackToken = visObj.optString("playback_token")
        val sessionId = visObj.optString("session_id")
        val items = visObj.optJSONArray("items") ?: return

        for (idx in 0 until items.length()) {
            val item = items.optJSONObject(idx) ?: continue
            val asset = item.optJSONObject("asset") ?: continue
            val assetUid = asset.optString("uid")
            asset.optJSONArray("subtitles")?.let { subs ->
                for (s in 0 until subs.length()) {
                    val sub = subs.optJSONObject(s) ?: continue
                    val subUrl = sub.optString("url")
                    val subLang = sub.optString("label", "Arabic")
                    if (subUrl.startsWith("http")) subtitleCallback(newSubtitleFile(subLang, subUrl))
                }
            }

            val reqJson = JSONObject().put("asset_id", assetUid).put("playback_token", playbackToken).put("session_id", sessionId).toString()
            val pbRaw = runCatching {
                app.post(
                    url = "$origin/api/visitor/playback/info/",
                    headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Origin" to origin, "Content-Type" to "application/json"),
                    data = mapOf(),
                    requestBody = reqJson.toRequestBody(ArabicExtractorUtils.JSON_MEDIA_TYPE)
                ).text
            }.getOrNull() ?: continue

            val pbObj = runCatching { JSONObject(pbRaw) }.getOrNull() ?: continue
            val hls = pbObj.optJSONObject("hls_url")?.optString("url") ?: pbObj.optString("hls_url")

            if (hls.isNotBlank()) {
                val fullHls = if (hls.startsWith("http")) hls else "$origin$hls"
                ArabicExtractorUtils.emitHls(name, name, fullHls, "$origin/", mapOf("Origin" to origin), Qualities.P1080.value, callback)
                return
            }
        }
    }
}

class ArabicVideaExtractor : ExtractorApi() {
    override val name = "Videa"
    override val mainUrl = "https://videa.hu"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val videoId = Regex("""[?&]v=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val playerUrl = "$origin/player?v=$videoId"
        val playerHtml = runCatching {
            app.get(playerUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val nonce = Regex("""_xt\s*=\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.get(1) ?: return
        val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
        val paramS = if (nonce.length > 32) nonce.substring(32) else ""

        val unscrambled = buildString(32) {
            for (i in 0 until 32) {
                val ch = paramL[i]
                val idxInSecret = VIDEA_SECRET.indexOf(ch).takeIf { it >= 0 } ?: 0
                val index = i - (idxInSecret - 31)
                val safeIndex = index.coerceIn(0, (paramS.length - 1).coerceAtLeast(0))
                append(if (paramS.isNotEmpty()) paramS[safeIndex] else 'a')
            }
        }

        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val seed = (1..8).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        val paramT = unscrambled.take(16).padEnd(16, '0')
        val rc4KeyPart = if (unscrambled.length > 16) unscrambled.substring(16) else ""

        val xmlEndpoint = "$origin/player/xml?platform=desktop&v=$videoId&_s=$seed&_t=$paramT"
        val xmlRes = runCatching {
            app.get(xmlEndpoint, referer = playerUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA, "Origin" to origin))
        }.getOrNull() ?: return

        val xVideaXs = xmlRes.headers["x-videa-xs"].orEmpty()
        val xmlBody = xmlRes.text
        val xmlString = if (xmlBody.trimStart().startsWith("<")) xmlBody else {
            val decoded = runCatching { Base64.decode(xmlBody.trim(), Base64.DEFAULT) }.getOrNull() ?: return
            rc4Decrypt(decoded, rc4KeyPart + seed + xVideaXs)
        }

        val xmlDoc = Jsoup.parse(xmlString, "", Parser.xmlParser())
        for (src in xmlDoc.select("video_source")) {
            val qName = src.attr("name").trim()
            val rawVideoUrl = src.text().trim()
            if (rawVideoUrl.isEmpty()) continue
            val exp = src.attr("exp").trim()
            val md5 = xmlDoc.getElementsByTag("hash_value_$qName").firstOrNull()?.text()?.trim()
            val base = if (rawVideoUrl.startsWith("//")) "https:$rawVideoUrl" else rawVideoUrl
            val signed = if (!md5.isNullOrEmpty() && exp.isNotEmpty()) "$base?md5=$md5&expires=$exp" else base
            val qVal = ArabicExtractorUtils.parseQuality(qName)

            callback(
                newExtractorLink(name, "$name [$qName]", signed, ExtractorLinkType.VIDEO) {
                    this.referer = playerUrl
                    this.quality = qVal
                }
            )
        }
    }

    private fun rc4Decrypt(data: ByteArray, key: String): String {
        if (key.isEmpty()) return ""
        val s = IntArray(256) { it }
        val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + (keyBytes[i % keyBytes.size].toInt() and 0xFF)) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
        }
        val out = ByteArray(data.size)
        var i = 0; j = 0
        for (idx in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]; s[i] = s[j]; s[j] = tmp
            val k = s[(s[i] + s[j]) and 0xFF]
            out[idx] = ((data[idx].toInt() and 0xFF) xor k).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    companion object {
        private const val VIDEA_SECRET = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"
    }
}

class ArabicOkRuExtractor : ExtractorApi() {
    override val name = "OkRu"
    override val mainUrl = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val embedUrl = url.replace("/video/", "/videoembed/")
        val html = runCatching {
            app.get(embedUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val optionsRaw = Jsoup.parse(html).selectFirst("[data-options]")?.attr("data-options").orEmpty()
        val optsJson = runCatching { JSONObject(optionsRaw) }.getOrNull() ?: return
        val flashvars = optsJson.optJSONObject("flashvars") ?: return
        val meta = flashvars.optJSONObject("metadata") ?: runCatching { JSONObject(flashvars.optString("metadata")) }.getOrNull() ?: return

        val videos = meta.optJSONArray("videos")
        if (videos != null) {
            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val vUrl = v.optString("url").takeIf { it.startsWith("http") } ?: continue
                val vName = v.optString("name").trim()
                val q = ArabicExtractorUtils.parseQuality(vName)

                callback(
                    newExtractorLink(name, "$name [$vName]", vUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "https://ok.ru/"
                        this.quality = q
                    }
                )
            }
        }
    }
}

class ArabicVkExtractor : ExtractorApi() {
    override val name = "VK Video"
    override val mainUrl = "https://vkvideo.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = runCatching {
            app.get(url, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val unescaped = html.replace("\\/", "/")
        for ((field, qVal, label) in listOf(
            Triple("url1080", Qualities.P1080.value, "1080p"),
            Triple("url720", Qualities.P720.value, "720p"),
            Triple("url480", Qualities.P480.value, "480p"),
            Triple("url360", Qualities.P360.value, "360p")
        )) {
            val mp4 = Regex(""""$field"\s*:\s*"([^"]+)"""").find(unescaped)?.groupValues?.get(1)?.replace("\\u0026", "&")
            if (!mp4.isNullOrBlank() && mp4.startsWith("http")) {
                callback(
                    newExtractorLink(name, "$name ($label)", mp4, ExtractorLinkType.VIDEO) {
                        this.referer = "https://vk.ru/"
                        this.quality = qVal
                    }
                )
            }
        }
    }
}

class ArabicMediaFireExtractor : ExtractorApi() {
    override val name = "MediaFire"
    override val mainUrl = "https://www.mediafire.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.substringBefore('?').lowercase()
        if (cleanUrl.endsWith(".zip") || cleanUrl.endsWith(".rar") || cleanUrl.endsWith(".7z")) return

        val html = runCatching {
            app.get(url, referer = referer, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val scrambled = Regex("""data-scrambled-url=["']([^"']+)["']""").find(html)?.groupValues?.get(1)
        val directUrl = if (!scrambled.isNullOrBlank()) {
            runCatching { String(Base64.decode(scrambled, Base64.DEFAULT), Charsets.UTF_8).trim() }.getOrNull()?.takeIf { it.startsWith("http") }
        } else {
            Regex("""href=["'](https?://download\d*\.mediafire\.com/[^"']+)["']""").find(html)?.groupValues?.get(1)
                ?: Regex("""https?://download\d*\.mediafire\.com/[^\s"'<>]+""").find(html)?.value
        } ?: return

        callback(
            newExtractorLink(name, "$name [MP4]", directUrl, ExtractorLinkType.VIDEO) {
                this.referer = "https://www.mediafire.com/"
                this.quality = Qualities.P1080.value
            }
        )
    }
}

class ArabicPixelDrainExtractor : ExtractorApi() {
    override val name = "PixelDrain"
    override val mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = ArabicExtractorUtils.originOf(url) ?: mainUrl
        val id = Regex("""pixeldrain\.[^/]+/(?:u|api/file)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val directUrl = "$origin/api/file/$id"

        callback(
            newExtractorLink(name, "$name [MP4]", directUrl, ExtractorLinkType.VIDEO) {
                this.referer = "$origin/"
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)
            }
        )
    }
}

class ArabicFourSharedExtractor : ExtractorApi() {
    override val name = "4Shared"
    override val mainUrl = "https://www.4shared.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""4shared\.[^/]+/(?:web/embed/file|embed|video|file|s)/([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
        val embedUrl = "https://www.4shared.com/web/embed/file/$fileId"

        val html = runCatching {
            app.get(embedUrl, headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val doc = Jsoup.parse(html)
        val mp4 = doc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
            ?: Regex("""https?://dc\d+\.4shared\.com/[^\s"'<>]+\.mp4[^\s"'<>]*""").find(html)?.value
            ?: return

        callback(
            newExtractorLink(name, "$name [MP4]", mp4, ExtractorLinkType.VIDEO) {
                this.referer = "https://www.4shared.com/"
                this.quality = Qualities.P720.value
            }
        )
    }
}

class ArabicGoFileExtractor : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val contentId = url.trimEnd('/').substringAfterLast('/').substringBefore('?')
        if (contentId.isBlank()) return

        val accRes = runCatching {
            app.post("https://api.gofile.io/accounts", headers = mapOf("User-Agent" to ArabicExtractorUtils.DEFAULT_UA)).text
        }.getOrNull() ?: return

        val token = runCatching { JSONObject(accRes).optJSONObject("data")?.optString("token") }.getOrNull()
        if (token.isNullOrBlank()) return

        val contentRaw = runCatching {
            app.get(
                url = "https://api.gofile.io/contents/$contentId",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "User-Agent" to ArabicExtractorUtils.DEFAULT_UA
                )
            ).text
        }.getOrNull() ?: return

        val data = runCatching { JSONObject(contentRaw).optJSONObject("data") }.getOrNull() ?: return
        val children = data.optJSONObject("children") ?: return
        val keys = children.keys()

        while (keys.hasNext()) {
            val item = children.optJSONObject(keys.next()) ?: continue
            val link = item.optString("link")
            val nameStr = item.optString("name").lowercase()

            if (link.startsWith("http") && !nameStr.endsWith(".zip") && !nameStr.endsWith(".rar") && !nameStr.endsWith(".7z")) {
                callback(
                    newExtractorLink(name, "$name [MP4]", link, ExtractorLinkType.VIDEO) {
                        this.referer = "https://gofile.io/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("Cookie" to "accountToken=$token")
                    }
                )
            }
        }
    }
}

object UniversalExtractor {
    private const val TAG = "UniversalExtractor"
    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36"
    private const val FALLBACK_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    suspend fun extract(
        url: String,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        serverName: String? = null
    ): Boolean {
        return try {
            val serverUrl = fixUrl(url)
            if (serverUrl.isBlank()) return false

            val uri = runCatching { URL(serverUrl) }.getOrNull() ?: return false
            val domain = "${uri.protocol}://${uri.host}/"
            val host = uri.host.lowercase()
            val displayName = serverName?.takeIf { it.isNotBlank() }
                ?: host.removePrefix("www.").substringBefore(".")

            val emittedUrls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            var extractedAny = false
            val dedup: (ExtractorLink) -> Unit = { link ->
                val key = normalizeForDedup(link.url)
                if (emittedUrls.add(key)) {
                    extractedAny = true
                    callback(link)
                }
            }

            var rawHtml = fetchHtml(serverUrl, referer ?: domain)
            var unpacked = ""

            if (!rawHtml.isNullOrBlank()) {
                val cookieMap = extractCookiesFromHtml(rawHtml)
                val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                Log.d(TAG, "Cookies found: $cookieMap")
                if (cookieMap.isNotEmpty()) {
                    val withCookies = fetchHtml(serverUrl, referer ?: domain, cookieHeader)
                    if (!withCookies.isNullOrBlank() && withCookies.length >= rawHtml.length) {
                        rawHtml = withCookies
                    }
                }
                unpacked = extractLogic(rawHtml)
                if (unpacked.length > rawHtml.length) {
                    Log.d(TAG, "Unpacked content expanded: ${rawHtml.length} → ${unpacked.length}")
                }
                try {
                    extractSubtitles(unpacked, domain, subtitleCallback)
                } catch (_: Exception) {}

                val videoLinks = findVideoLinks(unpacked)
                    .map { fixUrl(it, domain) }
                    .filter { it.isNotBlank() && (it.startsWith("http") || it.startsWith("/")) }
                    .distinct()

                Log.d(TAG, "Candidate video links (${videoLinks.size}): ${videoLinks.take(10)}")
                val extraLinks = safeRunResult { tryDlEndpoint(rawHtml, serverUrl, domain, cookieHeader) } ?: emptyList()
                val allLinks = (videoLinks + extraLinks).distinct()

                for (rawLink in allLinks) {
                    val finalUrl = fixUrl(rawLink, domain)
                    if (!finalUrl.startsWith("http")) continue

                    val isHls = isHlsUrl(finalUrl)
                    val isVideo = isDirectVideo(finalUrl)

                    if (!isHls && !isVideo) continue

                    val verifyHeaders = buildVerifyHeaders(domain, finalUrl, cookieHeader)
                    val ok = verifyUrl(finalUrl, verifyHeaders)
                    if (!ok) {
                        Log.d(TAG, "Verify failed for: $finalUrl")
                        val altHeaders = verifyHeaders + mapOf("User-Agent" to FALLBACK_UA)
                        if (!verifyUrl(finalUrl, altHeaders)) continue
                    }

                    if (isHls) {
                        val m3u8Links = safeRunResult {
                            M3u8Helper.generateM3u8(
                                source = displayName,
                                streamUrl = finalUrl,
                                referer = domain,
                                headers = verifyHeaders,
                                name = displayName
                            )
                        }.orEmpty()

                        if (m3u8Links.isNotEmpty()) {
                            m3u8Links.forEach(dedup)
                        } else {
                            val (q, qName) = parseQualityFromUrl(finalUrl)
                            dedup(
                                newExtractorLink(
                                    source = displayName,
                                    name = "$displayName $qName".trim(),
                                    url = finalUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = domain
                                    this.quality = q
                                    this.headers = verifyHeaders
                                }
                            )
                        }
                    } else {
                        val (q, qName) = parseQualityFromUrl(finalUrl)
                        dedup(
                            newExtractorLink(
                                source = displayName,
                                name = "$displayName [$qName]",
                                url = finalUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = domain
                                this.quality = q
                                this.headers = verifyHeaders
                            }
                        )
                    }
                }
            }

            if (!extractedAny) {
                runFallbackByHostOrSignature(
                    url = serverUrl,
                    host = host,
                    serverName = serverName,
                    origin = domain.removeSuffix("/"),
                    referer = referer,
                    html = unpacked.ifBlank { rawHtml.orEmpty() },
                    subtitleCallback = subtitleCallback,
                    callback = dedup
                )
            }

            extractedAny
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed for $url: ${e.message}", e)
            false
        }
    }

    private suspend fun runFallbackByHostOrSignature(
        url: String,
        host: String,
        serverName: String?,
        origin: String,
        referer: String?,
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val h = host.lowercase()
        val n = serverName.orEmpty().lowercase().trim()
        val target = "$h $n"
        val serverBaseName = h.removePrefix("www.").substringBefore(".")

        val extractor: ExtractorApi? = when {
            target.contains("streamwish") || target.contains("wishembed") || target.contains("wishfast") ||
                target.contains("dwish") || target.contains("embedwish") || target.contains("strwish") ->
                ArabicStreamWishExtractor("StreamWish", origin)

            target.contains("vidhide") ->
                ArabicStreamWishExtractor("VidHide", origin)

            target.contains("filelions") || target.contains("lion") ->
                ArabicStreamWishExtractor("Filelions", origin)

            target.contains("morencius") || target.contains("earnvids") ->
                ArabicStreamWishExtractor("EarnVids", origin)

            target.contains("vibuxer") || target.contains("streamhg") || target.contains("hgcloud") ->
                ArabicStreamWishExtractor("StreamHG", origin)

            target.contains("hanerix") ->
                ArabicStreamWishExtractor("Hanerix", origin)
            target.contains("dood") || target.contains("d0o0d") || target.contains("do0od") ||
                target.contains("dsvplay") || target.contains("ds2play") || target.contains("doply") ->
                ArabicDoodExtractor("DoodStream", origin)

            target.contains("playmogo") ->
                ArabicDoodExtractor("Playmogo", origin)

            target.contains("myvidplay") ->
                ArabicDoodExtractor("MyVidPlay", origin)
            target.contains("upns") || target.contains("uns.") || target.contains("upnshare") ->
                ArabicVidstackExtractor("UPNShare", origin)

            target.contains("strp2p") || target.contains("p2pstream") || target.contains("streamp2p") ->
                ArabicVidstackExtractor("StreamP2P", origin)

            target.contains("rpmplay") || target.contains("rpmshare") ->
                ArabicVidstackExtractor("RPMShare", origin)
            target.contains("megamax") ->
                ArabicMegaMaxExtractor("MegaMax", origin)

            target.contains("share4max") ->
                ArabicMegaMaxExtractor("Share4Max", origin)
            target.contains("byse") ->
                ArabicByseExtractor("Byse", origin)
            target.contains("lulu") ->
                LuluStreamExtractor().apply { mainUrl = origin }

            target.contains("videoland") ->
                ArabicPackerExtractor("Videoland", origin)

            target.contains("updown") ->
                ArabicPackerExtractor("UpDown", origin)

            target.contains("okstream") ->
                ArabicPackerExtractor("OkStream", origin)

            target.contains("upstream") ->
                ArabicPackerExtractor("UpStream", origin)

            target.contains("lolololu") || target.contains("tuktuk") ->
                TukTukVipExtractor().apply { mainUrl = origin }
            target.contains("streamtape") || target.contains("shavetape") ->
                ArabicStreamTapeExtractor()

            serverBaseName == "voe" || h.contains("voe.") || n == "voe" || n.contains("voe") ->
                ArabicVoeExtractor()

            target.contains("mixdrop") ->
                ArabicMixdropExtractor()

            target.contains("mp4upload") ->
                ArabicMp4UploadExtractor()

            target.contains("firestream") ->
                ArabicFireStreamExtractor()

            target.contains("vidara") ->
                ArabicVidaraExtractor()

            target.contains("yonaplay") || target.contains("dotplay") ->
                ArabicYonaPlayExtractor()

            target.contains("videas") ->
                ArabicVideasExtractor()

            serverBaseName == "videa" || h.contains("videa.") || n == "videa" ->
                ArabicVideaExtractor()

            h.contains("ok.ru") || h.contains("odnoklassniki") || n == "ok" || n == "ok.ru" ->
                ArabicOkRuExtractor()

            h.contains("vk.com") || h.contains("vkvideo") || h.contains("vk.ru") || n == "vk" || n.contains("vk video") ->
                ArabicVkExtractor()

            target.contains("mediafire") ->
                ArabicMediaFireExtractor()

            target.contains("pixeldrain") ->
                ArabicPixelDrainExtractor()

            target.contains("4shared") ->
                ArabicFourSharedExtractor()

            target.contains("gofile") ->
                ArabicGoFileExtractor()
            html.contains("/pass_md5/") ->
                ArabicDoodExtractor("DoodStream", origin)

            html.contains("var links") || html.contains("hls4") ->
                ArabicStreamWishExtractor(displayNameIfNeeded(serverName, serverBaseName), origin)

            html.contains("MDCore.wurl") ->
                ArabicMixdropExtractor()

            html.contains("ideoolink") || html.contains("robotlink") ->
                ArabicStreamTapeExtractor()

            html.contains("key_parts") && html.contains("playback") ->
                ArabicByseExtractor("Byse", origin)

            html.contains("data-options") && html.contains("flashvars") ->
                ArabicOkRuExtractor()

            else -> null
        }

        if (extractor != null) {
            Log.d(TAG, "Fallback matched [${extractor.name}] for host=$host, serverName=$serverName")
            safeRun { extractor.getUrl(url, referer, subtitleCallback, callback) }
        }
    }

    private fun displayNameIfNeeded(serverName: String?, fallback: String): String {
        return serverName?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback.ifBlank { "StreamWish" }
    }


    private suspend fun fetchHtml(
        url: String,
        referer: String,
        cookie: String = ""
    ): String? {
        val h = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to DEFAULT_UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7"
        )
        if (cookie.isNotBlank()) h["Cookie"] = cookie
        return safeRunResult {
            val r = app.get(url, headers = h, timeout = 20)
            if (r.code in 200..399) r.text else null
        }
    }

    private fun buildVerifyHeaders(domain: String, finalUrl: String, cookie: String): Map<String, String> {
        val map = mutableMapOf(
            "Referer" to domain,
            "Origin" to domain.removeSuffix("/"),
            "User-Agent" to DEFAULT_UA,
            "Accept" to "*/*",
            "Accept-Language" to "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )
        if (cookie.isNotBlank()) map["Cookie"] = cookie
        return map
    }

    private suspend fun verifyUrl(url: String, headers: Map<String, String>): Boolean {
        return safeRunResult {
            val r = app.get(url, headers = headers, timeout = 12, allowRedirects = true)
            r.code in listOf(200, 206, 301, 302, 304)
        } ?: false
    }

    private fun extractCookiesFromHtml(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val patterns = listOf(
            """\$\.cookie\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]""",
            """document\.cookie\s*=\s*['"]([^'"=]+)=([^;'"]+)""",
            """setCookie\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]*)['"]"""
        )
        for (p in patterns) {
            Regex(p).findAll(html).forEach { m ->
                if (m.groupValues.size >= 3) {
                    result[m.groupValues[1]] = m.groupValues[2]
                }
            }
        }
        return result
    }

    private fun deobfuscatePacker(packed: String): String {
        return try {
            val packerRegex = Regex(
                """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*(?:,\s*[a-zA-Z])?\s*\)\s*\{.*?\}\s*\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split\s*\(\s*(['"])\|?\7\s*\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = packerRegex.find(packed) ?: return ""
            val p = match.groupValues[2]
            val a = match.groupValues[3].toIntOrNull() ?: return ""
            val c = match.groupValues[4].toIntOrNull() ?: return ""
            val k = match.groupValues[6].split('|')

            fun toBaseN(num: Int, base: Int): String {
                if (base == 36) return num.toString(36)
                if (base == 62) {
                    val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    if (num == 0) return "0"
                    var n = num
                    val sb = StringBuilder()
                    while (n > 0) { sb.append(chars[n % 62]); n /= 62 }
                    return sb.reverse().toString()
                }
                if (num == 0) return "0"
                var n = num
                val sb = StringBuilder()
                while (n > 0) { sb.append(Character.forDigit(n % base, base)); n /= base }
                return sb.reverse().toString()
            }

            val dict = HashMap<String, String>(c * 2)
            for (i in 0 until c) {
                val key = toBaseN(i, a)
                val value = if (i < k.size && k[i].isNotEmpty()) k[i] else key
                dict[key] = value
            }

            Regex("""\b\w+\b""").replace(p) { mr ->
                dict[mr.value] ?: mr.value
            }
        } catch (e: Exception) {
            Log.w(TAG, "Packer deobfuscation failed: ${e.message}")
            ""
        }
    }

    private fun extractLogic(html: String): String {
        var current = html
        var iter = 0
        while (iter < 5) {
            iter++
            val startIdx = current.indexOf("eval(function(p,a,c,k,e,")
            if (startIdx == -1) break
            val endMarkers = listOf(".split('|')))", ".split('|'))", ".split(\"|\")))")
            var endIdx = -1
            var endLen = 0
            for (em in endMarkers) {
                val i = current.indexOf(em, startIdx)
                if (i != -1 && (endIdx == -1 || i < endIdx)) {
                    endIdx = i
                    endLen = em.length
                }
            }
            if (endIdx == -1) break
            val packed = current.substring(startIdx, endIdx + endLen)
            val unpacked = deobfuscatePacker(packed)
            if (unpacked.isBlank()) break
            current = current.substring(0, startIdx) + unpacked + current.substring(min(current.length, endIdx + endLen))
        }
        current = current.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/")
        return current
    }

    private fun findVideoLinks(text: String): List<String> {
        val found = LinkedHashSet<String>()
        val objectJsonRegex = Regex(
            """(?:var|let|const)\s+\w+\s*=\s*(\{[^{}]*(?:hls|m3u8|mp4|stream|file)[^{}]*\})""",
            RegexOption.IGNORE_CASE
        )
        objectJsonRegex.findAll(text).forEach { m ->
            safeRun {
                val obj = JSONObject(m.groupValues[1])
                for (key in obj.keys()) {
                    val v = obj.optString(key).trim()
                    val u = cleanUrl(v)
                    if (looksLikeVideoUrl(u)) found.add(u)
                }
            }
        }
        Regex("""getElementById\(['"][^'"]+['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]\s*\+\s*\(?['"]([^'"]+)['"]\)?""")
            .find(text)?.let { m ->
                var direct = m.groupValues[1] + m.groupValues[2]
                if (direct.startsWith("//")) direct = "https:$direct"
                if (!direct.contains("&stream=")) direct += "&stream=1"
                if (looksLikeVideoUrl(direct)) found.add(direct)
            }
        Regex("""(?:file|sources?|src|url|stream)\s*[:=]\s*\[?\s*["']([^"']+\.(?:m3u8|mp4|txt|php)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m ->
                val u = cleanUrl(m.groupValues[1])
                if (looksLikeVideoUrl(u)) found.add(u)
            }
        Regex("""(https?:[^\s"'<>]+?\.m3u8[^\s"'<>?]*(?:\?[^\s"'<>]*)?)""")
            .findAll(text).forEach { m -> found.add(cleanUrl(m.groupValues[1])) }
        Regex("""(https?:[^\s"'<>]+?\.mp4[^\s"'<>?]*(?:\?[^\s"'<>]*)?)""")
            .findAll(text).forEach { m -> found.add(cleanUrl(m.groupValues[1])) }
        Regex("""(https?:[^\s"'<>]+?/(?:hls|stream|index)[^\s"'<>]*\.txt[^\s"'<>]*)""", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { m -> found.add(cleanUrl(m.groupValues[1])) }
        Regex("""["'](/[^"']+\.m3u8[^"']*)["']""").findAll(text).forEach { m ->
            found.add(m.groupValues[1].trim())
        }

        return found.toList()
    }

    private suspend fun tryDlEndpoint(
        html: String,
        baseUrl: String,
        domain: String,
        cookie: String
    ): List<String> {
        val fileId = Regex("""\$\.cookie\(['"]file_id['"]\s*,\s*['"]([^'"]+)['"]""")
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        val results = mutableListOf<String>()
        val tries = listOf(
            "${domain}dl?op=view4&file_code=$fileId&stream=1",
            "${domain}dl?op=view4&file_code=$fileId",
            "${domain}dl?op=view&file_code=$fileId"
        )
        for (u in tries) {
            val body = safeRunResult {
                val r = app.get(u, headers = mapOf(
                    "Referer" to baseUrl,
                    "User-Agent" to DEFAULT_UA,
                    "Accept" to "*/*",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Cookie" to cookie
                ), timeout = 12)
                if (r.code == 200) r.text else null
            }
            if (!body.isNullOrBlank()) {
                results += findVideoLinks(body)
                if (results.isNotEmpty()) break
            }
        }
        return results.distinct()
    }

    private suspend fun extractSubtitles(text: String, domain: String, cb: (SubtitleFile) -> Unit) {
        val patterns = listOf(
            """["'](?:file|src|url)["']?\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["']""",
            """(https?:[^\s"'<>]+\.(?:vtt|srt)[^\s"'<>]*)"""
        )
        for (p in patterns) {
            Regex(p, RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
                val raw = m.groupValues[1].trim()
                if (raw.isEmpty()) return@forEach
                val subUrl = when {
                    raw.startsWith("//") -> "https:$raw"
                    raw.startsWith("http") -> raw
                    raw.startsWith("/") -> "${domain.trimEnd('/')}$raw"
                    else -> safeRunResult { URL(URL(domain), raw).toString() } ?: raw
                }
                val lang = when {
                    subUrl.contains("ar", true) -> "Arabic"
                    subUrl.contains("en", true) -> "English"
                    else -> "Arabic"
                }
                safeRun { cb(newSubtitleFile(lang, subUrl)) }
            }
        }
    }

    private fun parseQualityFromUrl(url: String): Pair<Int, String> {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> Qualities.P2160.value to "4K"
            lower.contains("1440") || lower.contains("2k") -> Qualities.P1440.value to "2K"
            lower.contains("1080") || lower.contains("fhd") -> Qualities.P1080.value to "1080p"
            lower.contains("720") || lower.contains("hd") -> Qualities.P720.value to "720p"
            lower.contains("480") || lower.contains("sd") -> Qualities.P480.value to "480p"
            lower.contains("360") -> Qualities.P360.value to "360p"
            else -> {
                val q = getQualityFromName(lower)
                if (q > 0) q to "${q}p" else Qualities.P720.value to "720p"
            }
        }
    }

    private fun isHlsUrl(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".m3u8") ||
            (l.contains(".txt") && (l.contains("hls") || l.contains("index") || l.contains("master")))
    }

    private fun isDirectVideo(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".mp4") || l.contains(".mkv") || l.contains(".webm") || l.contains(".avi")
    }

    private fun looksLikeVideoUrl(u: String): Boolean {
        if (u.isBlank()) return false
        val l = u.lowercase()
        return l.startsWith("http") || l.startsWith("/") || l.startsWith("//")
    }

    private fun cleanUrl(u: String): String {
        return u.trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("&amp;", "&")
    }

    private fun normalizeForDedup(u: String): String {
        return u.substringBefore("#").trim()
    }

    private fun fixUrl(url: String, baseDomain: String = ""): String {
        val t = url.trim()
        if (t.startsWith("//")) return "https:$t"
        if (t.startsWith("/") && baseDomain.isNotBlank()) return "${baseDomain.trimEnd('/')}$t"
        return t
    }

    private inline fun <T> safeRun(block: () -> T?): T? =
        try { block() } catch (e: Exception) { Log.w(TAG, "safeRun: ${e.message}"); null }

    private inline fun <T> safeRunResult(block: () -> T?): T? = safeRun(block)
}
