package com.syria
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.syrialive.SyriaLiveProvider

@CloudstreamPlugin
class syriaPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SyriaLiveProvider())
    }
}