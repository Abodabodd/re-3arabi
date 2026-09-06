package com.arabseed

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class GameHubExtractor : ExtractorApi() {
    override val name = "سيرفر عرب سيد"
    override val mainUrl = "https://m.reviewrate.net"
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
            val qualityStr = url.substringAfter("#quality=", "").substringBefore("#")
            var cleanUrl = url.substringBefore("#quality=")
            cleanUrl = cleanUrl.replace("m.arabseed.me", "m.reviewrate.net")

            val displayName = if (qualityStr.isNotBlank()) "$name - ${qualityStr}p" else name
            Log.d(name, "Extracted Quality: $qualityStr | Clean URL: $cleanUrl | Display Name: $displayName")

            Log.i(name, "Step 1: GET page -> $cleanUrl (referer=$referer)")
            val initialResponse = app.get(cleanUrl, referer = referer ?: mainUrl)
            val html = initialResponse.text
            val finalResolvedUrl = initialResponse.url // الرابط النهائي بعد إعادة التوجيه

            Log.i(name, "Step 1 Completed. Final Resolved URL: $finalResolvedUrl")
            Log.d(name, "GET page snippet (first 500 chars):\n${html.take(500)}")
            var finalDomain = mainUrl
            try {
                val urlObj = java.net.URL(finalResolvedUrl)
                finalDomain = "${urlObj.protocol}://${urlObj.host}"
                Log.d(name, "Parsed final domain for AJAX: $finalDomain")
            } catch (e: Exception) {
                Log.e(name, "Failed to parse final domain, using fallback: $mainUrl")
            }

            val csrfToken = html.let { Regex("""['"]csrf_token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

            if (csrfToken.isNullOrBlank()) {
                Log.w(name, "Step 2: No csrf_token found! Trying to extract direct media links from HTML...")

                val mediaMatches = Regex("""https?://[^\s"']+\.(m3u8|mp4|mkv)""").findAll(html).toList()
                if (mediaMatches.isEmpty()) {
                    Log.w(name, "No direct media links found in HTML either.")
                } else {
                    mediaMatches.forEach { m ->
                        val link = m.value
                        Log.i(name, "-> Found direct media link: $link")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = displayName,
                                url = link,
                                type = if (link.endsWith("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = finalResolvedUrl // استخدام الرابط النهائي كـ Referer
                                this.quality = getQualityFromName(displayName)
                            }
                        )
                    }
                }
                return
            }

            Log.i(name, "Step 2: Found csrf_token: $csrfToken")

            val objId = cleanUrl.substringAfter("embed-", "").substringBefore(".html")
            if (objId.isBlank()) {
                Log.w(name, "Warning: Could not extract embed ID (objId) from URL: $cleanUrl")
            } else {
                Log.d(name, "Extracted POST ID (objId): $objId")
            }
            val ajaxUrl = "${finalDomain}/get__watch__server/"
            Log.i(name, "Step 3: Preparing POST AJAX request to -> $ajaxUrl")

            val postResponse = app.post(
                ajaxUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to finalResolvedUrl, // استخدام الرابط النهائي
                    "Origin" to finalDomain        // استخدام النطاق النهائي
                ),
                data = mapOf(
                    "post_id" to objId,
                    "csrf_token" to csrfToken
                )
            ).text

            Log.d(name, "POST response snippet (first 500 chars):\n${postResponse.take(500)}")

            val iframeMatches = Regex("""src=["'](https?://[^"']+)["']""").findAll(postResponse).toList()
            if (iframeMatches.isNotEmpty()) {
                iframeMatches.forEach { match ->
                    val iframeUrl = match.groupValues[1]
                    Log.i(name, "-> Step 4a: Extracted inner iframe URL from POST response: $iframeUrl")
                    loadExtractor(iframeUrl, finalResolvedUrl, subtitleCallback, callback)
                }
            } else {
                Log.d(name, "No inner iframe URLs found in POST response.")
            }

            val m3u8Matches = Regex("""https?://[^\s"']+\.m3u8""").findAll(postResponse).toList()
            if (m3u8Matches.isNotEmpty()) {
                m3u8Matches.forEach { m ->
                    val m3u8 = m.value
                    Log.i(name, "-> Step 4b: Found direct m3u8 in POST response: $m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$displayName M3U8",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = finalResolvedUrl
                            if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                        }
                    )
                }
            } else {
                Log.d(name, "No m3u8 URLs found in POST response.")
            }

            Log.i(name, "================ EXTRACTOR FINISHED ================")

        } catch (e: Exception) {
            Log.e(name, "CRITICAL ERROR in GameHubExtractor", e)
        }
    }
}


class GameHubExtractor1 : ExtractorApi() {
    override val name = "arabseed"
    override val mainUrl = "https://m.arabseed.me" // يمكنك إبقاءها هكذا لأن الكود سيقوم بالاستبدال بالأسفل
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
            val qualityStr = url.substringAfter("#quality=", "").substringBefore("#")
            var cleanUrl = url.substringBefore("#quality=")
            cleanUrl = cleanUrl.replace("m.arabseed.me", "m.reviewrate.net")

            val displayName = if (qualityStr.isNotBlank()) "$name - ${qualityStr}p" else name
            Log.d(name, "Extracted Quality: $qualityStr | Clean URL: $cleanUrl | Display Name: $displayName")

            Log.i(name, "Step 1: GET page -> $cleanUrl (referer=$referer)")
            val initialResponse = app.get(cleanUrl, referer = referer ?: mainUrl)
            val html = initialResponse.text
            val finalResolvedUrl = initialResponse.url

            Log.i(name, "Step 1 Completed. Final Resolved URL: $finalResolvedUrl")

            var finalDomain = mainUrl
            try {
                val urlObj = java.net.URL(finalResolvedUrl)
                finalDomain = "${urlObj.protocol}://${urlObj.host}"
                Log.d(name, "Parsed final domain for AJAX: $finalDomain")
            } catch (e: Exception) {
                Log.e(name, "Failed to parse final domain, using fallback: $mainUrl")
            }

            val csrfToken = html.let { Regex("""['"]csrf_token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

            if (csrfToken.isNullOrBlank()) {
                Log.w(name, "Step 2: No csrf_token found! Trying to extract direct media links from HTML...")

                val mediaMatches = Regex("""https?://[^\s"']+\.(m3u8|mp4|mkv)""").findAll(html).toList()
                if (mediaMatches.isEmpty()) {
                    Log.w(name, "No direct media links found in HTML either.")
                } else {
                    mediaMatches.forEach { m ->
                        val link = m.value
                        Log.i(name, "-> Found direct media link: $link")
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = displayName,
                                url = link,
                                type = if (link.endsWith("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = finalResolvedUrl
                            }
                        )
                    }
                }
                return
            }

            Log.i(name, "Step 2: Found csrf_token: $csrfToken")

            val objId = cleanUrl.substringAfter("embed-", "").substringBefore(".html")
            if (objId.isBlank()) {
                Log.w(name, "Warning: Could not extract embed ID (objId) from URL: $cleanUrl")
            } else {
                Log.d(name, "Extracted POST ID (objId): $objId")
            }

            val ajaxUrl = "${finalDomain}/get__watch__server/"
            Log.i(name, "Step 3: Preparing POST AJAX request to -> $ajaxUrl")

            val postResponse = app.post(
                ajaxUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to finalResolvedUrl,
                    "Origin" to finalDomain
                ),
                data = mapOf(
                    "post_id" to objId,
                    "csrf_token" to csrfToken
                )
            ).text

            val iframeMatches = Regex("""src=["'](https?://[^"']+)["']""").findAll(postResponse).toList()
            if (iframeMatches.isNotEmpty()) {
                iframeMatches.forEach { match ->
                    val iframeUrl = match.groupValues[1]
                    Log.i(name, "-> Step 4a: Extracted inner iframe URL from POST response: $iframeUrl")
                    loadExtractor(iframeUrl, finalResolvedUrl, subtitleCallback, callback)
                }
            } else {
                Log.d(name, "No inner iframe URLs found in POST response.")
            }

            val m3u8Matches = Regex("""https?://[^\s"']+\.m3u8""").findAll(postResponse).toList()
            if (m3u8Matches.isNotEmpty()) {
                m3u8Matches.forEach { m ->
                    val m3u8 = m.value
                    Log.i(name, "-> Step 4b: Found direct m3u8 in POST response: $m3u8")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "$displayName M3U8",
                            url = m3u8,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = finalResolvedUrl
                            if (qualityStr.isNotBlank()) this.quality = getQualityFromName(qualityStr)
                        }
                    )
                }
            } else {
                Log.d(name, "No m3u8 URLs found in POST response.")
            }

            Log.i(name, "================ EXTRACTOR FINISHED ================")

        } catch (e: Exception) {
            Log.e(name, "CRITICAL ERROR in GameHubExtractor1", e)
        }
    }
}