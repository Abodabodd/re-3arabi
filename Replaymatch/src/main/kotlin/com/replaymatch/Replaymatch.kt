package com.replaymatch

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope


data class MainCategory(val name: String, val url: String, val key: String)

class FullMatchShowsProvider(private val context: Context) : MainAPI() {

    override var name = "FullMatchShows"
    override var mainUrl = "https://fullmatchshows.com"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    private val categories = listOf(
        MainCategory("England - Premier League", "$mainUrl/leagues/premier-league/", "show_eng_premier"),
        MainCategory("England - ChampionShip", "$mainUrl/leagues/championship/", "show_eng_championship"),
        MainCategory("England - FA Cup", "$mainUrl/leagues/fa-cup/", "show_eng_fa_cup"),
        MainCategory("England - Carabao Cup", "$mainUrl/leagues/carabao-cup/", "show_eng_carabao"),
        MainCategory("Spain - La liga", "$mainUrl/leagues/la-liga/", "show_spa_liga"),
        MainCategory("Spain - Copa Del Rey", "$mainUrl/leagues/copa-del-rey/", "show_spa_copa"),
        MainCategory("Italy - Serie A", "$mainUrl/leagues/serie-a/", "show_ita_serie_a"),
        MainCategory("Italy - Coppa Italia", "$mainUrl/leagues/coppa-italia/", "show_ita_coppa"),
        MainCategory("Germany - DFB Pokal", "$mainUrl/leagues/dfb-pokal/", "show_ger_pokal"),
        MainCategory("Germany - BundesLiga", "$mainUrl/leagues/bundesliga/", "show_ger_bundesliga"),
        MainCategory("Netherland - Eredivisie", "$mainUrl/leagues/eredivisie/", "show_ned_eredivisie"),
        MainCategory("Europe - Champions League", "$mainUrl/leagues/champions-league/", "show_eur_champions"),
        MainCategory("Europe - Europa League", "$mainUrl/leagues/europa-league/", "show_eur_europa"),
        MainCategory("Europe - Nations League", "$mainUrl/leagues/nations-league/", "show_eur_nations"),
        MainCategory("Europe - Super Cup", "$mainUrl/leagues/super-cup/", "show_eur_super_cup"),
        MainCategory("International - Friendly Match", "$mainUrl/leagues/friendly-match/", "show_int_friendly"),
        MainCategory("International - Club Friendlies", "$mainUrl/leagues/club-friendlies/", "show_int_club_friendly"),
        MainCategory("International - World Cup Qualifiers", "$mainUrl/leagues/world-cup-qualifiers/", "show_int_wc_qualifiers"),
        MainCategory("Extras - Africa Cup", "$mainUrl/leagues/africa-cup/", "show_ext_africa"),
        MainCategory("Extras - Liga Portugal", "$mainUrl/leagues/liga-portugal/", "show_ext_portugal"),
        MainCategory("Extras - Saudi Pro League", "$mainUrl/leagues/saudi-pro-league/", "show_ext_saudi"),
        MainCategory("Extras - Turkish Super Lig", "$mainUrl/leagues/turkish-super-lig/", "show_ext_turkish")
    )


    private fun isCategoryEnabled(category: MainCategory): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getBoolean(category.key, false)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(if (page == 1) mainUrl else "$mainUrl/page/$page/").document
        val mainPageItems = parsePostItems(document.select("ul#posts-container li.post-item"))

        val lists = mutableListOf(
            HomePageList("Latest Matches", mainPageItems)
        )

        if (page == 1) {
            categories.forEach { category ->
                try {
                    if (!isCategoryEnabled(category)) {
                        println("Skipping category: ${category.name} (disabled in settings)")
                        return@forEach
                    }
                    val catDoc = app.get(category.url).document
                    val items = parsePostItems(catDoc.select("ul#posts-container li.post-item"))
                    lists.add(HomePageList(category.name, items.take(10)))
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        return newHomePageResponse(lists)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1.post-title.entry-title")?.text() ?: return null
        val posterUrl = fixUrl(document.selectFirst("figure.single-featured-image img")?.attr("src") ?: "")
        val plot = document.select("div.entry-content.entry.clearfix p").joinToString("\n") { it.text().trim() }
        val tags = document.select("div.post-bottom-meta.post-bottom-tags a").map { it.text().trim() }
        val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

        val recommendations = document.select("#related-posts .related-item").mapNotNull {
            val recTitleElement = it.selectFirst("h3.post-title a") ?: return@mapNotNull null
            val recTitle = recTitleElement.text()
            val recUrl = fixUrl(recTitleElement.attr("href"))
            val recPosterUrl = fixUrl(it.selectFirst("a.post-thumb img")?.attr("src") ?: "")
            val recYear = Regex("""\d{4}""").find(recTitle)?.value?.toIntOrNull()

            newMovieSearchResponse(
                name = recTitle,
                url = recUrl,
                type = TvType.Movie
            ) {
                this.posterUrl = recPosterUrl
                this.year = recYear
            }
        }

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = tags
            this.year = year
            this.recommendations = recommendations
        }
    }

    private fun parsePostItems(elements: List<Element>): List<SearchResponse> {
        return elements.mapNotNull {
            val titleElement = it.selectFirst("h2.post-title a") ?: return@mapNotNull null
            val title = titleElement.text()
            val url = fixUrl(titleElement.attr("href"))
            val posterUrl = fixUrl(it.selectFirst("a.post-thumb img")?.attr("src") ?: "")
            val year = Regex("""\d{4}""").find(title)?.value?.toIntOrNull()

            newMovieSearchResponse(
                name = title,
                url = url,
                type = TvType.Movie
            ) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? = coroutineScope {
        val encoded = URLEncoder.encode(query, "utf-8")

        val candidates = listOf(
            if (page <= 1) "$mainUrl/?s=$encoded" else "$mainUrl/page/$page/?s=$encoded",
            if (page <= 1) "$mainUrl/search/$encoded/" else "$mainUrl/search/$encoded/page/$page/"
        )

        val resultsPerPattern = candidates.map { url ->
            async {
                runCatching {
                    val doc = app.get(url).document
                    val items = parsePostItems(doc.select("ul#posts-container li.post-item"))
                    println("Search: tried $url -> found ${items.size} items")
                    items
                }.getOrDefault(emptyList())
            }
        }.awaitAll()

        val merged = resultsPerPattern.firstOrNull { it.isNotEmpty() } ?: emptyList()
        newSearchResponseList(merged, merged.isNotEmpty())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false
        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.isNotEmpty() && !src.contains("facebook.com") && !src.contains("google")) {
                if (processUrlForM3u8(src, data, callback)) foundLinks = true
            }
        }
        document.select("a.myButton").forEach { button ->
            val btnUrl = fixUrl(button.attr("href"))
            val btnText = button.text().trim()

            if (btnUrl.isNotEmpty() && btnUrl != data) {
                if (processUrlForM3u8(btnUrl, data, callback, btnText)) foundLinks = true
            }
        }

        return foundLinks
    }
    private suspend fun processUrlForM3u8(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        namePrefix: String = "Direct Stream"
    ): Boolean {
        return try {
            val response = app.get(url, referer = referer)
            val html = response.text
            var found = false
            val plInitRegex = Regex("""pl\.init\(['"](.*?)['"]\)""")
            val match = plInitRegex.find(html)

            if (match != null) {
                var m3u8Url = match.groupValues[1]
                if (m3u8Url.startsWith("//")) {
                    m3u8Url = "https:$m3u8Url"
                }

                if (m3u8Url.isNotEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = namePrefix,
                            url = m3u8Url,
                        ) {
                            this.referer = url
                            quality = Qualities.P720.value // أو Unknown
                        }
                    )
                    found = true
                }
            }
            if (!found) {
                found = loadExtractor(url, referer, { }, callback)
            }

            found
        } catch (e: Exception) {
            false
        }
    }

    private fun fixUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> mainUrl.trimEnd('/') + trimmed
            else -> mainUrl.trimEnd('/') + "/" + trimmed
        }
    }
}