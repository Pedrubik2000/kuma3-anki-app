// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.R
import com.ichi2.anki.leaderboard.Leaderboard

/**
 * The switches of Settings > kuma3 (res/xml/preferences_kuma3.xml, [Kuma3SettingsFragment]): which
 * of kuma3's extras are on. Each extra reads its switch here; the keys are in res/values/kuma3.xml.
 */
object Kuma3Settings {
    private val prefs: SharedPreferences
        get() = PreferenceManager.getDefaultSharedPreferences(AnkiDroidApp.instance)

    private fun get(
        @StringRes key: Int,
        default: Boolean,
    ) = prefs.getBoolean(AnkiDroidApp.instance.getString(key), default)

    /** The review heatmap below the decks. */
    val heatmap: Boolean get() = get(R.string.kuma3_heatmap_key, true)

    /** The time answered today on each deck row. */
    val deckTimes: Boolean get() = get(R.string.kuma3_deck_times_key, true)

    /**
     * The leaderboard: its board below the decks, its menu item and its uploads. Off for new
     * users (it talks to a third-party server); on by default for someone already signed in.
     */
    val leaderboard: Boolean get() = get(R.string.kuma3_leaderboard_key, Leaderboard.isSignedIn)

    /** The RWKV forecast (due / near / safe, due soon) under "Studied … today". */
    val rwkvForecast: Boolean get() = get(R.string.kuma3_rwkv_forecast_key, true)

    /** The RWKV forecast's dot graph above its counts. */
    val rwkvForecastGraph: Boolean get() = get(R.string.kuma3_rwkv_forecast_graph_key, true)

    /** The Lofi button in the deck list's and the reviewer's top bar (com.ichi2.anki.kuma3.lofi). */
    val lofi: Boolean get() = get(R.string.kuma3_lofi_key, true)

    // The "RWKV ready" switch (R.string.kuma3_rwkv_toast_key) is read by anki-common's RwkvOffline.
}
