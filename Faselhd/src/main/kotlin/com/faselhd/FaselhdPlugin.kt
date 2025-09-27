package com.mycima
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.faselhd.FASELHD

@CloudstreamPlugin
class FaselhdPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FASELHD(context))
    }
}