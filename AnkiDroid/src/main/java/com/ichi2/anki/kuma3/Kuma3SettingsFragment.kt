// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3

import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.R
import com.ichi2.anki.RwkvOffline
import com.ichi2.anki.leaderboard.Leaderboard
import com.ichi2.anki.preferences.SettingsFragment
import com.ichi2.anki.preferences.requirePreference

/** Settings > kuma3: the switches of [Kuma3Settings]. */
class Kuma3SettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_kuma3
    override val analyticsScreenNameConstant: String
        get() = "prefs.kuma3"

    override fun initSubscreen() {
        // the leaderboard's default depends on the sign-in: show the value in effect
        requirePreference<SwitchPreferenceCompat>(R.string.kuma3_leaderboard_key).apply {
            isChecked = Kuma3Settings.leaderboard
            summary =
                if (Leaderboard.isSignedIn) {
                    getString(R.string.kuma3_pref_leaderboard_signed_in, Leaderboard.username)
                } else {
                    getString(R.string.kuma3_pref_leaderboard_signed_out)
                }
        }
        // the backend rebuilds the RWKV state for the new model or precision right away
        for (key in listOf(R.string.kuma3_rwkv_model_key, R.string.kuma3_rwkv_single_model_key, R.string.kuma3_rwkv_precision_key)) {
            requirePreference<Preference>(key).setOnPreferenceChangeListener { _, _ ->
                view?.post { RwkvOffline.prepareAfterOpen(requireContext().applicationContext, now = true) }
                true
            }
        }
        requirePreference<Preference>(R.string.kuma3_update_now_key).apply {
            val built =
                java.text.DateFormat
                    .getDateInstance()
                    .format(java.util.Date(BuildConfig.BUILD_TIME))
            summary = getString(R.string.kuma3_pref_update_now_summary, BuildConfig.GIT_COMMIT_HASH.take(10), built)
            if (Kuma3Update.available) {
                setOnPreferenceClickListener {
                    Kuma3Update.check(requireActivity())
                    true
                }
            } else {
                summary = getString(R.string.kuma3_pref_update_debug, summary)
                isEnabled = false
            }
        }
        requirePreference<SwitchPreferenceCompat>(R.string.kuma3_update_check_key).isEnabled = Kuma3Update.available
    }
}
