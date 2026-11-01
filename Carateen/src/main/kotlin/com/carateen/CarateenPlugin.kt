package com.carateen

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CarateenPlugin : Plugin() {
    override fun load() {
        registerMainAPI(CarateenProvider())
    }
}
