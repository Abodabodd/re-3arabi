package com.cimatn
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class cimatnPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaTn())
    }
}