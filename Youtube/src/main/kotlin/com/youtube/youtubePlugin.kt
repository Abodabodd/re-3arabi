package com.youtube

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YoutubeTokenPlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("YouTube", Context.MODE_PRIVATE)
        registerMainAPI(com.lagradost.cloudstream3.ar.youtube.YoutubeProvider(sharedPref))
        openSettings = { ctx ->
            val activity = ctx as? AppCompatActivity

            if (activity != null) {
                com.youtube.YoutubeSettingsBottomSheet.show(activity.supportFragmentManager, sharedPref)
            }
        }
    }
}
