package com.witanime

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val customUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/155.0.0.0 Mobile Safari/537.36"

    private val baseHeadersMap = mapOf(
        "User-Agent" to customUserAgent,
        "Sec-Ch-Ua" to "\"Google Chrome\";v=\"155\", \"Chromium\";v=\"155\", \"Not(A:Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )
    private val rotatingUas = listOf(
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2.1 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:123.0) Gecko/20100101 Firefox/123.0",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_3_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Mobile/15E148 Safari/604.1",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Edge/122.0.2365.66"
    )

    companion object {
        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L
    }

    object PlayerAccess {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L)
            }
        }

        fun startMonitoring() {
            if (isLoopStarted) return
            isLoopStarted = true
            isMonitoring = true
            handler.post(monitorRunnable)
        }

        private fun findFragmentRecursive(fragment: androidx.fragment.app.Fragment, packageName: String): androidx.fragment.app.Fragment? {
            if (fragment.javaClass.name.startsWith(packageName)) return fragment
            try {
                for (child in fragment.childFragmentManager.fragments) {
                    if (child != null) {
                        val found = findFragmentRecursive(child, packageName)
                        if (found != null) return found
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        fun getActiveActivity(): Activity? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
                val activities = activitiesField.get(activityThread) as Map<*, *>
                for (activityRecord in activities.values) {
                    if (activityRecord == null) continue
                    val act = activityRecord.javaClass.getDeclaredField("activity").apply { isAccessible = true }.get(activityRecord) as? Activity
                    if (act != null && !act.isFinishing && !act.isDestroyed) return act
                }
                null
            } catch (e: Exception) { null }
        }

        fun getPlayerFragment(): Any? {
            val activity = getActiveActivity() ?: return null
            return try {
                val fragments = (activity as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager?.fragments ?: return null
                for (f in fragments) {
                    if (f != null) {
                        val found = findFragmentRecursive(f, "com.lagradost.cloudstream3.ui.player")
                        if (found != null) return found
                    }
                }
                null
            } catch (e: Exception) { null }
        }

        fun currentPlayer(): Any? {
            val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment ?: return null
            return try {
                val playerField = fragment.javaClass.getDeclaredField("player").apply { isAccessible = true }
                playerField.get(fragment)
            } catch (e: Exception) {
                try {
                    fragment.javaClass.getMethod("getPlayer").invoke(fragment)
                } catch (ex: Exception) { null }
            }
        }

        fun pausePlayer() {
            val player = currentPlayer() ?: return
            try {
                val handleEventMethod = player.javaClass.methods.firstOrNull { it.name == "handleEvent" }
                if (handleEventMethod != null) {
                    val parameterTypes = handleEventMethod.parameterTypes
                    if (parameterTypes.isNotEmpty()) {
                        val eventEnumClass = parameterTypes[0]
                        val pauseEnumConstant = eventEnumClass.enumConstants?.firstOrNull { it.toString().contains("Pause", ignoreCase = true) }
                        if (parameterTypes.size == 2) {
                            val sourceEnumClass = parameterTypes[1]
                            val syncEnumConstant = sourceEnumClass.enumConstants?.firstOrNull {
                                it.toString().contains("Sync", ignoreCase = true) || it.toString().contains("UI", ignoreCase = true)
                            }
                            handleEventMethod.invoke(player, pauseEnumConstant, syncEnumConstant)
                        } else {
                            handleEventMethod.invoke(player, pauseEnumConstant)
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        fun getRealExoPlayer(player: Any): Any? {
            val rootClassName = player.javaClass.name
            if (rootClassName.contains("Player", ignoreCase = true) && !rootClassName.contains("Cache", ignoreCase = true)) {
                if (player.javaClass.methods.any { it.name == "addListener" }) return player
            }
            try {
                for (f in player.javaClass.declaredFields) {
                    f.isAccessible = true
                    val value = f.get(player) ?: continue
                    if (value.javaClass.name.contains("Player", ignoreCase = true) && !value.javaClass.name.contains("Cache", ignoreCase = true)) {
                        if (value.javaClass.methods.any { it.name == "addListener" }) return value
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        private fun hookPlayerListener() {
            val rawPlayer = currentPlayer() ?: return
            val player = getRealExoPlayer(rawPlayer) ?: return
            if (player === lastHookedPlayer) return
            lastHookedPlayer = player

            try {
                val listenerClass = try {
                    Class.forName("androidx.media3.common.Player\$Listener")
                } catch (e: Exception) {
                    Class.forName("com.google.android.exoplayer2.Player\$Listener")
                }
                val addListenerMethod = player.javaClass.getMethod("addListener", listenerClass)
                val proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { proxy, method, args ->
                    if (method.name == "equals") return@newProxyInstance proxy === (args?.get(0))
                    if (method.name == "hashCode") return@newProxyInstance System.identityHashCode(proxy)
                    if (method.name == "toString") return@newProxyInstance "ExoPlayerProxyListener"
                    handler.post { checkMegaPlayback(player) }
                    val returnType = method.returnType
                    if (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.java) false
                    else if (returnType?.isPrimitive == true) 0
                    else null
                }
                addListenerMethod.invoke(player, proxyListener)
            } catch (e: Exception) {}
        }

        private fun findUrlInObject(obj: Any, depth: Int = 0): String? {
            if (depth > 3) return null
            try {
                for (f in obj.javaClass.declaredFields) {
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is String && value.contains("mega-webview://")) return value
                    if (value is Uri && value.toString().contains("mega-webview://")) return value.toString()
                    val pkg = value.javaClass.`package`?.name ?: ""
                    if (pkg.contains("lagradost") || pkg.contains("media3") || pkg.contains("exoplayer") || pkg.contains("google")) {
                        val found = findUrlInObject(value, depth + 1)
                        if (found != null) return found
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        private fun getPlayingUrl(player: Any): String? {
            return try {
                val mediaItem = player.javaClass.getMethod("getCurrentMediaItem").invoke(player) ?: return null
                val localConfig = mediaItem.javaClass.getDeclaredField("localConfiguration").apply { isAccessible = true }.get(mediaItem) ?: return null
                val uri = localConfig.javaClass.getDeclaredField("uri").apply { isAccessible = true }.get(localConfig) as? Uri
                uri?.toString() ?: findUrlInObject(player)
            } catch (e: Exception) {
                findUrlInObject(player)
            }
        }

        private fun checkMegaPlayback(player: Any) {
            val currentTime = System.currentTimeMillis()
            synchronized(WitAnime::class.java) {
                if (WitAnime.isWebViewOpen || (currentTime - WitAnime.lastWebViewOpenTime) < 10000L || activeDialog?.isShowing == true) return
                val playingUrl = getPlayingUrl(player) ?: return
                if (playingUrl.contains("mega-webview://")) {
                    WitAnime.isWebViewOpen = true
                    WitAnime.lastWebViewOpenTime = currentTime
                    pausePlayer()
                    val realMegaUrl = playingUrl.substringAfter("mega-webview://")
                    CoroutineScope(Dispatchers.Main).launch { openMegaPlayer(realMegaUrl) }
                }
            }
        }

        private suspend fun openMegaPlayer(megaUrl: String) {
            withContext(Dispatchers.Main) {
                if (activeDialog?.isShowing == true) return@withContext
                val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment
                val activity = fragment?.activity ?: getActiveActivity()

                if (activity != null) {
                    try {
                        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                        activeDialog = dialog
                        val originalOrientation = activity.requestedOrientation
                        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        dialog.setOnDismissListener {
                            activity.requestedOrientation = originalOrientation
                            activeDialog = null
                            synchronized(WitAnime::class.java) {
                                WitAnime.isWebViewOpen = false
                                WitAnime.lastWebViewOpenTime = System.currentTimeMillis()
                            }
                            lastHookedPlayer = null
                        }
                        val frameLayout = FrameLayout(activity).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        }
                        val webView = WebView(activity).apply {
                            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            webViewClient = WebViewClient()
                            webChromeClient = WebChromeClient()
                            loadUrl(megaUrl)
                        }
                        frameLayout.addView(webView)
                        val closeButton = android.widget.Button(activity).apply {
                            text = "✕"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#99000000"))
                            }
                            setOnClickListener { dialog.dismiss() }
                        }
                        val btnSize = (32 * activity.resources.displayMetrics.density).toInt()
                        val btnParams = FrameLayout.LayoutParams(btnSize, btnSize, Gravity.TOP or Gravity.END).apply {
                            topMargin = (8 * activity.resources.displayMetrics.density).toInt()
                            marginEnd = (8 * activity.resources.displayMetrics.density).toInt()
                        }
                        frameLayout.addView(closeButton, btnParams)
                        dialog.setContentView(frameLayout)
                        dialog.show()
                    } catch (e: Exception) {
                        synchronized(WitAnime::class.java) { WitAnime.isWebViewOpen = false }
                        activeDialog = null
                    }
                }
            }
        }
    }

    init {
        PlayerAccess.startMonitoring()
    }

    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val req = Request.Builder()
            .url(url)
            .apply {
                baseHeadersMap.forEach { (k, v) -> header(k, v) }
                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                header("Accept-Language", "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            }
            .build()
        val res = app.baseClient.newCall(req).execute()
        val body = res.body?.string().orEmpty()
        res.close()
        return Jsoup.parse(body)
    }
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return withContext(Dispatchers.IO) {
            val document = fetchDocument(mainUrl)
            val homePageList = ArrayList<HomePageList>()

            document.select("section.mb-14").forEach { section ->
                val title = section.selectFirst("h2")?.text()?.trim() ?: return@forEach

                val items = section.select("a[href*='/watch/'], a[href*='/anime/'], a[href*='/movie/']").mapNotNull { card ->
                    val href = card.attr("href")
                    if (!href.contains("/watch/") && !href.contains("/anime/") && !href.contains("/movie/")) return@mapNotNull null

                    val name = card.selectFirst("h3")?.text()?.trim()
                        ?: card.attr("title")
                        ?: card.selectFirst("img")?.attr("alt")
                        ?: return@mapNotNull null

                    val epText = card.selectFirst("div:contains(الحلقة)")?.text()?.trim()
                    val finalTitle = if (!epText.isNullOrEmpty() && !name.contains("الحلقة")) "$name - $epText" else name
                    val poster = card.selectFirst("img")?.attr("src")

                    newAnimeSearchResponse(finalTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                    }
                }.distinctBy { it.url }

                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(title, items))
                }
            }

            val aside = document.selectFirst("aside")
            if (aside != null) {
                val asideTitle = aside.selectFirst("h2")?.text()?.trim() ?: "أشهر أنميات الموسم"
                val asideItems = aside.select("a[href*='/anime/']").mapNotNull { item ->
                    val href = item.attr("href")
                    val name = item.selectFirst("span[dir='ltr']")?.text()?.trim() ?: return@mapNotNull null
                    val poster = item.selectFirst("img")?.attr("src")
                    newAnimeSearchResponse(name, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                    }
                }.distinctBy { it.url }

                if (asideItems.isNotEmpty()) {
                    homePageList.add(HomePageList(asideTitle, asideItems))
                }
            }

            newHomePageResponse(homePageList)
        }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        return withContext(Dispatchers.IO) {
            val url = "$mainUrl/search?q=$query"
            val document = fetchDocument(url)

            document.select("div.grid a[href*='/anime/'], div.grid a[href*='/movie/']").mapNotNull { card ->
                val href = card.attr("href")
                val title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null
                val poster = card.selectFirst("img")?.attr("src")
                val isMovie = href.contains("/movie/")

                newAnimeSearchResponse(title, fixUrl(href), if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                    this.posterUrl = poster
                }
            }.distinctBy { it.url }
        }
    }
    override suspend fun load(url: String): LoadResponse {
        return withContext(Dispatchers.IO) {
            val document = fetchDocument(url)

            val title = document.selectFirst("h1 span[dir='ltr']")?.text()?.trim()
                ?: document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:title']")?.attr("content")
                    ?.replace("مترجم", "")
                    ?.replace("اون لاين", "")
                    ?.replace("فيلم", "")
                    ?.replace("انمي", "")
                    ?.trim()
                ?: ""
            val poster = document.selectFirst("img[src*='/posters/']")?.attr("src")
                ?: document.selectFirst("div.shrink-0 img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("meta[name='image']")?.attr("content")

            val description = document.selectFirst("p.leading-relaxed")?.text()?.trim()
                ?: document.selectFirst("meta[property='og:description']")?.attr("content")

            val isMovie = url.contains("/movie/")
            val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

            val genres = mutableListOf<String>()
            document.select("div.flex-wrap.gap-2 span.rounded-full").forEach {
                val tag = it.text().trim()
                if (tag.isNotEmpty()) genres.add(tag)
            }

            var year: Int? = null
            var status = ShowStatus.Completed

            if (document.selectFirst("span:contains(مستمر)") != null) {
                status = ShowStatus.Ongoing
            }

            document.select("div.grid > div").forEach { box ->
                val label = box.selectFirst("span")?.text()?.trim() ?: ""
                val value = box.selectFirst("p")?.text()?.trim() ?: ""
                if (label.contains("السنة")) {
                    year = Regex("""\d{4}""").find(value)?.value?.toIntOrNull()
                }
            }

            val ratingText = document.selectFirst("span[dir='ltr']:has(svg)")?.text()?.trim()
            val rating = ratingText?.toFloatOrNull()

            val episodes = mutableListOf<Episode>()

            if (isMovie) {
                val watchUrl = document.selectFirst("a[href*='/watch/movie/']")?.attr("href") ?: url
                episodes.add(newEpisode(fixUrl(watchUrl)) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                })
            } else {
                document.select("div[data-episode-list] a[href*='/watch/']").forEach { epTag ->
                    val epHref = epTag.attr("href")
                    val epNum = Regex("""/(\d+)$""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""\d+""").find(epTag.text())?.value?.toIntOrNull()

                    episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = "الحلقة ${epNum ?: ""}".trim()
                        this.episode = epNum
                        this.posterUrl = poster
                    })
                }
                episodes.sortBy { it.episode }
            }

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                this.showStatus = status
                
                addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
            }
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val directClient = app.baseClient.newBuilder()
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .callTimeout(20, TimeUnit.SECONDS)
                    .build()

                val cookieStore = mutableMapOf<String, String>()

                fun saveCookiesFrom(res: okhttp3.Response) {
                    res.headers("Set-Cookie").forEach { header ->
                        val cookiePart = header.substringBefore(";")
                        val eqIdx = cookiePart.indexOf("=")
                        if (eqIdx > 0) {
                            val k = cookiePart.substring(0, eqIdx).trim()
                            val v = cookiePart.substring(eqIdx + 1).trim()
                            cookieStore[k] = v
                        }
                    }
                }

                fun makeCookieHeader(): String =
                    cookieStore.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val cacheBusterUrl = "$data?t=${System.currentTimeMillis()}"
                val randomUa = rotatingUas.random()

                val pageReq = Request.Builder()
                    .url(cacheBusterUrl)
                    .header("User-Agent", randomUa)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ar-IQ,ar;q=0.9,en-US;q=0.8,en;q=0.7")
                    .build()

                val pageRes = directClient.newCall(pageReq).execute()
                saveCookiesFrom(pageRes)
                val pageHtml = pageRes.body?.string().orEmpty()
                pageRes.close()

                val csrfToken = Regex("""name=['"]csrf-token['"]\s+content=['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""data-csrf=['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                    ?: return@withContext false
                delay(1300)
                val sourcesPattern = Regex("""sourcesUrl:\s*['"]([^'"]+)['"]""")
                val rawSources = sourcesPattern.find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")
                    ?: "${data.trimEnd('/')}/sources"
                val sourcesUrl = fixUrl(rawSources)

                val sourcesReq = Request.Builder()
                    .url(sourcesUrl)
                    .apply {
                        baseHeadersMap.forEach { (k, v) -> header(k, v) }
                        header("X-Csrf-Token", csrfToken)
                        header("Accept", "application/json")
                        header("Origin", "https://witanime.site")
                        header("Referer", data)
                        header("Sec-Fetch-Dest", "empty")
                        header("Sec-Fetch-Mode", "cors")
                        header("Sec-Fetch-Site", "same-origin")
                        header("Cookie", makeCookieHeader())
                    }
                    .post(RequestBody.create(null, ByteArray(0)))
                    .build()

                val sourcesRes = directClient.newCall(sourcesReq).execute()
                saveCookiesFrom(sourcesRes)
                val sourcesJsonStr = sourcesRes.body?.string().orEmpty()
                sourcesRes.close()

                val json = JSONObject(sourcesJsonStr)
                val players = json.optJSONObject("players")
                val downloads = json.optJSONObject("downloads")

                data class ServerItem(val quality: String, val label: String, val token: String, val isStream: Boolean)
                val allServers = mutableListOf<ServerItem>()
                players?.let { p ->
                    for (q in p.keys()) {
                        val arr = p.optJSONArray(q) ?: continue
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val token = obj.optString("token")
                            val label = obj.optString("label", "Server")
                            if (token.isNotBlank()) {
                                allServers.add(ServerItem(q, label, token, true))
                            }
                        }
                    }
                }
                downloads?.let { d ->
                    for (q in d.keys()) {
                        val arr = d.optJSONArray(q) ?: continue
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val token = obj.optString("token")
                            val label = obj.optString("label", "Download")
                            if (token.isNotBlank()) {
                                allServers.add(ServerItem(q, label, token, false))
                            }
                        }
                    }
                }
                val limitedServers = allServers.take(10)
                for ((idx, server) in limitedServers.withIndex()) {
                    try {
                        val sourceEndpoint = if (server.isStream) "stream-source" else "download-source"
                        val gateEndpoint = if (server.isStream) "stream-gate" else "download-gate"

                        val sourceUrl = "https://witanime.site/watch/$sourceEndpoint/${server.token}"
                        val gateUrl = "https://witanime.site/watch/$gateEndpoint/${server.token}"
                        val sReq = Request.Builder()
                            .url(sourceUrl)
                            .apply {
                                baseHeadersMap.forEach { (k, v) -> header(k, v) }
                                header("X-Csrf-Token", csrfToken)
                                header("Accept", "application/json")
                                header("Origin", "https://witanime.site")
                                header("Sec-Fetch-Dest", "empty")
                                header("Sec-Fetch-Mode", "cors")
                                header("Sec-Fetch-Site", "same-origin")
                                header("Cookie", makeCookieHeader())
                            }
                            .post(RequestBody.create(null, ByteArray(0)))
                            .build()

                        val sRes = directClient.newCall(sReq).execute()
                        saveCookiesFrom(sRes)
                        sRes.close()
                        val gReq = Request.Builder()
                            .url(gateUrl)
                            .apply {
                                header("Host", "witanime.site")
                                header("User-Agent", customUserAgent)
                                header("Sec-Ch-Ua", "\"Google Chrome\";v=\"155\", \"Chromium\";v=\"155\", \"Not(A:Brand\";v=\"24\"")
                                header("Sec-Ch-Ua-Mobile", "?1")
                                header("Sec-Ch-Ua-Platform", "\"Android\"")
                                header("Upgrade-Insecure-Requests", "1")
                                header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/jxl,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                                header("Sec-Fetch-Site", "same-origin")
                                header("Sec-Fetch-Mode", "navigate")
                                header("Sec-Fetch-User", "?1")
                                header("Sec-Fetch-Dest", "iframe")
                                header("Cookie", makeCookieHeader())
                            }
                            .get()
                            .build()

                        val gRes = directClient.newCall(gReq).execute()
                        saveCookiesFrom(gRes)
                        var embedUrl = gRes.header("Location") ?: gRes.header("location")
                        if (embedUrl.isNullOrBlank()) {
                            val gBody = gRes.body?.string().orEmpty()
                            val match = Regex("""url=['"]([^'"]+)['"]""").find(gBody)
                                ?: Regex("""href=["'](https?://[^"']+)["']""").find(gBody)
                            embedUrl = match?.groupValues?.get(1)
                        }
                        gRes.close()
                        if (!embedUrl.isNullOrBlank() && embedUrl.startsWith("http")) {
                            val typeLabel = if (server.isStream) "مشاهدة" else "تنزيل"
                            val displayName = "${server.label} (${server.quality}) [$typeLabel]"
                            
                            if (embedUrl.contains("mega.nz")) {
                                val dummyUrl = "https://raw.githubusercontent.com/Anarios/Cloudstream/master/app/src/main/res/raw/blank.mp4#mega-webview://$embedUrl"
                                callback(
                                    newExtractorLink(
                                        name = "$displayName [WebView]",
                                        source = "WitAnime",
                                        url = dummyUrl
                                    ) {
                                        this.referer = embedUrl
                                        this.quality = Qualities.Unknown.value
                                        this.type = ExtractorLinkType.VIDEO
                                    }
                                )
                            } else {
                                loadExtractor(embedUrl, data, subtitleCallback, callback)
                            }
                        }
                        if (idx < limitedServers.size - 1) {
                            delay(2000)
                        }
                    } catch (e: Exception) {
                        logError(e)
                        delay(2000)
                    }
                }

                true
            } catch (e: Exception) {
                logError(e)
                false
            }
        }
    }
}
