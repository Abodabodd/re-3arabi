package com.tuniflix
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class tuniflixPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Tuniflix())
    }
}