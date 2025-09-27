package com.arabseed
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
class GameHubExtractor : ExtractorApi() {
    override var name = "سيرفر عرب سيد"
    override var mainUrl = "https://m.reviewrate.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Step 1: GET page -> $url  (referer=$referer)")
            val initialResponse = app.get(url, referer = referer ?: mainUrl)
            val html = initialResponse.text
            Log.d(name, "GET page snippet (first 1200 chars):\n${html.take(1200)}")

            val csrfToken = html.let { Regex("""['"]csrf_token['"]\s*:\s*['"]([^'"]+)['"]""").find(it)?.groupValues?.get(1) }

            if (csrfToken.isNullOrBlank()) {
                Log.w(name, "No csrf_token found — trying to extract media link directly from HTML")

                Regex("""https?://[^\s"']+\.(m3u8|mp4|mkv)""").findAll(html).forEach { m ->
                    val link = m.value
                    Log.d(name, "Found media link in page HTML: $link")
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = link,
                            type = if (link.endsWith("m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                        }
                    )
                }
                return
            }

            Log.d(name, "Found csrf_token: $csrfToken")

            val objId = url.substringAfter("embed-", "").substringBefore(".html")
            if (objId.isBlank()) {
                Log.w(name, "Could not extract embed ID from URL")
            }

            val ajaxUrl = "${mainUrl.trimEnd('/')}/get__watch__server/"
            Log.d(name, "POST -> $ajaxUrl (id=$objId)")

            val postResponse = app.post(
                ajaxUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url,
                    "Origin" to mainUrl
                ),
                data = mapOf(
                    "post_id" to objId,
                    "csrf_token" to csrfToken
                )
            ).text

            Log.d(name, "POST response snippet (first 1600 chars):\n${postResponse.take(1600)}")

            Regex("""src=["'](https?://[^"']+)["']""").findAll(postResponse).forEach { match ->
                val iframeUrl = match.groupValues[1]
                Log.d(name, "Extracted iframe URL from POST response: $iframeUrl")
                loadExtractor(iframeUrl, url, subtitleCallback, callback)
            }

            Regex("""https?://[^\s"']+\.m3u8""").findAll(postResponse).forEach { m ->
                val m3u8 = m.value
                Log.d(name, "Found m3u8 in POST response: $m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} M3U8",
                        url = m3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error in GameHub extractor", e)
        }
    }
}
