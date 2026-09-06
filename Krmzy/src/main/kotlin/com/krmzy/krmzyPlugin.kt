package com.krmzy
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Krmzy: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(krmzyProvider())

    }
}