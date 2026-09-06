package com.lody

import android.util.Log
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URI
import kotlin.text.RegexOption

object ExternalEarnVidsExtractor {

    private const val TAG = "EarnVidsExtractor"

    suspend fun extract(pageUrl: String, mainReferer: String): String? {
        try {

            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
                "Connection" to "keep-alive"
            )

            if (pageUrl.contains("fdewsdc.sbs", true)) {
                headers["Referer"] = "https://shhahid4u.cam"
                Log.d(TAG, "🌐 تم تعيين Referer: https://shhahid4u.cam")
            } else {
                headers["Referer"] = mainReferer
            }

            val response = app.get(pageUrl, headers = headers)
            val html = response.text ?: ""
            Log.d(TAG, "Fetched page length=${html.length} for $pageUrl")

            try {
                val m3u8Regex = Regex("""https?://[^'"\s>]+?\.m3u8[^'"\s>]*""", RegexOption.IGNORE_CASE)
                val m3u8Match = m3u8Regex.find(html)
                if (m3u8Match != null) {
                    var direct = m3u8Match.value.replace("\\/", "/")
                    if (direct.startsWith("/")) direct = URI(pageUrl).resolve(direct).toString()
                    Log.d(TAG, "🔎 Found direct .m3u8 in HTML -> $direct")
                    return direct
                }
            } catch (e: Exception) {
                Log.w(TAG, "m3u8 quick search failed: ${e.message}")
            }

            if (!html.contains("eval(function")) {
                Log.w(TAG, "❌ لا يوجد eval(function) في الصفحة - لن نحاول فكّ packer.")
                return null
            }

            var working = html
            var unpacked: String? = null
            val maxIterations = 4
            for (i in 1..maxIterations) {
                unpacked = unpackPackerSimple(working, pageUrl)
                if (unpacked.isNullOrBlank()) {
                    Log.d(TAG, "unpack iteration $i => null/blank")
                    break
                }
                Log.d(TAG, "unpack iteration $i => length=${unpacked.length}")

                if (!unpacked.contains("eval(function")) {
                    working = unpacked
                    break
                } else {

                    working = unpacked
                }
            }

            if (unpacked.isNullOrBlank()) {
                Log.w(TAG, "❌ فشل فكّ packer.")
                return null
            }

            val cleaned = unpacked.replace("\\/", "/")

            val linksRegex = Regex("""var\s+links\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
            val match = linksRegex.find(cleaned)
            if (match == null) {
                Log.w(TAG, "❌ لم يُعثر على كائن links بعد فكّ packer.")

                val hlsInline = Regex(""""hls4"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)
                    ?: Regex(""""hls"\s*:\s*"([^"]+)"""").find(cleaned)?.groupValues?.get(1)
                if (!hlsInline.isNullOrBlank()) {
                    var link = hlsInline.replace("\\/", "/")
                    if (link.startsWith("/")) link = URI(pageUrl).resolve(link).toString()
                    Log.d(TAG, "🔎 Found hls directly in unpacked payload -> $link")
                    return link
                }
                return null
            }

            val jsonRaw = match.groupValues[1].replace("'", "\"")

            val map = mutableMapOf<String, String>()
            try {
                val jo = JSONObject(jsonRaw)
                val keys = jo.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    try {
                        map[k] = jo.getString(k)
                    } catch (_: Exception) {

                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "JSONObject parse failed, falling back to regex: ${e.message}")
                val pairRegex = Regex(""""([^"]+)"\s*:\s*"([^"]+)"""")
                for (m in pairRegex.findAll(jsonRaw)) {
                    map[m.groupValues[1]] = m.groupValues[2]
                }
            }

            var link = map["hls4"] ?: map["hls"] ?: ""
            if (link.isBlank()) {
                Log.w(TAG, "❌ لم يتم العثور على hls/hls4 في JSON المُفكّك.")
                return null
            }
            link = link.replace("\\/", "/")
            if (link.startsWith("/")) {
                link = URI(pageUrl).resolve(link).toString()
            }

            Log.d(TAG, "✅ Extracted HLS: $link")
            return link
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error extracting EarnVids/StreamHG: ${e.message}", e)
            return null
        }
    }

    /**
     * يطابق منطق unpack_packer_simple من بايثون.
     * يُعيد payload بعد استبدال الرموز بالرموز الحقيقية وفق symtab.
     * يقوم باستبدال مراجع المتصفح الشائعة لكي لا تحتاج بيئة JS.
     */
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
            Log.w(TAG, "unpackPackerSimple failed: ${e.message}")
            return null
        }
    }
}
