package com.animerift
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimeRiftPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeRift())
    }
}