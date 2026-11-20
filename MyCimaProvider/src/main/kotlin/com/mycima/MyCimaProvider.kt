package com.mycima

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class MyCimaProvider : MainAPI() {
    override var lang = "ar"
    override var mainUrl = "https://mycima.recipes"
    override var name = "MyCima"
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes =
        setOf(TvType.TvSeries, TvType.Movie, TvType.Anime, TvType.AsianDrama)

    companion object {
        const val TAG = "MyCima"
        var redirectUrl: String? = null

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Mobile Safari/537.36"

        private const val REQUEST_DELAY = 700L
        private const val MAX_AJAX_RETRIES = 15

        private val streamWishPreferredDomains = listOf(
            "vibuxer.com",
            "hanerix.com",
            "niramirus.com",
            "hglamioz.com",
            "playnixes.com",
            "medixiru.com"
        )
    }

    private suspend fun baseUrl(): String {
        redirectUrl?.let { return it }

        return try {
            val response = app.get(mainUrl, allowRedirects = true)
            val finalUrl = response.url

            val base = try {
                val uri = URI(finalUrl)
                "${uri.scheme}://${uri.host}"
            } catch (e: Exception) {
                mainUrl
            }

            redirectUrl = base
            mainUrl = base
            Log.d(TAG, "Resolved main domain: $base")
            base
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun getHeaders(referer: String? = null): Map<String, String> {
        val activeBase = redirectUrl ?: mainUrl
        val safeReferer = referer?.takeIf { it.all { ch -> ch.code in 32..126 } } ?: activeBase

        return mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Referer" to safeReferer
        )
    }

    private suspend fun smartGet(
        url: String,
        referer: String? = null,
        timeoutSeconds: Long = 20L
    ): Document {
        return try {
            app.get(
                url,
                headers = getHeaders(referer),
                timeout = timeoutSeconds
            ).document
        } catch (e: Exception) {
            Log.e(TAG, "smartGet failed for $url: ${e.message}")
            Jsoup.parse("<html><body>Error loading page</body></html>", url)
        }
    }

    private suspend fun smartPost(
        url: String,
        data: Map<String, String>,
        referer: String? = null,
        timeoutSeconds: Long = 20L
    ): Document {
        return try {
            val headers = getHeaders(referer).toMutableMap().apply {
                put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                put("X-Requested-With", "XMLHttpRequest")
            }

            app.post(
                url,
                data = data,
                headers = headers,
                timeout = timeoutSeconds
            ).document
        } catch (e: Exception) {
            Log.e(TAG, "smartPost failed for $url: ${e.message}")
            Jsoup.parse("", url)
        }
    }

    private fun extractNumbers(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("""\d+""").find(text)?.value?.toIntOrNull()
    }

    private fun getPosterFromStyle(element: Element?): String? {
        val style = element?.attr("style")?.ifBlank { null } ?: element?.attr("data-lazy-style")
        return style?.let {
            Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1)
                ?.trim('\'', '"', ' ')
                ?.ifBlank { null }
        }
    }

    private fun extractServerName(element: Element): String {
        return (element.ownText().ifBlank { element.text() }).replace(Regex("\\s+"), " ").trim()
    }

    private fun String.encodeURL(): String {
        return try {
            URLEncoder.encode(this, "UTF-8")
        } catch (e: Exception) {
            this
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("div.Thumb--GridItem a") ?: return null
        val url = linkElement.attr("href")
        if (url.isBlank()) return null

        val posterUrl = getPosterFromStyle(linkElement.selectFirst("span.BG--GridItem"))
        val titleTag = linkElement.selectFirst("strong") ?: return null
        val title = titleTag.ownText().trim().ifBlank { linkElement.attr("title").trim() }
        val year = titleTag.selectFirst("span.year")?.text()?.let { extractNumbers(it) }

        val isMovie = this.selectFirst("div.Episode--number") == null && !url.contains("/series/")

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "احدث الاضافات",
        "$mainUrl/movies/" to "افلام جديدة",
        "$mainUrl/series/" to "مسلسلات جديدة",
        "$mainUrl/category/مسلسلات-عربي/" to "مسلسلات عربي",
        
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val activeBase = baseUrl().trimEnd('/')
            val baseRequestUrl = request.data.replace(Regex("^https?://[^/]+"), activeBase)
            val url = if (page > 1) {
                "${baseRequestUrl.removeSuffix("/")}/page/$page/"
            } else {
                baseRequestUrl
            }

            val document = smartGet(url)

            val isBannerRequest = request.name == "احدث الاضافات" && page == 1
            val selector =
                "div.Grid--WecimaPosts div.GridItem, div#MainFiltar div.GridItem, div.Slider--Grid div.GridItem"
            val list = document.select(selector).mapNotNull { it.toSearchResult() }

            val homePageList = HomePageList(
                name = request.name,
                list = list,
                isHorizontalImages = isBannerRequest
            )

            newHomePageResponse(homePageList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load page $page for ${request.name}", e)
            val homePageList = HomePageList(
                name = request.name,
                list = emptyList(),
                isHorizontalImages = false
            )
            newHomePageResponse(homePageList)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "${baseUrl()}/filtering/?keywords=${query.encodeURL()}"
        val document = smartGet(url)
        return document.select("div#MainFiltar div.GridItem").mapNotNull { it.toSearchResult() }
    }

    private fun parseSeasonNumber(text: String): Int? {
        Regex("(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }

        val normalized = text
            .replace(Regex("[\u064B-\u065F\u0670\u0640]"), "")
            .replace('أ', 'ا')
            .replace('إ', 'ا')
            .replace('آ', 'ا')
            .replace('ٱ', 'ا')
            .replace('ى', 'ي')
            .replace('ؤ', 'و')
            .replace('ئ', 'ي')
            .replace(Regex("\\s+"), " ")
            .trim()

        val ordinals = listOf(
            "الثلاثون" to 30, "الثلاثين" to 30,
            "التاسع والعشرون" to 29, "التاسع والعشرين" to 29,
            "الثامن والعشرون" to 28, "الثامن والعشرين" to 28,
            "السابع والعشرون" to 27, "السابع والعشرين" to 27,
            "السادس والعشرون" to 26, "السادس والعشرين" to 26,
            "الخامس والعشرون" to 25, "الخامس والعشرين" to 25,
            "الرابع والعشرون" to 24, "الرابع والعشرين" to 24,
            "الثالث والعشرون" to 23, "الثالث والعشرين" to 23,
            "الثاني والعشرون" to 22, "الثاني والعشرين" to 22,
            "الحادي والعشرون" to 21, "الحادي والعشرين" to 21, "الواحد والعشرون" to 21, "الواحد والعشرين" to 21,
            "العشرون" to 20, "العشرين" to 20,
            "التاسع عشر" to 19,
            "الثامن عشر" to 18,
            "السابع عشر" to 17,
            "السادس عشر" to 16,
            "الخامس عشر" to 15,
            "الرابع عشر" to 14,
            "الثالث عشر" to 13,
            "الثاني عشر" to 12,
            "الحادي عشر" to 11, "الواحد عشر" to 11,
            "العاشر" to 10,
            "التاسع" to 9,
            "الثامن" to 8,
            "السابع" to 7,
            "السادس" to 6,
            "الخامس" to 5,
            "الرابع" to 4,
            "الثالث" to 3,
            "الثاني" to 2,
            "الاول" to 1
        )

        return ordinals.firstOrNull { normalized.contains(it.first) }?.second
    }

    override suspend fun load(url: String): LoadResponse? {
        val currentBase = try {
            val u = URL(url)
            "${u.protocol}://${u.host}"
        } catch (_: Exception) {
            baseUrl()
        }
        var document: Document? = null
        for (attempt in 1..15) {
            try {
                val doc = smartGet(url, referer = currentBase, timeoutSeconds = 15L)
                val bodyText = doc.body().text()
                if (!bodyText.contains("Error loading page") && 
                    !bodyText.contains("503 Service") && 
                    !bodyText.contains("Cloudflare") &&
                    doc.selectFirst("h1, singlesection, title") != null) {
                    document = doc
                    break
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(800L)
        }
        val doc = document ?: Jsoup.parse("<html><body></body></html>", url)

        fun extractPostId(d: Document): String? {
            val html = d.html()
            Regex("""Single/Episodes\.php.*?post_id:\s*['"](\d+)['"]""", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1)?.let { return it }
            d.selectFirst("input[name=post_id]")?.attr("value")?.takeIf { it.isNotBlank() }?.let { return it }
            d.selectFirst("[data-post_id]")?.attr("data-post_id")?.takeIf { it.isNotBlank() }?.let { return it }
            d.selectFirst("[data-postid]")?.attr("data-postid")?.takeIf { it.isNotBlank() }?.let { return it }
            d.selectFirst("link[rel=shortlink]")?.attr("href")?.let {
                Regex("p=(\\d+)").find(it)?.groupValues?.get(1)
            }?.let { return it }
            val scriptsText = d.select("script").joinToString(" ") { it.data() }
            Regex("""post_id['"]?\s*[:=]\s*['"]?(\d{3,})['"]?""").find(scriptsText)?.groupValues?.get(1)?.let { return it }
            return null
        }

        val isSeriesPage = url.contains("/series/") || doc.selectFirst(
            "div.SeasonsList ul li a, div.Seasons--Episodes, div.EpisodesList a, singlesection.Series--Section a"
        ) != null
        val seriesTitleFromSection = doc.selectFirst("singlesection.Series--Section h2")?.text()
            ?.replace("حلقات ", "")
            ?.trim()
            ?.ifBlank { null }

        var rawTitle = if (isSeriesPage && !seriesTitleFromSection.isNullOrBlank()) {
            seriesTitleFromSection
        } else {
            doc.selectFirst("div.Title--Content--Single-begin > h1")?.ownText()
                ?.trimEnd('(', ' ', ')')?.trim()
        }

        if (rawTitle.isNullOrBlank()) {
            rawTitle = doc.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
                ?.replace(" - وي سيما WECIMA ماي سيما MYCIMA", "")
                ?.replace(" - ماي سيما", "")
        }
        if (rawTitle.isNullOrBlank()) {
            rawTitle = doc.title().takeIf { !it.contains("Error loading page") && it.isNotBlank() }
        }
        if (rawTitle.isNullOrBlank()) {
            val slug = url.trimEnd('/').substringAfterLast('/')
            rawTitle = try {
                URLDecoder.decode(slug, "UTF-8").replace("-", " ")
            } catch (_: Exception) {
                slug.replace("-", " ")
            }
        }

        val finalTitle = rawTitle.replace(Regex("""\s+"""), " ").trim()

        val poster = getPosterFromStyle(doc.selectFirst("wecima.separated--top"))
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }

        val year = doc.selectFirst("div.Title--Content--Single-begin h1 a")?.text()
            ?.trim()?.toIntOrNull()
            ?: extractNumbers(doc.selectFirst("div.Title--Content--Single-begin h1")?.text())
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(url)?.groupValues?.get(1)?.toIntOrNull()

        val plot = doc.selectFirst("div.StoryMovieContent")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("div.AsideContext p, div.AsideContext div")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("div.PostItemContent")?.text()?.trim()?.ifBlank { null }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.trim()?.ifBlank { null }

        val tags = doc.select("ul.Terms--Content--Single-begin li:has(span:contains(النوع)) p a")
            .map { it.text().trim() }

        val recommendations = doc.select("div.Grid--WecimaPosts div.GridItem")
            .mapNotNull { it.toSearchResult() }

        val seriesUrlFromEpisode = doc
            .selectFirst("singlesection.Series--Section > a, ul.Terms--Content--Single-begin li:contains(المسلسل) a")
            ?.attr("href")

        fun extractEpisodeNumberFromText(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            Regex("""الحلقة\s*(\d+)""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            Regex("""\b(\d{1,4})\b""").find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            return null
        }

        fun resolveUrl(base: String, relative: String): String {
            return try {
                URL(URL(base), relative).toString()
            } catch (e: Exception) {
                if (relative.startsWith("http")) relative else currentBase.trimEnd('/') + "/" + relative.trimStart('/')
            }
        }

        fun parseEpisodesFromAnchors(
            anchors: List<Element>,
            seasonLabel: String?,
            seasonNumber: Int?
        ): List<Episode> {
            var counter = 0
            return anchors.mapNotNull { a ->
                val rawHref = a.attr("href").ifBlank { a.attr("data-href") }
                if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.contains("season", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val epHref = resolveUrl(url, rawHref)

                val epTitleRaw = a.selectFirst("episodetitle, .episodetitle")?.text()?.trim()
                    ?: a.attr("title").ifBlank { a.text() }.trim()

                val epNum = extractEpisodeNumberFromText(epTitleRaw) ?: run {
                    counter++
                    counter
                }

                val epName = if (!seasonLabel.isNullOrBlank()) {
                    "$seasonLabel الحلقة $epNum"
                } else {
                    "الحلقة $epNum"
                }

                newEpisode(epHref) {
                    this.name = epName
                    this.season = seasonNumber
                    this.episode = epNum
                    this.posterUrl = poster
                }
            }
        }

        val htmlText = doc.html()
        val ajaxBase = Regex("""var\s+AjaxtURL\s*=\s*"([^"]+)"""")
            .find(htmlText)?.groupValues?.get(1)
            ?: "$currentBase/wp-content/themes/mycima/Ajaxt/"
        val ajaxUrl = "${ajaxBase.trimEnd('/')}/Single/Episodes.php"
        val postId = extractPostId(doc)
        if (isSeriesPage) {
            val allAnchors = doc.select("div.SeasonsList ul li a, .Seasons--Episodes ul li a, div.SeasonsList a")
            val seasonAnchors = allAnchors.filter { a ->
                val text = a.text().trim()
                !text.contains("حلقة") && (text.contains("الموسم") || text.contains("موسم") || a.hasAttr("data-season"))
            }

            val allEpisodes = mutableListOf<Episode>()

            if (seasonAnchors.isEmpty()) {
                val anchors = doc.select("div.EpisodesList a[href], div.Episodes--Seasons--Episodes a[href]")
                allEpisodes.addAll(parseEpisodesFromAnchors(anchors, null, 1))
            } else {
                data class SeasonTask(
                    val id: String,
                    val label: String,
                    val number: Int
                )

                val parsedNums = seasonAnchors.map { parseSeasonNumber(it.text()) }
                val usedNumbers = parsedNums.filterNotNull().toMutableSet()
                var nextMissing = 1
                val resolvedSeasonNums = parsedNums.map { known ->
                    if (known != null) {
                        known
                    } else {
                        while (usedNumbers.contains(nextMissing)) nextMissing++
                        val assigned = nextMissing
                        usedNumbers.add(assigned)
                        nextMissing++
                        assigned
                    }
                }

                val pendingTasks = mutableListOf<SeasonTask>()
                val activeBox = doc.select("div.EpisodesList a[href], div.Episodes--Seasons--Episodes a[href]")

                seasonAnchors.forEachIndexed { idx, seasonEl ->
                    val seasonNumber = resolvedSeasonNums[idx]
                    val rawSeasonText = seasonEl.text().trim()
                    val seasonLabel = rawSeasonText
                        .replace(Regex("\\s*(مترجم|مدبلج).*"), "")
                        .trim()
                        .ifBlank { "الموسم $seasonNumber" }

                    val seasonId = seasonEl.attr("data-season").ifBlank { seasonEl.attr("data-season-id") }
                    val isActiveSeason = seasonEl.parent()?.hasClass("active") == true || seasonEl.hasClass("active")

                    if (isActiveSeason && activeBox.isNotEmpty()) {
                        val inPageEpisodes = parseEpisodesFromAnchors(activeBox, seasonLabel, seasonNumber)
                        if (inPageEpisodes.isNotEmpty()) {
                            allEpisodes.addAll(inPageEpisodes)
                            return@forEachIndexed
                        }
                    }

                    if (seasonId.isNotBlank()) {
                        pendingTasks.add(SeasonTask(seasonId, seasonLabel, seasonNumber))
                    }
                }

                suspend fun fetchStaggered(tasks: List<SeasonTask>): Pair<List<Episode>, List<SeasonTask>> {
                    val failed = mutableListOf<SeasonTask>()
                    val fetched = coroutineScope {
                        tasks.mapIndexed { index, task ->
                            if (index > 0) {
                                kotlinx.coroutines.delay(REQUEST_DELAY)
                            }
                            async(Dispatchers.IO) {
                                try {
                                    if (!postId.isNullOrBlank()) {
                                        val seasonDoc = smartPost(
                                            ajaxUrl,
                                            data = mapOf("season" to task.id, "post_id" to postId),
                                            referer = currentBase,
                                            timeoutSeconds = 12L
                                        )
                                        val anchors = seasonDoc.select("a[href]")
                                        val eps = parseEpisodesFromAnchors(anchors, task.label, task.number)
                                        if (eps.isNotEmpty()) {
                                            return@async Pair(eps, null)
                                        }
                                    }
                                } catch (_: Exception) {}
                                Pair(emptyList<Episode>(), task)
                            }
                        }.awaitAll()
                    }

                    val successEps = mutableListOf<Episode>()
                    fetched.forEach { (eps, failTask) ->
                        if (eps.isNotEmpty()) {
                            successEps.addAll(eps)
                        } else if (failTask != null) {
                            failed.add(failTask)
                        }
                    }
                    return Pair(successEps, failed)
                }

                var (stageEps, failedTasks) = fetchStaggered(pendingTasks)
                allEpisodes.addAll(stageEps)

                var retryCount = 1
                while (failedTasks.isNotEmpty() && retryCount <= MAX_AJAX_RETRIES) {
                    kotlinx.coroutines.delay(800L)
                    val (retryEps, stillFailed) = fetchStaggered(failedTasks)
                    allEpisodes.addAll(retryEps)
                    failedTasks = stillFailed
                    retryCount++
                }
            }

            val distinctEpisodes = allEpisodes.distinctBy { it.data }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            val seriesPageUrl = seriesUrlFromEpisode?.let { resolveUrl(url, it) } ?: url
            return newTvSeriesLoadResponse(finalTitle, seriesPageUrl, TvType.TvSeries, distinctEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        } else if (!seriesUrlFromEpisode.isNullOrBlank()) {
            return load(resolveUrl(url, seriesUrlFromEpisode))
        } else {
            var movieWatchUrl = url
            val hasServersInPage = doc.selectFirst("ul#watch li[data-watch], ul.List--Download--Wecima--Single li a[href]") != null
            if (!hasServersInPage && !postId.isNullOrBlank()) {
                for (retry in 1..15) {
                    try {
                        val ajaxDoc = smartPost(
                            ajaxUrl,
                            data = mapOf("post_id" to postId),
                            referer = currentBase,
                            timeoutSeconds = 12L
                        )
                        val ajaxWatchLink = ajaxDoc.selectFirst("ul#watch li[data-watch], a[href]")?.attr("href")
                        if (!ajaxWatchLink.isNullOrBlank()) {
                            movieWatchUrl = resolveUrl(url, ajaxWatchLink)
                            break
                        }
                    } catch (_: Exception) {}
                    kotlinx.coroutines.delay(600L)
                }
            }

            return newMovieLoadResponse(finalTitle, url, TvType.Movie, movieWatchUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }
    private fun decodeWecimaUrl(encodedStr: String): String? {
        return try {
            if (encodedStr.isBlank()) return null
            val cleanedStr = encodedStr.replace("+", "").trim()
            val finalB64Str = if (!cleanedStr.startsWith("aHR0c")) "aHR0c$cleanedStr" else cleanedStr
            String(Base64.decode(finalB64Str, Base64.DEFAULT))
        } catch (_: Exception) {
            null
        }
    }

    private fun customB64Decode(s: String, alphabet: String): String {
        var bc = 0
        var bs = 0
        val out = mutableListOf<Byte>()
        for (ch in s) {
            val idx = alphabet.indexOf(ch)
            if (idx == -1) continue
            bs = if (bc % 4 != 0) (bs * 64 + idx) else idx
            if (bc % 4 != 0) {
                out.add((0xff and (bs shr ((-2 * (bc + 1)) and 6))).toByte())
            }
            bc++
        }
        return try {
            val pct = out.joinToString("") { "%02x".format(it) }
            URLDecoder.decode("%$pct".replace("%", "%25").replace("%25", "%"), "UTF-8")
        } catch (e: Exception) {
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun rc4(s: String, key: String): String {
        val sbox = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + sbox[i] + key[i % key.length].code) % 256
            val temp = sbox[i]; sbox[i] = sbox[j]; sbox[j] = temp
        }
        var i = 0
        j = 0
        val res = StringBuilder()
        for (ch in s) {
            i = (i + 1) % 256
            j = (j + sbox[i]) % 256
            val temp = sbox[i]; sbox[i] = sbox[j]; sbox[j] = temp
            res.append((ch.code xor sbox[(sbox[i] + sbox[j]) % 256]).toChar())
        }
        return res.toString()
    }

    private fun deobfuscateJs(jsCode: String): String {
        val jsId = """[a-zA-Z_$][\w$]*"""
        val decRegex = Regex("""function\s+($jsId)\s*\(\s*($jsId)(?:\s*,\s*$jsId)?\s*\)\s*\{\s*\2\s*=\s*\2\s*([-+])\s*([^;]+);\s*(?:const|let|var)\s+$jsId\s*=\s*($jsId)\s*\(\s*\)\s*;""")
        val mDec = decRegex.find(jsCode) ?: return jsCode

        val decFn = mDec.groupValues[1]
        val op = mDec.groupValues[3]
        val offsetVal = mDec.groupValues[4].trim().toIntOrNull() ?: 0
        val offset = if (op == "+") -offsetVal else offsetVal
        val arrFn = mDec.groupValues[5]

        val arrRegex = Regex("""function\s+${Regex.escape(arrFn)}\s*\(\s*\)\s*\{\s*(?:const|let|var)\s+$jsId\s*=\s*(\[.*?\])\s*;""", RegexOption.DOT_MATCHES_ALL)
        val mArr = arrRegex.find(jsCode) ?: return jsCode
        val rawArrStr = mArr.groupValues[1]

        val arr = Regex("""['"]([^'"]+)['"]""").findAll(rawArrStr).map { it.groupValues[1] }.toMutableList()
        val alphabetMatch = Regex("""['"]([A-Za-z0-9+/=]{64,65})['"]""").find(jsCode)
        val alphabet = alphabetMatch?.groupValues?.get(1)
            ?: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789+/="

        fun decodeItem(idx: Int, key: String): String {
            val realIdx = idx - offset
            if (realIdx !in arr.indices) return ""
            val raw = arr[realIdx]
            return try {
                if (key.isNotEmpty()) {
                    rc4(customB64Decode(raw, alphabet), key)
                } else {
                    customB64Decode(raw, alphabet)
                }
            } catch (e: Exception) {
                raw
            }
        }

        val callRegex = Regex("""\b${Regex.escape(decFn)}\(\s*(0x[0-9a-fA-F]+|\d+)(?:\s*,\s*['"]([^'"]*)['"])?\s*\)""")
        return callRegex.replace(jsCode) { m ->
            val numStr = m.groupValues[1]
            val idx = if (numStr.startsWith("0x")) numStr.substring(2).toInt(16) else numStr.toInt()
            val key = m.groupValues.getOrNull(2) ?: ""
            "\"${decodeItem(idx, key)}\""
        }
    }

    private fun extractDomains(decodedJs: String, currentHost: String): List<String> {
        val domains = mutableListOf<String>()
        val listRegex = Regex("""\b(?:main|dmca)\s*=\s*\[([^\]]+)\]""")
        listRegex.findAll(decodedJs).forEach { match ->
            val content = match.groupValues[1]
            Regex("""['"]([a-zA-Z0-9.-]+\.[a-z]{2,})['"]""").findAll(content).forEach { d ->
                domains.add(d.groupValues[1])
            }
        }
        if (domains.isEmpty()) {
            val domainRegex = Regex("""['"]([a-zA-Z0-9.-]+\.[a-z]{2,})['"]""")
            domainRegex.findAll(decodedJs).forEach { d ->
                val dom = d.groupValues[1]
                if (!dom.contains("google") && !dom.contains("cloudflare") && !dom.endsWith(currentHost)) {
                    domains.add(dom)
                }
            }
        }
        return domains.distinct()
    }

    private suspend fun replaceStreamWishDomain(targetUrl: String, referer: String? = null): String {
        return try {
            val uri = URL(targetUrl)
            val query = if (!uri.query.isNullOrBlank()) "?${uri.query}" else ""
            val path = uri.path

            for (domain in streamWishPreferredDomains) {
                val candidateUrl = "https://$domain$path$query"
                try {
                    val check = app.get(
                        candidateUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to (referer ?: "https://$domain/")
                        ),
                        timeout = 6
                    )
                    if (check.code == 200 && !check.text.contains("loading", ignoreCase = true)) {
                        return candidateUrl
                    }
                } catch (_: Exception) {}
            }

            "https://${streamWishPreferredDomains.first()}$path$query"
        } catch (e: Exception) {
            targetUrl
        }
    }

    private suspend fun resolveHgCloud(targetUrl: String, referer: String? = null): String? {
        return try {
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to (referer ?: targetUrl)
            )

            val r1 = app.get(targetUrl, headers = headers, timeout = 8)
            if (r1.code != 200) return null

            val html = r1.text
            val jsPathMatch = Regex("""<script[^>]+src=["']([^"']*main\.js[^"']*)["']""").find(html)
                ?: if (html.lowercase().contains("loading")) Regex("""<script[^>]+src=["'](/[^"']+\.js[^"']*)["']""").find(html) else null

            val uri = URL(r1.url)
            val currentHost = uri.host
            val candidates = mutableListOf<String>()
            candidates.addAll(streamWishPreferredDomains)

            if (jsPathMatch != null) {
                val jsRelative = jsPathMatch.groupValues[1]
                val jsUrl = if (jsRelative.startsWith("http")) jsRelative else "${uri.protocol}://${uri.host}$jsRelative"

                val r2 = app.get(jsUrl, headers = headers, timeout = 8)
                if (r2.code == 200) {
                    try {
                        val decodedJs = deobfuscateJs(r2.text)
                        candidates.addAll(extractDomains(decodedJs, currentHost))
                    } catch (_: Exception) {}
                }
            }

            val uniqueCandidates = candidates.distinct().take(8)

            for (dest in uniqueCandidates) {
                if (dest.equals(currentHost, ignoreCase = true)) continue
                val query = if (!uri.query.isNullOrBlank()) "?${uri.query}" else ""
                val testUrl = "https://$dest${uri.path}$query"

                try {
                    val check = app.get(testUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to r1.url), timeout = 5)
                    if (check.code == 200 && !check.text.contains("loading", ignoreCase = true)) {
                        return testUrl
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun extractHexM3u8(html: String): String? {
        val hexRegex = Regex("""["'](68747470[0-9a-fA-F]{30,})["']""")
        val match = hexRegex.find(html)?.groupValues?.get(1) ?: return null
        return try {
            match.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activeBase = baseUrl()
        val document = smartGet(data, referer = activeBase)
        val collectedLinks = linkedMapOf<String, String?>()

        fun addLink(rawUrl: String?, serverName: String?) {
            val cleanUrl = rawUrl?.trim().orEmpty()
            if (!cleanUrl.startsWith("http")) return
            val cleanName = serverName?.trim()?.takeIf { it.isNotBlank() }
            if (!collectedLinks.containsKey(cleanUrl)) {
                collectedLinks[cleanUrl] = cleanName
            } else if (collectedLinks[cleanUrl].isNullOrBlank() && cleanName != null) {
                collectedLinks[cleanUrl] = cleanName
            }
        }

        document.select("ul#watch li[data-watch]").forEach { serverEl ->
            val watchUrl = serverEl.attr("data-watch")
            val serverName = extractServerName(serverEl)
            addLink(watchUrl, serverName)
        }

        document.select("ul.List--Download--Wecima--Single li a[href]").forEach { downloadEl ->
            val downloadUrl = downloadEl.attr("href")
            val serverName = downloadEl.selectFirst("quality")?.text()?.trim() ?: "تحميل"
            addLink(downloadUrl, serverName)
        }

        document.select("ul.WatchServersList li btn[data-url]").forEach { serverBtn ->
            decodeWecimaUrl(serverBtn.attr("data-url"))?.let { decodedUrl ->
                val serverName = serverBtn.text().trim().ifBlank { serverBtn.parent()?.text()?.trim() }
                addLink(decodedUrl, serverName)
            }
        }

        document.select(".openLinkDown[data-href]").forEach { downloadBtn ->
            decodeWecimaUrl(downloadBtn.attr("data-href"))?.let { decodedUrl ->
                val serverName = downloadBtn.selectFirst("quality")?.text()?.trim()
                addLink(decodedUrl, serverName)
            }
        }

        coroutineScope {
            collectedLinks.entries.toList().map { (link, serverName) ->
                async {
                    var targetUrl = link
                    if (targetUrl.contains("govid.live/play/=")) {
                        try {
                            val playDoc = smartGet(targetUrl, referer = activeBase)
                            val iframeSrc = playDoc.selectFirst("iframe[src]")?.attr("src")
                                ?: Regex("""<!--\s*sss(https?://[^\s>]+)\s*-->""").find(playDoc.html())?.groupValues?.get(1)
                                ?: Regex("""src=["'](https?://[^"']+)["']""").find(playDoc.html())?.groupValues?.get(1)

                            if (!iframeSrc.isNullOrBlank()) {
                                targetUrl = iframeSrc.trim()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching govid play iframe: ${e.message}")
                        }
                    }
                    if (targetUrl.contains("govid.live/e/")) {
                        try {
                            val govidHeaders = mapOf(
                                "User-Agent" to MOBILE_USER_AGENT,
                                "Referer" to activeBase
                            )
                            val res = app.get(targetUrl, headers = govidHeaders, timeout = 10L)
                            val streamUrl = extractHexM3u8(res.text)
                            if (!streamUrl.isNullOrBlank()) {
                                val m3u8Headers = mapOf(
                                    "User-Agent" to MOBILE_USER_AGENT,
                                    "Referer" to targetUrl,
                                    "Origin" to "https://govid.live",
                                    "Accept" to "*/*",
                                    "Sec-Fetch-Site" to "same-origin",
                                    "Sec-Fetch-Mode" to "cors",
                                    "Sec-Fetch-Dest" to "empty",
                                    "sec-ch-ua-platform" to "\"Android\"",
                                    "sec-ch-ua-mobile" to "?1"
                                )

                                callback(
                                    newExtractorLink(
                                        source = "Vidsharing",
                                        name = serverName ?: "Vidsharing (GoVid)",
                                        url = streamUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = targetUrl
                                        this.quality = Qualities.P1080.value
                                        this.headers = m3u8Headers
                                    }
                                )
                                return@async
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error resolving GoVid stream: ${e.message}")
                        }
                    }
                    val isStreamWishOrHg = targetUrl.contains("hgcloud", ignoreCase = true) ||
                            targetUrl.contains("streamwish", ignoreCase = true) ||
                            targetUrl.contains("fsdcmo.sbs", ignoreCase = true) ||
                            serverName?.contains("hgcloud", ignoreCase = true) == true ||
                            serverName?.contains("streamwish", ignoreCase = true) == true

                    if (isStreamWishOrHg) {
                        val resolved = if (targetUrl.contains("hgcloud", ignoreCase = true)) {
                            resolveHgCloud(targetUrl, activeBase) ?: replaceStreamWishDomain(targetUrl, activeBase)
                        } else {
                            replaceStreamWishDomain(targetUrl, activeBase)
                        }
                        if (resolved.isNotBlank()) {
                            targetUrl = resolved
                        }
                    }
                    try {
                        loadExtractor(targetUrl, activeBase, subtitleCallback, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Extractor Error for $targetUrl: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        return collectedLinks.isNotEmpty()
    }
}
