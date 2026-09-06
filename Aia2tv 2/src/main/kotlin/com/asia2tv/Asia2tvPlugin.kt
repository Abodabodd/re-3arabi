package com.asia2tv
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Asia2tv: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Asia2tvProvider())

    }
}