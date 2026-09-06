



package com.cinemana

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import androidx.fragment.app.FragmentActivity

@CloudstreamPlugin
class CinemanaPlugin : Plugin() {
    override fun load(context: Context) {

        registerMainAPI(Cinemana(context))

        openSettings = { activityContext ->
            (activityContext as? FragmentActivity)?.let { activity ->
                val settingsFragment = CinemanaSettings()
                settingsFragment.show(activity.supportFragmentManager, "CinemanaSettings")
            }

        }
    }
}
