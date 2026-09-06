package com.viu


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import java.util.UUID
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils
import android.R.attr.mimeType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
class Viu : MainAPI() {
    override var mainUrl = "https://www.viu.com"
    override var name = "Viu"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val mobileApiUrl = "https://api-gateway-global.viu.com/api/mobile"
    private val tokenUrl = "https://api-gateway-global.viu.com/api/auth/token"
    private val playbackUrl = "https://api-gateway-global.viu.com/api/playback/distribute"

    private val areaId = "1004" // Iraq/MENA
    private val countryCode = "IQ"
    private val languageId = "6" // Arabic
    private var cachedToken: String? = null
    private var tokenExpiry: Long = 0
    private val deviceId = UUID.randomUUID().toString()
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12)",
        "Accept" to "application/json",
        "Referer" to "https://www.viu.com/",
        "Origin" to "https://www.viu.com"
    )

    private suspend fun getAuthToken(): String {
        val currentTime = System.currentTimeMillis() / 1000
        if (cachedToken != null && currentTime < tokenExpiry) {
            return cachedToken!!
        }

        val payload = mapOf(
            "countryCode" to countryCode,
            "platform" to "android",
            "platformFlagLabel" to "phone",
            "language" to languageId,
            "deviceId" to deviceId,
            "dataTrackingDeviceId" to UUID.randomUUID().toString(),
            "osVersion" to "33",
            "appVersion" to "2.23.0",
            "buildVersion" to "790",
            "carrierId" to "0",
            "carrierName" to "null",
            "appBundleId" to "com.vuclip.viu",
            "flavour" to "all"
        )

        val response = app.post(
            tokenUrl,
            headers = baseHeaders + mapOf("Content-Type" to "application/x-www-form-urlencoded"),
            data = payload
        ).parsedSafe<TokenResponse>()

        val token = response?.token ?: response?.data?.token
        ?: throw Error("Failed to get Auth Token")

        val expiresIn = response?.expiresIn ?: response?.data?.expiresIn ?: 3600

        cachedToken = token
        tokenExpiry = currentTime + expiresIn

        return token
    }

    private suspend fun getAuthenticatedHeaders(): Map<String, String> {
        val token = getAuthToken()
        return baseHeaders + mapOf("Authorization" to "Bearer $token")
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val headers = getAuthenticatedHeaders()
        val items = ArrayList<HomePageList>()
        val url = "$mobileApiUrl?r=/home/index" +
                "&platform_flag_label=phone" +
                "&language_flag_id=$languageId" +
                "&ut=0" +
                "&area_id=$areaId" +
                "&os_flag_id=2" +
                "&countryCode=$countryCode"

        val response = app.get(url, headers = headers).parsedSafe<ViuHomeResponse>()
        val data = response?.data ?: return newHomePageResponse(items)
        if (!data.banners.isNullOrEmpty()) {
            val bannerItems = data.banners.mapNotNull { it.toSearchResponse() }
            if (bannerItems.isNotEmpty()) {
                items.add(HomePageList("Featured", bannerItems))
            }
        }
        data.grids?.forEach { grid ->
            val title = grid.name ?: "Unknown Category"
            val products = grid.products?.mapNotNull { it.toSearchResponse() }

            if (!products.isNullOrEmpty()) {
                items.add(HomePageList(title, products))
            }
        }

        return newHomePageResponse(items)
    }
    private fun ViuHomeItem.toSearchResponse(): SearchResponse? {
        val name = this.seriesName ?: this.title?.takeIf { it.isNotEmpty() } ?: this.synopsis?.split("-")?.firstOrNull() ?: "Unknown"
        val image = this.coverLandscapeImage
            ?: this.seriesCoverLandscapeImage
            ?: this.imageUrl // البانر عادة يكون أفقي
            ?: this.coverImageUrl // احتياطي عمودي
            ?: this.productImageUrl

        val isMovieType = this.isMovie == 1
        val type = if (isMovieType) TvType.Movie else TvType.TvSeries

        val finalId = if (isMovieType) (this.productId ?: this.id) else (this.seriesId ?: this.id)
        if (finalId == null) return null

        val dataUrl = "$mainUrl/load?type=${if (isMovieType) "movie" else "series"}&id=$finalId"

        return if (isMovieType) {
            newMovieSearchResponse(name, dataUrl, type) {
                this.posterUrl = image
            }
        } else {
            newTvSeriesSearchResponse(name, dataUrl, type) {
                this.posterUrl = image
            }
        }


    }    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val headers = getAuthenticatedHeaders()
        val url =
            "$mobileApiUrl?platform_flag_label=web&r=/search/video" +
                    "&keyword=$query&page=$page&limit=20" +
                    "&area_id=$areaId&language_flag_id=$languageId"

        val resp = app.get(url, headers = headers)
            .parsedSafe<ViuSearchResponse>()

        val results = ArrayList<SearchResponse>()
        resp?.data?.series?.forEach { item ->
            val seriesId = item.seriesId ?: item.id ?: return@forEach
            val title = item.seriesName ?: item.name ?: return@forEach

            val dataUrl = "$mainUrl/load?type=series&id=$seriesId"

            results.add(
                newTvSeriesSearchResponse(
                    title,
                    dataUrl,
                    TvType.TvSeries
                ) {
                    posterUrl = item.coverImage ?: item.posterUrl
                }
            )
        }
        resp?.data?.movies?.forEach { item ->
            val productId = item.productId ?: return@forEach
            val title = item.name ?: item.title ?: return@forEach

            val dataUrl = "$mainUrl/load?type=movie&id=$productId"

            results.add(
                newMovieSearchResponse(
                    title,
                    dataUrl,
                    TvType.Movie
                ) {
                    posterUrl = item.coverImage ?: item.posterUrl
                }
            )
        }
        val merged = results
        return newSearchResponseList(merged, merged.isNotEmpty())
    }



    override suspend fun load(url: String): LoadResponse? {
        val headers = getAuthenticatedHeaders()
        val uri = android.net.Uri.parse(url)
        val seriesId = uri.getQueryParameter("id") ?: return null
        val epUrl = "$mobileApiUrl?platform_flag_label=phone&os_flag_id=2" +
                "&r=/vod/product-list" +
                "&series_id=$seriesId" +
                "&size=1000" +
                "&area_id=$areaId" +
                "&language_flag_id=$languageId"

        val resp = app.get(epUrl, headers = headers).parsedSafe<ViuEpisodeListResponse>()
        val products = resp?.data?.products ?: return null
        if (products.isEmpty()) return null
        val episodes = products.mapNotNull { ep ->
            val ccsId = ep.ccsProductId ?: return@mapNotNull null
            val productId = ep.productId ?: return@mapNotNull null

            newEpisode(ccsId) {
                data = mapOf(
                    "ccs" to ccsId,
                    "pid" to productId
                ).toJson()
                name = ep.synopsis?.trim() ?: "Episode ${ep.number}"

                episode = ep.number?.toIntOrNull()
                posterUrl = ep.coverImage
                description = ep.description
            }
        }.sortedBy { it.episode }
        val first = products.firstOrNull()
        val seriesTitle = first?.seriesCategoryName ?: "Unknown Series" // أو يمكن جلبه من loadLinks لاحقاً

        return newTvSeriesLoadResponse(
            first?.synopsis?.split("-")?.firstOrNull()?.trim() ?: "Viu Series", // محاولة استخراج اسم المسلسل من اسم الحلقة
            url,
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = first?.coverImage
            plot = first?.description
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[VIU-DEBUG] ================= START LOADLINKS =================")
        return try {
            val json = AppUtils.parseJson<Map<String, String>>(data)
            val ccsId = json["ccs"] ?: return false.also { println("[VIU-DEBUG] ❌ Error: ccsId is null") }
            val productId = json["pid"] ?: return false.also { println("[VIU-DEBUG] ❌ Error: productId is null") }

            println("[VIU-DEBUG] Processing Product ID: $productId | CCS ID: $ccsId")

            val headers = mapOf(
                "Authorization" to "Bearer ${getAuthToken()}",
                "User-Agent" to "okhttp/4.12.0",
                "Accept" to "application/json",
                "Referer" to "https://www.viu.com/"
            )
            val detailUrl = "$mobileApiUrl?r=/vod/detail" +
                    "&product_id=$productId" +
                    "&platform_flag_label=phone" +
                    "&language_flag_id=$languageId" +
                    "&area_id=$areaId" +
                    "&os_flag_id=2" +
                    "&countryCode=$countryCode"

            println("[VIU-DEBUG] Fetching Detail URL: $detailUrl")

            val rawResponse = app.get(detailUrl, headers = headers).text

            val detailResp = AppUtils.parseJson<ViuDetailResponse>(rawResponse)
            val currentProduct = detailResp.data?.currentProduct

            if (currentProduct == null) {
                println("[VIU-DEBUG] ⚠️ Warning: current_product is null in JSON response!")
            } else {
                val subsList = currentProduct.subtitles
                println("[VIU-DEBUG] Found Subtitles List Size: ${subsList?.size ?: 0}")

                subsList?.forEachIndexed { index, sub ->
                    val subUrl = sub.url ?: sub.subtitleUrl
                    val subName = sub.name ?: "Unknown"
                    val subCode = sub.isoCode ?: sub.code ?: "und"

                    println("[VIU-DEBUG] [$index] Sub: $subName ($subCode) -> URL: $subUrl")

                    if (!subUrl.isNullOrEmpty()) {
                        subtitleCallback(
                            newSubtitleFile(
                                lang = subCode,
                                url = subUrl
                            )
                        )
                        println("[VIU-DEBUG] ✅ Added Subtitle: $subName")
                    } else {
                        println("[VIU-DEBUG] ❌ Skipped Subtitle: URL is empty")
                    }
                }
            }
            println("[VIU-DEBUG] Fetching Playback Stream...")
            val playUrl = "$playbackUrl?ccs_product_id=$ccsId" +
                    "&platform_flag_label=phone" +
                    "&language_flag_id=$languageId" +
                    "&area_id=$areaId"

            val playResp = app.get(playUrl, headers = headers).parsedSafe<ViuPlaybackResponse>()
            val streams = playResp?.data?.stream?.url

            if (streams.isNullOrEmpty()) {
                println("[VIU-DEBUG] ❌ No video streams found!")
            } else {
                println("[VIU-DEBUG] Found ${streams.size} video qualities.")
                streams.forEach { (q, url) ->
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "Viu ${q.uppercase()}",
                            url = url
                        ) {
                            referer = "https://www.viu.com/"
                            quality = when {
                                q.contains("1080") -> Qualities.P1080.value
                                q.contains("720") -> Qualities.P720.value
                                q.contains("480") -> Qualities.P480.value
                                q.contains("240") -> 240
                                else -> Qualities.Unknown.value
                            }
                        }
                    )
                }



            }

            println("[VIU-DEBUG] ================= END LOADLINKS =================")
            true
        } catch (e: Exception) {
            println("[VIU-DEBUG] 💥 Critical Error in loadLinks: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    data class ViuDetailResponse(
        @JsonProperty("data") val data: ViuDetailData?
    )

    data class ViuDetailData(
        @JsonProperty("current_product") val currentProduct: ViuProductDetail?
    )

    data class ViuProductDetail(
        @JsonProperty("product_id") val productId: String?,
        @JsonProperty("subtitle") val subtitles: List<ViuSubtitle>?
    )

    data class ViuSubtitle(
        @JsonProperty("name") val name: String?,
        @JsonProperty("code") val code: String?,
        @JsonProperty("iso_code") val isoCode: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("subtitle_url") val subtitleUrl: String?
    )




    private fun ViuItem.toSearchResponse(): SearchResponse? {
        val id = this.productId ?: this.id ?: return null
        val title = this.seriesName ?: this.name ?: this.title ?: "Unknown"
        val image = this.coverImage ?: this.posterUrl

        return newMovieSearchResponse(title, "https://www.viu.com/product/$id", TvType.TvSeries) {
            this.posterUrl = image
        }
    }

    data class TokenResponse(
        @JsonProperty("token") val token: String?,
        @JsonProperty("expires_in") val expiresIn: Long?,
        @JsonProperty("data") val data: TokenData?
    )

    data class TokenData(
        @JsonProperty("token") val token: String?,
        @JsonProperty("expires_in") val expiresIn: Long?
    )

    data class ViuResponse(
        @JsonProperty("data") val data: ViuData?
    )

    data class ViuSearchResponse(
        @JsonProperty("data") val data: ViuSearchData?
    )

    data class ViuSearchData(
        @JsonProperty("series") val series: List<ViuItem>?,
        @JsonProperty("movie") val movies: List<ViuItem>?
    )

    data class ViuData(
        @JsonProperty("items") val items: List<ViuItem>?
    )








    data class ViuSeries(
        @JsonProperty("id") val id: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("series_id") val seriesId: String?,
    )

    data class ViuItem(
        @JsonProperty("product_id") val productId: String?,
        @JsonProperty("id") val id: String?,
        @JsonProperty("series_id") val seriesId: String?,
        @JsonProperty("series_name") val seriesName: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("cover_image_url") val coverImage: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("is_movie") val isMovie: Int?,
        @JsonProperty("number") val number: String?,
        @JsonProperty("ccs_product_id") val ccsProductId: String?,
        @JsonProperty("subtitles") val subtitles: List<ViuSubtitle>?
    )



    data class ViuPlaybackResponse(
        @JsonProperty("data") val data: PlaybackData?
    )

    data class PlaybackData(
        @JsonProperty("stream") val stream: PlaybackStream?
    )

    data class PlaybackStream(
        @JsonProperty("url") val url: Map<String, String>?
    )
    data class ViuEpisodeListResponse(
        @JsonProperty("data") val data: ViuEpisodeListData?
    )

    data class ViuEpisodeListData(
        @JsonProperty("product_list") val products: List<ViuProductItem>?
    )

    data class ViuProductItem(
        @JsonProperty("product_id") val productId: String?,
        @JsonProperty("ccs_product_id") val ccsProductId: String?,
        @JsonProperty("number") val number: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("description") val description: String?,

        @JsonProperty("cover_image_url") val coverImage: String?,
        @JsonProperty("series_category_name") val seriesCategoryName: String?
    )

    data class ViuHomeResponse(
        @JsonProperty("data") val data: ViuHomeData?
    )

    data class ViuHomeData(
        @JsonProperty("banner") val banners: List<ViuHomeItem>?,
        @JsonProperty("grid") val grids: List<ViuHomeGrid>?
    )

    data class ViuHomeGrid(
        @JsonProperty("name") val name: String?,
        @JsonProperty("product") val products: List<ViuHomeItem>?
    )


    data class ViuHomeItem(
        @JsonProperty("id") val id: String?,
        @JsonProperty("product_id") val productId: String?,
        @JsonProperty("series_id") val seriesId: String?,

        @JsonProperty("series_name") val seriesName: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("image_url") val imageUrl: String?, // عادة للبانر
        @JsonProperty("cover_image_url") val coverImageUrl: String?,
        @JsonProperty("product_image_url") val productImageUrl: String?,
        @JsonProperty("cover_landscape_image_url") val coverLandscapeImage: String?,
        @JsonProperty("series_cover_landscape_image_url") val seriesCoverLandscapeImage: String?,

        @JsonProperty("is_movie") val isMovie: Int?
    )
}