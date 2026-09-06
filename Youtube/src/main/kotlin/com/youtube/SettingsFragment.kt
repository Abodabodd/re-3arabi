package com.youtube

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.*
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.preference.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.preference.Preference.SummaryProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.graphics.PathParser
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.PathShape
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import com.lagradost.cloudstream3.app
import org.jsoup.Jsoup
data class CustomSection(
    @JsonProperty("name") var name: String = "",
    @JsonProperty("url") var url: String = "",
    @JsonProperty("isEnabled") var isEnabled: Boolean = true
)


class YoutubeSettingsBottomSheet(private val sharedPref: SharedPreferences) : BottomSheetDialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentContainerView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment(sharedPref))
            .commit()
    }

    companion object {
        fun show(fm: FragmentManager, sp: SharedPreferences) {
            YoutubeSettingsBottomSheet(sp).show(fm, "yt_settings")
        }
    }
    class PrefsFragment(private val sharedPref: SharedPreferences) : PreferenceFragmentCompat() {

        private val KEY_VISITOR = "VISITOR_INFO1_LIVE"
        private val KEY_PAGES = "channel_pages_limit"
        private val KEY_PLAYLIST_TAG = "playlist_search_tag"
        private val KEY_LANGUAGE = "youtube_language"
        private val KEY_PLAYER_TYPE = "youtube_player_type"

        private lateinit var languagePref: ListPreference
        private lateinit var authCategory: PreferenceCategory
        private lateinit var customCategory: PreferenceCategory
        private lateinit var capturePref: Preference
        private lateinit var loginStatusPref: Preference
        private lateinit var visitorPref: EditTextPreference
        private lateinit var clearPref: Preference
        private lateinit var playerTypePref: ListPreference
        private lateinit var pagesPref: SeekBarPreference
        private lateinit var playlistTagPref: EditTextPreference
        private lateinit var homeCategory: PreferenceCategory
        private lateinit var manageChannelsPref: Preference
        private lateinit var trendingPref: SwitchPreferenceCompat

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            preferenceManager.preferenceDataStore = null
            preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
            homeCategory = PreferenceCategory(ctx)
            preferenceScreen.addPreference(homeCategory)

            trendingPref = SwitchPreferenceCompat(ctx).apply {
                key = "show_trending_home"
                setDefaultValue(true)
            }
            homeCategory.addPreference(trendingPref)

            manageChannelsPref = Preference(ctx).apply {
                key = "manage_custom_channels"
                setIcon(android.R.drawable.ic_menu_sort_by_size)
                setOnPreferenceClickListener {
                    val dialog = CustomSectionsDialog(sharedPref)
                    dialog.setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                    dialog.show(parentFragmentManager, "custom_sections_dialog")
                    true
                }
            }
            homeCategory.addPreference(manageChannelsPref)
            languagePref = ListPreference(ctx).apply {
                key = KEY_LANGUAGE
                entryValues = Loc.availableLanguages.map { it.first }.toTypedArray()
                entries = Loc.availableLanguages.map { "${it.third} (${it.second})" }.toTypedArray()
                if (value == null) setValue("ar")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newValue ->
                    sharedPref.edit().putString(KEY_LANGUAGE, newValue as String).apply()
                    view?.post { updateTexts() }
                    true
                }
            }
            preferenceScreen.addPreference(languagePref)
            authCategory = PreferenceCategory(ctx)
            preferenceScreen.addPreference(authCategory)

            capturePref = Preference(ctx).apply {
                key = "capture_webview_btn"
                setOnPreferenceClickListener {
                    val webDialog = WebViewCaptureDialog(sharedPref) { success ->
                        if (success) {
                            visitorPref.text = sharedPref.getString(KEY_VISITOR, "")
                            Toast.makeText(ctx, Loc.getString(sharedPref, "saved_msg"), Toast.LENGTH_SHORT).show()
                            updateTexts()
                        }
                    }
                    webDialog.setStyle(DialogFragment.STYLE_NO_TITLE, android.R.style.Theme_Material_Light_NoActionBar_Fullscreen)
                    webDialog.show(parentFragmentManager, "webview_fullscreen")
                    true
                }
            }
            authCategory.addPreference(capturePref)

            loginStatusPref = Preference(ctx).apply { isEnabled = false }
            authCategory.addPreference(loginStatusPref)

            visitorPref = EditTextPreference(ctx).apply {
                key = KEY_VISITOR
                setOnBindEditTextListener { it.setText(sharedPref.getString(KEY_VISITOR, "")) }
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, newVal ->
                    sharedPref.edit().putString(KEY_VISITOR, newVal as String).apply()
                    true
                }
            }
            authCategory.addPreference(visitorPref)

            clearPref = Preference(ctx).apply {
                setOnPreferenceClickListener {
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(Loc.getString(sharedPref, "logout_confirm_title"))
                        .setMessage(Loc.getString(sharedPref, "logout_confirm_msg"))
                        .setPositiveButton(Loc.getString(sharedPref, "delete")) { _, _ ->
                            sharedPref.edit().apply {
                                remove(KEY_VISITOR); remove("SID"); remove("HSID")
                                remove("SSID"); remove("APISID"); remove("SAPISID")
                            }.apply()
                            Toast.makeText(ctx, Loc.getString(sharedPref, "cleared_msg"), Toast.LENGTH_SHORT).show()
                            visitorPref.text = ""
                            updateTexts()
                        }
                        .setNegativeButton(Loc.getString(sharedPref, "cancel"), null)
                        .show()
                    true
                }
            }
            authCategory.addPreference(clearPref)
            customCategory = PreferenceCategory(ctx)
            preferenceScreen.addPreference(customCategory)

            playerTypePref = ListPreference(ctx).apply {
                key = KEY_PLAYER_TYPE
                entryValues = arrayOf("advanced", "classic")
                if (value == null) setValue("advanced")
                summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
                setOnPreferenceChangeListener { _, _ -> true }
            }
            customCategory.addPreference(playerTypePref)

            pagesPref = SeekBarPreference(ctx).apply {
                key = KEY_PAGES
                min = 1; max = 50
                setDefaultValue(6)
                showSeekBarValue = true
                setOnPreferenceChangeListener { _, newVal ->
                    sharedPref.edit().putInt(KEY_PAGES, newVal as Int).apply()
                    true
                }
            }
            pagesPref.value = sharedPref.getInt(KEY_PAGES, 6)
            customCategory.addPreference(pagesPref)

            playlistTagPref = EditTextPreference(ctx).apply {
                key = KEY_PLAYLIST_TAG
                setOnBindEditTextListener { it.setText(sharedPref.getString(KEY_PLAYLIST_TAG, "{p}")) }
                summaryProvider = SummaryProvider<EditTextPreference> { pref ->
                    val value = pref.text
                    val currentTxt = Loc.getString(sharedPref, "current")
                    if (value.isNullOrBlank()) "{p}" else "$currentTxt$value"
                }
                setOnPreferenceChangeListener { _, newVal ->
                    val v = (newVal as String).trim()
                    sharedPref.edit().putString(KEY_PLAYLIST_TAG, v.ifEmpty { "{p}" }).apply()
                    true
                }
            }
            if (!sharedPref.contains(KEY_PLAYLIST_TAG)) sharedPref.edit().putString(KEY_PLAYLIST_TAG, "{p}").apply()
            playlistTagPref.text = sharedPref.getString(KEY_PLAYLIST_TAG, "{p}")
            customCategory.addPreference(playlistTagPref)

            updateTexts()
        }

        private fun updateTexts() {
            homeCategory.title = Loc.getString(sharedPref, "home_section")
            trendingPref.title = Loc.getString(sharedPref, "trending")
            manageChannelsPref.title = Loc.getString(sharedPref, "manage_channels")
            manageChannelsPref.summary = Loc.getString(sharedPref, "manage_channels_sum")

            languagePref.title = Loc.getString(sharedPref, "lang_title")
            authCategory.title = Loc.getString(sharedPref, "account_section")
            customCategory.title = Loc.getString(sharedPref, "browsing_section")

            capturePref.title = Loc.getString(sharedPref, "login_btn")
            capturePref.summary = Loc.getString(sharedPref, "login_sum")

            val hasSid = sharedPref.getString("SID", null) != null
            loginStatusPref.title = Loc.getString(sharedPref, "status_title")
            loginStatusPref.summary = if (hasSid) Loc.getString(sharedPref, "status_logged_in") else Loc.getString(sharedPref, "status_logged_out")

            visitorPref.title = Loc.getString(sharedPref, "visitor_id")
            visitorPref.dialogTitle = Loc.getString(sharedPref, "edit_manual")
            visitorPref.setPositiveButtonText(Loc.getString(sharedPref, "ok"))
            visitorPref.setNegativeButtonText(Loc.getString(sharedPref, "cancel"))

            clearPref.title = Loc.getString(sharedPref, "logout_btn")

            playerTypePref.title = Loc.getString(sharedPref, "player_engine")
            playerTypePref.entries = arrayOf(
                Loc.getString(sharedPref, "player_advanced"),
                Loc.getString(sharedPref, "player_classic")
            )
            playerTypePref.setPositiveButtonText(Loc.getString(sharedPref, "ok"))
            playerTypePref.setNegativeButtonText(Loc.getString(sharedPref, "cancel"))

            pagesPref.title = Loc.getString(sharedPref, "pages_limit")
            pagesPref.summary = Loc.getString(sharedPref, "pages_sum")

            playlistTagPref.title = Loc.getString(sharedPref, "playlist_tag")
            playlistTagPref.dialogTitle = Loc.getString(sharedPref, "tag_hint")
            playlistTagPref.setPositiveButtonText(Loc.getString(sharedPref, "ok"))
            playlistTagPref.setNegativeButtonText(Loc.getString(sharedPref, "cancel"))
        }
    }
    class CustomSectionsDialog(private val sharedPref: SharedPreferences) : DialogFragment() {

        private val mapper = jacksonObjectMapper()
        private lateinit var recyclerView: RecyclerView
        private lateinit var adapter: CustomSectionsAdapter
        private var sectionsList = mutableListOf<CustomSection>()

        override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val ctx = requireContext()
            loadSections()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#121212"))
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

            val toolbar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(Color.parseColor("#1E1E1E"))
                setPadding(30, 30, 30, 30)
                gravity = Gravity.CENTER_VERTICAL
            }

            val backBtn = ImageView(ctx).apply {
                setImageDrawable(createSvgIcon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z", "#FFFFFF"))
                val outValue = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
                setBackgroundResource(outValue.resourceId)
                setPadding(15, 15, 15, 15)
                layoutParams = LinearLayout.LayoutParams(90, 90).apply {
                    setMargins(0, 0, 30, 0)
                }

                setOnClickListener { dismiss() }
            }

            val titleView = TextView(ctx).apply {
                text = Loc.getString(sharedPref, "manage_channels")
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            toolbar.addView(backBtn)
            toolbar.addView(titleView)

            val addBtn = Button(ctx).apply {
                text = Loc.getString(sharedPref, "add_channel_btn")
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#E53935"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(30, 30, 30, 15)
                }
                setOnClickListener { showAddEditDialog(null, -1) }
            }

            recyclerView = RecyclerView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
                layoutManager = LinearLayoutManager(ctx)
            }

            adapter = CustomSectionsAdapter()
            recyclerView.adapter = adapter

            val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
                override fun onMove(rv: RecyclerView, source: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                    val fromPosition = source.adapterPosition
                    val toPosition = target.adapterPosition
                    java.util.Collections.swap(sectionsList, fromPosition, toPosition)
                    adapter.notifyItemMoved(fromPosition, toPosition)
                    return true
                }
                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
                override fun clearView(rv: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                    super.clearView(rv, viewHolder)
                    saveSections()
                }
            })
            itemTouchHelper.attachToRecyclerView(recyclerView)

            root.addView(toolbar)
            root.addView(addBtn)
            root.addView(recyclerView)

            return root
        }

        private fun loadSections() {
            val json = sharedPref.getString("custom_homepages_v3", "[]") ?: "[]"
            sectionsList = try {
                mapper.readValue(json)
            } catch (e: Exception) {
                mutableListOf()
            }
        }

        private fun saveSections() {
            val json = mapper.writeValueAsString(sectionsList)
            sharedPref.edit().putString("custom_homepages_v3", json).apply()
        }

        private fun createSvgIcon(pathData: String, colorHex: String): android.graphics.drawable.Drawable {
            val path = PathParser.createPathFromPathData(pathData)
            val shape = PathShape(path, 24f, 24f)
            return ShapeDrawable(shape).apply {
                paint.color = Color.parseColor(colorHex)
                paint.style = android.graphics.Paint.Style.FILL
                intrinsicWidth = 70
                intrinsicHeight = 70
            }
        }

        private fun showAddEditDialog(existingItem: CustomSection?, index: Int) {
            val ctx = requireContext()
            val layout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
            }

            fun createInput(hintText: String, prefill: String): EditText {
                return EditText(ctx).apply {
                    hint = hintText
                    setText(prefill)
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.GRAY)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 20)
                    }
                }
            }
            val nameWrapper = FrameLayout(ctx)
            val inputName = createInput(Loc.getString(sharedPref, "section_name"), existingItem?.name ?: "")

            val loadingIndicator = ProgressBar(ctx, null, android.R.attr.progressBarStyleSmall).apply {
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(80, 80).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    marginEnd = 20
                }
            }
            nameWrapper.addView(inputName)
            nameWrapper.addView(loadingIndicator)

            val inputUrl = createInput(Loc.getString(sharedPref, "url_hint"), existingItem?.url ?: "")
            var fetchJob: kotlinx.coroutines.Job? = null
            inputUrl.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    val url = s.toString().trim()
                    fetchJob?.cancel() // إلغاء أي عملية سابقة
                    if (url.startsWith("http") && url.contains("youtube") && inputName.text.isBlank()) {
                        loadingIndicator.visibility = View.VISIBLE

                        fetchJob = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val response = com.lagradost.cloudstream3.app.get(url).text
                                val doc = org.jsoup.Jsoup.parse(response)
                                var title = doc.selectFirst("meta[property=og:title]")?.attr("content")
                                    ?.replace(" - YouTube", "")

                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (!title.isNullOrBlank()) {
                                        inputName.setText(title)
                                    }
                                    loadingIndicator.visibility = View.GONE
                                }
                            } catch (e: Exception) {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    loadingIndicator.visibility = View.GONE
                                }
                            }
                        }
                    }
                }
            })

            layout.addView(nameWrapper)
            layout.addView(inputUrl)

            val dialogTitle = if (existingItem == null) Loc.getString(sharedPref, "add_new") else Loc.getString(sharedPref, "edit_item")

            MaterialAlertDialogBuilder(ctx)
                .setTitle(dialogTitle)
                .setView(layout)
                .setPositiveButton(Loc.getString(sharedPref, "save")) { _, _ ->
                    val newUrl = inputUrl.text.toString().trim()
                    if (newUrl.isEmpty()) {
                        Toast.makeText(ctx, Loc.getString(sharedPref, "url_empty"), Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    val finalName = inputName.text.toString().trim().ifEmpty { Loc.getString(sharedPref, "unnamed_section") }

                    val newItem = CustomSection(
                        name = finalName,
                        url = newUrl,
                        isEnabled = existingItem?.isEnabled ?: true
                    )

                    if (index >= 0) sectionsList[index] = newItem else sectionsList.add(newItem)

                    saveSections()
                    adapter.notifyDataSetChanged()
                }
                .setNegativeButton(Loc.getString(sharedPref, "cancel"), null)
                .show()
        }

        inner class CustomSectionsAdapter : RecyclerView.Adapter<CustomSectionsAdapter.ViewHolder>() {

            inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
                val dragHandle: TextView = view.findViewWithTag("dragHandle")
                val nameTxt: TextView = view.findViewWithTag("nameTxt")
                val urlTxt: TextView = view.findViewWithTag("urlTxt")
                val enableSwitch: SwitchCompat = view.findViewWithTag("enableSwitch")
                val editBtn: ImageView = view.findViewWithTag("editBtn")
                val deleteBtn: ImageView = view.findViewWithTag("deleteBtn")
            }

            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
                val ctx = parent.context
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(20, 15, 20, 15)
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#1E1E1E"))
                        cornerRadius = 16f
                    }
                    setPadding(20, 30, 20, 30)
                }

                val dragHandle = TextView(ctx).apply {
                    text = "☰"
                    tag = "dragHandle"
                    textSize = 26f
                    setTextColor(Color.GRAY)
                    setPadding(10, 10, 30, 10)
                }

                val textLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                val nameTxt = TextView(ctx).apply {
                    tag = "nameTxt"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setTypeface(null, Typeface.BOLD)
                }

                val urlTxt = TextView(ctx).apply {
                    tag = "urlTxt"
                    setTextColor(Color.parseColor("#90CAF9"))
                    textSize = 12f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }

                textLayout.addView(nameTxt)
                textLayout.addView(urlTxt)

                val actionsLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val enableSwitch = SwitchCompat(ctx).apply {
                    tag = "enableSwitch"
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(10, 0, 20, 0)
                    }
                }

                val editBtn = ImageView(ctx).apply {
                    tag = "editBtn"
                    setImageDrawable(createSvgIcon("M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z", "#0078FF"))
                    setPadding(15, 15, 15, 15)
                    layoutParams = LinearLayout.LayoutParams(130, 130)
                }

                val deleteBtn = ImageView(ctx).apply {
                    tag = "deleteBtn"
                    setImageDrawable(createSvgIcon("M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6V19zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z", "#0078FF"))
                    setPadding(15, 15, 10, 15)
                    layoutParams = LinearLayout.LayoutParams(130, 130)
                }

                actionsLayout.addView(enableSwitch)
                actionsLayout.addView(editBtn)
                actionsLayout.addView(deleteBtn)

                card.addView(dragHandle)
                card.addView(textLayout)
                card.addView(actionsLayout)

                return ViewHolder(card)
            }

            override fun onBindViewHolder(holder: ViewHolder, position: Int) {
                val section = sectionsList[position]

                val displayName = section.name.ifBlank { Loc.getString(sharedPref, "unnamed_section") }
                holder.nameTxt.text = displayName
                holder.urlTxt.text = section.url

                holder.enableSwitch.setOnCheckedChangeListener(null)
                holder.enableSwitch.isChecked = section.isEnabled

                if (section.isEnabled) holder.nameTxt.setTextColor(Color.WHITE) else holder.nameTxt.setTextColor(Color.GRAY)

                holder.enableSwitch.setOnCheckedChangeListener { _, isChecked ->
                    section.isEnabled = isChecked
                    saveSections()
                    if (isChecked) holder.nameTxt.setTextColor(Color.WHITE) else holder.nameTxt.setTextColor(Color.GRAY)
                }

                holder.editBtn.setOnClickListener {
                    showAddEditDialog(section, holder.adapterPosition)
                }
                holder.deleteBtn.setOnClickListener {
                    val pos = holder.adapterPosition
                    if (pos != RecyclerView.NO_POSITION) {
                        MaterialAlertDialogBuilder(holder.itemView.context)
                            .setTitle(Loc.getString(sharedPref, "confirm_delete_title"))
                            .setMessage(Loc.getString(sharedPref, "confirm_delete_msg"))
                            .setPositiveButton(Loc.getString(sharedPref, "delete")) { _, _ ->
                                sectionsList.removeAt(pos)
                                saveSections()
                                notifyItemRemoved(pos)
                            }
                            .setNegativeButton(Loc.getString(sharedPref, "cancel"), null)
                            .show()
                    }
                }
            }

            override fun getItemCount(): Int = sectionsList.size
        }
    }
    class WebViewCaptureDialog(
        private val sharedPref: SharedPreferences,
        private val onFinish: (Boolean) -> Unit
    ) : DialogFragment() {

        private lateinit var webView: WebView
        private val targetCookies = listOf("VISITOR_INFO1_LIVE", "SID", "HSID", "SSID", "APISID", "SAPISID")

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
            val ctx = requireContext()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(Color.WHITE)
            }

            val toolbar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(Color.parseColor("#EEEEEE"))
                gravity = Gravity.CENTER_VERTICAL
            }

            val closeBtn = Button(ctx).apply {
                text = Loc.getString(sharedPref, "close")
                setOnClickListener { dismiss() }
            }

            val titleView = TextView(ctx).apply {
                text = Loc.getString(sharedPref, "browse_youtube")
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val saveBtn = Button(ctx).apply {
                text = Loc.getString(sharedPref, "save")
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#FF0000"))
                setOnClickListener { captureAndClose() }
            }

            toolbar.addView(closeBtn)
            toolbar.addView(titleView)
            toolbar.addView(saveBtn)

            val webContainer = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            }

            webView = WebView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = false
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
            webView.loadUrl("https://www.youtube.com")

            webContainer.addView(webView)
            root.addView(toolbar)
            root.addView(webContainer)

            return root
        }

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val dialog = super.onCreateDialog(savedInstanceState)
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && ::webView.isInitialized && webView.canGoBack()) {
                    webView.goBack()
                    true
                } else false
            }
            return dialog
        }

        private fun captureAndClose() {
            try {
                CookieManager.getInstance().flush()
                val cookieStr = CookieManager.getInstance().getCookie(webView.url ?: "https://www.youtube.com") ?: ""
                val editor = sharedPref.edit()
                var found = 0

                cookieStr.split(";").forEach {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2 && targetCookies.contains(parts[0].trim())) {
                        editor.putString(parts[0].trim(), parts[1].trim())
                        found++
                    }
                }
                editor.apply()

                if (found > 0) {
                    onFinish(true)
                    dismiss()
                } else {
                    context?.let {
                        Toast.makeText(it, Loc.getString(sharedPref, "no_data"), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                context?.let {
                    Toast.makeText(it, "Error", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}