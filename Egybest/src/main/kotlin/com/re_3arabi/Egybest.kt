package com.re_3arabi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Egybest : MainAPI() {
    override var mainUrl = "https://egybest.to"
    override var name = "Egybest"
    override val hasMainPage = true
    override var lang = "ar"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/explore/?q=$query"
        val document = app.get(url).document
        return document.select("div.movie").mapNotNull {
            val title = it.selectFirst(".title")?.text() ?: return@mapNotNull null
            val href = it.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val posterUrl = it.selectFirst("img")?.attr("src")
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }
}
