package com.alooy
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class alooyPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AlooyTvProvider())
    }
}