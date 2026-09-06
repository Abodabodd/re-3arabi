package com.eseek
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class eseek: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(GessehProvider())

    }
}