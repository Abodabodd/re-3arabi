package com.rate

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object RatingSettings {

    private const val PLUGINS_URL =
        "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/builds/plugins.json"

    private const val COUNTER_API =
        "https://counterapi.com/api"

    private const val PREFS =
        "extension_ratings_prefs"

    private val imageCache = ConcurrentHashMap<String, Bitmap>()
    private var allPluginsMasterList = mutableListOf<PluginRating>()

    fun show(fragmentManager: FragmentManager) {
        RatingListDialog().show(
            fragmentManager,
            "rating_list"
        )
    }

    private fun getRepository(pluginUrl: String): String {
        return pluginUrl
            .split("/")
            .drop(2)
            .take(3)
            .joinToString("-")
    }

    private fun transformUrl(url: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest("${url}#funny-salt".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun calculateScore(likes: Int, dislikes: Int): Double {
        val total = likes + dislikes
        if (total == 0) return 0.0

        var score = 1.0 + ((likes.toDouble() / total) * 4.0)

        if (total < 10) {
            score -= 1.0
        }

        return score.coerceIn(1.0, 5.0)
    }

    data class ThemeColor(
        val rootBg: String,
        val cardBg: String,
        val cardStroke: String,
        val titleColor: String
    )

    private val THEMES = listOf(
        ThemeColor("#040C1A", "#081326", "#0E3A6E", "#38BDF8"),
        ThemeColor("#120C03", "#1F1406", "#5C3A08", "#FBBF24"),
        ThemeColor("#0D051A", "#160A2E", "#4C1D95", "#C084FC")
    )

    class RatingListDialog : DialogFragment() {

        private var sortMode = 0
        private var isEnglish = false
        private var adapter: RatingAdapter? = null

        override fun onCreateDialog(
            savedInstanceState: Bundle?
        ): Dialog {

            val dialog = Dialog(requireContext())

            val root = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(10.dp(context), 14.dp(context), 10.dp(context), 8.dp(context))
                setBackgroundColor(Color.parseColor(THEMES[0].rootBg))
            }

            val header = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    54.dp(context)
                ).apply {
                    bottomMargin = 8.dp(context)
                }
            }

            val close = TextView(requireContext()).apply {
                text = "×"
                textSize = 34f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    38.dp(context),
                    38.dp(context)
                )
                addTouchScaleEffect { dismiss() }
            }

            val title = TextView(requireContext()).apply {
                text = if (isEnglish) "⭐ Ratings" else "⭐ التقييمات"
                textSize = 17f
                setTextColor(Color.parseColor(THEMES[0].titleColor))
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val headerButtons = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val langBtn = TextView(requireContext()).apply {
                text = if (isEnglish) "🌐 عربي" else "🌐 EN"
                textSize = 11f
                setTextColor(Color.parseColor("#E2E8F0"))
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#1E293B"),
                    Color.parseColor("#475569"),
                    1.dp(context),
                    11f
                )
                setPadding(8.dp(context), 6.dp(context), 8.dp(context), 6.dp(context))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 5.dp(context)
                }
            }

            val sortBtn = TextView(requireContext()).apply {
                text = if (isEnglish) "🔥 Most Liked" else "🔥 الأكثر إعجاباً"
                textSize = 11f
                setTextColor(Color.parseColor("#38BDF8"))
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#0C1C36"),
                    Color.parseColor("#0284C7"),
                    1.dp(context),
                    11f
                )
                setPadding(9.dp(context), 6.dp(context), 9.dp(context), 6.dp(context))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            fun updateSortButtonText() {
                when (sortMode) {
                    1 -> {
                        sortBtn.text = if (isEnglish) "⭐ Top Rated" else "⭐ الأعلى تقييماً"
                        sortBtn.setTextColor(Color.parseColor("#FBBF24"))
                        sortBtn.background = roundedStrokeBackground(
                            Color.parseColor("#261C08"),
                            Color.parseColor("#D97706"),
                            1.dp(context),
                            11f
                        )
                    }
                    2 -> {
                        sortBtn.text = if (isEnglish) "📋 Default" else "📋 الافتراضي"
                        sortBtn.setTextColor(Color.parseColor("#C084FC"))
                        sortBtn.background = roundedStrokeBackground(
                            Color.parseColor("#1E1438"),
                            Color.parseColor("#7C3AED"),
                            1.dp(context),
                            11f
                        )
                    }
                    else -> {
                        sortBtn.text = if (isEnglish) "🔥 Most Liked" else "🔥 الأكثر إعجاباً"
                        sortBtn.setTextColor(Color.parseColor("#38BDF8"))
                        sortBtn.background = roundedStrokeBackground(
                            Color.parseColor("#0C1C36"),
                            Color.parseColor("#0284C7"),
                            1.dp(context),
                            11f
                        )
                    }
                }
            }

            langBtn.addTouchScaleEffect {
                isEnglish = !isEnglish
                langBtn.text = if (isEnglish) "🌐 عربي" else "🌐 EN"
                title.text = if (isEnglish) "⭐ Ratings" else "⭐ التقييمات"
                updateSortButtonText()
                applySort()
            }

            sortBtn.addTouchScaleEffect {
                if (allPluginsMasterList.isEmpty()) return@addTouchScaleEffect
                sortMode = (sortMode + 1) % 3
                val currentTheme = THEMES[sortMode]

                root.setBackgroundColor(Color.parseColor(currentTheme.rootBg))
                dialog.window?.setBackgroundDrawable(
                    roundedBackground(Color.parseColor(currentTheme.rootBg), 26f)
                )
                title.setTextColor(Color.parseColor(currentTheme.titleColor))

                updateSortButtonText()
                applySort()
            }

            headerButtons.addView(langBtn)
            headerButtons.addView(sortBtn)

            header.addView(close)
            header.addView(title)
            header.addView(headerButtons)

            root.addView(header)

            val progress = ProgressBar(
                requireContext(),
                null,
                android.R.attr.progressBarStyleHorizontal
            ).apply {
                isIndeterminate = true
                visibility = View.VISIBLE
            }

            root.addView(
                progress,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    3.dp(context)
                )
            )

            val recycler = RecyclerView(requireContext()).apply {
                layoutManager = LinearLayoutManager(requireContext())
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(0, 6.dp(context), 0, 20.dp(context))
            }

            root.addView(
                recycler,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )

            dialog.setContentView(root)

            dialog.window?.setBackgroundDrawable(
                roundedBackground(Color.parseColor(THEMES[0].rootBg), 26f)
            )

            dialog.setOnShowListener {
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }

            adapter = RatingAdapter(mutableListOf(), 0, isEnglish) {
                applySort()
            }
            recycler.adapter = adapter

            loadRatings(progress)

            return dialog
        }

        private fun applySort() {
    if (allPluginsMasterList.isEmpty()) return

    val result = when (sortMode) {
        1 -> allPluginsMasterList.sortedWith(
            compareByDescending<PluginRating> { it.likes }
                .thenByDescending { it.likes - it.dislikes }
                .thenBy { it.dislikes }
        )
        2 -> allPluginsMasterList.sortedWith(
            compareByDescending<PluginRating> { calculateScore(it.likes, it.dislikes) }
                .thenByDescending { it.likes + it.dislikes }
                .thenByDescending { it.likes }
        )
        else -> allPluginsMasterList.toList() // <--- هذا السطر فقط هو ما يجعل الأولوية للترتيب الافتراضي
    }

    adapter?.updateData(result, sortMode, isEnglish)
}

        private fun parseTypesList(typesArray: JSONArray?): List<String> {
            if (typesArray == null || typesArray.length() == 0) return emptyList()
            val list = mutableListOf<String>()
            for (i in 0 until typesArray.length()) {
                list.add(typesArray.optString(i))
            }
            return list
        }

        private fun loadRatings(
            progress: ProgressBar
        ) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = app.get(PLUGINS_URL)
                    val array = JSONArray(response.text)
                    val rawList = mutableListOf<JSONObject>()

                    for (i in 0 until array.length()) {
                        rawList.add(array.getJSONObject(i))
                    }

                    val plugins = coroutineScope {
                        rawList.map { obj ->
                            async(Dispatchers.IO) {
                                val name = obj.optString("name")
                                val url = obj.optString("url")

                                if (name.isBlank() || url.isBlank()) return@async null

                                val icon = when {
                                    obj.optString("iconUrl").isNotBlank() -> obj.optString("iconUrl")
                                    obj.optString("icon").isNotBlank() -> obj.optString("icon")
                                    else -> ""
                                }

                                val rawLang = obj.optString("language", "ar")
                                val rawTags = parseTypesList(obj.optJSONArray("tvTypes"))

                                val likesDeferred = async { getOfficialLikes(url) }
                                val dislikesDeferred = async { getOfficialDislikes(url) }

                                PluginRating(
                                    name = name,
                                    url = url,
                                    icon = icon,
                                    rawLanguage = rawLang,
                                    rawTags = rawTags,
                                    likes = likesDeferred.await(),
                                    dislikes = dislikesDeferred.await()
                                )
                            }
                        }.awaitAll().filterNotNull()
                    }

                    allPluginsMasterList = plugins.toMutableList()

                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        applySort()
                    }

                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        progress.visibility = View.GONE
                        Toast.makeText(
                            requireContext(),
                            if (isEnglish) "Error loading ratings" else "حدث خطأ أثناء تحميل التقييمات",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }

        private suspend fun getOfficialLikes(pluginUrl: String): Int {
            return try {
                val repository = getRepository(pluginUrl)
                val key = transformUrl(pluginUrl)
                val url = "$COUNTER_API/cs-$repository/vote/$key?readOnly=true"
                val response = app.get(url)
                val json = JSONObject(response.text)
                json.optInt("value", json.optInt("count", 0))
            } catch (e: Exception) {
                0
            }
        }

        private suspend fun getOfficialDislikes(pluginUrl: String): Int {
            return try {
                val repository = getRepository(pluginUrl)
                val key = transformUrl(pluginUrl)
                val url = "$COUNTER_API/cs-$repository/dislike/$key?readOnly=true"
                val response = app.get(url)
                val json = JSONObject(response.text)
                json.optInt("value", json.optInt("count", 0))
            } catch (e: Exception) {
                0
            }
        }
    }

    data class PluginRating(
        val name: String,
        val url: String,
        val icon: String,
        val rawLanguage: String,
        val rawTags: List<String>,
        var likes: Int,
        var dislikes: Int
    )

    class RatingAdapter(
        private var items: List<PluginRating>,
        private var currentThemeIndex: Int,
        private var isEnglish: Boolean,
        private val onDataChanged: () -> Unit
    ) : RecyclerView.Adapter<RatingAdapter.ViewHolder>() {

        fun updateData(newItems: List<PluginRating>, themeIndex: Int, english: Boolean) {
            items = newItems
            currentThemeIndex = themeIndex
            isEnglish = english
            notifyDataSetChanged()
        }

        private fun formatLanguageBadge(lang: String): String {
            return when (lang.lowercase()) {
                "ar" -> if (isEnglish) "🇸🇦 Arabic" else "🇸🇦 عربي"
                "en" -> if (isEnglish) "🌐 English" else "🌐 EN"
                "iq" -> if (isEnglish) "🇮🇶 Iraqi" else "🇮🇶 عراقي"
                else -> lang.uppercase()
            }
        }

        private fun formatTag(tag: String): String {
            val arMap = mapOf(
                "TvSeries" to "مسلسلات", "Movie" to "أفلام", "Anime" to "أنمي",
                "AsianDrama" to "آسيوي", "Live" to "مباشر", "Drama" to "دراما",
                "Music" to "موسيقى", "Documentary" to "وثائقي", "Others" to "منوعة"
            )
            val enMap = mapOf(
                "TvSeries" to "Series", "Movie" to "Movies", "Anime" to "Anime",
                "AsianDrama" to "Asian", "Live" to "Live", "Drama" to "Drama",
                "Music" to "Music", "Documentary" to "Doc", "Others" to "Other"
            )
            return if (isEnglish) enMap[tag] ?: tag else arMap[tag] ?: tag
        }

        class ViewHolder(
            val card: FrameLayout,
            val icon: ImageView,
            val name: TextView,
            val tagsContainer: LinearLayout,
            val ratingText: TextView,
            val likeBtn: LinearLayout,
            val likeCount: TextView,
            val dislikeBtn: LinearLayout,
            val dislikeCount: TextView
        ) : RecyclerView.ViewHolder(card)

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
        ): ViewHolder {
            val ctx = parent.context
            val theme = THEMES[currentThemeIndex]

            val card = FrameLayout(ctx).apply {
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                background = roundedStrokeBackground(
                    Color.parseColor(theme.cardBg),
                    Color.parseColor(theme.cardStroke),
                    1.dp(ctx),
                    18f
                )

                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    94.dp(ctx)
                ).apply {
                    setMargins(0, 3.dp(ctx), 0, 3.dp(ctx))
                }
            }

            val icon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = roundedBackground(Color.parseColor("#151D33"), 15f)
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, 15.dp(ctx).toFloat())
                    }
                }
                clipToOutline = true

                layoutParams = FrameLayout.LayoutParams(
                    60.dp(ctx),
                    60.dp(ctx)
                ).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    leftMargin = 10.dp(ctx)
                }
            }

            val infoContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL

                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    leftMargin = 78.dp(ctx)
                    rightMargin = 78.dp(ctx)
                }
            }

            val name = TextView(ctx).apply {
                textSize = 14.5f
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setTypeface(typeface, Typeface.BOLD)
            }

            val tagsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                setPadding(0, 3.dp(ctx), 0, 3.dp(ctx))
            }

            val ratingText = TextView(ctx).apply {
                textSize = 11.5f
                setTextColor(Color.parseColor("#94A3B8"))
                gravity = Gravity.START
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            infoContainer.addView(name)
            infoContainer.addView(tagsContainer)
            infoContainer.addView(ratingText)

            val actionsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER

                layoutParams = FrameLayout.LayoutParams(
                    68.dp(ctx),
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    gravity = Gravity.END or Gravity.CENTER_VERTICAL
                    rightMargin = 8.dp(ctx)
                }
            }

            val likeBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#06241C"),
                    Color.parseColor("#10B981"),
                    1.dp(ctx),
                    10f
                )

                layoutParams = LinearLayout.LayoutParams(
                    64.dp(ctx),
                    31.dp(ctx)
                ).apply {
                    bottomMargin = 4.dp(ctx)
                }
            }

            val likeIcon = TextView(ctx).apply {
                text = "👍"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 0, 5.dp(ctx), 0)
            }

            val likeCount = TextView(ctx).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }

            likeBtn.addView(likeIcon)
            likeBtn.addView(likeCount)

            val dislikeBtn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                background = roundedStrokeBackground(
                    Color.parseColor("#260910"),
                    Color.parseColor("#EF4444"),
                    1.dp(ctx),
                    10f
                )

                layoutParams = LinearLayout.LayoutParams(
                    64.dp(ctx),
                    31.dp(ctx)
                )
            }

            val dislikeIcon = TextView(ctx).apply {
                text = "👎"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 0, 5.dp(ctx), 0)
            }

            val dislikeCount = TextView(ctx).apply {
                textSize = 12f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
            }

            dislikeBtn.addView(dislikeIcon)
            dislikeBtn.addView(dislikeCount)

            actionsContainer.addView(likeBtn)
            actionsContainer.addView(dislikeBtn)

            card.addView(icon)
            card.addView(infoContainer)
            card.addView(actionsContainer)

            return ViewHolder(
                card,
                icon,
                name,
                tagsContainer,
                ratingText,
                likeBtn,
                likeCount,
                dislikeBtn,
                dislikeCount
            )
        }

        override fun onBindViewHolder(
            holder: ViewHolder,
            position: Int
        ) {
            val ctx = holder.itemView.context
            val item = items[position]
            val theme = THEMES[currentThemeIndex]

            holder.card.background = roundedStrokeBackground(
                Color.parseColor(theme.cardBg),
                Color.parseColor(theme.cardStroke),
                1.dp(ctx),
                18f
            )

            holder.name.text = item.name

            val total = item.likes + item.dislikes
            if (total == 0) {
                holder.ratingText.text = if (isEnglish) "✨ New • Awaiting votes" else "✨ جديد • بانتظار التقييم"
                holder.ratingText.setTextColor(Color.parseColor("#64748B"))
            } else {
                val score = calculateScore(item.likes, item.dislikes)
                val formattedScore = String.format(Locale.US, "%.1f", score)
                val voteUnit = if (isEnglish) "votes" else "صوت"
                holder.ratingText.text = "⭐ $formattedScore  ($total $voteUnit)"
                holder.ratingText.setTextColor(Color.parseColor("#FBBF24"))
            }

            holder.tagsContainer.removeAllViews()

            val langTag = TextView(ctx).apply {
                text = formatLanguageBadge(item.rawLanguage)
                textSize = 9.5f
                setTextColor(Color.parseColor("#E2E8F0"))
                background = roundedBackground(Color.parseColor("#334155"), 5f)
                setPadding(5.dp(ctx), 2.dp(ctx), 5.dp(ctx), 2.dp(ctx))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    rightMargin = 4.dp(ctx)
                }
            }
            holder.tagsContainer.addView(langTag)

            item.rawTags.take(2).forEach { rawTag ->
                val tagView = TextView(ctx).apply {
                    text = formatTag(rawTag)
                    textSize = 9.5f
                    setTextColor(Color.parseColor("#93C5FD"))
                    background = roundedBackground(Color.parseColor("#172554"), 5f)
                    setPadding(5.dp(ctx), 2.dp(ctx), 5.dp(ctx), 2.dp(ctx))
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        rightMargin = 4.dp(ctx)
                    }
                }
                holder.tagsContainer.addView(tagView)
            }

            holder.likeCount.text = item.likes.toString()
            holder.dislikeCount.text = item.dislikes.toString()

            holder.icon.setImageDrawable(null)
            holder.icon.tag = item.icon

            if (item.icon.isNotBlank()) {
                val cached = imageCache[item.icon]
                if (cached != null) {
                    holder.icon.setImageBitmap(cached)
                } else {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val response = app.get(item.icon)
                            val body = response.body
                            val bytes = body.bytes()
                            body.close()

                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                            if (bitmap != null) {
                                imageCache[item.icon] = bitmap
                                withContext(Dispatchers.Main) {
                                    if (holder.icon.tag == item.icon) {
                                        holder.icon.setImageBitmap(bitmap)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }
            }

            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = transformUrl(item.url)
            val hasLiked = prefs.getBoolean("cs3_like_$key", false)
            val hasDisliked = prefs.getBoolean("cs3_dislike_$key", false)

            updateLikeVisual(ctx, holder.likeBtn, hasLiked)
            updateDislikeVisual(ctx, holder.dislikeBtn, hasDisliked)

            holder.card.addTouchScaleEffect()

            holder.likeBtn.addTouchScaleEffect {
                if (prefs.getBoolean("cs3_like_$key", false)) {
                    Toast.makeText(
                        ctx,
                        if (isEnglish) "You already liked this extension" else "لقد سجلت إعجابك بهذه الإضافة مسبقاً",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addTouchScaleEffect
                }

                holder.likeBtn.isEnabled = false

                CoroutineScope(Dispatchers.IO).launch {
                    val success = RatingSettings.voteLike(item.url)

                    withContext(Dispatchers.Main) {
                        holder.likeBtn.isEnabled = true

                        if (success) {
                            prefs.edit().putBoolean("cs3_like_$key", true).apply()
                            item.likes += 1
                            allPluginsMasterList.find { it.url == item.url }?.likes = item.likes

                            holder.likeCount.text = item.likes.toString()
                            val updatedTotal = item.likes + item.dislikes
                            val updatedScore = String.format(Locale.US, "%.1f", calculateScore(item.likes, item.dislikes))
                            val voteUnit = if (isEnglish) "votes" else "صوت"
                            holder.ratingText.text = "⭐ $updatedScore  ($updatedTotal $voteUnit)"
                            holder.ratingText.setTextColor(Color.parseColor("#FBBF24"))

                            updateLikeVisual(ctx, holder.likeBtn, true)
                        } else {
                            Toast.makeText(
                                ctx,
                                if (isEnglish) "Failed to submit like" else "تعذر تسجيل الإعجاب",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }

            holder.dislikeBtn.addTouchScaleEffect {
                if (prefs.getBoolean("cs3_dislike_$key", false)) {
                    Toast.makeText(
                        ctx,
                        if (isEnglish) "You already disliked this extension" else "لقد سجلت عدم إعجابك بهذه الإضافة مسبقاً",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@addTouchScaleEffect
                }

                AlertDialog.Builder(ctx)
                    .setTitle(if (isEnglish) "Confirm Vote" else "تأكيد التصويت")
                    .setMessage(if (isEnglish) "Are you sure you want to dislike this extension?" else "هل أنت متأكد أنك تريد تسجيل عدم الإعجاب بالإضافة؟")
                    .setPositiveButton(if (isEnglish) "Yes" else "نعم") { _, _ ->
                        holder.dislikeBtn.isEnabled = false

                        CoroutineScope(Dispatchers.IO).launch {
                            val success = RatingSettings.voteDislike(item.url)

                            withContext(Dispatchers.Main) {
                                holder.dislikeBtn.isEnabled = true

                                if (success) {
                                    prefs.edit().putBoolean("cs3_dislike_$key", true).apply()
                                    item.dislikes += 1
                                    allPluginsMasterList.find { it.url == item.url }?.dislikes = item.dislikes

                                    holder.dislikeCount.text = item.dislikes.toString()
                                    val updatedTotal = item.likes + item.dislikes
                                    val updatedScore = String.format(Locale.US, "%.1f", calculateScore(item.likes, item.dislikes))
                                    val voteUnit = if (isEnglish) "votes" else "صوت"
                                    holder.ratingText.text = "⭐ $updatedScore  ($updatedTotal $voteUnit)"
                                    holder.ratingText.setTextColor(Color.parseColor("#FBBF24"))

                                    updateDislikeVisual(ctx, holder.dislikeBtn, true)
                                } else {
                                    Toast.makeText(
                                        ctx,
                                        if (isEnglish) "Failed to submit dislike" else "تعذر تسجيل عدم الإعجاب",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton(if (isEnglish) "Cancel" else "إلغاء", null)
                    .show()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private suspend fun voteLike(pluginUrl: String): Boolean {
        return try {
            val repository = getRepository(pluginUrl)
            val key = transformUrl(pluginUrl)
            val url = "$COUNTER_API/cs-$repository/vote/$key"
            val response = app.get(url)
            val json = JSONObject(response.text)
            json.has("value") || json.has("count")
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun voteDislike(pluginUrl: String): Boolean {
        return try {
            val repository = getRepository(pluginUrl)
            val key = transformUrl(pluginUrl)
            val url = "$COUNTER_API/cs-$repository/dislike/$key"
            val response = app.get(url)
            val json = JSONObject(response.text)
            json.has("value") || json.has("count")
        } catch (e: Exception) {
            false
        }
    }

    private fun updateLikeVisual(ctx: Context, likeBtn: LinearLayout, active: Boolean) {
        if (active) {
            likeBtn.background = roundedStrokeBackground(
                Color.parseColor("#064E3B"),
                Color.parseColor("#34D399"),
                1.5.dp(ctx),
                10f
            )
        } else {
            likeBtn.background = roundedStrokeBackground(
                Color.parseColor("#06241C"),
                Color.parseColor("#10B981"),
                1.dp(ctx),
                10f
            )
        }
    }

    private fun updateDislikeVisual(ctx: Context, dislikeBtn: LinearLayout, active: Boolean) {
        if (active) {
            dislikeBtn.background = roundedStrokeBackground(
                Color.parseColor("#4C0519"),
                Color.parseColor("#F87171"),
                1.5.dp(ctx),
                10f
            )
        } else {
            dislikeBtn.background = roundedStrokeBackground(
                Color.parseColor("#260910"),
                Color.parseColor("#EF4444"),
                1.dp(ctx),
                10f
            )
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.addTouchScaleEffect(onClick: (() -> Unit)? = null) {
        var isInside = false
        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isInside = true
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                }
                MotionEvent.ACTION_MOVE -> {
                    val withinBounds = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (isInside != withinBounds) {
                        isInside = withinBounds
                        if (isInside) {
                            v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80).start()
                        } else {
                            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start()
                    val withinBounds = event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (withinBounds && isInside) {
                        onClick?.invoke()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    isInside = false
                    v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(80).start()
                }
            }
            true
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
        }
    }

    private fun roundedStrokeBackground(
        color: Int,
        strokeColor: Int,
        strokeWidth: Int,
        radius: Float
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            setStroke(strokeWidth, strokeColor)
            cornerRadius = radius
        }
    }

    private fun Number.dp(context: Context?): Int {
        val safeContext = context ?: return this.toInt()
        return (this.toFloat() * safeContext.resources.displayMetrics.density).toInt()
    }
}
