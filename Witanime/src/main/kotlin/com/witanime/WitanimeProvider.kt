package com.witanime

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
class EngineSessionCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, Cookie>()

    private fun baseDomain(host: String): String {
        val parts = host.lowercase().trim('.').split('.')
        return if (parts.size >= 2) parts.takeLast(2).joinToString(".") else host.lowercase()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val base = baseDomain(url.host)
        for (cookie in cookies) {
            val key = "$base|${cookie.name}"
            if (cookie.value.isEmpty()) {
                store.remove(key)
            } else {
                store[key] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val base = baseDomain(url.host)
        val result = mutableListOf<Cookie>()
        for (entry in store.entries) {
            val cookie = entry.value
            if (entry.key.startsWith("$base|") || cookie.matches(url)) {
                result.add(cookie)
            }
        }
        return result
    }
}

class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.site"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    companion object {
        private const val TAG = "WitAnimeDebug"

        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L

        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        val engineCookieJar = EngineSessionCookieJar()
        val trustAllManager: X509TrustManager = @SuppressLint("CustomX509TrustManager") object : X509TrustManager {
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            @SuppressLint("TrustAllX509TrustManager")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val trustAllSslSocketFactory = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }.socketFactory

        private val clientHintsInterceptor = okhttp3.Interceptor { chain ->
            val orig = chain.request()
            val ua = orig.header("User-Agent") ?: DEFAULT_UA
            if (ua.contains("Chrome/", ignoreCase = true) && orig.header("sec-ch-ua") == null) {
                val updated = orig.newBuilder()
                    .header("sec-ch-ua", "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                    .header("sec-ch-ua-mobile", "?0")
                    .header("sec-ch-ua-platform", "\"Windows\"")
                    .build()
                chain.proceed(updated)
            } else {
                chain.proceed(orig)
            }
        }

        val engineClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .cookieJar(engineCookieJar)
                .addInterceptor(clientHintsInterceptor)
                .sslSocketFactory(trustAllSslSocketFactory, trustAllManager)
                .hostnameVerifier { _, _ -> true }
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        val noRedirectClient: OkHttpClient by lazy {
            engineClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
        fun hostPriority(name: String): Int {
            val n = name.lowercase().trim()
            return when {
                n.contains("videas") || n == "svidea" -> 100
                n == "videa" -> 95
                n == "ok" || n.contains("okru") -> 90
                n.contains("mp4upload") -> 85
                n.contains("mega") -> 80
                n.contains("4shared") -> 75
                n.contains("yonaplay") -> 70
                else -> 50
            }
        }

        fun qualityRank(q: String): Int = when (q.uppercase().trim()) {
            "4K", "2160P" -> 400
            "FHD", "1080P" -> 300
            "HD", "720P" -> 200
            "SD", "480P" -> 100
            else -> 50
        }
    }

    object PlayerAccess {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false

        fun startMonitoring() {
            if (isLoopStarted) return
            isLoopStarted = true
            isMonitoring = true
            handler.post(monitorRunnable)
        }

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L)
            }
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
            } catch (_: Exception) {}
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
            } catch (_: Exception) { null }
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
            } catch (_: Exception) { null }
        }

        fun currentPlayer(): Any? {
            val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment ?: return null
            return try {
                val playerField = fragment.javaClass.getDeclaredField("player").apply { isAccessible = true }
                playerField.get(fragment)
            } catch (_: Exception) {
                try {
                    fragment.javaClass.getMethod("getPlayer").invoke(fragment)
                } catch (_: Exception) { null }
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
            } catch (_: Exception) {}
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
                } catch (_: Exception) {
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
            } catch (_: Exception) {}
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
            } catch (_: Exception) {}
            return null
        }

        private fun getPlayingUrl(player: Any): String? {
            return try {
                val mediaItem = player.javaClass.getMethod("getCurrentMediaItem").invoke(player) ?: return null
                val localConfig = mediaItem.javaClass.getDeclaredField("localConfiguration").apply { isAccessible = true }.get(mediaItem) ?: return null
                val uri = localConfig.javaClass.getDeclaredField("uri").apply { isAccessible = true }.get(localConfig) as? Uri
                uri?.toString() ?: findUrlInObject(player)
            } catch (_: Exception) {
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
                    } catch (_: Exception) {
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

    private fun buildDocumentRequest(url: String, referer: String? = "$mainUrl/"): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", DEFAULT_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
            .header("Upgrade-Insecure-Requests", "1")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-User", "?1")
            .header("Sec-Fetch-Site", if (referer != null) "same-origin" else "none")
        if (!referer.isNullOrBlank()) {
            builder.header("Referer", referer)
        }
        return builder.build()
    }

    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val res = engineClient.newCall(buildDocumentRequest(url)).execute()
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
            val rating = ratingText?.toDoubleOrNull()

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
                this.score = rating?.let {
                    Score.from10(it)
                }

                addEpisodes(DubStatus.Subbed, episodes.distinctBy { it.data })
            }
        }
    }

    data class ServerItem(val quality: String, val label: String, val token: String, val isStream: Boolean)
    
    private data class GateResult(
        val success: Boolean,
        val isRateLimited: Boolean,
        val remainingQuota: Int,
        val embedUrl: String? = null
    )

    private fun unlockGate(
        s: ServerItem,
        csrfToken: String,
        watchUrl: String
    ): GateResult {
        val emptyBody = ByteArray(0).toRequestBody(null)
        val encodedToken = URLEncoder.encode(s.token, "UTF-8")

        return try {
            if (s.isStream) {
                val sourceUrl = "$mainUrl/watch/stream-source/$encodedToken"
                val sReq = Request.Builder()
                    .url(sourceUrl)
                    .post(emptyBody)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", csrfToken)
                    .header("Origin", mainUrl)
                    .header("Referer", watchUrl)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()

                var gatePath = "/watch/stream-gate/$encodedToken"
                var refPolicy = "no-referrer"

                engineClient.newCall(sReq).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateResult(false, isRateLimited = true, remainingQuota = rem)
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) return GateResult(false, isRateLimited = false, remainingQuota = rem)

                    runCatching { JSONObject(body) }.getOrNull()?.let { j ->
                        j.optString("gateUrl").takeIf { it.isNotBlank() }?.let { gatePath = it }
                        j.optString("referrerPolicy").takeIf { it.isNotBlank() }?.let { refPolicy = it }
                    }
                }

                val fullGateUrl = fixUrl(gatePath)
                val gateReqBuilder = Request.Builder()
                    .url(fullGateUrl)
                    .get()
                    .header("User-Agent", DEFAULT_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "iframe")
                    .header("Upgrade-Insecure-Requests", "1")

                if (refPolicy.equals("origin", ignoreCase = true)) {
                    gateReqBuilder.header("Referer", "$mainUrl/")
                }

                noRedirectClient.newCall(gateReqBuilder.build()).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateResult(false, isRateLimited = true, remainingQuota = rem)

                    var embedUrl = resp.header("Location") ?: resp.header("location")
                    if (embedUrl.isNullOrBlank()) {
                        val gBody = resp.body?.string().orEmpty()
                        val doc = Jsoup.parse(gBody, fullGateUrl)
                        embedUrl = doc.selectFirst("iframe[src]")?.attr("src")
                            ?: Regex("""url=['"]([^'"]+)['"]""").find(gBody)?.groupValues?.get(1)
                            ?: Regex("""href=["'](https?://[^"']+)["']""").find(gBody)?.groupValues?.get(1)
                    }

                    if (!embedUrl.isNullOrBlank() && embedUrl.startsWith("http")) {
                        return GateResult(true, isRateLimited = false, remainingQuota = rem, embedUrl = fixUrl(embedUrl))
                    }
                }
            } else {
                val sourceUrl = "$mainUrl/watch/download-source/$encodedToken"
                val sReq = Request.Builder()
                    .url(sourceUrl)
                    .post(emptyBody)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", csrfToken)
                    .header("Origin", mainUrl)
                    .header("Referer", watchUrl)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()

                engineClient.newCall(sReq).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateResult(false, isRateLimited = true, remainingQuota = rem)
                    if (!resp.isSuccessful) return GateResult(false, isRateLimited = false, remainingQuota = rem)
                }

                val fullGateUrl = "$mainUrl/watch/download-gate/$encodedToken"
                val gateReq = Request.Builder()
                    .url(fullGateUrl)
                    .get()
                    .header("User-Agent", DEFAULT_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                    .header("Referer", watchUrl)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Dest", "document")
                    .header("Upgrade-Insecure-Requests", "1")
                    .build()

                noRedirectClient.newCall(gateReq).execute().use { resp ->
                    val rem = resp.header("x-ratelimit-remaining")?.toIntOrNull() ?: 0
                    if (resp.code == 429) return GateResult(false, isRateLimited = true, remainingQuota = rem)

                    val loc = resp.header("Location") ?: resp.header("location")
                    if (!loc.isNullOrBlank()) {
                        val resolved = fixUrl(loc.trim())
                        return GateResult(true, isRateLimited = false, remainingQuota = rem, embedUrl = resolved)
                    }
                }
            }
            GateResult(false, isRateLimited = false, remainingQuota = 0)
        } catch (_: Exception) {
            GateResult(false, isRateLimited = false, remainingQuota = 0)
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
                val cleanWatchUrl = fixUrl(data.substringBefore('?').trimEnd('/'))
                Log.i(TAG, "1. البدء برابط المشاهدة: $cleanWatchUrl")

                var finalWatchUrl = cleanWatchUrl
                val pageReq = buildDocumentRequest(cleanWatchUrl, "$mainUrl/")
                val pageHtml = engineClient.newCall(pageReq).execute().use { resp ->
                    finalWatchUrl = resp.request.url.toString()
                    Log.i(TAG, "2. استجابة صفحة الحلقة كود: ${resp.code} (الرابط: $finalWatchUrl)")
                    if (!resp.isSuccessful) return@withContext false
                    resp.body?.string().orEmpty()
                }
                val csrfToken = Regex("""name=['"]csrf-token['"]\s+content=['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                    ?: Regex("""data-csrf=['"]([^'"]+)['"]""").find(pageHtml)?.groupValues?.get(1)
                    ?: run {
                        Log.e(TAG, "فشل استخراج CSRF Token!")
                        return@withContext false
                    }
                Log.i(TAG, "3. تم العثور على CSRF Token: $csrfToken")
                val sourcesPattern = Regex("""sourcesUrl\s*:\s*['"]([^'"]+)['"]""")
                val rawSources = sourcesPattern.find(pageHtml)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
                    ?: "${finalWatchUrl.substringBefore('?').trimEnd('/')}/sources"
                val sourcesUrl = fixUrl(rawSources)
                Log.i(TAG, "4. رابط Sources المستخرج: $sourcesUrl")

                delay(300)
                val emptyBody = ByteArray(0).toRequestBody(null)
                val sourcesReq = Request.Builder()
                    .url(sourcesUrl)
                    .post(emptyBody)
                    .header("User-Agent", DEFAULT_UA)
                    .header("Accept", "application/json")
                    .header("X-CSRF-TOKEN", csrfToken)
                    .header("Origin", mainUrl)
                    .header("Referer", finalWatchUrl)
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Dest", "empty")
                    .build()

                val sourcesJsonStr = engineClient.newCall(sourcesReq).execute().use { resp ->
                    Log.i(TAG, "5. استجابة /sources كود: ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "تفاصيل خطأ sources: $body")
                        return@withContext false
                    }
                    body
                }

                if (sourcesJsonStr.isBlank()) return@withContext false
                val rootJson = JSONObject(sourcesJsonStr)
                val allServers = mutableListOf<ServerItem>()

                fun extractGroup(playersObj: JSONObject?, downloadsObj: JSONObject?) {
                    playersObj?.let { p ->
                        for (q in p.keys()) {
                            val arr = p.optJSONArray(q) ?: continue
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val token = obj.optString("token")
                                val label = obj.optString("label").ifBlank { obj.optString("name", "Server") }
                                if (token.isNotBlank()) allServers.add(ServerItem(q, label, token, true))
                            }
                        }
                    }
                    downloadsObj?.let { d ->
                        for (q in d.keys()) {
                            val arr = d.optJSONArray(q) ?: continue
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val token = obj.optString("token")
                                val label = obj.optString("label").ifBlank { obj.optString("name", "Download") }
                                if (token.isNotBlank()) allServers.add(ServerItem(q, label, token, false))
                            }
                        }
                    }
                }

                if (rootJson.has("players") || rootJson.has("downloads")) {
                    extractGroup(rootJson.optJSONObject("players"), rootJson.optJSONObject("downloads"))
                }

                rootJson.optJSONObject("versions")?.let { vObj ->
                    val keys = vObj.keys()
                    while (keys.hasNext()) {
                        val ver = keys.next()
                        val subGroup = vObj.optJSONObject(ver) ?: continue
                        extractGroup(subGroup.optJSONObject("players"), subGroup.optJSONObject("downloads"))
                    }
                }

                Log.i(TAG, "6. تم استخراج ${allServers.size} سيرفر!")
                if (allServers.isEmpty()) return@withContext false
                val sortedServers = allServers.sortedByDescending { if (it.isStream) 1 else 0 }
                val limitedServers = sortedServers.take(10)
                var foundAny = false
                for ((idx, server) in limitedServers.withIndex()) {
                    try {
                        val gateResult = unlockGate(server, csrfToken, finalWatchUrl)
                        val embedUrl = gateResult.embedUrl

                        if (gateResult.success && !embedUrl.isNullOrBlank()) {
                            Log.i(TAG, "سيرفر [${server.label}] تم فك الرابط بنجاح: $embedUrl")

                            if (embedUrl.contains("mega.nz") || embedUrl.contains("mega.co.nz")) {
                                val typeLabel = if (server.isStream) "مشاهدة" else "تنزيل"
                                val displayName = "${server.label} (${server.quality}) [$typeLabel]"

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
                                foundAny = true
                            } else {
                                loadExtractor(embedUrl, finalWatchUrl, subtitleCallback) { link ->
                                    foundAny = true
                                    callback(link)
                                }
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

                Log.i(TAG, "انتهت العملية، هل تم العثور على روابط: $foundAny")
                foundAny
            } catch (e: Exception) {
                logError(e)
                false
            }
        }
    }
}
