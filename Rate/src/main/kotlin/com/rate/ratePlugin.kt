package com.rate

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ratePlugin : Plugin() {

    companion object {
        var pluginContext: Context? = null

        fun openRatings(context: Context?) {
            var current = context
            while (current is ContextWrapper) {
                if (current is FragmentActivity) {
                    RatingSettings.show(current.supportFragmentManager)
                    return
                }
                current = current.baseContext
            }
            if (current is FragmentActivity) {
                RatingSettings.show(current.supportFragmentManager)
            }
        }
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(Rate())

        openSettings = { ctx ->
            openRatings(ctx)
        }
    }
}
