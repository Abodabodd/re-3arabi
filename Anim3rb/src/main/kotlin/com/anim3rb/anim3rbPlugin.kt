package com.anim3rb

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class anim3rbPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anime3rb(context))
    }
}