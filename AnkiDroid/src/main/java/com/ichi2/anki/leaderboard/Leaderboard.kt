// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.File
import java.time.LocalDateTime

/**
 * Client for the "Anki Leaderboard" desktop add-on's server (add-on 175794613, "Fix by Shige").
 *
 * Does what the add-on's "Sync and update" does: computes today's numbers from the review log
 * (LeaderboardStats.kt, same queries as its Stats.py), posts them (LeaderboardServer.kt), and
 * keeps the server's reply, which is the board. The numbers are uploaded after each AnkiWeb sync,
 * when this device's review log is most complete, and when the user refreshes the board.
 *
 * This file: the account, the upload, and reading the board from the saved reply. The board is
 * drawn by BoardTable.kt, below the decks by LeaderboardFooter.kt and in the dialogs of
 * LeaderboardDialogs.kt.
 */
object Leaderboard {
    private const val KEY_USERNAME = "username"
    private const val KEY_TOKEN = "authToken"
    private const val KEY_COUNTRY = "country"
    private const val KEY_GROUPS = "groups"
    private const val KEY_GROUP = "group"
    private const val KEY_NEW_DAY = "newDayHour"
    private const val KEY_UPDATED = "updatedMillis"

    /** The add-on's default "new day" hour. */
    private const val DEFAULT_NEW_DAY_HOUR = 4

    data class Row(
        val rank: Int,
        val name: String,
        val reviews: Int,
        val minutes: Double,
        val streak: Int,
        /** Reviews in the past 31 days. */
        val month: Int,
        val retention: Double,
        /** The add-on's country key (name without spaces). */
        val country: String,
        /** League name (Alpha, Beta, ...), if the user is in one. */
        val league: String?,
        val me: Boolean,
        /** False for members who have not synced since the start of yesterday (shown with zeros). */
        val active: Boolean,
    )

    data class Board(
        val group: String,
        val rows: List<Row>,
        val updatedMillis: Long,
    )

    data class Country(
        val name: String,
        val flag: String?,
    )

    /** The add-on's countries (assets/leaderboard/countries.json, made by make_leaderboard_assets.py). */
    val countries: Map<String, Country> by lazy {
        try {
            val json =
                JSONObject(
                    context.assets
                        .open("leaderboard/countries.json")
                        .bufferedReader()
                        .use { it.readText() },
                )
            json.keys().asSequence().associateWith { key ->
                val entry = json.getJSONObject(key)
                Country(entry.getString("name"), if (entry.isNull("flag")) null else entry.getString("flag"))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private val context: Context get() = AnkiDroidApp.instance

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences("leaderboard", Context.MODE_PRIVATE)

    private val cacheFile: File get() = File(context.noBackupFilesDir, "leaderboard.json")

    val isSignedIn: Boolean
        get() = !prefs.getString(KEY_TOKEN, null).isNullOrEmpty()

    val username: String get() = prefs.getString(KEY_USERNAME, "") ?: ""

    val groups: List<String>
        get() = JSONArray(prefs.getString(KEY_GROUPS, "[]")).let { array -> List(array.length()) { array.getString(it) } }

    var group: String
        get() = prefs.getString(KEY_GROUP, null) ?: groups.firstOrNull() ?: ""
        set(value) = prefs.edit { putString(KEY_GROUP, value) }

    private val newDayHour: Int get() = prefs.getInt(KEY_NEW_DAY, DEFAULT_NEW_DAY_HOUR)

    /** Signs in with the leaderboard account and loads its country and groups. */
    suspend fun signIn(
        username: String,
        password: String,
    ) = withContext(Dispatchers.IO) {
        val reply = LeaderboardServer.post("logIn/", mapOf("username" to username, "pwd" to password))
        val token = JSONTokener(reply).nextValue() as String
        // country, groups, league, history, status
        val info = JSONArray(LeaderboardServer.post("getUserinfo/", mapOf("username" to username)))
        prefs.edit {
            putString(KEY_USERNAME, username)
            putString(KEY_TOKEN, token)
            putString(KEY_COUNTRY, info.optString(0))
            putString(KEY_GROUPS, (info.optJSONArray(1) ?: JSONArray()).toString())
            remove(KEY_GROUP)
        }
    }

    fun signOut() {
        prefs.edit { clear() }
        cacheFile.delete()
    }

    /** Uploads this device's numbers and returns the board from the server's reply. */
    suspend fun sync(): Board {
        val season = withContext(Dispatchers.IO) { LeaderboardServer.fetchSeason() }
        val stats = withCol { computeStats(this, season, newDayHour) }
        withContext(Dispatchers.IO) {
            val form =
                mutableMapOf(
                    "username" to username,
                    "streak" to stats.streak.toString(),
                    "cards" to stats.cards.toString(),
                    "time" to decimal(stats.minutes),
                    "syncDate" to timestamp(LocalDateTime.now()),
                    "month" to stats.month.toString(),
                    "country" to (prefs.getString(KEY_COUNTRY, "") ?: "").replace(" ", ""),
                    "retention" to decimal(stats.retention),
                    "authToken" to (prefs.getString(KEY_TOKEN, "") ?: ""),
                    "version" to LeaderboardServer.ADDON_VERSION,
                    "updateLeague" to if (stats.league != null) "True" else "False",
                    "sortby" to "Cards",
                )
            stats.league?.let {
                form["leagueReviews"] = it.reviews.toString()
                form["leagueTime"] = decimal(it.minutes)
                form["leagueRetention"] = decimal(it.retention)
                form["leagueDaysPercent"] = decimal(it.daysPercent)
            }
            val reply = LeaderboardServer.post("sync/", form)
            JSONArray(reply) // refuse to cache anything that is not the board
            cacheFile.writeText(reply)
            prefs.edit { putLong(KEY_UPDATED, System.currentTimeMillis()) }
        }
        return checkNotNull(cachedBoard())
    }

    /** Called after a successful AnkiWeb sync. */
    fun uploadAfterSync(activity: FragmentActivity) {
        if (!isSignedIn) return
        activity.lifecycleScope.launch {
            try {
                sync()
                LeaderboardFooter.refresh()
            } catch (e: Exception) {
                Timber.w(e, "leaderboard upload failed")
            }
        }
    }

    /**
     * The group board from the last server reply, with the add-on's home-screen rules: members who
     * synced since the start of yesterday, by reviews; members idle for up to 30 days follow with
     * zeros.
     */
    fun cachedBoard(): Board? {
        val reply =
            try {
                JSONArray(cacheFile.readText())
            } catch (e: Exception) {
                return null
            }
        val wanted = group.replace(" ", "")
        val dayStart = dayStart(LocalDateTime.now(), newDayHour)
        val since = dayStart.minusDays(1)
        val monthAgo = dayStart.minusDays(30)
        val active = mutableListOf<Row>()
        val idle = mutableListOf<Pair<LocalDateTime, Row>>()
        val users = reply.optJSONArray(0) ?: JSONArray()
        val leagues = HashMap<String, String>()
        (reply.optJSONArray(1) ?: JSONArray()).let { league ->
            for (i in 0 until league.length()) {
                val item = league.optJSONArray(i) ?: continue
                if (!item.isNull(5)) leagues[item.optString(0).substringBefore(" |")] = item.optString(5)
            }
        }
        for (i in 0 until users.length()) {
            val item = users.optJSONArray(i) ?: continue
            val name = item.optString(0).substringBefore(" |")
            val memberOf = mutableListOf<String>()
            if (!item.isNull(6) && item.optString(6).isNotEmpty()) memberOf += item.optString(6)
            if (!item.isNull(9)) {
                try {
                    JSONArray(item.optString(9)).let { extra -> repeat(extra.length()) { memberOf += extra.getString(it) } }
                } catch (e: Exception) {
                    // no further groups
                }
            }
            if (memberOf.none { it.replace(" ", "") == wanted }) continue
            val synced =
                try {
                    LocalDateTime.parse(item.optString(4), TIMESTAMP_PARSER)
                } catch (e: Exception) {
                    continue
                }
            val row =
                Row(
                    rank = 0,
                    name = name,
                    reviews = item.optInt(2),
                    minutes = item.optDouble(3, 0.0),
                    streak = item.optInt(1),
                    month = item.optInt(5),
                    retention = item.optDouble(8, 0.0),
                    country = if (item.isNull(7)) "" else item.optString(7).replace(" ", ""),
                    league = leagues[name],
                    me = name == username,
                    active = true,
                )
            if (synced > since) {
                active += row
            } else if (synced > monthAgo) {
                idle += synced to row.copy(reviews = 0, minutes = 0.0, streak = 0, month = 0, retention = 0.0, active = false)
            }
        }
        val rows =
            (active.sortedByDescending { it.reviews } + idle.sortedByDescending { it.first }.map { it.second })
                .mapIndexed { index, row -> row.copy(rank = index + 1) }
        return Board(group, rows, prefs.getLong(KEY_UPDATED, 0))
    }
}
