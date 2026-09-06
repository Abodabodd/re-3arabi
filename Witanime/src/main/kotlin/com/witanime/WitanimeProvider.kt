
package com.witanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import android.util.Base64
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.nio.charset.Charset
import org.json.JSONArray
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlin.text.toIntOrNull
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import kotlinx.coroutines.*
import kotlin.text.RegexOption
import android.util.Log
import android.widget.FrameLayout
import java.util.logging.Handler
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Looper
import android.view.Gravity
class WitAnime : MainAPI() {
    override var mainUrl = "https://witanime.you"
    override var name = "WitAnime"
    override val hasMainPage = true
    override var lang = "ar"

    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    private val userAgent =
        "Mozilla/5.0 (Linux; Android 10; SM-G975F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/83.0.4103.106 Mobile Safari/537.36"
    companion object {
        @Volatile var isWebViewOpen = false
        @Volatile var lastWebViewOpenTime = 0L
        private const val DEBOUNCE_DELAY_MS = 10000L // 🌟 مهلة 10 ثوانٍ كاملة لمنع فتح نافذتين في نفس الوقت
    }
    object PlayerAccess {

        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private var isMonitoring = false
        private var lastHookedPlayer: Any? = null
        private var activeDialog: Dialog? = null
        private var isLoopStarted = false
        @Volatile var isWebViewOpen = false

        private val monitorRunnable = object : Runnable {
            override fun run() {
                if (!isMonitoring) return
                hookPlayerListener()
                handler.postDelayed(this, 1000L) // فحص دوري كل ثانية واحدة لحقن المستمع
            }
        }

        fun startMonitoring() {
            if (isLoopStarted) {
                android.util.Log.d("WitAnimeScanner", "⏭️ حلقة المراقبة تعمل بالفعل في الذاكرة، تم تجاهل الطلب المكرر.")
                return
            }
            isLoopStarted = true
            isMonitoring = true
            android.util.Log.d("WitAnimeScanner", "🚀 تم بدء تشغيل دالة مراقبة مشغل الفيديو بنجاح لأول مرة كحلقة وحيدة!")
            handler.post(monitorRunnable)
        }
        private fun findFragmentRecursive(fragment: androidx.fragment.app.Fragment, packageName: String): androidx.fragment.app.Fragment? {
            if (fragment.javaClass.name.startsWith(packageName)) return fragment
            try {
                val childFragments = fragment.childFragmentManager.fragments
                for (child in childFragments) {
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
                val activitiesField = activityThreadClass.getDeclaredField("mActivities")
                activitiesField.isAccessible = true
                val activities = activitiesField.get(activityThread) as Map<*, *>
                var activeActivity: Activity? = null
                for (activityRecord in activities.values) {
                    if (activityRecord == null) continue
                    val activityRecordClass = activityRecord.javaClass
                    val activityField = activityRecordClass.getDeclaredField("activity")
                    activityField.isAccessible = true
                    val act = activityField.get(activityRecord) as? Activity

                    if (act != null && !act.isFinishing && !act.isDestroyed) {
                        activeActivity = act
                        break
                    }
                }
                activeActivity
            } catch (e: Exception) {
                android.util.Log.e("WitAnimeScanner", "❌ فشل استخراج الـ Activity ريفلكتيفلي", e)
                null
            }
        }
        fun getPlayerFragment(): Any? {
            val activity = getActiveActivity()
            if (activity == null) {
                return null
            }
            return try {
                val fragments = (activity as? androidx.fragment.app.FragmentActivity)
                    ?.supportFragmentManager
                    ?.fragments
                if (fragments == null) {
                    return null
                }
                for (f in fragments) {
                    if (f != null) {
                        val found = findFragmentRecursive(f, "com.lagradost.cloudstream3.ui.player")
                        if (found != null) {
                            return found
                        }
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
        fun currentPlayer(): Any? {
            val fragment = getPlayerFragment() as? androidx.fragment.app.Fragment ?: return null
            return try {
                val playerField = fragment.javaClass.getDeclaredField("player")
                playerField.isAccessible = true
                playerField.get(fragment)
            } catch (e: Exception) {
                try {
                    val getPlayerMethod = fragment.javaClass.getMethod("getPlayer")
                    getPlayerMethod.invoke(fragment)
                } catch (ex: Exception) {
                    null
                }
            }
        }
        fun getAppContext(): Context? {
            return try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val currentApplicationMethod = activityThreadClass.getMethod("currentApplication")
                currentApplicationMethod.invoke(null) as? Context
            } catch (e: Exception) {
                null
            }
        }
        fun pausePlayer() {
            val player = currentPlayer() ?: return
            try {
                val methods = player.javaClass.methods
                val handleEventMethod = methods.firstOrNull { it.name == "handleEvent" }
                if (handleEventMethod != null) {
                    val parameterTypes = handleEventMethod.parameterTypes
                    if (parameterTypes.isNotEmpty()) {
                        val eventEnumClass = parameterTypes[0]
                        val pauseEnumConstant = eventEnumClass.enumConstants?.firstOrNull {
                            it.toString().contains("Pause", ignoreCase = true)
                        }

                        if (parameterTypes.size == 2) {
                            val sourceEnumClass = parameterTypes[1]
                            val syncEnumConstant = sourceEnumClass.enumConstants?.firstOrNull {
                                it.toString().contains("Sync", ignoreCase = true)
                            } ?: sourceEnumClass.enumConstants?.firstOrNull {
                                it.toString().contains("UI", ignoreCase = true)
                            }
                            handleEventMethod.invoke(player, pauseEnumConstant, syncEnumConstant)
                        } else {
                            handleEventMethod.invoke(player, pauseEnumConstant)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        fun getRealExoPlayer(player: Any): Any? {
            val rootClassName = player.javaClass.name
            if (rootClassName.contains("Player", ignoreCase = true) && !rootClassName.contains("Cache", ignoreCase = true)) {
                try {
                    val methods = player.javaClass.methods
                    if (methods.any { it.name == "addListener" }) {
                        return player
                    }
                } catch (e: Exception) {}
            }

            try {
                val fields = player.javaClass.declaredFields
                for (f in fields) {
                    f.isAccessible = true
                    val value = f.get(player) ?: continue
                    val className = value.javaClass.name

                    if (className.contains("Player", ignoreCase = true) && !className.contains("Cache", ignoreCase = true)) {
                        try {
                            val methods = value.javaClass.methods
                            if (methods.any { it.name == "addListener" }) {
                                return value
                            }
                        } catch (ex: Exception) {}
                    }
                }
            } catch (e: Exception) {}
            return null
        }
        private fun hookPlayerListener() {
            val rawPlayer = currentPlayer() ?: return
            val player = getRealExoPlayer(rawPlayer) ?: return // جلب المشغل الحقيقي من داخل كلاس الحماية
            if (player === lastHookedPlayer) return
            lastHookedPlayer = player

            android.util.Log.d("WitAnimeScanner", "🚀 [مشغل نشط مكتشف!] جاري حقن مستمع الـ ExoPlayer الحقيقي في الذاكرة...")
            try {
                val listenerClass = try {
                    Class.forName("androidx.media3.common.Player\$Listener")
                } catch (e: Exception) {
                    Class.forName("com.google.android.exoplayer2.Player\$Listener")
                }

                val addListenerMethod = player.javaClass.getMethod("addListener", listenerClass)

                val proxyListener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass),
                    object : java.lang.reflect.InvocationHandler {
                        override fun invoke(proxy: Any, method: java.lang.reflect.Method, args: Array<out Any>?): Any? {
                            val methodName = method.name
                            if (methodName == "equals") {
                                return proxy === (args?.get(0))
                            }
                            if (methodName == "hashCode") {
                                return System.identityHashCode(proxy)
                            }
                            if (methodName == "toString") {
                                return "ExoPlayerProxyListener"
                            }
                            handler.post {
                                checkMegaPlayback(player)
                            }

                            val returnType = method.returnType
                            if (returnType == Boolean::class.javaPrimitiveType || returnType == Boolean::class.java) {
                                return false
                            }
                            if (returnType?.isPrimitive == true) {
                                return 0
                            }
                            return null
                        }
                    }
                )

                addListenerMethod.invoke(player, proxyListener)
                android.util.Log.d("WitAnimeScanner", "✅ [تم الاختراق بنجاح!] تم حقن مستمع الـ ExoPlayer بنجاح وتفعيل المراقبة المباشرة!")
            } catch (e: Exception) {
                android.util.Log.e("WitAnimeScanner", "❌ فشل حقن مستمع الـ ExoPlayer", e)
            }
        }
        private fun findUrlInObject(obj: Any, depth: Int = 0): String? {
            if (depth > 3) return null
            try {
                val fields = obj.javaClass.declaredFields
                for (f in fields) {
                    f.isAccessible = true
                    val value = f.get(obj) ?: continue
                    if (value is String && value.contains("mega-webview://")) {
                        return value
                    }
                    if (value is Uri && value.toString().contains("mega-webview://")) {
                        return value.toString()
                    }
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
                val getMediaItemMethod = player.javaClass.getMethod("getCurrentMediaItem")
                val mediaItem = getMediaItemMethod.invoke(player) ?: return null
                val localConfigField = mediaItem.javaClass.getDeclaredField("localConfiguration")
                localConfigField.isAccessible = true
                val localConfig = localConfigField.get(mediaItem) ?: return null
                val uriField = localConfig.javaClass.getDeclaredField("uri")
                uriField.isAccessible = true
                val uri = uriField.get(localConfig) as? Uri
                uri?.toString() ?: findUrlInObject(player)
            } catch (e: Exception) {
                findUrlInObject(player)
            }
        }
        private fun checkMegaPlayback(player: Any) {
            val currentTime = System.currentTimeMillis()
            synchronized(WitAnime::class.java) {
                if (WitAnime.isWebViewOpen || (currentTime - WitAnime.lastWebViewOpenTime) < 10000L || activeDialog?.isShowing == true) {
                    return
                }

                val playingUrl = getPlayingUrl(player) ?: return
                if (playingUrl.contains("mega-webview://")) {
                    WitAnime.isWebViewOpen = true
                    WitAnime.lastWebViewOpenTime = currentTime

                    android.util.Log.d("WitAnimeScanner", "🎯 [هدف مكتشف!] تم اعتراض تشغيل سيرفر Mega المخصص بنجاح: $playingUrl")
                    pausePlayer()

                    val realMegaUrl = playingUrl.substringAfter("mega-webview://")

                    val dispatcher = Dispatchers.Main
                    CoroutineScope(dispatcher).launch {
                        android.util.Log.d("WitAnimeScanner", "🌐 فتح واجهة الـ WebView المخصصة لـ Mega مع زر الخروج: $realMegaUrl")
                        openMegaPlayer(realMegaUrl)
                    }
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

                        val frameLayout = FrameLayout(activity)
                        frameLayout.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        val webView = WebView(activity)
                        webView.layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.mediaPlaybackRequiresUserGesture = false
                        webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                        webView.webViewClient = WebViewClient()
                        webView.webChromeClient = WebChromeClient()
                        webView.loadUrl(megaUrl)

                        frameLayout.addView(webView)
                        val closeButton = android.widget.Button(activity).apply {
                            text = "✕"
                            setTextColor(Color.WHITE)
                            textSize = 14f
                            setTypeface(null, android.graphics.Typeface.BOLD)
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(Color.parseColor("#99000000")) // أسود شفاف بنسبة 60%
                            }
                            setPadding(0, 0, 0, 0)
                            setOnClickListener {
                                dialog.dismiss()
                            }
                        }

                        val btnSize = (32 * activity.resources.displayMetrics.density).toInt() // حجم الزر 32dp فقط
                        val btnParams = FrameLayout.LayoutParams(
                            btnSize,
                            btnSize,
                            Gravity.TOP or Gravity.END // أعلى اليمين
                        ).apply {
                            topMargin = (8 * activity.resources.displayMetrics.density).toInt() // يبعد 8dp فقط من الأعلى
                            marginEnd = (8 * activity.resources.displayMetrics.density).toInt() // يبعد 8dp فقط من اليمين
                        }

                        frameLayout.addView(closeButton, btnParams)
                        dialog.setContentView(frameLayout)
                        dialog.show()

                    } catch (e: Exception) {
                        synchronized(WitAnime::class.java) {
                            WitAnime.isWebViewOpen = false
                        }
                        activeDialog = null
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl))
                            activity.startActivity(intent)
                        } catch (ex: Exception) {
                            android.util.Log.e("WitAnime", "Error starting activity", ex)
                        }
                    }
                } else {
                    synchronized(WitAnime::class.java) {
                        WitAnime.isWebViewOpen = false
                    }
                    activeDialog = null
                    try {
                        val context = getAppContext()
                        if (context != null) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(megaUrl))
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("WitAnime", "Error starting context", e)
                    }
                }
            }
        }
    }
    init {
        PlayerAccess.startMonitoring()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageList = ArrayList<HomePageList>()

        document.select("div.main-widget").forEach { widget ->
            val title =
                widget.selectFirst("div.main-didget-head h3")?.text()?.trim() ?: return@forEach

            val isEpisodeList = title.contains("حلقات")

            val items =
                widget.select(if (isEpisodeList) "div.episodes-card-container" else "div.anime-card-container")
                    .mapNotNull {
                        val a =
                            if (isEpisodeList) it.selectFirst(".ep-card-anime-title a") else it.selectFirst(
                                "a.overlay"
                            )
                        val itemUrl = a?.attr("href") ?: return@mapNotNull null
                        val itemName =
                            (if (isEpisodeList) a?.text() else it.selectFirst(".anime-card-title a")
                                ?.text()) ?: ""
                        val itemPoster = it.selectFirst("img")?.attr("src")

                        val finalTitle = if (isEpisodeList) {
                            val epTitle = it.selectFirst(".episodes-card-title a")?.text() ?: ""
                            "$itemName - $epTitle"
                        } else {
                            itemName
                        }

                        newAnimeSearchResponse(finalTitle, itemUrl, TvType.Anime) {
                            posterUrl = itemPoster
                        }
                    }
            if (items.isNotEmpty()) homePageList.add(HomePageList(title, items))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?search_param=animes&s=$query"

        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        return document.select("div.anime-list-content div.anime-card-container").mapNotNull {
            val a = it.selectFirst("div.anime-card-poster a")
            val href = a?.attr("href") ?: return@mapNotNull null

            val title =
                it.selectFirst("div.anime-card-title h3 a")?.text() ?: return@mapNotNull null
            val poster = it.selectFirst("img.img-responsive")?.attr("src")

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(
            url,
            interceptor = WebViewResolver(interceptUrl = Regex(url))
        ).document

        val title = document.selectFirst("h1.anime-details-title")?.text()?.trim() ?: ""
        val poster = document.selectFirst("div.anime-thumbnail img")?.attr("src")
        val description = document.selectFirst("p.anime-story")?.text()?.trim()
        val genres = document.select("ul.anime-genres li a").map { it.text() }

        var status = ShowStatus.Ongoing
        var tvType = TvType.Anime

        document.select(".anime-info").forEach {
            val infoText = it.text()
            if (infoText.startsWith("حالة الأنمي:")) {
                status =
                    if (infoText.contains("مكتمل")) ShowStatus.Completed else ShowStatus.Ongoing
            }
            if (infoText.startsWith("النوع:")) {
                tvType = if (infoText.contains("Movie")) TvType.AnimeMovie else TvType.Anime
            }
        }

        var episodes = listOf<Episode>()


        val regex = Regex("""var\s+processedEpisodeData\s*=\s*'([^']+)'""")
        val match = regex.find(document.html())
        val encodedData = match?.groupValues?.get(1)

        if (!encodedData.isNullOrBlank()) {
            try {
                val parts = encodedData.split(".")
                if (parts.size == 2) {
                    val part1 =
                        String(android.util.Base64.decode(parts[0], android.util.Base64.DEFAULT))
                    val part2 =
                        String(android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT))

                    val decodedJson = StringBuilder()
                    for (i in part1.indices) {
                        decodedJson.append((part1[i].code xor part2[i % part2.length].code).toChar())
                    }

                    val episodesList =
                        AppUtils.parseJson(decodedJson.toString()) as? List<Map<String, Any>>
                    if (episodesList != null) {
                        episodes = episodesList.mapNotNull { ep ->
                            val epUrl = ep["url"]?.toString() ?: return@mapNotNull null
                            val epName =
                                ep["number"]?.toString() ?: ep["title"]?.toString() ?: "حلقة"
                            newEpisode(epUrl) { this.name = epName }
                        }
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        return newAnimeLoadResponse(title, url, tvType) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            this.showStatus = status
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val TAG = "WitAnimeLinks"

        fun cleanBase64Chars(s: String): String = s.replace(Regex("[^A-Za-z0-9+/=]"), "")

        fun base64DecodeBytes(input: String?): ByteArray {
            if (input.isNullOrBlank()) return ByteArray(0)
            return try {
                android.util.Base64.decode(input, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                ByteArray(0)
            }
        }

        fun bytesToStringSafe(bytes: ByteArray): String {
            if (bytes.isEmpty()) return ""
            return try {
                String(bytes, Charsets.UTF_8)
            } catch (e: Exception) {
                try {
                    String(bytes, Charset.forName("ISO-8859-1"))
                } catch (e2: Exception) {
                    bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() }
                }
            }
        }

        fun hexToByteArray(hex: String?): ByteArray {
            if (hex.isNullOrBlank()) return ByteArray(0)
            val cleaned = hex.replace(Regex("[^0-9a-fA-F]"), "")
            if (cleaned.length % 2 != 0) return ByteArray(0)
            return cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun xorWithKey(data: ByteArray, key: ByteArray): ByteArray {
            if (key.isEmpty()) return data
            val out = ByteArray(data.size)
            for (i in data.indices) out[i] =
                (data[i].toInt() xor key[i % key.size].toInt()).toByte()
            return out
        }

        fun safeTrim(s: String?): String {
            if (s == null) return ""
            return s.replace(Regex("[\\x00\\u0000]"), "").trim()
        }

        suspend fun fetchUrl(url: String): String {
            return try {
                app.get(url).text
            } catch (e: Exception) {
                ""
            }
        }

        fun getParamOffsetFromConfig(config: Any?): Int {
            if (config == null) return 0
            try {
                if (config is Map<*, *>) {
                    val k = config["k"] as? String ?: return 0
                    val d = config["d"]
                    val idxStr = try {
                        bytesToStringSafe(base64DecodeBytes(k))
                    } catch (e: Exception) {
                        ""
                    }
                    val idx = idxStr.toIntOrNull() ?: return 0
                    when (d) {
                        is List<*> -> return (d.getOrNull(idx) as? Number)?.toInt() ?: 0
                        is Array<*> -> return (d.getOrNull(idx) as? Number)?.toInt() ?: 0
                        else -> return 0
                    }
                } else if (config is JSONObject) {
                    val k = if (config.has("k")) config.optString("k", null) else null
                    if (k.isNullOrBlank()) return 0
                    val idxStr = bytesToStringSafe(base64DecodeBytes(k))
                    val idx = idxStr.toIntOrNull() ?: return 0
                    val dArr = if (config.has("d")) config.get("d") else return 0
                    if (dArr is JSONArray) return dArr.getInt(idx)
                }
            } catch (e: Exception) { /* ignore */
            }
            return 0
        }

        fun decodeX18cResource(resourceRaw: Any?, paramOffset: Int): String {
            var raw: String? = null
            if (resourceRaw is String) raw = resourceRaw
            else if (resourceRaw is Map<*, *>) {
                raw =
                    (resourceRaw["r"] ?: resourceRaw["resource"] ?: resourceRaw["data"]) as? String
            } else if (resourceRaw is JSONObject) {
                raw = when {
                    resourceRaw.has("r") -> resourceRaw.optString("r", null)
                    resourceRaw.has("resource") -> resourceRaw.optString("resource", null)
                    resourceRaw.has("data") -> resourceRaw.optString("data", null)
                    else -> null
                }
            }
            if (raw.isNullOrBlank()) return ""
            val rev = raw.reversed()
            val cleaned = cleanBase64Chars(rev)
            val decodedBytes = try {
                android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                ByteArray(0)
            }
            val slice =
                if (paramOffset > 0 && paramOffset <= decodedBytes.size) decodedBytes.copyOf(
                    decodedBytes.size - paramOffset
                ) else decodedBytes
            val out = safeTrim(bytesToStringSafe(slice))
            return out
        }

        fun parsePx9FromScript(js: String): Triple<String?, List<String>, Map<String, List<String>>> {
            val mMatch = Regex("""var\s+_m\s*=\s*\{\s*\"r\"\s*:\s*\"([^\"]+)\"""").find(js)
            val mVal = mMatch?.groupValues?.get(1)

            val xMatch =
                Regex("""var\s+_x\s*=\s*\[(.*?)\]\s*;""", RegexOption.DOT_MATCHES_ALL).find(js)
            val xList = mutableListOf<String>()
            if (xMatch != null) {
                val body = xMatch.groupValues[1]
                val items = Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
                xList.addAll(items)
            }

            val pMatches = Regex(
                """var\s+(_p\d+)\s*=\s*\[\s*(.*?)\s*\]\s*;""",
                RegexOption.DOT_MATCHES_ALL
            ).findAll(js)
            val pMap = mutableMapOf<String, List<String>>()
            for (m in pMatches) {
                val key = m.groupValues[1]
                val body = m.groupValues[2]
                val items = Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
                pMap[key] = items
            }
            return Triple(mVal, xList, pMap)
        }

        fun processPxChunk(hex: String?, secret: ByteArray): String {
            val data = hexToByteArray(hex)
            if (data.isEmpty()) return ""
            val xored = xorWithKey(data, secret)
            val s = bytesToStringSafe(xored)
            return safeTrim(s)
        }

        fun decryptPx9All(
            mrBase64: String?,
            xList: List<String>,
            pDict: Map<String, List<String>>
        ): List<String> {
            if (mrBase64.isNullOrBlank()) return emptyList()
            val secret = try {
                android.util.Base64.decode(mrBase64, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                ByteArray(0)
            }
            val results = mutableListOf<String>()
            val count = maxOf(xList.size, pDict.size)
            for (i in 0 until count) {
                val key = "_p$i"
                val chunks = pDict[key] ?: continue

                val seq: IntArray? = if (i < xList.size) {
                    try {
                        val seqHex = xList[i]
                        val seqDecoded = processPxChunk(seqHex, secret)
                        val arr = JSONArray(seqDecoded)
                        IntArray(arr.length()) { idx -> arr.getInt(idx) }
                    } catch (e: Exception) {
                        null
                    }
                } else null

                val decrypted = chunks.map { ch -> processPxChunk(ch, secret) }

                val final = if (seq != null && seq.size == decrypted.size) {
                    val arr = Array(decrypted.size) { "" }
                    for (j in decrypted.indices) {
                        val pos = seq[j]
                        if (pos in arr.indices) arr[pos] = decrypted[j] else {
                            val idxFallback = arr.indexOfFirst { it.isEmpty() }
                            if (idxFallback >= 0) arr[idxFallback] = decrypted[j] else { /* skip */
                            }
                        }
                    }
                    arr.joinToString("")
                } else {
                    decrypted.joinToString("")
                }

                results.add(safeTrim(final))
            }
            return results
        }

        fun findServerElements(html: String): List<Pair<String, String>> {
            val items = mutableListOf<Pair<String, String>>()
            val anchorRegex = Regex(
                """(<a[^>]+class=[\"'][^\"']*server-link[^\"']*[\"'][^>]*>.*?</a>)""",
                RegexOption.DOT_MATCHES_ALL
            )
            for (m in anchorRegex.findAll(html)) {
                val tag = m.groupValues[1]
                val sid =
                    Regex("""data-server-id\s*=\s*[\"']([^\"']+)[\"']""").find(tag)?.groupValues?.get(
                        1
                    )
                val label = Regex(
                    """<span[^>]+class=[\"'][^\"']*ser[^\"']*[\"'][^>]*>(.*?)</span>""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(tag)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
                if (sid != null) items.add(sid to (label ?: "server-$sid"))
            }
            return items
        }

        return try {
            var html = fetchUrl(data)
            val m1 = Regex("""_m1\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
            val m2 = Regex("""_m2\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
            val m3 = Regex("""_m3\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
            val m4 = Regex("""_m4\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""

            val frameworkHash =
                if (m1.isNotEmpty() && m2.isNotEmpty() && m3.isNotEmpty() && m4.isNotEmpty()) {
                    m1 + m2 + m3 + m4
                } else {
                    "9933bd27-92ea-4ee9-807d-e612029d6318" // القيمة الافتراضية الاحتياطية
                }
            var zT: String? =
                Regex("""var\s+_zT\s*=\s*\"([^\"]+)\"""").find(html)?.groupValues?.get(1)
            var zV: String? =
                Regex("""var\s+_zV\s*=\s*\"([^\"]+)\"""").find(html)?.groupValues?.get(1)

            if (zT.isNullOrBlank() || zV.isNullOrBlank()) {
                val inlineScripts =
                    Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(
                        html
                    )
                        .map { it.groupValues[1] }.toList()
                for (s in inlineScripts) {
                    if (zT.isNullOrBlank()) zT =
                        Regex("""var\s+_zT\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
                    if (zV.isNullOrBlank()) zV =
                        Regex("""var\s+_zV\s*=\s*\"([^\"]+)\"""").find(s)?.groupValues?.get(1)
                    if (!zT.isNullOrBlank() && !zV.isNullOrBlank()) break
                }
            }

            if (zT.isNullOrBlank() || zV.isNullOrBlank()) {
                val scriptSrcs = Regex(
                    """<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>""",
                    RegexOption.IGNORE_CASE
                ).findAll(html).map { it.groupValues[1] }.toList()
                for (src in scriptSrcs) {
                    val srcUrl = if (src.startsWith("http")) src else {
                        try {
                            java.net.URL(java.net.URL(data), src).toString()
                        } catch (e: Exception) {
                            src
                        }
                    }
                    val jsText = fetchUrl(srcUrl)
                    if (zT.isNullOrBlank()) zT =
                        Regex("""var\s+_zT\s*=\s*\"([^\"]+)\"""").find(jsText)?.groupValues?.get(1)
                    if (zV.isNullOrBlank()) zV =
                        Regex("""var\s+_zV\s*=\s*\"([^\"]+)\"""").find(jsText)?.groupValues?.get(1)
                    if (!zT.isNullOrBlank() && !zV.isNullOrBlank()) break
                }
            }

            val resourceRegistryObj: Any? = try {
                val dec = base64DecodeBytes(zT).let { bytesToStringSafe(it) }
                try {
                    JSONObject(dec)
                } catch (e: Exception) {
                    try {
                        JSONArray(dec)
                    } catch (e2: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }

            val configRegistryObj: Any? = try {
                val dec = base64DecodeBytes(zV).let { bytesToStringSafe(it) }
                try {
                    JSONObject(dec)
                } catch (e: Exception) {
                    try {
                        JSONArray(dec)
                    } catch (e2: Exception) {
                        null
                    }
                }
            } catch (e: Exception) {
                null
            }

            val servers = findServerElements(html)
            val PARALLELISM = 6
            val semaphore = Semaphore(PARALLELISM)

            fun lookupRegistry(reg: Any?, sid: String): Any? {
                if (reg == null) return null
                try {
                    when (reg) {
                        is JSONObject -> if (reg.has(sid)) return reg.get(sid) else {
                            val idx = sid.toIntOrNull()
                            if (idx != null && reg.has(idx.toString())) return reg.get(idx.toString())
                        }

                        is JSONArray -> {
                            val idx = sid.toIntOrNull()
                            if (idx != null && idx >= 0 && idx < reg.length()) return reg.get(idx)
                        }

                        is Map<*, *> -> return reg[sid] ?: reg[sid.toIntOrNull()]
                    }
                } catch (e: Exception) {
                }
                return null
            }
            val megaLinksToEmit = java.util.Collections.synchronizedList(mutableListOf<ExtractorLink>())

            supervisorScope {
                val tasks = servers.map { (sid, label) ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                val resourceRaw = lookupRegistry(resourceRegistryObj, sid)
                                val configRaw = lookupRegistry(configRegistryObj, sid)
                                val paramOffset = getParamOffsetFromConfig(configRaw)
                                val link = decodeX18cResource(resourceRaw, paramOffset)

                                val finalLink = if (link.contains("yonaplay.net/embed.php")) {
                                    if (link.contains("apiKey=")) link else "$link&apiKey=$frameworkHash"
                                } else {
                                    link
                                }

                                if (finalLink.isNotBlank()) {
                                    if (finalLink.contains("yonaplay.net", ignoreCase = true)) {
                                        try {
                                            decodeYonaplayAndLoad(
                                                finalLink,
                                                data,
                                                megaLinksToEmit,
                                                subtitleCallback,
                                                callback
                                            )
                                        } catch (e: Exception) {
                                        }
                                    } else {
                                        try {
                                            when {
                                                finalLink.contains(
                                                    "videa.hu",
                                                    ignoreCase = true
                                                ) -> {
                                                    launch(Dispatchers.IO) {
                                                        try {
                                                            val vExtractor = VideaExtractor()
                                                            vExtractor.getUrl(
                                                                finalLink,
                                                                null,
                                                                subtitleCallback,
                                                                callback
                                                            )
                                                        } catch (e: Exception) {
                                                        }
                                                    }
                                                    try {
                                                        loadExtractor(
                                                            finalLink,
                                                            subtitleCallback,
                                                            callback
                                                        )
                                                    } catch (e: Exception) {
                                                    }
                                                }

                                                finalLink.contains(
                                                    "my.mail.ru",
                                                    ignoreCase = true
                                                ) || finalLink.contains(
                                                    "/video/embed/",
                                                    ignoreCase = true
                                                ) -> {
                                                    launch(Dispatchers.IO) {
                                                        try {
                                                            val mailExtractor = MailruExtractor()
                                                            mailExtractor.getUrl(
                                                                finalLink,
                                                                null,
                                                                subtitleCallback,
                                                                callback
                                                            )
                                                        } catch (e: Exception) {
                                                        }
                                                    }
                                                    try {
                                                        loadExtractor(
                                                            finalLink,
                                                            subtitleCallback,
                                                            callback
                                                        )
                                                    } catch (e: Exception) {
                                                    }
                                                }

                                                else -> {
                                                    try {
                                                        loadExtractor(
                                                            finalLink,
                                                            "https://witanime.red/",
                                                            subtitleCallback,
                                                            callback
                                                        )
                                                    } catch (e: Exception) {
                                                    }
                                                }
                                            }
                                        } catch (e: Exception) {
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                tasks.awaitAll()
            }

            var px_mr: String? = null
            var px_x: List<String> = emptyList()
            val px_p = mutableMapOf<String, List<String>>()

            val inlineScripts =
                Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
                    .map { it.groupValues[1] }
            for (s in inlineScripts) {
                if ("_m" in s && "_p0" in s) {
                    val (mVal, xList, pMap) = parsePx9FromScript(s)
                    px_mr = mVal ?: px_mr
                    if (xList.isNotEmpty()) px_x = xList
                    px_p.putAll(pMap)
                    if (!px_mr.isNullOrBlank() && px_p.isNotEmpty()) break
                }
            }

            if (px_p.isEmpty() || px_mr.isNullOrBlank()) {
                val scriptSrcs = Regex(
                    """<script[^>]+src=[\"']([^\"']+)[\"'][^>]*>""",
                    RegexOption.IGNORE_CASE
                ).findAll(html).map { it.groupValues[1] }.toList()
                for (src in scriptSrcs) {
                    val srcUrl = if (src.startsWith("http")) src else try {
                        java.net.URL(java.net.URL(data), src).toString()
                    } catch (e: Exception) {
                        src
                    }

                    val js = fetchUrl(srcUrl)
                    if (js.isBlank()) continue
                    if (px_p.isEmpty() || px_mr.isNullOrBlank()) {
                        val (mVal2, xList2, pMap2) = parsePx9FromScript(js)
                        if (mVal2 != null && px_mr.isNullOrBlank()) px_mr = mVal2
                        if (xList2.isNotEmpty() && px_x.isEmpty()) px_x = xList2
                        if (pMap2.isNotEmpty()) px_p.putAll(pMap2)
                        if (!px_mr.isNullOrBlank() && px_p.isNotEmpty()) break
                    }
                }
            }

            if (px_p.isEmpty()) {
                val (mVal3, xList3, pMap3) = parsePx9FromScript(html)
                if (mVal3 != null) px_mr = mVal3
                if (xList3.isNotEmpty()) px_x = xList3
                if (pMap3.isNotEmpty()) px_p.putAll(pMap3)
            }
            val downloadLinks = decryptPx9All(px_mr, px_x, px_p)

            supervisorScope {
                val dlTasks = downloadLinks.mapIndexed { idx, dl ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                if (dl.isNotBlank()) {
                                    val httpIndex = dl.indexOf("http")
                                    val cleaned =
                                        if (httpIndex >= 0) dl.substring(httpIndex) else dl
                                    val final = safeTrim(cleaned)

                                    if (final.startsWith("http")) {
                                        try {
                                            loadExtractor(final, data, subtitleCallback, callback)
                                        } catch (e: Exception) {
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                            }
                        }
                    }
                }
                dlTasks.awaitAll()
            }
            for (link in megaLinksToEmit) {
                callback(link)
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun decodeYonaplayAndLoad(
        yonaplayUrl: String,
        refererUrl: String,
        megaList: MutableList<ExtractorLink>, // مصفوفة روابط Mega المؤجلة
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val TAG = "YonaplayExtractor"

        try {
            val baseReferer = try {
                val uri = java.net.URI(refererUrl)
                "${uri.scheme}://${uri.host}/"
            } catch (e: Exception) {
                "$mainUrl/"
            }

            val html = app.get(
                yonaplayUrl,
                referer = baseReferer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept-Language" to "ar,en-US;q=0.9,en;q=0.8",
                    "Accept" to "*/*"
                )
            ).text

            val regex = Regex(
                """go_to_player\('([^']+)'\).*?<span>([^<]+)</span>""",
                RegexOption.DOT_MATCHES_ALL
            )
            val matches = regex.findAll(html)

            if (matches.none()) {
                return
            }

            for (match in matches) {
                val encoded = match.groupValues[1]
                val serverName = match.groupValues[2].trim()

                var fixed = encoded.replace(Regex("[^A-Za-z0-9+/=]"), "")
                val padding = fixed.length % 4
                if (padding != 0) fixed += "=".repeat(4 - padding)

                try {
                    val decodedUrl = String(
                        android.util.Base64.decode(
                            fixed,
                            android.util.Base64.DEFAULT
                        )
                    ).trim()
                    if (decodedUrl.contains("drive.google.com/file/d/")) {
                        val fileIdMatch = Regex("""/file/d/([0-9A-Za-z_-]{10,})""").find(decodedUrl)
                        val fileId = fileIdMatch?.groupValues?.get(1)
                        if (fileId != null) {
                            val directUrl =
                                "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"

                            callback(
                                newExtractorLink(
                                    name = "$serverName (Direct)",
                                    source = "Yonaplay",
                                    url = directUrl,
                                ) {
                                    referer = "https://drive.google.com/"
                                    quality = Qualities.Unknown.value
                                    type = ExtractorLinkType.VIDEO
                                }
                            )
                            continue
                        }
                    }
                    if (decodedUrl.contains("dotplay.net")) {
                        try {
                            val code = decodedUrl.trimEnd('/').substringAfterLast("/")
                            val apiUrl = "https://dotplay.net/api.php?code=$code"

                            val apiResponse = app.get(
                                apiUrl,
                                headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                    "Accept" to "application/json",
                                    "Referer" to decodedUrl,
                                )
                            ).text

                            val json = org.json.JSONObject(apiResponse)
                            if (json.optBoolean("success", false)) {
                                val encodedVideo = json.optString("video_url", "")
                                if (encodedVideo.isNotEmpty()) {
                                    val cleanedVideoB64 =
                                        encodedVideo.replace(Regex("[^A-Za-z0-9+/=]"), "")
                                    val decodedMp4 = String(
                                        android.util.Base64.decode(
                                            cleanedVideoB64,
                                            android.util.Base64.DEFAULT
                                        )
                                    )

                                    loadExtractor(decodedMp4, subtitleCallback, callback)
                                    continue
                                }
                            }
                        } catch (e: Exception) {
                            logError(e)
                        }
                    }

                    Log.d("this", "$decodedUrl")
                    if (decodedUrl.contains("mega.nz")) {
                        val dummyUrl = "https://raw.githubusercontent.com/Anarios/Cloudstream/master/app/src/main/res/raw/blank.mp4#mega-webview://$decodedUrl"

                        megaList.add(
                            newExtractorLink(
                                name = "$serverName (WebView)",
                                source = "Yonaplay",
                                url = dummyUrl,
                            ) {
                                referer = decodedUrl
                                quality = 10 // جودة 10 تضمن البقاء في نهاية قائمة السيرفرات دائماً
                                type = ExtractorLinkType.VIDEO
                            }
                        )
                        continue
                    }
                    loadExtractor(decodedUrl, subtitleCallback, callback)

                } catch (e: Exception) {
                    logError(e)
                }
            }
            for (link in megaList) {
                callback(link)
            }

        } catch (e: Exception) {
            logError(e)
        }
    }
}