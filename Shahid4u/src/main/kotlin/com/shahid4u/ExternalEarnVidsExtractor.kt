package com.shahid4u

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI
import java.net.URL
import kotlin.text.RegexOption

class ExternalEarnVidsExtractor : ExtractorApi() {
    override val name = "EarnVids / FastVid"
    override val mainUrl = "https://fastvid.cam"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.i(name, "================ EXTRACTOR START ================")
        Log.i(name, "Original input URL: $url")

        try {
            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive"
            )
            val resolvedReferer = if (url.contains("fdewsdc.sbs", true)) {
                "https://shhahid4u.cam"
            } else {
                referer ?: mainUrl
            }

            val safeReferer = safeEncodeUrl(resolvedReferer)
            headers["Referer"] = safeReferer
            Log.d(name, "🌐 Encoded Referer used: $safeReferer")
            val response = app.get(url, headers = headers)
            val html = response.text ?: ""
            val finalResolvedUrl = response.url
            Log.d(name, "Fetched page length=${html.length} for $url")
            try {
                val m3u8Regex = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
                val m3u8Match = m3u8Regex.find(html)
                if (m3u8Match != null) {
                    var direct = m3u8Match.value.replace("\\/", "/")
                    if (direct.startsWith("/")) {
                        direct = URI(url).resolve(direct).toString()
                    }
                    Log.i(name, "🔎 Found direct .m3u8 in HTML -> $direct")

                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = direct,
                        ) {
                            this.referer = finalResolvedUrl
                            type = ExtractorLinkType.M3U8
                        }
                    )
                    return
                }
            } catch (e: Exception) {
                Log.w(name, "m3u8 quick search failed: ${e.message}")
            }
            if (!html.contains("eval(function")) {
                Log.w(name, "❌ لا يوجد eval(function) في الصفحة - لن نحاول فكّ packer.")
                return
            }
            var working = html
            var unpacked: String? = null
            val maxIterations = 4
            for (i in 1..maxIterations) {
                unpacked = unpackPackerSimple(working, url)
                if (unpacked.isNullOrBlank()) {
                    Log.d(name, "unpack iteration $i => null/blank")
                    break
                }
                Log.d(name, "unpack iteration $i => length=${unpacked.length}")

                if (!unpacked.contains("eval(function")) {
                    working = unpacked
                    break
                } else {
                    working = unpacked
                }
            }

            if (unpacked.isNullOrBlank()) {
                Log.w(name, "❌ فشل فكّ تشفير الـ packer.")
                return
            }

            val cleaned = unpacked.replace("\\/", "/")
            val linksRegex = Regex("""var\s+links\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            val match = linksRegex.find(cleaned)
            var extractedM3u8: String? = null

            if (match == null) {
                Log.w(name, "❌ لم يُعثر على كائن links بعد فكّ packer. نحاول البحث المباشر عن hls4 أو hls...")

                val hlsInline = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)
                    ?: Regex(""""hls"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)

                if (!hlsInline.isNullOrBlank()) {
                    extractedM3u8 = hlsInline.replace("\\/", "/")
                }
            } else {
                val jsonRaw = match.groupValues[1].replace("'", "\"")
                val map = mutableMapOf<String, String>()

                try {
                    val jo = JSONObject(jsonRaw)
                    val keys = jo.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        try {
                            map[k] = jo.getString(k)
                        } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.d(name, "JSONObject parse failed, falling back to regex: ${e.message}")
                    val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
                    for (m in pairRegex.findAll(jsonRaw)) {
                        map[m.groupValues[1]] = m.groupValues[2]
                    }
                }

                extractedM3u8 = map["hls4"] ?: map["hls"]
            }
            if (!extractedM3u8.isNullOrBlank()) {
                var finalLink = extractedM3u8.replace("\\/", "/")
                if (finalLink.startsWith("/")) {
                    finalLink = URI(url).resolve(finalLink).toString()
                }

                Log.i(name, "✅ Extracted M3U8 Link: $finalLink")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = finalLink,
                    ) {
                        this.referer = finalResolvedUrl
                        type = ExtractorLinkType.M3U8
                    }
                )
            } else {
                Log.w(name, "❌ لم يتم العثور على أي روابط m3u8 صالحة بعد فك التشفير.")
            }

            Log.i(name, "================ EXTRACTOR FINISHED ================")

        } catch (e: Exception) {
            Log.e(name, "❌ Error inside ExternalEarnVidsExtractor: ${e.message}", e)
        }
    }

    /**
     * دالة لتشفير الحروف غير القياسية (مثل العربية) في الروابط وجعلها آمنة للـ Headers.
     */
    private fun safeEncodeUrl(urlStr: String): String {
        return try {
            val parsedUrl = URL(urlStr)
            val uri = URI(
                parsedUrl.protocol,
                parsedUrl.userInfo,
                parsedUrl.host,
                parsedUrl.port,
                parsedUrl.path,
                parsedUrl.query,
                parsedUrl.ref
            )
            uri.toASCIIString()
        } catch (e: Exception) {
            Log.w(name, "URL Encoding failed, returning original URL: ${e.message}")
            urlStr
        }
    }

    private fun unpackPackerSimple(js: String, pageUrl: String): String? {
        try {
            val regex = Regex(
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.+?)['"]""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(js) ?: return null
            val (payloadRaw, radixStr, sympipe) = match.destructured
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")

            var payload = payloadRaw
                .replace("location.href", "'$pageUrl'")
                .replace("location", "'$pageUrl'")
                .replace("document.cookie", "''")
                .replace("window.location", "'$pageUrl'")
                .replace("window", "this")

            val tokenRe = Regex("""\b[0-9a-zA-Z]+\b""")

            val replaced = tokenRe.replace(payload) { mo ->
                val tok = mo.value
                try {
                    val idx = tok.toInt(radix)
                    if (idx in 0 until symtab.size) symtab[idx] else tok
                } catch (_: Exception) {
                    tok
                }
            }

            return replaced
        } catch (e: Exception) {
            Log.w(name, "unpackPackerSimple failed: ${e.message}")
            return null
        }
    }
}