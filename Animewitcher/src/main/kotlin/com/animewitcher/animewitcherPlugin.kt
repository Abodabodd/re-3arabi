package com.animewitcher

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context


@CloudstreamPlugin
class animewitcherPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeWitcherProvider())
    }
}