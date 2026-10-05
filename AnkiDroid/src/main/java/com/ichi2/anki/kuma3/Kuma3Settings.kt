// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3

import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.leaderboard.Leaderboard

/**
 * The switches of Settings > kuma3 (res/xml/preferences_kuma3.xml, [Kuma3SettingsFragment]): which
 * of kuma3's extras are on. Each extra reads its switch here; the keys are the ones in the XML.
 */
object Kuma3Settings {
    const val KEY_HEATMAP = "kuma3_heatmap"
    const val KEY_DECK_TIMES = "kuma3_deck_times"
    const val KEY_LEADERBOARD = "kuma3_leaderboard"

    /** Read by anki-common's RwkvOffline directly (it cannot see this module). */
    const val KEY_RWKV_TOAST = "kuma3_rwkv_toast"

    private val prefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(AnkiDroidApp.instance)

    /** The review heatmap below the decks. */
    val heatmap: Boolean get() = prefs.getBoolean(KEY_HEATMAP, true)

    /** The time answered today on each deck row. */
    val deckTimes: Boolean get() = prefs.getBoolean(KEY_DECK_TIMES, true)

    /**
     * The leaderboard: its board below the decks, its menu item and its uploads. Off for new
     * users (it talks to a third-party server); on by default for someone already signed in.
     */
    val leaderboard: Boolean get() = prefs.getBoolean(KEY_LEADERBOARD, Leaderboard.isSignedIn)
}
