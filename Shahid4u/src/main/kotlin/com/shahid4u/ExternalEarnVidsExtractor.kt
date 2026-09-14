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

open class ExternalEarnVidsExtractor : ExtractorApi() {
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
            val resolvedReferer = if (url.contains("fdewsdc", true)) {
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
            val quick = findStreamUrl(html, url)
            if (quick != null) {
                Log.i(name, "Found direct stream -> $quick")
                emitStream(quick, finalResolvedUrl, callback)
                return
            }

            if (!html.contains("eval(function")) {
                Log.w(name, "no eval(function) found - no stream extracted.")
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
                working = unpacked
                if (!unpacked.contains("eval(function")) break
            }

            val cleaned = unpacked?.replace("\\/", "/")
            if (cleaned.isNullOrBlank()) {
                Log.w(name, "❌ فشل فكّ تشفير الـ packer.")
                return
            }
            var extractedM3u8: String? = null
            val linksRegex = Regex("""var\s+links\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            val match = linksRegex.find(cleaned)

            if (match != null) {
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

                extractedM3u8 = map["hls4"]
                    ?: map["hls"]
                            ?: map["hls2"]
                            ?: map["hls3"]
                            ?: map["file"]
            }
            if (extractedM3u8.isNullOrBlank()) {
                extractedM3u8 = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
                    .find(cleaned)?.value
            }
            if (extractedM3u8.isNullOrBlank()) {
                extractedM3u8 = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                    .find(cleaned)?.groupValues?.get(1)
                    ?.takeIf { it.contains(".m3u8") || it.contains("/hls/") || it.startsWith("/") }
            }

            if (!extractedM3u8.isNullOrBlank()) {
                var finalLink = extractedM3u8.replace("\\/", "/")
                if (finalLink.startsWith("/")) {
                    finalLink = URI(url).resolve(finalLink).toString()
                }
                Log.i(name, "✅ Extracted M3U8 Link: $finalLink")
                emitStream(finalLink, finalResolvedUrl, callback)
            } else {
                Log.w(name, "❌ لم يتم العثور على أي روابط m3u8 صالحة بعد فك التشفير.")
            }

            Log.i(name, "================ EXTRACTOR FINISHED ================")

        } catch (e: Exception) {
            Log.e(name, "❌ Error inside ExternalEarnVidsExtractor: ${e.message}", e)
        }
    }

    /**
     * بحث سريع عن روابط مباشرة (m3u8 / mp4 / jwplayer file) قبل محاولة فكّ الـ packer.
     */
    private fun findStreamUrl(html: String, pageUrl: String): String? {
        val m3u8 = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.replace("\\/", "/")
        if (m3u8 != null) return resolveRelative(m3u8, pageUrl)

        val video = Regex("""https?://[^'"\s>]+?\.(?:mp4|mkv|webm|avi)(?:\?[^'"\s>]*)?""", RegexOption.IGNORE_CASE)
            .find(html)?.value?.replace("\\/", "/")
        if (video != null) return resolveRelative(video, pageUrl)

        val file = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        if (!file.isNullOrBlank() &&
            (file.contains(".m3u8") || file.endsWith(".mp4") || file.endsWith(".mkv"))
        ) {
            return resolveRelative(file, pageUrl)
        }
        return null
    }

    private fun resolveRelative(link: String, pageUrl: String): String {
        if (link.startsWith("//")) return "https:$link"
        return if (link.startsWith("/") || link.startsWith("./") || !link.startsWith("http")) {
            try {
                URI(pageUrl).resolve(link).toString()
            } catch (e: Exception) {
                link
            }
        } else {
            link
        }
    }

    private suspend fun emitStream(link: String, referer: String, callback: (ExtractorLink) -> Unit) {
        val finalLink = link.replace("\\/", "/")
        val lower = finalLink.lowercase()
        val isM3u8 = lower.contains(".m3u8") || lower.contains("/hls/")
        Log.i(name, "Emitting stream: $finalLink (m3u8=$isM3u8)")
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalLink,
            ) {
                this.referer = referer
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            }
        )
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
                """eval\(function\(p,a,c,k,e,d\)\{.*?\}\(\s*['"](.+?)['"]\s*,\s*(\d+)\s*,\s*\d+\s*,\s*['"](.*?)['"]\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(js) ?: return null
            val (payloadRaw, radixStr, sympipe) = match.destructured
            val radix = radixStr.toIntOrNull() ?: 36
            val symtab = sympipe.split("|")

            var payload = payloadRaw
            for (i in (symtab.size - 1) downTo 0) {
                val word = symtab[i]
                if (word.isNotEmpty()) {
                    val token = Integer.toString(i, radix)
                    payload = payload.replace(Regex("""\b$token\b"""), word)
                }
            }

            return payload
        } catch (e: Exception) {
            Log.w(name, "unpackPackerSimple failed: ${e.message}")
            return null
        }
    }
}

class ExternalFastVedExtractor : ExternalEarnVidsExtractor() {
    override val name = "EarnVids / FastVed"
    override val mainUrl = "https://fastved.cam"
}
