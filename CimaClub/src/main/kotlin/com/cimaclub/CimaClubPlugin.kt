package com.cimanow
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cimaclub.CimaClub

@CloudstreamPlugin
class CimaClubPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CimaClub())
    }
}