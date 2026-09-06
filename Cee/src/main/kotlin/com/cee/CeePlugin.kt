package com.cee

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
@CloudstreamPlugin
class CeePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(CeeProvider())
    }
}