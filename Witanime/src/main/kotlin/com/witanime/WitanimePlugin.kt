package com.cimanow
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.witanime.MailruExtractor
import com.witanime.VideaExtractor
import com.witanime. WitAnime

@CloudstreamPlugin
class WitAnimePlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WitAnime())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())

    }
}