package com.eshk

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log
import kotlin.io.encoding.Base64
import android.service.controls.ControlsProviderService.TAG
import com.lagradost.cloudstream3.syncproviders.providers.OpenSubtitlesApi.Companion.headers

class eishk : MainAPI() {
    override var mainUrl = "https://3esk.onl"
    override var name = "قصة عشق"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override var lang = "ar"
    override val hasMainPage = true

    private fun Element.toSearchResponse(): SearchResponse? {

        val encodedUrl = this.attr("data-clse")

        val href = if (encodedUrl.isNotBlank()) {
            try {

                try {
                    String(android.util.Base64.decode(encodedUrl, android.util.Base64.DEFAULT))
                } catch (_: Exception) {
                    try {
                        String(android.util.Base64.decode(encodedUrl, android.util.Base64.URL_SAFE))
                    } catch (_: Exception) {

                        String(android.util.Base64.decode(encodedUrl, android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Base64 decoding failed for '$encodedUrl'. Falling back to href. Error: ${e.message}")
                this.attr("href")
            }
        } else {
            this.attr("href")
        }

        if (href.isBlank()) return null
        val title = this.attr("title")
        val posterUrl = this.selectFirst("img")?.let { it.attr("data-image").ifBlank { it.attr("src") } }

        return when {
            href.contains("/tvshows/") -> newTvSeriesSearchResponse(title, href) { this.posterUrl = posterUrl }
            href.contains("/movies/") -> newMovieSearchResponse(title, href) { this.posterUrl = posterUrl }
            href.contains("/episodes/") -> {
                val seriesTitle = title.substringBefore(" الحلقة").trim()
                newTvSeriesSearchResponse(seriesTitle.ifBlank { title }, href) { this.posterUrl = posterUrl }
            }
            else -> null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val all = ArrayList<HomePageList>()

        document.select("section.home-items-sec").forEach { section ->
            val title = section.selectFirst(".sec-title")?.text() ?: return@forEach
            val items =
                section.select("li.type_item_box a.type_item, li.type_item_wide_box a.type_item_wide")
                    .mapNotNull { it.toSearchResponse() }

            if (items.isNotEmpty()) {
                all.add(HomePageList(title, items))
            }
        }
        return newHomePageResponse(all)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/$query/"
        Log.d(TAG, "search called with query: '$query', URL: $url")
        val document = app.get(url, headers = headers).document

        val results = document.select("ul.search-page li.type_item_box a.type_item").mapNotNull {
            it.toSearchResponse()
        }
        Log.d(TAG, "Found ${results.size} search results.")
        return results
    }

    private fun decodeBase64Compat(encoded: String): String? {
        var s = encoded.trim()

        val mod = s.length % 4
        if (mod != 0) {
            s += "=".repeat(4 - mod)
        }

        val flagsToTry = listOf(
            android.util.Base64.DEFAULT,
            android.util.Base64.NO_WRAP,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        )

        for (flags in flagsToTry) {
            try {
                val bytes = android.util.Base64.decode(s, flags)

                return try {
                    String(bytes, Charsets.UTF_8)
                } catch (e: Exception) {
                    String(bytes)
                }
            } catch (ignored: IllegalArgumentException) {

            }
        }

        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val TAG = "Qesat3eshqProvider"
        Log.d(TAG, "load started for URL: $url")

        if (url.contains("/episodes/")) {
            Log.d(TAG, "URL is an episode link, finding the main series URL.")
            val episodePage = app.get(url).document
            val seriesUrl = episodePage.selectFirst("a.single-serie-btn")?.attr("href")
            if (seriesUrl.isNullOrBlank()) {
                Log.e(TAG, "Could not find series URL from episode page.")
                return null
            }
            Log.d(TAG, "Found series URL: $seriesUrl. Redirecting to load it.")
            return load(seriesUrl) // أعِد استدعاء الدالة مع رابط المسلسل
        }

        val document = app.get(url).document
        val title = document.selectFirst("div.single_info h1.title")?.text()
            ?.replace("مترجم", "")?.replace("مدبلج", "")?.trim()
            ?: return null
        Log.d(TAG, "Loading series: $title")

        val poster = document.selectFirst("div.poster-wrapper img")?.attr("src")
        val description = document.selectFirst("div.description span[data-nosnippet]")?.text()
        val tvType = if (url.contains("/tvshows/")) TvType.TvSeries else TvType.Movie

        if (tvType == TvType.Movie) {
            return newMovieLoadResponse(title, url, tvType, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        val episodes = ArrayList<Episode>()

        document.select("div.season-eps").forEach { seasonDiv ->

            val seasonNum = seasonDiv.attr("id").removePrefix("season-num-").toIntOrNull() ?: 1

            seasonDiv.select("a.ep-num").forEach { epA ->

                val rawUrl = epA.attr("data-clse").ifBlank { epA.attr("href") }

                if (rawUrl.isBlank()) {
                    Log.w(TAG, "Skipping episode, both 'data-clse' and 'href' are missing.")
                    return@forEach
                }

                val epUrl = if (rawUrl.startsWith("http")) {
                    rawUrl
                } else {
                    try {
                        decodeBase64Compat(rawUrl) ?: epA.attr("href")
                    } catch (e: Exception) {
                        Log.e(TAG, "Base64 decoding failed for: $rawUrl. Falling back to href.")
                        epA.attr("href")
                    }
                }

                Log.d(TAG, "Found Episode URL: $epUrl")

                val epNum = epA.attr("data-ep-num").toIntOrNull()
                val epName = epA.attr("title").ifBlank { "الحلقة $epNum" }

                episodes.add(
                    newEpisode(epUrl) {
                        name = epName
                        episode = epNum
                        season = seasonNum
                        posterUrl = poster
                    }
                )
            }
        }


        if (episodes.isEmpty()) {
            Log.e(TAG, "No episodes found for series: $title")
            return null
        }

        return newTvSeriesLoadResponse(title, url, tvType, episodes.sortedBy { it.episode }) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "=== loadLinks START for: $data ===")

        fun jsStringUnescape(s: String): String {
            val regex = Regex("""\\u[0-9a-fA-F]{4}|\\x[0-9a-fA-F]{2}|\\.|\\n|\\r|\\t""")
            return regex.replace(s) { m ->
                val esc = m.value
                try {
                    when {
                        esc.startsWith("\\x") -> esc.substring(2).toInt(16).toChar().toString()
                        esc.startsWith("\\u") -> esc.substring(2).toInt(16).toChar().toString()
                        esc == "\\n" -> "\n"
                        esc == "\\r" -> "\r"
                        esc == "\\t" -> "\t"
                        esc == "\\'" -> "'"
                        esc == "\\\"" -> "\""
                        esc == "\\\\" -> "\\"
                        else -> if (esc.length >= 2 && esc[0] == '\\') esc.substring(1) else esc
                    }
                } catch (_: Exception) {
                    esc
                }
            }
        }

        fun intToBase36(n0: Int): String {
            if (n0 == 0) return "0"
            var n = n0
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(chars[n % 36])
                n /= 36
            }
            return sb.reverse().toString()
        }

        fun parseJsStringAt(text: String, idxInit: Int): Pair<String?, Int> {
            var idx = idxInit
            if (idx >= text.length) return Pair(null, idx)
            val quote = text[idx]
            if (quote != '"' && quote != '\'') return Pair(null, idx)
            idx += 1
            val out = StringBuilder()
            while (idx < text.length) {
                val ch = text[idx]
                if (ch == '\\') {
                    if (idx + 1 < text.length) {
                        out.append(text.substring(idx, idx + 2))
                        idx += 2
                    } else {
                        idx++
                    }
                } else if (ch == quote) {
                    val valStr = out.toString()
                    return Pair(jsStringUnescape(valStr), idx + 1)
                } else {
                    out.append(ch)
                    idx++
                }
            }
            return Pair(null, idx)
        }

        fun findMatchingBrace(text: String, startIdx: Int): Int {
            if (startIdx < 0 || startIdx >= text.length || text[startIdx] != '{') return -1
            var depth = 0
            var i = startIdx
            while (i < text.length) {
                val ch = text[i]
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) return i
                }
                i++
            }
            return -1
        }

        fun unpackPackerFromEval(evalText: String): Pair<String?, String?> {
            try {
                val startFn = evalText.indexOf("function(p,a,c,k,e,d)")
                if (startFn == -1) return Pair(null, "no function signature")
                val braceOpen = evalText.indexOf('{', startFn)
                if (braceOpen == -1) return Pair(null, "no opening brace")
                val braceClose = findMatchingBrace(evalText, braceOpen)
                if (braceClose == -1) return Pair(null, "no matching brace found for function body")
                val argsStart = evalText.indexOf('(', braceClose)
                if (argsStart == -1) return Pair(null, "no args start found")
                var i = argsStart + 1
                while (i < evalText.length && evalText[i].isWhitespace()) i++
                val (pVal, newI) = parseJsStringAt(evalText, i); i = newI
                if (pVal == null) return Pair(null, "cannot parse p string")
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val aMatch = Regex("""\d+""").find(evalText.substring(i))
                if (aMatch == null) return Pair(null, "cannot parse a")
                val aVal = aMatch.value.toInt()
                i += aMatch.range.last + 1
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val cMatch = Regex("""\d+""").find(evalText.substring(i))
                if (cMatch == null) return Pair(null, "cannot parse c")
                val cVal = cMatch.value.toInt()
                i += cMatch.range.last + 1
                while (i < evalText.length && (evalText[i].isWhitespace() || evalText[i] == ',')) i++
                val kList = mutableListOf<String>()
                if (i < evalText.length && (evalText[i] == '"' || evalText[i] == '\'')) {
                    val (kStr, i2) = parseJsStringAt(evalText, i)
                    i = i2
                    if (kStr != null) {
                        kList.addAll(kStr.split("|"))
                    }
                } else {
                    val m2 = Regex(
                        """(['"])(.*?)\1\s*\.split\s*\(\s*['"]\|['"]\s*\)""",
                        RegexOption.DOT_MATCHES_ALL
                    ).find(evalText)
                    if (m2 != null) {
                        kList.addAll(m2.groupValues[2].split("|"))
                    }
                }

                var p = pVal
                for (idx in cVal - 1 downTo 0) {
                    val key = intToBase36(idx)
                    if (idx < kList.size && kList[idx].isNotEmpty()) {
                        p = Regex("\\b" + Regex.escape(key) + "\\b").replace(p ?: "") { kList[idx] }
                    }
                }
                return Pair(p, null)
            } catch (e: Exception) {
                return Pair(null, "exception:${e.message}")
            }
        }

        fun analyzeAndSaveEvalScripts(htmlText: String): List<String> {
            try {
                val doc = org.jsoup.Jsoup.parse(htmlText)
                val scripts = doc.select("script")
                val found = mutableListOf<String>()
                for (s in scripts) {
                    val content = s.data().ifBlank { s.html() }
                    if (content.contains("eval(")) {
                        val m =
                            Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\)\s*\{""").find(
                                content
                            )
                        if (m != null) {
                            val start = m.range.first
                            val sample = if (content.length > start + 10000) content.substring(
                                start,
                                start + 10000
                            ) else content.substring(start)
                            val (unpacked, err) = unpackPackerFromEval(sample)
                            if (unpacked != null) {
                                val mediaRegex = Regex(
                                    """(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""",
                                    RegexOption.IGNORE_CASE
                                )
                                mediaRegex.findAll(unpacked)
                                    .forEach { found.add(it.groupValues[1]) }
                            }
                        }
                    }
                }
                return found
            } catch (e: Exception) {
                Log.e(TAG, "analyzeAndSaveEvalScripts error", e)
                return emptyList()
            }
        }

        fun getAllIframeSrcs(doc: org.jsoup.nodes.Document): List<String> {
            return doc.select("iframe").mapNotNull { it.attr("src").ifBlank { null } }
        }

        suspend fun processSingleEmbedServer(
            embedUrl: String,
            refererFromPrevPage: String,
            headersBase: Map<String, String>,
            serverLabel: String = "unknown"
        ): Set<String> {
            val result = mutableSetOf<String>()
            try {
                val hdrs = headersBase.toMutableMap()
                hdrs["Referer"] = refererFromPrevPage
                val rIf1 = try {
                    app.get(embedUrl, referer = refererFromPrevPage, headers = hdrs)
                } catch (e: Exception) {
                    Log.w(TAG, "GET embed $embedUrl failed", e)
                    return result
                }
                val text1 = rIf1.text

                Regex(
                    """(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""",
                    RegexOption.IGNORE_CASE
                ).findAll(text1)
                    .forEach { result.add(it.groupValues[1]) }

                analyzeAndSaveEvalScripts(text1).forEach { result.add(it) }

                val docIf1 = rIf1.document
                val iframe1Srcs = getAllIframeSrcs(docIf1)
                if (iframe1Srcs.isNotEmpty()) {
                    val iframe2Src = iframe1Srcs[0]
                    val hdrs2 = hdrs.toMutableMap()
                    hdrs2["Referer"] = embedUrl
                    val rFinal = try {
                        app.get(iframe2Src, referer = embedUrl, headers = hdrs2)
                    } catch (e: Exception) {
                        Log.w(TAG, "GET nested iframe $iframe2Src failed", e)
                        null
                    }
                    if (rFinal != null) {
                        val t = rFinal.text
                        Regex(
                            """(https?://[^\s"']+\.(?:m3u8|mp4|webm|mov)[^\s"']*)""",
                            RegexOption.IGNORE_CASE
                        ).findAll(t)
                            .forEach { result.add(it.groupValues[1]) }
                        analyzeAndSaveEvalScripts(t).forEach { result.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "processSingleEmbedServer exception", e)
            }
            return result
        }

        try {

            Log.d(TAG, "STEP: GET initial page: $data")
            val r0 = try {
                app.get(data, headers = headers)
            } catch (e: Exception) {
                Log.e(TAG, "Initial GET failed", e); return false
            }

            r0.text.chunked(3000).forEachIndexed { i, ch ->
                Log.d(
                    TAG,
                    "initial page chunk ${i + 1}/${(r0.text.length + 2999) / 3000}: $ch"
                )
            }

            val soup0 = r0.document
            var watchForm = soup0.selectFirst("button.single-watch-btn")
                ?.let { it.parent() } // قد لا يكون دقيقًا، لذلك fallback
            if (watchForm == null) {
                for (f in soup0.select("form")) {
                    val act = f.attr("action")
                    if (act.contains("3isk") || act.contains("aa.3isk") || act.contains("watch")) {
                        watchForm = f
                        break
                    }
                }
            }
            if (watchForm == null) {
                Log.e(TAG, "No watch form found on initial page. Dumping HTML for debug.")
                r0.text.chunked(3000)
                    .forEachIndexed { i, ch -> Log.d(TAG, "initial page chunk ${i + 1}: $ch") }
                return false
            }
            val firstPostUrl = watchForm.attr("action")
            val firstFormData = watchForm.select("input[type=hidden]")
                .associate { it.attr("name") to it.attr("value") }.toMutableMap()

            val watchBtn = soup0.selectFirst("button.single-watch-btn")
            if (watchBtn != null) {
                val btnName = watchBtn.attr("name")
                if (btnName.isNotBlank()) firstFormData[btnName] = watchBtn.attr("value")
            }
            Log.d(TAG, "STEP: POST 1 -> $firstPostUrl (fields=${firstFormData.size})")
            headers.toMutableMap()["Referer"] = data
            val r1 = try {
                app.post(firstPostUrl, data = firstFormData, referer = data, headers = headers)
            } catch (e: Exception) {
                Log.e(TAG, "POST first failed to $firstPostUrl", e); return false
            }
            Log.d(TAG, "POST1 response length=${r1.text.length}")
            r1.text.chunked(3000)
                .forEachIndexed { i, ch -> Log.d(TAG, "post1 chunk ${i + 1}: $ch") }
            val mMyurl = Regex("""var\s+myUrl\s*=\s*["']([^"']+)["']""").find(r1.text)
            val mNews = Regex("""myInput\.value\s*=\s*["']([^"']+)["']""").find(r1.text)
            if (mMyurl == null || mNews == null) {
                Log.e(TAG, "Failed to extract myUrl or news from POST1 response. Dumping response.")
                r1.text.chunked(3000)
                    .forEachIndexed { i, ch -> Log.d(TAG, "post1 chunk ${i + 1}: $ch") }
                return false
            }
            val nextPost = mMyurl.groupValues[1]
            val newsVal = mNews.groupValues[1]
            Log.d(TAG, "STEP: nextPost=$nextPost , newsVal length=${newsVal.length}")
            val post2Data = mapOf("news" to newsVal, "u" to "", "submit" to "submit")
            val r2 = try {
                app.post(nextPost, data = post2Data, referer = r1.url, headers = headers)
            } catch (e: Exception) {
                Log.e(TAG, "POST2 failed to $nextPost", e); return false
            }
            Log.d(TAG, "POST2 response length=${r2.text.length}")
            r2.text.chunked(3000)
                .forEachIndexed { i, ch -> Log.d(TAG, "post2 chunk ${i + 1}: $ch") }
            val soup2 = r2.document
            val iframeSrcsOnR2 = getAllIframeSrcs(soup2)
            Log.d(TAG, "STEP: found ${iframeSrcsOnR2.size} iframe(s) on r2")
            if (iframeSrcsOnR2.isEmpty()) {
                Log.e(TAG, "No iframe found on page after POST2")
                return false
            }
            val baseIframeSrc = iframeSrcsOnR2[0]
            Log.d(TAG, "Base iframe src: $baseIframeSrc")

            val foundAllMediaLinks = mutableMapOf<String, MutableSet<String>>()
            val embedMatch = Regex("""(https://3esk\.onl/embed/)(\d+)/(.*)""").find(baseIframeSrc)
            if (embedMatch != null) {
                val baseUrlPrefix = embedMatch.groupValues[1]
                val trailingPart = embedMatch.groupValues[3]
                Log.d(TAG, "Processing embed servers 1..5 (trailing=$trailingPart)")
                val maxServersToCheck = 5
                for (serverNum in 1..maxServersToCheck) {
                    val currentEmbedUrl = "$baseUrlPrefix$serverNum/$trailingPart"
                    Log.d(TAG, "Checking embed server $serverNum -> $currentEmbedUrl")
                    val mediaLinks = processSingleEmbedServer(
                        currentEmbedUrl,
                        r2.url,
                        headers,
                        serverLabel = serverNum.toString()
                    )
                    if (mediaLinks.isNotEmpty()) {
                        Log.d(TAG, "  -> found ${mediaLinks.size} link(s) on server $serverNum")
                        mediaLinks.forEach { link ->
                            foundAllMediaLinks.getOrPut(link) { mutableSetOf() }
                                .add(serverNum.toString())
                        }
                    } else {
                        Log.d(TAG, "  -> no links on server $serverNum")
                    }
                }
            } else {
                Log.d(TAG, "Embed pattern didn't match; processing base iframe directly")
                val mediaLinks =
                    processSingleEmbedServer(baseIframeSrc, r2.url, headers, serverLabel = "base")
                if (mediaLinks.isNotEmpty()) {
                    mediaLinks.forEach {
                        foundAllMediaLinks.getOrPut(it) { mutableSetOf() }.add("base")
                    }
                }
            }
            if (foundAllMediaLinks.isEmpty()) {
                Log.e(TAG, "No media links were extracted from any embed servers.")
                return false
            }
            for ((link, servers) in foundAllMediaLinks) {
                Log.d(TAG, "EXTRACTED: $link (servers=${servers.joinToString(",")})")

                try {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = link,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending link to player: ${e.message}")
                }
            }
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in loadLinks", e)
            return false
        }
    } // ← إغلاق try الرئيسي
} // ← إغلاق دالة loadLinks بالكامل
