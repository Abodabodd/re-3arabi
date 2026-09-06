package com.animerco
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AnimercoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(animerco())
        registerExtractorAPI(VideaExtractor())
        registerExtractorAPI(MailruExtractor())
    }
}