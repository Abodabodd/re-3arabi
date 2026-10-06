package com.rate

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext



class Rate : MainAPI() {

    override var mainUrl = "https://raw.githubusercontent.com/Abodabodd/re-3arabi/refs/heads/builds/plugins.json"
    override var name = "تقييم الإضافات"
    override var lang = "ar"

    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "ratings" to "⭐ التقييمات"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        withContext(Dispatchers.Main) {
            try {
                findActivity(ratePlugin.pluginContext)?.supportFragmentManager?.let { fm ->
                    RatingSettings.show(fm)
                }
            } catch (e: Exception) {
            }
        }

        return newHomePageResponse(
            request.name,
            emptyList(),
            hasNext = false
        )
    }

    private fun findActivity(context: Context?): FragmentActivity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) {
                return current
            }
            current = current.baseContext
        }
        return null
    }
}
