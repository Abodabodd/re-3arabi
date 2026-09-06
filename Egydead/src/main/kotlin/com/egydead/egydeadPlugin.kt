package com.eshk
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.egydead.EgyDead
@CloudstreamPlugin
class egydeadPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(EgyDead())
    }
}