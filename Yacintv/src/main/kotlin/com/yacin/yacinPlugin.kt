package com.yacin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class YacineTVPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(YacineTVProvider())
    }
}