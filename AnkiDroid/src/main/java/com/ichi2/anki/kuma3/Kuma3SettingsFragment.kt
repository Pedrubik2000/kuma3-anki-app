// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3

import androidx.preference.SwitchPreferenceCompat
import com.ichi2.anki.R
import com.ichi2.anki.leaderboard.Leaderboard
import com.ichi2.anki.preferences.SettingsFragment

/** Settings > kuma3: the switches of [Kuma3Settings]. */
class Kuma3SettingsFragment : SettingsFragment() {
    override val preferenceResource: Int
        get() = R.xml.preferences_kuma3
    override val analyticsScreenNameConstant: String
        get() = "prefs.kuma3"

    override fun initSubscreen() {
        // the leaderboard's default depends on the sign-in: show the value in effect
        findPreference<SwitchPreferenceCompat>(Kuma3Settings.KEY_LEADERBOARD)?.apply {
            isChecked = Kuma3Settings.leaderboard
            summary =
                if (Leaderboard.isSignedIn) {
                    "Signed in as ${Leaderboard.username}. Off hides the board and stops the uploads; " +
                        "the sign-in is kept."
                } else {
                    "The Anki Leaderboard add-on's board (an unofficial client of its server). " +
                        "Sign in from the deck list menu."
                }
        }
    }
}
