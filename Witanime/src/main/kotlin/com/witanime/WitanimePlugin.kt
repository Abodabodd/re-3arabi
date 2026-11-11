package com.witanime

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class WitAnimePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WitAnime())

        registerExtractorAPI(MegaExtractor())
        registerExtractorAPI(GoFileExtractor())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(VideasExtractor())
        registerExtractorAPI(MailruExtractor())
        registerExtractorAPI(FourSharedExtractor())
        registerExtractorAPI(YonaPlayExtractor()) 
        registerExtractorAPI(GoogleDriveExtractor()) 
    }
}
