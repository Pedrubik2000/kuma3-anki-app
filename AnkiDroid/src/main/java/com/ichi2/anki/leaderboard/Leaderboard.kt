// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Client for the "Anki Leaderboard" desktop add-on's server (add-on 175794613, "Fix by Shige").
 *
 * Does what the add-on's "Sync and update" does: computes today's numbers from the review log
 * (same queries as its Stats.py), posts them, and keeps the server's reply, which is the board.
 * The numbers are uploaded after each AnkiWeb sync, when this device's review log is most
 * complete, and when the user refreshes the board.
 */
object Leaderboard {
    private const val API = "https://shigeyuki.pythonanywhere.com/api/v2/"

    /** The add-on version this client behaves like. */
    private const val ADDON_VERSION = "v5.0 (Fixed by Shige)"

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

    /**
     * The add-on's grade for a position on a board (its `compute_user_rank`): the band
     * (10, 20, ... 100) and its letter.
     */
    fun grade(
        position: Int,
        total: Int,
    ): Pair<Int, String> {
        val letters =
            mapOf(
                10 to "A+",
                20 to "A",
                30 to "B+",
                40 to "B",
                50 to "C+",
                60 to "C",
                70 to "D+",
                80 to "D",
                90 to "E",
                100 to "F",
            )
        val number = position.coerceIn(1, maxOf(1, total))
        val fifth = total * 20 / 100
        val band =
            when {
                total <= 10 -> number * 10
                number <= fifth -> if (number > fifth / 2) 20 else 10
                number <= total - fifth -> {
                    val step = (total - fifth * 2) / 6
                    when {
                        number <= fifth + step -> 30
                        number <= fifth + step * 2 -> 40
                        number <= fifth + step * 3 -> 50
                        number <= fifth + step * 4 -> 60
                        number <= fifth + step * 5 -> 70
                        else -> 80
                    }
                }
                number <= total - fifth / 2 -> 90
                else -> 100
            }
        return band to letters.getValue(band)
    }

    data class Board(
        val group: String,
        val rows: List<Row>,
        val updatedMillis: Long,
    )

    private data class Season(
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    private class Stats(
        val streak: Int,
        val cards: Int,
        val minutes: Double,
        val month: Int,
        val retention: Double,
        val league: League?,
    )

    private class League(
        val reviews: Int,
        val minutes: Double,
        val retention: Double,
        val daysPercent: Double,
    )

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
        val token = JSONTokener(post("logIn/", mapOf("username" to username, "pwd" to password))).nextValue() as String
        // country, groups, league, history, status
        val info = JSONArray(post("getUserinfo/", mapOf("username" to username)))
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
        val season = withContext(Dispatchers.IO) { fetchSeason() }
        val stats = withCol { computeStats(this, season) }
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
                    "version" to ADDON_VERSION,
                    "updateLeague" to if (stats.league != null) "True" else "False",
                    "sortby" to "Cards",
                )
            stats.league?.let {
                form["leagueReviews"] = it.reviews.toString()
                form["leagueTime"] = decimal(it.minutes)
                form["leagueRetention"] = decimal(it.retention)
                form["leagueDaysPercent"] = decimal(it.daysPercent)
            }
            val reply = post("sync/", form)
            JSONArray(reply) // refuse to cache anything that is not the board
            cacheFile.writeText(reply)
            prefs.edit { putLong(KEY_UPDATED, System.currentTimeMillis()) }
        }
        return checkNotNull(cachedBoard())
    }

    /** Called after a successful AnkiWeb sync. */
    fun uploadAfterSync(activity: FragmentActivity) {
        LeaderboardUi.refreshFooter() // the sync may have brought reviews: redraw the heatmap
        if (!isSignedIn) return
        activity.lifecycleScope.launch {
            try {
                sync()
                LeaderboardUi.refreshFooter()
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
        val dayStart = dayStart(LocalDateTime.now())
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

    // --- numbers, as in the add-on's Stats.py ---

    private fun dayStart(now: LocalDateTime): LocalDateTime {
        val newDay = LocalTime.of(newDayHour, 0)
        val day = if (now.toLocalTime() < newDay) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return day.atTime(newDay)
    }

    private fun computeStats(
        col: Collection,
        season: Season?,
    ): Stats {
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now()
        val dayStart = dayStart(now)

        fun millis(time: LocalDateTime) = time.atZone(zone).toInstant().toEpochMilli()

        fun reviewsAndRetention(
            start: LocalDateTime,
            end: LocalDateTime,
        ): Pair<Int, Double> {
            val reviews =
                col.db.queryScalar(
                    "SELECT COUNT(*) FROM revlog WHERE id >= ? AND id < ? AND ease > 0 AND time >= 1",
                    millis(start),
                    millis(end),
                )
            if (reviews == 0) return 0 to 0.0
            val flunked =
                col.db.queryScalar(
                    "SELECT COUNT(*) FROM revlog WHERE ease == 1 AND id >= ? AND id < ? AND time >= 1",
                    millis(start),
                    millis(end),
                )
            return reviews to round1(100.0 / reviews * (reviews - flunked))
        }

        fun minutes(
            start: LocalDateTime,
            end: LocalDateTime,
        ): Double {
            val spent =
                col.db.queryLongScalar(
                    "SELECT COALESCE(SUM(time), 0) FROM revlog WHERE id >= ? AND id < ? AND time >= 1",
                    millis(start),
                    millis(end),
                )
            return if (spent <= 0) 0.0 else round1(spent / 60000.0)
        }

        // streak: consecutive days with a review, counted back from today (days start at newDayHour)
        val shift = newDayHour * 3_600_000L
        var day: LocalDate = dayStart.toLocalDate()
        var streak = 0
        for (id in col.db.queryLongList("SELECT id FROM revlog WHERE ease > 0 ORDER BY id DESC")) {
            val reviewed = Instant.ofEpochMilli(id - shift).atZone(zone).toLocalDate()
            if (reviewed.isAfter(day)) continue // a day already counted
            if (reviewed != day) break
            streak++
            day = day.minusDays(1)
        }

        val (cards, retention) = reviewsAndRetention(dayStart, dayStart.plusDays(1))
        val month = reviewsAndRetention(dayStart.plusDays(1).minusDays(31), dayStart.plusDays(1)).first

        val league =
            season?.takeIf { now < it.end }?.let {
                val (leagueReviews, leagueRetention) = reviewsAndRetention(it.start, it.end)
                val newDay = LocalTime.of(newDayHour, 0)
                var learned = 0
                var over = 0
                for (offset in 0..ChronoUnit.DAYS.between(it.start, it.end)) {
                    val start =
                        it.start
                            .toLocalDate()
                            .atTime(newDay)
                            .plusDays(offset)
                    if (minutes(start, start.plusDays(1)) >= 5) learned++
                    if (start.toLocalDate() == now.toLocalDate() && now.toLocalTime() < newDay) continue
                    if (!start.toLocalDate().isAfter(now.toLocalDate())) over++
                }
                League(
                    reviews = leagueReviews,
                    minutes = minutes(it.start, it.end),
                    retention = leagueRetention,
                    daysPercent = if (over == 0) 0.0 else round1(100.0 / over * learned),
                )
            }
        return Stats(streak, cards, minutes(dayStart, dayStart.plusDays(1)), month, retention, league)
    }

    private fun fetchSeason(): Season? =
        try {
            val reply = JSONArray(request("season/", null).second)

            fun time(index: Int): LocalDateTime {
                val parts = reply.getJSONArray(index)
                return LocalDateTime.of(
                    parts.getInt(0),
                    parts.getInt(1),
                    parts.getInt(2),
                    parts.getInt(3),
                    parts.getInt(4),
                    parts.getInt(5),
                )
            }
            Season(time(0), time(1))
        } catch (e: Exception) {
            Timber.w(e, "leaderboard season unavailable")
            null
        }

    // --- HTTP ---

    private fun post(
        endpoint: String,
        form: Map<String, String>,
    ): String {
        val (code, text) = request(endpoint, form)
        if (code !in 200..299) throw IOException(text.take(300).ifBlank { "Leaderboard server error $code" })
        return text
    }

    private fun request(
        endpoint: String,
        form: Map<String, String>?,
    ): Pair<Int, String> {
        val connection = URL(API + endpoint).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                val body =
                    form.entries.joinToString("&") { (key, value) ->
                        URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
                    }
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return code to decode(stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The server compresses the board (seen: a zlib stream, which the desktop's `requests` unpacks
     * by itself and HttpURLConnection does not). Unpack by what the bytes are, not by the headers.
     */
    private fun decode(bytes: ByteArray): String {
        fun byte(index: Int) = bytes[index].toInt() and 0xff
        val unpacked =
            try {
                when {
                    bytes.size > 2 && byte(0) == 0x1f && byte(1) == 0x8b ->
                        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
                    // zlib header: deflate method, and the two bytes are a multiple of 31
                    bytes.size > 2 && (byte(0) and 0x0f) == 8 && (byte(0) * 256 + byte(1)) % 31 == 0 ->
                        InflaterInputStream(bytes.inputStream()).use { it.readBytes() }
                    else -> bytes
                }
            } catch (e: IOException) {
                bytes
            }
        return unpacked.toString(Charsets.UTF_8)
    }

    private fun round1(value: Double) = Math.round(value * 10) / 10.0

    private fun decimal(value: Double) = String.format(Locale.US, "%.1f", value)

    /** Python's `str(datetime.now())`. */
    private fun timestamp(time: LocalDateTime) =
        time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + String.format(Locale.US, ".%06d", time.nano / 1000)

    private val TIMESTAMP_PARSER: DateTimeFormatter =
        DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter()
}
