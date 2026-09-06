package com.yacin

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Base64
import android.util.Log

class YacineTVProvider : MainAPI() {
    override var mainUrl = "https://def.ycnapi.com/api"
    private val fallbackUrl = "https://deft.yacinelive.com/api"

    override var name = "Yacine TV"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Live)

    private val baseKey = "c!xZj+N9&G@Ev@vw"

    data class LinkData(
        val id: String,
        val name: String,
        val poster: String?
    )

    private fun decrypt(encryptedText: String, tHeader: String): String {
        return try {
            val fullKey = baseKey + tHeader
            val decodedBytes = Base64.decode(encryptedText.trim(), Base64.DEFAULT)
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor fullKey[i % fullKey.length].code).toByte()
            }
            String(result)
        } catch (e: Exception) { "" }
    }

    private suspend fun fetchYacine(path: String): YacineResponse? {
        val endpoints = listOf(mainUrl, fallbackUrl)
        for (baseUrl in endpoints) {
            try {
                val fullUrl = "$baseUrl/$path".replace("//", "/").replace("https:/", "https://")
                val response = app.get(fullUrl, headers = mapOf("User-Agent" to "okhttp/4.12.0"), timeout = 10)
                if (response.code == 200) {
                    val tHeader = response.headers["t"] ?: ""
                    val decryptedJson = decrypt(response.text, tHeader)
                    return parseJson<YacineResponse>(decryptedJson)
                }
            } catch (e: Exception) { continue }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = fetchYacine("categories")?.data ?: emptyList()
        val homePageLists = categories.mapNotNull { cat ->
            val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
            if (channels.isEmpty()) return@mapNotNull null

            val channelItems = channels.map { chan ->
                val data = LinkData(chan.id.toString(), chan.name ?: "", chan.logo).toJson()
                newLiveSearchResponse(chan.name ?: "Unknown", data, TvType.Live) {
                    this.posterUrl = chan.logo
                }
            }
            HomePageList(cat.name ?: "Category", channelItems)
        }
        return newHomePageResponse(homePageLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val categories = fetchYacine("categories")?.data ?: emptyList()
        val results = mutableListOf<SearchResponse>()
        categories.forEach { cat ->
            val channels = fetchYacine("categories/${cat.id}/channels")?.data ?: emptyList()
            channels.forEach { chan ->
                if (chan.name?.contains(query, ignoreCase = true) == true) {
                    val data = LinkData(chan.id.toString(), chan.name ?: "", chan.logo).toJson()
                    results.add(
                        newLiveSearchResponse(chan.name, data, TvType.Live) {
                            this.posterUrl = chan.logo
                        }
                    )
                }
            }
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val data = parseJson<LinkData>(url)
        return newMovieLoadResponse(
            data.name,
            url,
            TvType.Live,
            url
        ) {
            this.posterUrl = data.poster
            this.plot = "شاهد بث مباشر لقناة ${data.name}"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val responseData = fetchYacine("channel/${linkData.id}")
        val streams = responseData?.data ?: return false

        streams.forEach { stream ->
            val finalUrl = stream.url?.replace("www.elahmad.coo", "www.elahmad.com") ?: ""
            if (finalUrl.isNotEmpty()) {
                val streamHeaders = mutableMapOf<String, String>()
                stream.headers?.forEach { (key, value) ->
                    if (value is String) streamHeaders[key] = value
                }
                if (!streamHeaders.containsKey("User-Agent")) {
                    streamHeaders["User-Agent"] = "okhttp/4.12.0"
                }

                callback.invoke(
                    newExtractorLink(
                        this.name,
                        stream.name ?: "Server",
                        finalUrl
                    ) {
                        this.headers = streamHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        return true
    }

    data class YacineResponse(
        @JsonProperty("data") val data: List<YacineData>? = null
    )

    data class YacineData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("headers") val headers: Map<String, Any>? = null
    )
}