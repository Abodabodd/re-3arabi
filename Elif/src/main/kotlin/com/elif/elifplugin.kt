package com.elif

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ElifNewsPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ElifNewsProvider())
    }
}
