// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.kuma3.Kuma3Settings
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
 * when this device's review log is most complete, when the user leaves the reviewer (an option,
 * like the add-on's "Sync when deck is finished") and when the user refreshes the board.
 *
 * The reply is the whole leaderboard (over 20,000 users, a few MB): it is read in the
 * background and only once per change ([board]), never on the main thread.
 *
 * This file: the account, the options, the upload, and reading the board from the saved reply.
 * The board is drawn by BoardTable.kt, below the decks by LeaderboardFooter.kt and in the
 * dialogs of LeaderboardDialogs.kt.
 */
object Leaderboard {
    private const val KEY_USERNAME = "username"
    private const val KEY_TOKEN = "authToken"
    private const val KEY_COUNTRY = "country"
    private const val KEY_GROUPS = "groups"
    private const val KEY_GROUP = "group"
    private const val KEY_NEW_DAY = "newDayHour"
    private const val KEY_UPDATED = "updatedMillis"
    private const val KEY_MAX_USERS = "maxUsers"
    private const val KEY_FOCUS_ON_USER = "focusOnUser"
    private const val KEY_SHOW_MEDALS = "showMedals"
    private const val KEY_UPLOAD_AFTER_REVIEWS = "uploadAfterReviews"

    /** The add-on's default "new day" hour. */
    private const val DEFAULT_NEW_DAY_HOUR = 4

    /** The add-on's limit for its home-screen board. */
    const val MAX_HOME_USERS = 100

    /** Leaving the reviewer again within this time does not upload again. */
    private const val UPLOAD_AFTER_REVIEWS_INTERVAL_MILLIS = 60_000L

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
        /** League medals won in past seasons. */
        val gold: Int = 0,
        val silver: Int = 0,
        val bronze: Int = 0,
    ) {
        /** The name as the add-on shows it, with medals when [withMedals]: "Molas | 2🥇 🥉". */
        fun displayName(withMedals: Boolean): String {
            if (!withMedals || gold + silver + bronze == 0) return name

            fun count(n: Int) = if (n == 1) "" else n.toString()
            val medals =
                listOfNotNull(
                    "${count(gold)}🥇".takeIf { gold > 0 },
                    "${count(silver)}🥈".takeIf { silver > 0 },
                    "${count(bronze)}🥉".takeIf { bronze > 0 },
                )
            return "$name | " + medals.joinToString(" ")
        }
    }

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

    // --- options, as in the add-on's Leaderboard > Config (same defaults) ---

    /** "Maximum number of users on the home screen Leaderboard". */
    var maxUsers: Int
        get() = prefs.getInt(KEY_MAX_USERS, 5).coerceIn(1, MAX_HOME_USERS)
        set(value) = prefs.edit { putInt(KEY_MAX_USERS, value.coerceIn(1, MAX_HOME_USERS)) }

    /** "Focus on user": the home-screen board shows the rows around the user, not the top. */
    var focusOnUser: Boolean
        get() = prefs.getBoolean(KEY_FOCUS_ON_USER, true)
        set(value) = prefs.edit { putBoolean(KEY_FOCUS_ON_USER, value) }

    /** "Show league medals next to username". */
    var showMedals: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MEDALS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_MEDALS, value) }

    /** "Sync when deck is finished": upload when the user leaves the reviewer. */
    var uploadAfterReviews: Boolean
        get() = prefs.getBoolean(KEY_UPLOAD_AFTER_REVIEWS, true)
        set(value) = prefs.edit { putBoolean(KEY_UPLOAD_AFTER_REVIEWS, value) }

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
        memo = null
    }

    /** Forgets the account and the board; the options stay. */
    fun signOut() {
        prefs.edit {
            for (key in listOf(KEY_USERNAME, KEY_TOKEN, KEY_COUNTRY, KEY_GROUPS, KEY_GROUP, KEY_UPDATED)) remove(key)
        }
        cacheFile.delete()
        memo = null
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
        return checkNotNull(board())
    }

    /** Called after a successful AnkiWeb sync. */
    fun uploadAfterSync(activity: FragmentActivity) {
        if (!isSignedIn || !Kuma3Settings.leaderboard) return
        upload(activity)
    }

    private var lastReviewUploadMillis = 0L

    /** Called when the user leaves the reviewer ([uploadAfterReviews]). */
    fun uploadAfterReviews(activity: FragmentActivity) {
        if (!isSignedIn || !uploadAfterReviews || !Kuma3Settings.leaderboard) return
        val now = System.currentTimeMillis()
        if (now - lastReviewUploadMillis < UPLOAD_AFTER_REVIEWS_INTERVAL_MILLIS) return
        lastReviewUploadMillis = now
        upload(activity)
    }

    private fun upload(activity: FragmentActivity) {
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
     * The rows for the board below the decks: at most [maxUsers]; with [focusOnUser], the ones
     * around the user (as the add-on's home screen), otherwise the top ones.
     */
    fun homeRows(board: Board): List<Row> {
        val rows = board.rows
        val max = maxUsers
        if (rows.size <= max) return rows
        val me = rows.indexOfFirst { it.me }
        if (!focusOnUser || me < 0) return rows.take(max)
        val start = (me - max / 2).coerceIn(0, rows.size - max)
        return rows.subList(start, start + max)
    }

    private class Memo(
        val key: String,
        val board: Board,
    )

    @Volatile
    private var memo: Memo? = null

    /**
     * The group board from the last server reply, with the add-on's home-screen rules: members who
     * synced since the start of yesterday, by reviews; members idle for up to 30 days follow with
     * zeros. Read in the background, and again only when the reply, the group or the day changes.
     */
    suspend fun board(): Board? =
        withContext(Dispatchers.Default) {
            val dayStart = dayStart(LocalDateTime.now(), newDayHour)
            val key = listOf(group, username, cacheFile.lastModified(), prefs.getLong(KEY_UPDATED, 0), dayStart).joinToString("|")
            memo?.takeIf { it.key == key }?.let { return@withContext it.board }
            readBoard(dayStart)?.also { memo = Memo(key, it) }
        }

    private fun readBoard(dayStart: LocalDateTime): Board? {
        val reply =
            try {
                JSONArray(cacheFile.readText())
            } catch (e: Exception) {
                return null
            }
        val wanted = group.replace(" ", "")
        val since = dayStart.minusDays(1)
        val monthAgo = dayStart.minusDays(30)
        val active = mutableListOf<Row>()
        val idle = mutableListOf<Pair<LocalDateTime, Row>>()
        val users = reply.optJSONArray(0) ?: JSONArray()
        val leagues = HashMap<String, String>()
        // league history: {"gold": n, "silver": n, "bronze": n, ...}
        val medals = HashMap<String, Triple<Int, Int, Int>>()
        (reply.optJSONArray(1) ?: JSONArray()).let { league ->
            for (i in 0 until league.length()) {
                val item = league.optJSONArray(i) ?: continue
                val name = item.optString(0).substringBefore(" |")
                if (!item.isNull(5)) leagues[name] = item.optString(5)
                if (!item.isNull(6)) {
                    try {
                        val history = JSONObject(item.optString(6))
                        val won = Triple(history.optInt("gold"), history.optInt("silver"), history.optInt("bronze"))
                        if (won.first + won.second + won.third > 0) medals[name] = won
                    } catch (e: Exception) {
                        // no medals
                    }
                }
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
            val won = medals[name]
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
                    gold = won?.first ?: 0,
                    silver = won?.second ?: 0,
                    bronze = won?.third ?: 0,
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
