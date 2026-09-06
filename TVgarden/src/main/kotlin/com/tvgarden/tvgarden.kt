package com.tvgarden

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class FamelackProvider : MainAPI() {
    override var mainUrl = "https://famelack.com"
    override var name = "TVgarden (Famelack)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "en"
    override val hasMainPage = true
    private val allChannelsUrl = "https://raw.githubusercontent.com/famelack/famelack-data/refs/heads/main/tv/raw/categories/all.json"
    private val countriesMetadataUrl = "https://raw.githubusercontent.com/famelack/famelack-data/refs/heads/main/tv/raw/countries_metadata.json"
    private val defaultPoster = "https://famelack.com/assets/favicons/favicon-512.png"
    private var cachedChannels: List<RawChannel>? = null
    private var cachedCountries: Map<String, CountryMeta>? = null

    private suspend fun getChannels(): List<RawChannel> {
        if (cachedChannels == null) {
            val response = app.get(allChannelsUrl).text
            cachedChannels = parseJson<List<RawChannel>>(response)
        }
        return cachedChannels ?: emptyList()
    }

    private suspend fun getCountries(): Map<String, CountryMeta> {
        if (cachedCountries == null) {
            val response = app.get(countriesMetadataUrl).text
            cachedCountries = parseJson<Map<String, CountryMeta>>(response)
        }
        return cachedCountries ?: emptyMap()
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val channels = getChannels()

        return channels.filter { ch ->
            val hasStream = ch.sources?.streams?.isNotEmpty() == true
            val hasYoutube = ch.sources?.youtube?.isNotEmpty() == true
            (hasStream || hasYoutube) && (ch.name?.contains(query, ignoreCase = true) == true)
        }.mapNotNull { ch ->
            val targetUrl = ch.sources?.streams?.firstOrNull() ?: ch.sources?.youtube?.firstOrNull()
            if (targetUrl.isNullOrEmpty()) return@mapNotNull null

            newLiveSearchResponse(
                name = ch.name ?: "Unknown Channel",
                url = targetUrl,
            ) {
                this.posterUrl = defaultPoster
            }
        }
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<HomePageList>()

        val countriesMap = getCountries()
        val allChannels = getChannels()

        val validCountries = countriesMap.entries.filter { it.value.hasChannels }.toList()
        val itemsPerPage = 10
        val pagedCountries = validCountries.drop((page - 1) * itemsPerPage).take(itemsPerPage)
        val hasNext = validCountries.size > page * itemsPerPage

        if (pagedCountries.isEmpty()) return newHomePageResponse(emptyList(), hasNext = false)

        val channelsByCountry = allChannels.groupBy { it.country?.lowercase() }

        for ((countryCode, meta) in pagedCountries) {
            val codeLower = countryCode.lowercase()
            val countryChannels = channelsByCountry[codeLower] ?: emptyList()

            val searchResponses = countryChannels.mapNotNull { ch ->
                val targetUrl = ch.sources?.streams?.firstOrNull() ?: ch.sources?.youtube?.firstOrNull()
                if (targetUrl.isNullOrEmpty()) return@mapNotNull null

                newLiveSearchResponse(
                    name = ch.name ?: "Unknown",
                    url = targetUrl,
                ) {
                    this.posterUrl = defaultPoster
                }
            }

            if (searchResponses.isNotEmpty()) {
                items.add(HomePageList(meta.country, searchResponses))
            }
        }

        return newHomePageResponse(items, hasNext = hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse(
            name = "Live Stream",
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = defaultPoster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.contains("youtube.com", ignoreCase = true) || data.contains("youtu.be", ignoreCase = true)) {
            return loadExtractor(data, subtitleCallback, callback)
        }

        val isM3u8 = data.contains(".m3u8", ignoreCase = true)

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = data,
            ) {
                referer = ""
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    data class RawChannel(
        @JsonProperty("nanoid") val nanoid: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("country") val country: String? = null,
        @JsonProperty("sources") val sources: ChannelSources? = null // تمت إضافة كائن sources
    )

    data class ChannelSources(
        @JsonProperty("streams") val streams: List<String>? = emptyList(),
        @JsonProperty("youtube") val youtube: List<String>? = emptyList()
    )

    data class CountryMeta(
        @JsonProperty("country") val country: String,
        @JsonProperty("hasChannels") val hasChannels: Boolean
    )
}