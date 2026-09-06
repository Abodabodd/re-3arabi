package com.arabseed

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ArabseedProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Arabseed())
        registerExtractorAPI(GameHubExtractor())
        registerExtractorAPI(GameHubExtractor1())


    }
}
