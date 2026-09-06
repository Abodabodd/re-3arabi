package com.cimanow

import org.junit.Test
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.regex.Pattern
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder

class CimaNowTest {
    private fun decodeHtml(doc: Document): Document {
        val rawHtml = doc.outerHtml()
        val tag = "CimaKotlinDecoder"

        println("\n🔍 [$tag] ================= بدء تشخيص فك التشفير =================")

        try {
            val secMatcher = Pattern.compile("""data-[a-zA-Z0-9]+="(\d+)"""").matcher(rawHtml)
            val secMath = if (secMatcher.find()) {
                val secStr = secMatcher.group(1) ?: return doc
                secStr.toInt().also { println("✅ [1/5] الرقم السري: $it") }
            } else return doc
            val keyMathMatcher = Pattern.compile("""=\s*(?:[a-zA-Z0-9_]+\s*\+\s*(\d+)\s*\+\s*(\d+)|(\d+)\s*\+\s*(\d+)\s*\+\s*[a-zA-Z0-9_]+)\s*;""").matcher(rawHtml)
            val k = if (keyMathMatcher.find()) {
                val p1 = keyMathMatcher.group(1) ?: keyMathMatcher.group(3) ?: return doc
                val p2 = keyMathMatcher.group(2) ?: keyMathMatcher.group(4) ?: return doc
                (p1.toInt() + p2.toInt() + secMath).also { println("✅ [2/5] المفتاح (K): $it") }
            } else return doc
            var subtraction = 0
            val subMatcher = Pattern.compile("""-\s*(\d+)\s*[;)]""").matcher(rawHtml)
            while (subMatcher.find()) {
                val v = subMatcher.group(1)?.toIntOrNull() ?: continue
                if (v > 10) {
                    subtraction = v
                    break
                }
            }
            if (subtraction == 0) return doc
            println("✅ [3/5] رقم الطرح: $subtraction")
            var radix = 20
            val dynamicRadixMatcher = Pattern.compile("""var\s+[a-zA-Z0-9_]+\s*=\s*(\d+)\s*(/|\*|\+|-)\s*(\d+)\s*;""").matcher(rawHtml)
            if (dynamicRadixMatcher.find()) {
                val num1 = dynamicRadixMatcher.group(1)?.toIntOrNull() ?: 20
                val operator = dynamicRadixMatcher.group(2) ?: ""
                val num2 = dynamicRadixMatcher.group(3)?.toIntOrNull() ?: 1
                radix = when (operator) {
                    "/" -> num1 / num2
                    "*" -> num1 * num2
                    "+" -> num1 + num2
                    "-" -> num1 - num2
                    else -> 20
                }
                println("✅ [4/5] الأساس (Radix) عبر معادلة: $radix (من $num1 $operator $num2)")
            } else {
                val radixMatcher = Pattern.compile("""parseInt\([^,]+,\s*(\d+)\)""").matcher(rawHtml)
                while (radixMatcher.find()) {
                    val foundRadix = radixMatcher.group(1)?.toIntOrNull() ?: 10
                    if (foundRadix != 10) {
                        radix = foundRadix
                        println("✅ [4/5] الأساس (Radix) مباشر: $radix")
                        break
                    }
                }
            }
            val splitMatcher = Pattern.compile("""\.split\(\s*['"]([^'"]+)['"]\s*\)""").matcher(rawHtml)
            val delimiter = if (splitMatcher.find()) {
                val d = splitMatcher.group(1) ?: return doc
                d.also { println("✅ [5/5] الفاصل: '$it'") }
            } else return doc
            val arrayMatcher = Pattern.compile("""(?:var|let|const)\s+[a-zA-Z0-9_]+\s*=\s*(?:new Array\()?\[?(.*?)\]?\)?\s*;""", Pattern.DOTALL).matcher(rawHtml)
            var rawContent = ""
            while (arrayMatcher.find()) {
                val content = arrayMatcher.group(1) ?: ""
                if (content.length > 500) {
                    rawContent = content
                    break
                }
            }
            if (rawContent.isEmpty()) return doc

            val sbClean = StringBuilder(rawContent.length)
            for (i in 0 until rawContent.length) {
                val c = rawContent[i]
                if (c != '"' && c != '\'' && c != '\n' && c != '\r' && c != ' ' && c != ',') {
                    sbClean.append(c)
                }
            }
            val rawPayload = sbClean.toString()
            println("📦 [$tag] طول البيانات المشفرة: ${rawPayload.length}")
            val outputStream = ByteArrayOutputStream(rawPayload.length / 4)
            var startIndex = 0
            val payloadLength = rawPayload.length
            val delimiterLength = delimiter.length
            var chunkCount = 0

            while (startIndex < payloadLength) {
                var endIndex = rawPayload.indexOf(delimiter, startIndex)
                if (endIndex == -1) endIndex = payloadLength

                if (endIndex > startIndex) {
                    val chunk = rawPayload.substring(startIndex, endIndex)
                    decodeChunkFast(chunk, radix, subtraction, k, outputStream)
                    chunkCount++
                }
                startIndex = endIndex + delimiterLength
            }

            val decodedHtmlString = outputStream.toString("UTF-8")
            if (decodedHtmlString.isBlank()) return doc

            println("🎉 [$tag] تمت معالجة $chunkCount قطعة بنجاح! طول الـ HTML: ${decodedHtmlString.length}")
            return Jsoup.parse(decodedHtmlString)

        } catch (e: Exception) {
            println("💥 [$tag] Error: ${e.message}")
            return doc
        }
    }

    private fun decodeChunkFast(
        chunk: String,
        radix: Int,
        subtraction: Int,
        key: Int,
        out: ByteArrayOutputStream
    ) {
        try {
            val r = chunk.length % 4
            val paddedChunk = if (r > 0) chunk + "===".substring(0, 4 - r) else chunk
            val decodedBytes = Base64.getDecoder().decode(paddedChunk.replace(Regex("[\\s\\r\\n]"), ""))

            var num = 0L
            var found = false

            for (i in decodedBytes.indices) {
                val b = decodedBytes[i].toInt()
                val digitValue = when (b) {
                    in 48..57 -> b - 48
                    in 97..122 -> b - 87
                    in 65..90 -> b - 55
                    else -> -1
                }

                if (digitValue in 0 until radix) {
                    num = num * radix + digitValue
                    found = true
                }
            }

            if (found) {
                val fC = (num.toInt() - subtraction) xor key
                out.write(fC)
            }
        } catch (ignored: Exception) {
        }
    }
    private fun extractGroup(regex: String, text: String, errorMsg: String): String {
        val matcher = Pattern.compile(regex).matcher(text)
        if (matcher.find()) {
            return matcher.group(1) ?: throw Exception(errorMsg)
        }
        System.err.println("\n❌ [FAIL] فشل الاستخراج بالتعبير النمطي: $regex")
        System.err.println("📄 جزء من الاستجابة المستلمة (أول 1000 حرف):")
        System.err.println("--------------------------------------------------")
        System.err.println(text.take(1000))
        System.err.println("--------------------------------------------------\n")

        throw Exception(errorMsg)
    }

    private fun calculateHmacSha256(message: String, secret: String): String {
        val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val bytes = mac.doFinal(message.toByteArray())
        return Base64.getEncoder().encodeToString(bytes).replace("\n", "").replace("\r", "")
    }

    @Test
    fun runFullCimaNowDecryptionTest() {
        println("\n============================================================")
        println("🚀 [START] بدء اختبار جلب وتجاوز حماية رابط CimaNow")
        println("============================================================")

        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        val client = OkHttpClient.Builder().cookieJar(cookieJar).build()
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val mainReferer = "https://rm.freex2line.online/"
        val startUrl = "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4NSVkOCViMyVkOSU4NCVkOCViMyVkOSU4NC1hLXNob3AtZm9yLWtpbGxlcnMtJWQ4JWFjMi0lZDglYWQxLSVkOSU4NSVkOCVhYSVkOCViMSVkOCVhYyVkOSU4NSVkOCVhOS93YXRjaGluZy8="

        try {
            println("[1/7] 🌐 جاري إنشاء الجلسة...")
            val startRequest = Request.Builder()
                .url(startUrl)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .build()
            client.newCall(startRequest).execute().use { response ->
                println("   -> حالة طلب الجلسة الأول: ${response.code}")
            }
            println("[2/7] 📄 جاري جلب صفحة تخطي الرابط الأساسية...")
            val pageUrl = "https://rm.freex2line.online/2020/02/blog-post.html/"
            val pageRequest = Request.Builder()
                .url(pageUrl)
                .header("User-Agent", userAgent)
                .header("Referer", mainReferer)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .build()

            val html = client.newCall(pageRequest).execute().use { response ->
                println("   -> حالة طلب صفحة المقال: ${response.code}")
                response.body?.string() ?: throw Exception("الاستجابة فارغة تماماً")
            }
            println("[3/7] 🔍 تحليل نظام الحماية واستخراج المعطيات...")
            val ctxName = extractGroup("""window\.ptr_[a-zA-Z0-9_]+\s*=\s*'([^']+)'""", html, "Pointer (ptr_) not found")
            val mapData = extractGroup("""window\.map_[a-zA-Z0-9_]+\s*=\s*\{([^}]+)\}""", html, "Map (map_) not found")
            val ctxData = extractGroup("""window\['$ctxName'\]\s*=\s*\{([^}]+)\}""", html, "Context data not found")

            val chK = extractGroup("""ch:\s*'([^']+)'""", mapData, "Key 'ch' not found in map")
            val riK = extractGroup("""ri:\s*'([^']+)'""", mapData, "Key 'ri' not found in map")
            val keK = extractGroup("""ke:\s*'([^']+)'""", mapData, "Key 'ke' not found in map")
            val seK = extractGroup("""se:\s*'([^']+)'""", mapData, "Key 'se' not found in map")

            val ch = extractGroup("""'$chK':\s*'([^']+)'""", ctxData, "Value for 'ch' not found")
            val requestId = extractGroup("""'$riK':\s*'([^']+)'""", ctxData, "Value for request_id not found")
            val encryptedKeyB64 = extractGroup("""'$keK':\s*'([^']+)'""", ctxData, "Value for encrypted key not found")
            val sXorKey = extractGroup("""'$seK':\s*'([^']+)'""", ctxData, "Value for XOR key not found")
            println("[4/7] 🔓 فك تشفير المفتاح السري وتوليد HMAC...")
            val encryptedBytes = Base64.getDecoder().decode(encryptedKeyB64.replace(Regex("[\\s\\r\\n]"), ""))
            val secretKey = encryptedBytes.mapIndexed { index, byte ->
                (byte.toInt() xor sXorKey[index % sXorKey.length].code).toChar()
            }.joinToString("")

            println("   🔑 مفتاح التوقيع الفعلي: $secretKey")

            val fpBase64 = "TW96aWxsYS81Ll9f" // بصمة المتصفح الثابتة لـ Chrome
            val messageToSign = requestId + ch + fpBase64
            val hmacTokenEncoded = URLEncoder.encode(calculateHmacSha256(messageToSign, secretKey), "UTF-8")
            println("\n⏳ جاري الانتظار 11 ثانية لتخطي العداد الزمني للسيرفر...")
            Thread.sleep(11000)
            println("\n[5/7] 🚀 إرسال طلب جلب الرابط إلى الـ API...")
            val apiUrl = "https://rm.freex2line.online/2020/02/blog-post.html/get-link.php?request_id=$requestId&hmac_token=$hmacTokenEncoded&ch=$ch&fp=$fpBase64"
            val apiRequest = Request.Builder()
                .url(apiUrl)
                .header("User-Agent", userAgent)
                .header("Referer", pageUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("Accept", "*/*")
                .build()

            val watchPageUrl = client.newCall(apiRequest).execute().use { response ->
                response.body?.string()?.trim() ?: throw Exception("استجابة الـ API فارغة")
            }

            if (!watchPageUrl.startsWith("http")) {
                throw Exception("فشل الحصول على رابط المشاهدة، استجابة الخادم: $watchPageUrl")
            }
            println("   ✅ تم بنجاح! رابط صفحة المشاهدة هو: $watchPageUrl")
            println("\n[6/7] 📄 جلب صفحة المشاهدة وفك التشفير عن محتواها...")
            val watchPageRequest = Request.Builder().url(watchPageUrl).header("User-Agent", userAgent).header("Referer", pageUrl).build()
            val encryptedHtmlData = client.newCall(watchPageRequest).execute().use { response ->
                response.body?.string() ?: throw Exception("صفحة المشاهدة فارغة")
            }

            val doc = Jsoup.parse(encryptedHtmlData)
            val finalHtmlDoc = decodeHtml(doc)
            val finalHtml = finalHtmlDoc.outerHtml()

            println("\n================== [DECODED HTML CONTENT (SAMPLE)] ==================")
            println(finalHtml.take(2000) + "\n...[TRUNCATED]...")
            println("=====================================================================")
            assert(finalHtml.contains("watch") || finalHtml.contains("id=\"watch\"") || finalHtml.contains("class=\"btns\"")) {
                "فشل فك التشفير: لم يتم استخراج حاويات السيرفرات بنجاح."
            }
            println("\n🎉🎉🎉 [SUCCESS] تم اجتياز الاختبار وفك تشفير البيانات بنجاح! 🎉🎉🎉")

        } catch (e: Exception) {
            println("\n💥 [FATAL ERROR] فشل الاختبار: ${e.message}")
            e.printStackTrace()
            assert(false)
        }
    }
}