package com.witanime

import android.net.Uri
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class VideaExtractor : ExtractorApi() {
    override val name: String = "Videa"
    override val mainUrl: String = "https://videa.hu"
    override val requiresReferer: Boolean = false

    private val TAG = "VIDEA_EXTRACTOR"
    private val STUPID_KEY = "xHb0ZvME5q8CBcoQi6AngerDu3FGO9fkUlwPmLVY_RTzj2hJIS4NasXWKy1td7p"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "▶️ getUrl start for: $url")
        try {
            val iframeSrc = url.trim()
            if (iframeSrc.isBlank()) {
                Log.w(TAG, "Empty iframe src")
                return
            }

            val pageResp = app.get(iframeSrc).text
            val nonceMatch = Regex("_xt\\s*=\\s*\"([^\"]+)\"").find(pageResp)
            if (nonceMatch == null) {
                Log.w(TAG, "لم يتم العثور على _xt في صفحة iframe")
                return
            }
            val nonce = nonceMatch.groupValues[1]
            Log.d(TAG, "Found nonce (_xt), len=${nonce.length}")

            val paramL = if (nonce.length >= 32) nonce.substring(0, 32) else nonce.padEnd(32, 'a')
            val paramSPart = if (nonce.length > 32) nonce.substring(32) else ""

            val resultBuilder = StringBuilder()
            for (i in 0 until 32) {
                val ch = paramL.getOrNull(i) ?: 'a'
                val idxInStupid = STUPID_KEY.indexOf(ch).takeIf { it >= 0 } ?: 0
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

            val seed = randomAlphaNumeric(8) // _s
            val paramT = if (result.length >= 16) result.substring(0, 16) else result.padEnd(16, '0')
            val rc4KeyPart = if (result.length > 16) result.substring(16) else ""

            Log.d(TAG, "params generated: _s=$seed, _t(len)=${paramT.length}, rc4PartLen=${rc4KeyPart.length}")

            val videoId = extractQueryParamV(iframeSrc)
            if (videoId.isNullOrBlank()) {
                Log.w(TAG, "video id 'v' not found in url")
                return
            }

            val xmlUrl = "https://videa.hu/player/xml?platform=desktop"
            val params = mapOf("_s" to seed, "_t" to paramT, "v" to videoId)
            val headers = mapOf("Referer" to iframeSrc, "Origin" to "https://videa.hu")

            Log.d(TAG, "Requesting XML...")
            val xmlResponse = app.get(xmlUrl, params = params, headers = headers)
            val body = xmlResponse.text
            val xVideaXsHeader = xmlResponse.headers["x-videa-xs"] ?: ""

            val finalDoc = if (body.trimStart().startsWith("<?xml")) {
                val parsed = Jsoup.parse(body, "", Parser.xmlParser())
                val err = parsed.selectFirst("error")
                if (err != null) {
                    Log.e(TAG, "Server returned error: ${err.text()}")
                    return
                }
                parsed
            } else {
                try {
                    Log.d(TAG, "Received base64 payload; decoding and RC4 decrypting...")
                    val decoded = Base64.decode(body.trim(), Base64.DEFAULT)
                    val finalRc4Key = rc4KeyPart + seed + xVideaXsHeader
                    val decrypted = rc4Decrypt(decoded, finalRc4Key)
                    Jsoup.parse(decrypted, "", Parser.xmlParser())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode/decrypt XML response", e)
                    return
                }
            }

            val videoSources = finalDoc.select("video_source")
            if (videoSources.isEmpty()) {
                Log.w(TAG, "No video_source elements found")
                return
            }
            val collected = mutableListOf<Triple<Int, String, String>>() // Triple(qualityValue, name, finalUrl)

            for (src in videoSources) {
                try {
                    val name = src.attr("name")
                    val videoUrlPart = src.text().trim()
                    val exp = src.attr("exp")
                    val hashTagName = "hash_value_$name"
                    val hashElem = finalDoc.getElementsByTag(hashTagName).first()
                    val md5 = hashElem?.text()?.trim() ?: continue
                    val finalUrl = if (videoUrlPart.startsWith("http")) {
                        "$videoUrlPart?md5=$md5&expires=$exp"
                    } else {
                        "https:$videoUrlPart?md5=$md5&expires=$exp"
                    }
                    val q = try {
                        val qv = getQualityFromName(name)
                        if (qv <= 0) Qualities.Unknown.value else qv
                    } catch (e: Exception) {
                        Qualities.Unknown.value
                    }

                    Log.d(TAG, "Found source: name='$name' qualityVal=$q -> $finalUrl")
                    collected.add(Triple(q, name, finalUrl))
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing a video_source element", e)
                }
            }
            val sorted = collected.sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }
                .thenByDescending { // tie-breaker: حاول ترتيب حسب اسم إن أردت (1080 قبل 720)
                    Regex("(\\d{3,4})").find(it.second)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                })
            withContext(Dispatchers.Main) {
                for ((qualityVal, name, finalUrl) in sorted) {
                    try {
                        val ex = newExtractorLink(
                            source = this@VideaExtractor.name,
                            name = "$name (Videa)",
                            url = finalUrl,
                            type = if (finalUrl.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = referer ?: iframeSrc
                            this.quality = qualityVal
                        }
                        callback.invoke(ex)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create/expose extractor link for $finalUrl", e)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "getUrl fatal error", e)
        }
    }

    private fun randomAlphaNumeric(len: Int): String {
        val allowed = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { allowed[Random.nextInt(allowed.length)] }.joinToString("")
    }

    private fun extractQueryParamV(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            uri.getQueryParameter("v")
        } catch (e: Exception) {
            Regex("[?&]v=([^&]+)").find(url)?.groupValues?.get(1)
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
            Log.e(TAG, "RC4 decrypt failed", e)
            ""
        }
    }
}
