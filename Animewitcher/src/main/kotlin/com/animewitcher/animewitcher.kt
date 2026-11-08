package com.animewitcher

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.PathParser
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

@CloudstreamPlugin
class animewitcherPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeWitcherProvider())

        openSettings = { activityContext ->
            (activityContext as? AppCompatActivity)?.let { activity ->
                Settings().show(activity.supportFragmentManager, "AnimewitcherSettings")
            }
        }
    }
}

class Settings : BottomSheetDialogFragment() {

    private fun dp(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
    private class PathDrawable(
        pathData: String,
        private val iconColor: Int,
        private val viewportWidth: Float = 24f,
        private val viewportHeight: Float = 24f
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = iconColor
            style = Paint.Style.FILL
        }
        
        private val path: Path = try {
            PathParser.createPathFromPathData(pathData)
        } catch (e: Exception) {
            Path() // في حال حدوث أي خطأ لا ينهار التطبيق
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val scaleX = b.width().toFloat() / viewportWidth
            val scaleY = b.height().toFloat() / viewportHeight
            val count = canvas.save()
            canvas.translate(b.left.toFloat(), b.top.toFloat())
            canvas.scale(scaleX, scaleY)
            canvas.drawPath(path, paint)
            canvas.restoreToCount(count)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            sheet.background = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = requireContext()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(28))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#181926"))
                cornerRadii = floatArrayOf(
                    dp(24).toFloat(), dp(24).toFloat(),
                    dp(24).toFloat(), dp(24).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
        }

        val handle = View(ctx).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#45475A"))
                cornerRadius = dp(3).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(20)
            }
        }
        root.addView(handle)

        val title = TextView(ctx).apply {
            text = "مجتمع ودعم الإضافة"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CAD3F5"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(ctx).apply {
            text = "تابع التحديثات وانضم لمجتمع النقاش والمطورين"
            textSize = 12.5f
            setTextColor(Color.parseColor("#8087A2"))
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(22))
        }
        root.addView(subtitle)

        fun createPlatformCard(
            svgPath: String,
            iconTintColor: String,
            badgeBackgroundColor: String,
            titleText: String,
            descText: String,
            targetUrl: String
        ): View {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))

                val shape = GradientDrawable().apply {
                    setColor(Color.parseColor("#24273A"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#363A4F"))
                }
                background = RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#3B4252")),
                    shape,
                    shape
                )

                isClickable = true
                isFocusable = true
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                    } catch (e: Exception) { }
                }

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
            }

            val iconBadge = ImageView(ctx).apply {
                setImageDrawable(PathDrawable(svgPath, Color.parseColor(iconTintColor)))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(badgeBackgroundColor))
                    cornerRadius = dp(12).toFloat()
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                    marginEnd = dp(14)
                }
            }
            card.addView(iconBadge)

            val textContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val cardTitle = TextView(ctx).apply {
                text = titleText
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#CAD3F5"))
            }
            textContainer.addView(cardTitle)

            val cardDesc = TextView(ctx).apply {
                text = descText
                textSize = 12f
                setTextColor(Color.parseColor("#A5ADCB"))
                setPadding(0, dp(2), 0, 0)
            }
            textContainer.addView(cardDesc)

            card.addView(textContainer)

            val arrow = TextView(ctx).apply {
                text = "↗"
                textSize = 17f
                setTextColor(Color.parseColor("#6E738D"))
                setTypeface(null, Typeface.BOLD)
            }
            card.addView(arrow)

            return card
        }
        val telegramSvg = "M 9.78 18.65 L 10.06 14.42 L 17.74 7.5 C 18.08 7.19 17.67 7.04 17.22 7.31 L 7.74 13.3 L 3.64 12 C 2.76 11.75 2.75 11.14 3.84 10.7 L 19.81 4.54 C 20.54 4.21 21.24 4.72 20.96 5.84 L 18.24 18.65 C 18.05 19.56 17.5 19.78 16.74 19.36 L 12.6 16.3 L 10.61 18.23 C 10.38 18.46 10.19 18.65 9.78 18.65 Z"
        
        val discordSvg = "M 19.27 5.33 C 17.94 4.71 16.5 4.26 15.0 4.0 C 14.9 4.0 14.85 4.1 14.8 4.2 C 14.6 4.55 14.4 5.0 14.25 5.3 C 12.75 5.05 11.25 5.05 9.75 5.3 C 9.6 5.0 9.4 4.55 9.2 4.2 C 9.15 4.1 9.1 4.0 9.0 4.0 C 7.5 4.26 6.06 4.71 4.73 5.33 C 2.0 9.4 1.26 13.37 1.63 17.29 C 3.43 18.61 5.16 19.41 6.87 19.94 C 7.27 19.39 7.63 18.81 7.94 18.2 C 7.37 17.98 6.83 17.72 6.3 17.42 C 6.41 17.34 6.52 17.25 6.63 17.17 C 10.07 18.74 13.78 18.74 17.18 17.17 C 17.29 17.26 17.4 17.34 17.51 17.42 C 16.99 17.73 16.44 17.98 15.86 18.2 C 16.18 18.81 16.54 19.39 16.94 19.94 C 18.66 19.41 20.39 18.61 22.19 17.29 C 22.63 12.76 21.46 8.83 19.27 5.33 Z M 8.52 14.91 C 7.49 14.91 6.63 13.96 6.63 12.79 C 6.63 11.62 7.47 10.67 8.52 10.67 C 9.58 10.67 10.42 11.63 10.41 12.79 C 10.41 13.96 9.57 14.91 8.52 14.91 Z M 15.49 14.91 C 14.46 14.91 13.6 13.96 13.6 12.79 C 13.6 11.62 14.44 10.67 15.49 10.67 C 16.55 10.67 17.39 11.63 17.38 12.79 C 17.38 13.96 16.55 14.91 15.49 14.91 Z"

        val githubSvg = "M 12.0 2.0 C 6.48 2.0 2.0 6.48 2.0 12.0 C 2.0 16.42 4.87 20.17 8.84 21.5 C 9.34 21.58 9.5 21.27 9.5 21.0 C 9.5 20.76 9.5 20.13 9.5 19.3 C 6.72 19.9 6.13 17.96 6.13 17.96 C 5.68 16.8 5.02 16.49 5.02 16.49 C 4.11 15.87 5.09 15.88 5.09 15.88 C 6.1 15.95 6.63 16.91 6.63 16.91 C 7.52 18.44 8.97 18.0 9.54 17.74 C 9.63 17.09 9.89 16.65 10.18 16.4 C 7.96 16.15 5.62 15.29 5.62 11.45 C 5.62 10.36 6.01 9.47 6.65 8.77 C 6.55 8.52 6.21 7.5 6.75 6.12 C 6.75 6.12 7.59 5.85 9.5 7.15 C 10.3 6.93 11.15 6.82 12.0 6.81 C 12.85 6.82 13.7 6.93 14.5 7.15 C 16.41 5.85 17.25 6.12 17.25 6.12 C 17.79 7.5 17.45 8.52 17.35 8.77 C 17.99 9.47 18.38 10.36 18.38 11.45 C 18.38 15.3 16.03 16.14 13.8 16.39 C 14.16 16.7 14.48 17.32 14.48 18.25 C 14.48 19.59 14.47 20.67 14.47 21.0 C 14.47 21.27 14.65 21.59 15.16 21.5 C 19.13 20.17 22.0 16.42 22.0 12.0 C 22.0 6.48 17.52 2.0 12.0 2.0 Z"

        root.addView(
            createPlatformCard(
                svgPath = telegramSvg,
                iconTintColor = "#FFFFFF",
                badgeBackgroundColor = "#229ED9",
                titleText = "قناة التليجرام",
                descText = "أحدث الإصدارات، الروابط والأخبار",
                targetUrl = "https://t.me/cs3iq"
            )
        )

        root.addView(
            createPlatformCard(
                svgPath = discordSvg,
                iconTintColor = "#FFFFFF",
                badgeBackgroundColor = "#5865F2",
                titleText = "سيرفر الديسكورد",
                descText = "نقاشات المجتمع، الدعم الفني والاقتراحات",
                targetUrl = "https://discord.gg/zfaXdKpbDH"
            )
        )

        root.addView(
            createPlatformCard(
                svgPath = githubSvg,
                iconTintColor = "#CAD3F5",
                badgeBackgroundColor = "#313244",
                titleText = "مشروع GitHub",
                descText = "المستودع المفتوح، الإبلاغ عن مشاكل والمساهمة",
                targetUrl = "https://github.com/Abodabodd/re-3arabi"
            )
        )

        return root
    }
}
class AnimeWitcherProvider : MainAPI() {
    override var mainUrl = "https://animewitcher.com"
    override var name = "AnimeWitcher"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "ar"
    override val hasMainPage = true
    @Volatile
    private var algoliaAppId = "TVNESH8OZY"
    @Volatile
    private var algoliaApiKey = "b56c01ef52540ef334bcdbaa00ded9e4"
    @Volatile
    private var buAuthKey = "6f1ec205-bbe8-45ce-97eb-1bc25a94bedf"
    private val firebaseApiKey = "AIzaSyAcbWRwfFNnCpoydDXlEALWnM_TYVcJOMU"

    private fun getAlgoliaHeaders(): Map<String, String> {
        return mapOf(
            "X-Algolia-Application-Id" to algoliaAppId,
            "X-Algolia-API-Key" to algoliaApiKey,
            "User-Agent" to "Algolia for Android (3.27.0); Android (16)",
            "Content-Type" to "application/json; charset=UTF-8",
        )
    }

    private val FIREBASE_PROJECT_ID = "animewitcher-1c66d"
    private val serverWordsCache = java.util.concurrent.ConcurrentHashMap<String, ServerWords>()
    data class EpisodeInfo(
        val id: String,
        val name: String?,
        val number: Int,
        val imageUrl: String? = null
    )
    data class ServerModel(
        val name: String?,
        val link: String?,
        val quality: String?,
        val originalLink: String?,
        val openBrowser: Boolean
    )
    data class ServerWords(
        val name: String,
        val word1: String?,
        val word2: String?,
        val word3: String?,
        val word4: String?
    )

    private fun logd(msg: String) {
        println("[AnimeWitcherLog] $msg")
    }

    private fun algoliaUrl(index: String) = "https://${algoliaAppId}-dsn.algolia.net/1/indexes/$index/query"

    private fun getQualityAsInt(quality: String?): Int {
        return quality?.filter { it.isDigit() }?.toIntOrNull() ?: 0
    }

    private suspend fun postAlgoliaQuery(index: String, body: okhttp3.RequestBody): String {
        val startTime = System.currentTimeMillis()
        return try {
            val response = app.post(algoliaUrl(index), requestBody = body, headers = getAlgoliaHeaders())
            if (response.code != 200) throw Exception("رمز الاستجابة ${response.code}")
            val duration = System.currentTimeMillis() - startTime
            logd("⚡ [Algolia POST Network] Succeeded in $duration ms.")
            response.text
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("⚠️ [Algolia POST Network] Failed after $duration ms: ${e.message}. Attempting keys refresh...")
            refreshAlgoliaKeys()

            val retryStartTime = System.currentTimeMillis()
            val response = app.post(algoliaUrl(index), requestBody = body, headers = getAlgoliaHeaders())
            if (response.code != 200) throw Exception("رمز الاستجابة بعد التجديد ${response.code}")
            logd("⚡ [Algolia POST Network] Retry Succeeded in ${System.currentTimeMillis() - retryStartTime} ms.")
            response.text
        }
    }

    private suspend fun getAlgoliaObjectByPost(index: String, objectId: String): JSONObject? {
        val startTime = System.currentTimeMillis()
        logd("⏱️ [Algolia Object] Starting POST query for ObjectID: '$objectId'")

        val escapedId = objectId.replace("\"", "\\\"")
        val payload = JSONObject().put("params", "filters=objectID:\"$escapedId\"")
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        return try {
            val res = postAlgoliaQuery(index, body)
            val hits = JSONObject(res).optJSONArray("hits")
            val duration = System.currentTimeMillis() - startTime
            if (hits != null && hits.length() > 0) {
                logd("✅ [Algolia Object] Found matching object. Time: $duration ms.")
                hits.getJSONObject(0)
            } else {
                logd("⚠️ [Algolia Object] Query returned empty hits. Time: $duration ms.")
                null
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("❌ [Algolia Object] Query failed after $duration ms. Error: ${e.message}")
            null
        }
    }

    private suspend fun refreshAlgoliaKeys() {
        try {
            val url = firestoreDocUrl("Settings/constants")
            val res = app.get(url).text
            val json = JSONObject(res)
            val fields = json.optJSONObject("fields") ?: return

            if (fields.has("search_settings")) {
                val searchSettings = fields.getJSONObject("search_settings")
                    .optJSONObject("mapValue")
                    ?.optJSONObject("fields")

                if (searchSettings != null) {
                    val newAppId = searchSettings.optJSONObject("app_id_v3")?.optString("stringValue")
                        ?: searchSettings.optJSONObject("app_id")?.optString("stringValue")
                        ?: searchSettings.optJSONObject("app_id_v2")?.optString("stringValue")
                    val newApiKey = searchSettings.optJSONObject("api_key")?.optString("stringValue")

                    if (!newAppId.isNullOrEmpty() && !newApiKey.isNullOrEmpty()) {
                        algoliaAppId = newAppId
                        algoliaApiKey = newApiKey
                        logd("✅ [Firestore Keys] Algolia AppId Updated: $algoliaAppId")
                    }
                }
            }

            if (fields.has("BU")) {
                val buSettings = fields.getJSONObject("BU")
                    .optJSONObject("mapValue")
                    ?.optJSONObject("fields")
                if (buSettings != null) {
                    val newAuthKey = buSettings.optJSONObject("auth_key")?.optString("stringValue")
                    if (!newAuthKey.isNullOrEmpty()) {
                        buAuthKey = newAuthKey
                        logd("✅ [Firestore Keys] BU Auth Key Updated: $buAuthKey")
                    }
                }
            }
        } catch (e: Exception) {
            logd("❌ [Firestore Keys] Failed to refresh keys. Error: ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = withContext(Dispatchers.IO) {
        val indexName = "recent"
        val pageParam = (page - 1).coerceAtLeast(0)

        val attributes = URLEncoder.encode(
            "[\"name\",\"date\",\"doc_ref\",\"episode_id\",\"anime_id\",\"episode_name\",\"title\",\"poster_uri\",\"poster_url_aniList\",\"type\",\"tags\",\"thumb_uri\",\"comments_closed\",\"filler\",\"note\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=30&page=$pageParam&query="

        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = postAlgoliaQuery(indexName, body)
        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("anime_id", obj.optString("objectID"))
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            list.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext newHomePageResponse("أحدث الحلقات", list)
    }

    override suspend fun search(query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        val indexName = "series"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val attributes = URLEncoder.encode(
            "[\"objectID\",\"name\",\"poster_uri\",\"type\",\"details\",\"tags\",\"story\",\"english_title\",\"poster\",\"rating\",\"dubbed\",\"path\"]",
            "utf-8"
        )
        val params = "attributesToRetrieve=$attributes&hitsPerPage=50&page=0&query=$encodedQuery"
        val payload = JSONObject().put("params", params)
        val body = payload.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val res = postAlgoliaQuery(indexName, body)
        val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
        val hits = json.optJSONArray("hits") ?: JSONArray()

        val results = ArrayList<SearchResponse>()
        for (i in 0 until hits.length()) {
            val obj = hits.getJSONObject(i)
            val title = obj.optString("name")
            if (title.isNullOrEmpty()) continue
            val poster = obj.optString("poster_uri")
            val animeId = obj.optString("objectID")
            val fullData = URLEncoder.encode(obj.toString(), "utf-8")
            val url = "$mainUrl/watch/${URLEncoder.encode(animeId, "utf-8")}?data=$fullData"
            results.add(newAnimeSearchResponse(title, url, TvType.Anime) { this.posterUrl = poster })
        }
        return@withContext results
    }

    override suspend fun load(url: String): LoadResponse = withContext(Dispatchers.IO) {
        val totalStartTime = System.currentTimeMillis()
        logd("====== [LOAD] PROCESS STARTED ======")
        val animeId = URLDecoder.decode(url.substringAfterLast('/').substringBefore('?'), "utf-8")
        logd("[LOAD] Extracted animeId: '$animeId'")
        val animeJson = getAlgoliaObjectByPost("series", animeId) ?: try {
            logd("⚠️ Algolia query failed or returned empty. Falling back to URL data parameter...")
            val encodedData = url.substringAfter("?data=", "")
            if (encodedData.isNotEmpty()) {
                val decodeStartTime = System.currentTimeMillis()
                val parsed = JSONObject(URLDecoder.decode(encodedData, "utf-8"))
                logd("✅ [LOAD] Decoded local fallback data in ${System.currentTimeMillis() - decodeStartTime} ms.")
                parsed
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            logd("❌ [LOAD] Failed to parse fallback URL data: ${e.message}")
            JSONObject()
        }

        val docRef = animeJson.optString("doc_ref", animeJson.optString("path", "anime_list/$animeId"))
        val cleanPath = docRef.substringAfter("anime_list/").removePrefix("/")
        val episodes = fetchEpisodes(cleanPath)

        val poster = animeJson.optString("poster_uri", animeJson.optJSONObject("poster")?.optString("medium"))

        val epList = episodes.map { info ->
            val dataStr = "$cleanPath|${info.id}"
            newEpisode(data = dataStr) {
                name = info.name ?: "الحلقة ${info.number}"
                episode = info.number
                posterUrl = info.imageUrl ?: poster
            }
        }

        val title = animeJson.optString("name", animeId)
        val details = animeJson.optJSONObject("details") ?: JSONObject()
        val plot = animeJson.optString("story").ifEmpty {
            animeJson.optJSONObject("_highlightResult")?.optJSONObject("story")?.optString("value")?.replace(Regex("</?em>"), "")
        } ?: ""

        val year = details.optString("year").toIntOrNull()
        val status = if (details.optString("state") == "مكتمل") ShowStatus.Completed else ShowStatus.Ongoing
        val tagsArray = animeJson.optJSONArray("tags")
        val tags = if (tagsArray != null) (0 until tagsArray.length()).map { tagsArray.getString(it) } else emptyList()

        logd("====== [LOAD] PROCESS ENDED - Total Duration: ${System.currentTimeMillis() - totalStartTime} ms ======")

        return@withContext newTvSeriesLoadResponse(title, url, TvType.Anime, epList) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.showStatus = status
            this.tags = tags
        }
    }

    private suspend fun fetchEpisodes(animeId: String): List<EpisodeInfo> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8").replace("+", "%20")
        val docUrl = firestoreDocUrl("anime_list/$encodedAnimeId/episodes_summery/summery")
        val list = ArrayList<EpisodeInfo>()

        logd("⏱ [Firestore Episodes] Fetching starting for URL: $docUrl")

        try {
            val res = app.get(docUrl).text
            val json = JSONObject(res)
            val fields = json.optJSONObject("fields") ?: return@withContext emptyList()
            val episodesObj = fields.optJSONObject("episodes") ?: return@withContext emptyList()
            val arrayValue = episodesObj.optJSONObject("arrayValue") ?: return@withContext emptyList()
            val values = arrayValue.optJSONArray("values") ?: JSONArray()

            for (i in 0 until values.length()) {
                val valueItem = values.getJSONObject(i)
                val mapValue = valueItem.optJSONObject("mapValue") ?: continue
                val epFields = mapValue.optJSONObject("fields") ?: continue

                val docId = epFields.optJSONObject("doc_id")?.optString("stringValue") ?: continue
                val titleTranslated = epFields.optJSONObject("title_translated")
                    ?.optJSONObject("mapValue")
                    ?.optJSONObject("fields")

                val arName = titleTranslated?.optJSONObject("ar")?.optString("stringValue")
                val epName = arName ?: epFields.optJSONObject("name")?.optString("stringValue") ?: "الحلقة $docId"

                val numberValObj = epFields.optJSONObject("number")
                val numberStr = numberValObj?.optString("integerValue") ?: numberValObj?.optString("stringValue")
                val number = numberStr?.toIntOrNull() ?: (i + 1)

                val image = epFields.optJSONObject("thumb_uri")?.optString("stringValue")

                list.add(EpisodeInfo(docId, epName, number, image))
            }

            list.sortBy { it.number }
            val duration = System.currentTimeMillis() - startTime
            logd("✅ [Firestore Episodes] Succeeded in $duration ms. Found ${list.size} episodes.")
            return@withContext list
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            logd("❌ [Firestore Episodes] Failed after $duration ms. Error: ${e.message}")
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private fun firestoreDocUrl(path: String): String =
        "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/(default)/documents/$path?key=$firebaseApiKey"

    private suspend fun fetchServersForEpisode(animeId: String, episodeId: String): List<ServerModel> = withContext(Dispatchers.IO) {
        val encodedAnimeId = URLEncoder.encode(animeId, "utf-8").replace("+", "%20")
        val encodedEpisodeId = URLEncoder.encode(episodeId, "utf-8").replace("+", "%20")

        try {
            val docPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers2/all_servers"
            val res = app.get(firestoreDocUrl(docPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val fields = json.optJSONObject("fields")
            if (fields != null && fields.has("servers")) {
                val arr = fields.getJSONObject("servers").optJSONObject("arrayValue")?.optJSONArray("values") ?: JSONArray()
                val list = ArrayList<ServerModel>()
                for (i in 0 until arr.length()) {
                    val map = arr.getJSONObject(i).getJSONObject("mapValue").getJSONObject("fields")
                    val name = map.optJSONObject("name")?.optString("stringValue")
                    val quality = map.optJSONObject("quality")?.optString("stringValue")
                    val link = map.optJSONObject("link")?.optString("stringValue")
                    val orig = map.optJSONObject("original_link")?.optString("stringValue")
                    val openBrowser = map.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                    if (!name.isNullOrEmpty() && !link.isNullOrEmpty()) {
                        list.add(ServerModel(name, link, quality, orig, openBrowser))
                    }
                }
                if (list.isNotEmpty()) {
                    list.sortByDescending { getQualityAsInt(it.quality) }
                    return@withContext list
                }
            }
        } catch (e: Exception) {
            logd("fetchServersForEpisode: servers2 doc failed: ${e.message}")
        }

        try {
            val collPath = "anime_list/$encodedAnimeId/episodes/$encodedEpisodeId/servers"
            val res = app.get(firestoreDocUrl(collPath)).text
            val json = try { JSONObject(res) } catch (e: Exception) { JSONObject() }
            val docs = json.optJSONArray("documents") ?: JSONArray()
            val list = ArrayList<ServerModel>()
            for (i in 0 until docs.length()) {
                val fields = docs.getJSONObject(i).optJSONObject("fields") ?: JSONObject()
                val name = fields.optJSONObject("name")?.optString("stringValue")
                val quality = fields.optJSONObject("quality")?.optString("stringValue")
                val link = fields.optJSONObject("link")?.optString("stringValue")
                val orig = fields.optJSONObject("original_link")?.optString("stringValue")
                val openBrowser = fields.optJSONObject("open_browser")?.optBoolean("booleanValue") ?: false
                val visible = fields.optJSONObject("visible")?.optBoolean("booleanValue") ?: true
                if (!name.isNullOrEmpty() && !link.isNullOrEmpty() && visible) {
                    list.add(ServerModel(name, link, quality, orig, openBrowser))
                }
            }
            list.sortByDescending { getQualityAsInt(it.quality) }
            return@withContext list
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    private suspend fun getServerWords(serverName: String): ServerWords? = withContext(Dispatchers.IO) {
        if (serverWordsCache.containsKey(serverName)) return@withContext serverWordsCache[serverName]
        try {
            val path = "Settings/servers/servers/${URLEncoder.encode(serverName, "utf-8").replace("+", "%20")}"
            val res = app.get(firestoreDocUrl(path)).text
            val f = JSONObject(res).optJSONObject("fields") ?: JSONObject()
            val sw = ServerWords(
                name = serverName,
                word1 = f.optJSONObject("word1")?.optString("stringValue"),
                word2 = f.optJSONObject("word2")?.optString("stringValue"),
                word3 = f.optJSONObject("word3")?.optString("stringValue"),
                word4 = f.optJSONObject("word4")?.optString("stringValue")
            )
            serverWordsCache[serverName] = sw
            return@withContext sw
        } catch (e: Exception) {
            return@withContext null
        }
    }

    private suspend fun getFinalRedirectUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = true).url
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getFinalDownloadUrl(urlIn: String): String? = withContext(Dispatchers.IO) {
        return@withContext try {
            app.get(urlIn, allowRedirects = false).headers["Location"]
        } catch (e: Exception) {
            null
        }
    }
    private suspend fun resolveServerModel(server: ServerModel): String? = withContext(Dispatchers.IO) {
        val name = server.name ?: return@withContext null
        val link = server.link ?: return@withContext null
        val words = getServerWords(name)

        try {
            when (name.uppercase(Locale.getDefault())) {
                "MF", "MF2", "ST", "MG", "SF" -> {
                    return@withContext link
                }
                "KF" -> {
                    if (words?.word1 == null || words.word2 == null) return@withContext link
                    val res = try { app.get(link).text } catch (e: Exception) { "" }
                    if (res.isBlank()) return@withContext link
                    return@withContext "https://${res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")}"
                }
                "PD" -> {
                    val html = try { app.get(link).text } catch (e: Exception) { "" }
                    val og = Regex("""<meta property="og:video" content="([^"]+)""").find(html)?.groupValues?.get(1)
                    if (!og.isNullOrEmpty()) return@withContext og.replace("&amp;", "&")
                    val m = Regex("""href="(/u/[A-Za-z0-9_-]+)"[^>]*>\s*(?:Download|تحميل|download)""", RegexOption.IGNORE_CASE).find(html)
                    if (m != null) {
                        val rel = m.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val m2 = Regex("(/u/[A-Za-z0-9_-]+)").find(html)
                    if (m2 != null) {
                        val rel = m2.groupValues[1]
                        val full = try { URL(link).let { base -> URL(base, rel).toString() } } catch (_: Exception) { rel }
                        return@withContext full
                    }
                    val abs = Regex("""https?://pixeldrain\.com/u/[A-Za-z0-9_-]+""").find(html)?.value
                    if (!abs.isNullOrEmpty()) return@withContext abs
                    return@withContext link
                }
                "VT" -> {
                    if (words?.word1 == null || words.word2 == null || words.word3 == null || words.word4 == null) return@withContext link
                    val res1 = try { app.get(link).text } catch (e: Exception) { "" }
                    val part1 = res1.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("\">", "").trim()
                    val newLink = "https://vidtube.one$part1"
                    val res2 = try { app.get(newLink).text } catch (e: Exception) { "" }
                    return@withContext "https://${res2.substringAfter(words.word3!!).substringBefore(words.word4!!)}"
                }
                "AR" -> {
                    return@withContext getFinalRedirectUrl(link) ?: link
                }
                "WC" -> {
                    return@withContext getFinalDownloadUrl(link) ?: link
                }
                "BU" -> {
                    return@withContext link
                }
                "QI" -> {
                    return@withContext server.originalLink ?: link
                }
                else -> {
                    if (words?.word1 != null && words.word2 != null) {
                        val res = try { app.get(link).text } catch (e: Exception) { "" }
                        if (res.isNotBlank()) {
                            return@withContext res.substringAfter(words.word1!!).substringBefore(words.word2!!).replace("amp;", "")
                        }
                    }
                    return@withContext link
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext link
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        logd("====== LOADLINKS START (PARALLEL) ======")
        try {
            val parts = data.split('|')
            if (parts.size < 2) return@withContext false
            var rawAnimeId = parts[0].trim()
            val episodeId = parts[1].trim()

            if (rawAnimeId.startsWith("http", ignoreCase = true) || rawAnimeId.contains(mainUrl)) {
                try {
                    val pathPart = rawAnimeId.substringAfterLast('/').substringBefore('?')
                    rawAnimeId = URLDecoder.decode(pathPart, "utf-8").trim()
                } catch (e: Exception) {
                    logd("Failed to normalize animeId: ${e.message}")
                }
            }

            val animeId = rawAnimeId
            val servers = fetchServersForEpisode(animeId, episodeId)
            if (servers.isEmpty()) return@withContext false

            val sortedServers = servers.sortedWith(
                compareByDescending<ServerModel> { getQualityAsInt(it.quality) }
                    .thenBy {
                        when (it.name?.uppercase(Locale.getDefault())) {
                            "PD" -> 0
                            "PD EU TEST" -> 1
                            else -> 2
                        }
                    }
            )

            logd("⚡ Processing ${sortedServers.size} servers in parallel with coroutineScope...")
            coroutineScope {
                sortedServers.map { server ->
                    async {
                        try {
                            val finalUrl = resolveServerModel(server)
                            if (!finalUrl.isNullOrBlank() && !finalUrl.trim().startsWith("<")) {
                                val serverName = server.name ?: "Server"

                                when {
                                    serverName.equals("PD", ignoreCase = true) -> {
                                        try {
                                            loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                        } catch (e: Exception) {
                                            logd("Error invoking loadExtractor for PD: ${e.message}")
                                        }
                                    }
                                    serverName.uppercase(Locale.getDefault()) == "KF" -> {
                                        callback(
                                            newExtractorLink(
                                                source = name,
                                                name = serverName,
                                                url = finalUrl,
                                            ) {
                                                referer = "https://krakenfiles.com/"
                                                quality = getQualityFromName(server.quality)
                                            }
                                        )
                                    }
                                    else -> {
                                        try {
                                            val handled = loadExtractor(finalUrl, mainUrl, subtitleCallback, callback)
                                            if (!handled) {
                                                callback(
                                                    newExtractorLink(
                                                        source = name,
                                                        name = "$serverName ${server.quality ?: ""}".trim(),
                                                        url = finalUrl,
                                                    ) {
                                                        this.referer = mainUrl
                                                        this.quality = getQualityFromName(server.quality)
                                                    }
                                                )
                                            }
                                        } catch (e: Exception) {
                                            logd("Error resolving $serverName: ${e.message}")
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logd("Exception on server ${server.name}: ${e.message}")
                        }
                    }
                }.awaitAll()
            }

            logd("====== LOADLINKS END ======")
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }
}
