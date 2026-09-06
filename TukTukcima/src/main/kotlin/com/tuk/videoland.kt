package com.tuk

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class VideolandExtractor : ExtractorApi() {
    override var name = "Videoland"
    override var mainUrl = "https://videoland.sbs"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Step 1: Fetching page -> $url")
            val response = app.get(url, referer = referer ?: mainUrl).text
            val unpackedHtml = JsUnpacker(response).unpack() ?: response
            val regex = Regex("""https://[^"']+?/hls\d+/[^"']+?/master\.(?:txt|m3u8)(?:\?[^"']*)?""")

            val matches = regex.findAll(unpackedHtml).toList()

            if (matches.isEmpty()) {
                Log.w(name, "No media links found in unpacked HTML")
                return
            }
            matches.forEach { match ->
                val link = match.value
                Log.d(name, "Found media link: $link")
                val isM3u8 = link.contains(".m3u8") || link.contains(".txt")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = link,
                    ){
                        this.referer = "https://videoland.sbs/"
                        quality = Qualities.Unknown.value
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        headers = mapOf(
                            "sec-ch-ua-platform" to "\"Android\"",
                            "sec-ch-ua" to "\"Chromium\";v=\"148\", \"Google Chrome\";v=\"148\", \"Not/A)Brand\";v=\"99\"",
                            "sec-ch-ua-mobile" to "?1",
                            "accept" to "*/*",
                            "origin" to "https://videoland.sbs",
                            "sec-fetch-site" to "cross-site",
                            "sec-fetch-mode" to "cors",
                            "sec-fetch-dest" to "empty"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(name, "Error in Videoland extractor", e)
        }
    }
}