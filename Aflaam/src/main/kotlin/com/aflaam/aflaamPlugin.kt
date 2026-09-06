package com.aflaam
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class aflamPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Aflaam())
    }
}