package com.replaymatch

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ReplaymatchSettings : BottomSheetDialogFragment() {

    class PrefsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val context = requireContext()
            val screen = preferenceManager.createPreferenceScreen(context)
            preferenceScreen = screen

            fun createSwitch(ctx: Context, title: String, key: String, isEnabled: Boolean): SwitchPreferenceCompat {
                return SwitchPreferenceCompat(ctx).apply {
                    this.key = key
                    this.title = title
                    setDefaultValue(isEnabled)
                }
            }

            val europeCategory = PreferenceCategory(context).apply { title = "Europe" }
            screen.addPreference(europeCategory)
            listOf(
                "Champions League" to "show_eur_champions",
                "Europa League" to "show_eur_europa",
                "Nations League" to "show_eur_nations",
                "Super Cup" to "show_eur_super_cup"
            ).forEach { (title, key) ->
                europeCategory.addPreference(createSwitch(context, title, key, false))
            }

            val englandCategory = PreferenceCategory(context).apply { title = "England" }
            screen.addPreference(englandCategory)
            listOf(
                "Premier League" to "show_eng_premier",
                "ChampionShip" to "show_eng_championship",
                "FA Cup" to "show_eng_fa_cup",
                "Carabao Cup" to "show_eng_carabao"
            ).forEach { (title, key) ->
                englandCategory.addPreference(createSwitch(context, title, key, key == "show_eng_premier"))
            }

            val spainCategory = PreferenceCategory(context).apply { title = "Spain" }
            screen.addPreference(spainCategory)
            listOf(
                "La Liga" to "show_spa_liga",
                "Copa Del Rey" to "show_spa_copa"
            ).forEach { (title, key) ->
                spainCategory.addPreference(createSwitch(context, title, key, key == "show_spa_liga"))
            }

            val italyCategory = PreferenceCategory(context).apply { title = "Italy" }
            screen.addPreference(italyCategory)
            listOf(
                "Serie A" to "show_ita_serie_a",
                "Coppa Italia" to "show_ita_coppa"
            ).forEach { (title, key) ->
                italyCategory.addPreference(createSwitch(context, title, key, key == "show_ita_serie_a"))
            }

            val germanyCategory = PreferenceCategory(context).apply { title = "Germany" }
            screen.addPreference(germanyCategory)
            listOf(
                "Bundesliga" to "show_ger_bundesliga",
                "DFB Pokal" to "show_ger_pokal"
            ).forEach { (title, key) ->
                germanyCategory.addPreference(createSwitch(context, title, key, key == "show_ger_bundesliga"))
            }

            val othersCategory = PreferenceCategory(context).apply { title = "Other Leagues" }
            screen.addPreference(othersCategory)
            listOf(
                "Netherland - Eredivisie" to "show_ned_eredivisie",
                "International - Friendly Match" to "show_int_friendly",
                "International - Club Friendlies" to "show_int_club_friendly",
                "International - World Cup Qualifiers" to "show_int_wc_qualifiers",
                "Extras - Africa Cup" to "show_ext_africa",
                "Extras - Liga Portugal" to "show_ext_portugal",
                "Extras - Saudi Pro League" to "show_ext_saudi",
                "Extras - Turkish Super Lig" to "show_ext_turkish"
            ).forEach { (title, key) ->
                othersCategory.addPreference(createSwitch(context, title, key, false))
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {

        val fragmentContainer = FragmentContainerView(requireContext())

        fragmentContainer.id = View.generateViewId()
        return fragmentContainer
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        childFragmentManager.beginTransaction()
            .replace(view.id, PrefsFragment())
            .commit()
    }
}