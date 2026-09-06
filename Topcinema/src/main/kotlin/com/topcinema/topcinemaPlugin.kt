package com.topcinema
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class eishkPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TopCinemaProvider())
    }
}