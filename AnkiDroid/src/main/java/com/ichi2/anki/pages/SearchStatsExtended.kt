// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.pages

import android.os.SystemClock
import android.webkit.WebView
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.RwkvOffline
import com.ichi2.anki.common.coroutines.applicationScope
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.libanki.Collection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * kuma3: the desktop "Search Stats Extended" add-on (JSchoreels' fork, with the Calibration, UM+ and RWKV
 * comparison graphs) on the statistics page. Its page code is bundled as is in `assets/sse/` (built by
 * kuma3-build/sse_build.sh); this file is its Python half: the values it is started with and the endpoints it
 * posts to (`/_anki/<name>`, JSON in and out), answered from the collection. FSRS's predictions per review come
 * from the core's FSRS cache (`FsrsCalibrationFinalFit`, the test folds of `RebuildFsrsReviewRetrievabilityCache`),
 * RWKV's from the backend's replay (`RwkvOfflineCalibration`).
 */
object SearchStatsExtended {
    private const val PREFS_KEY = "kuma3_sse_config"
    private const val LANG = "en_GB"
    private const val DAY_SECONDS = 86_400L

    /** Starts the add-on on the loaded page; it then wraps the page's next graphs request. */
    suspend fun inject(webView: WebView) {
        val assets = webView.context.assets
        val script =
            withContext(Dispatchers.IO) {
                fun asset(name: String) = assets.open("sse/$name").bufferedReader().use { it.readText() }
                val config = JSONObject(asset("config.json"))
                val saved = AnkiDroidApp.sharedPrefs().getString(PREFS_KEY, null)?.let(::JSONObject)
                saved?.keys()?.forEach { config.put(it, saved.get(it)) }
                val other = withCol { other(this, asset("$LANG.ftl")) }
                // once per page: the page can report "finished" twice
                "if (!window.kuma3Sse) { window.kuma3Sse = true;" +
                    "window.bridgeCommand = window.bridgeCommand || function () {};" +
                    "const css = ${JSONObject.quote(asset("stats.min.css"))};" +
                    "const SSEconfig = $config;" +
                    "const SSEother = $other;" +
                    asset("stats.min.js") +
                    "\n}"
            }
        webView.evaluateJavascript(script, null)
    }

    private fun other(
        col: Collection,
        ftl: String,
    ): JSONObject {
        val scheduling = col.getPreferences().scheduling
        val configs = JSONObject()
        col.decks.allConfig().forEach { configs.put(it.id.toString(), it.jsonObject) }
        val configIds = JSONObject()
        val decks = JSONObject(col.backend.getAllDecksLegacy().toStringUtf8())
        decks.keys().forEach { configIds.put(it, decks.getJSONObject(it).optLong("conf", 1)) }
        return JSONObject()
            .put("rollover", scheduling.rollover)
            .put("learn_ahead_secs", scheduling.learnAheadSecs)
            .put("selected_deck_id", col.decks.current().id)
            .put("deck_configs", configs)
            .put("deck_config_ids", configIds)
            .put("days_elapsed", col.sched.today)
            .put("lang", LANG)
            .put("available_langs", JSONArray(listOf(LANG)))
            .put("lang_ftl", ftl)
            .put("fallback_ftl", ftl)
    }

    val methods: Map<String, CollectionBackendInterface> =
        mapOf<String, CollectionBackendInterface>(
            "cardSearch" to { bytes -> JSONArray(findCards(bytes.decodeToString())).toBytes() },
            "cardData" to { bytes -> cardData(longs(JSONArray(bytes.decodeToString()))) },
            "fsrsPresetData" to { bytes -> fsrsPresetData(longs(JSONArray(bytes.decodeToString()))) },
            "revlogs" to { bytes -> revlogs(JSONObject(bytes.decodeToString())) },
            "fsrsReviewRetrievability" to { bytes -> fsrsReviewRetrievability(JSONObject(bytes.decodeToString())) },
            // the page's own FSRS replay (ts-fsrs, only the period shown): not kept
            "cacheFsrsReviewRetrievability" to { _ -> """{"stored":0}""".toByteArray() },
            "rwkvReviewRetrievability" to { bytes -> rwkvReviewRetrievability(JSONObject(bytes.decodeToString())) },
            // ponytail: RWKV Memorised over time is the desktop's per-day replay of every card; the page
            // then shows FSRS only
            "startRwkvMemorisedHistory" to { _ -> MEMORISED_UNAVAILABLE },
            "rwkvMemorisedHistoryProgress" to { _ -> MEMORISED_UNAVAILABLE },
            "cancelRwkvMemorisedHistory" to { _ -> """{"cancelled":false,"checkpointed":false}""".toByteArray() },
            "kuma3CalibrationStatus" to { _ -> calibrationStatus() },
            "kuma3CalibrationStart" to { _ -> calibrationStart() },
            "kuma3CalibrationSamples" to { bytes -> calibrationSamples(JSONObject(bytes.decodeToString())) },
            "writeConfig" to { bytes -> writeConfig(JSONObject(bytes.decodeToString())) },
            "openLocaleFolder" to { _ -> ByteArray(0) },
        )

    private val MEMORISED_UNAVAILABLE =
        """{"phase":"unavailable","done":true,"error":"RWKV Memorised history is desktop-only."}""".toByteArray()

    private fun JSONArray.toBytes() = toString().toByteArray()

    private fun longs(array: JSONArray) = List(array.length()) { array.getLong(it) }

    private val CARD_COLUMNS =
        listOf(
            "id",
            "nid",
            "did",
            "ord",
            "mod",
            "usn",
            "type",
            "queue",
            "due",
            "ivl",
            "factor",
            "reps",
            "lapses",
            "left",
            "odue",
            "odid",
            "flags",
            "data",
        )

    private val REVLOG_COLUMNS = listOf("id", "cid", "ease", "ivl", "lastIvl", "time", "factor", "type")

    private fun Collection.cardData(cids: List<Long>): ByteArray =
        rows(CARD_COLUMNS, cids) { "select ${CARD_COLUMNS.joinToString()} from cards where id in ($it)" }

    private fun Collection.revlogs(request: JSONObject): ByteArray {
        val lower = revlogLowerLimit(request.optInt("day_range"))
        return rows(REVLOG_COLUMNS, longs(request.getJSONArray("cids")), sortByFirst = true) {
            "select ${REVLOG_COLUMNS.joinToString()} from revlog where cid in ($it) and id > $lower"
        }
    }

    /**
     * `{"columns": [...], "data": [[...], ...]}` for the rows [sql] gives for [ids] (asked in chunks: one
     * statement with every id of a big collection is too long for SQLite), text in the last column.
     */
    private fun Collection.rows(
        columns: List<String>,
        ids: List<Long>,
        sortByFirst: Boolean = false,
        sql: (String) -> String,
    ): ByteArray {
        val rows = mutableListOf<Pair<Long, String>>()
        for (chunk in ids.chunked(10_000)) {
            db.query(sql(chunk.joinToString(","))).use { cursor ->
                while (cursor.moveToNext()) {
                    val row =
                        columns.indices.joinToString(",", "[", "]") { i ->
                            if (columns[i] == "data") JSONObject.quote(cursor.getString(i)) else cursor.getLong(i).toString()
                        }
                    rows.add(cursor.getLong(0) to row)
                }
            }
        }
        if (sortByFirst) rows.sortBy { it.first }
        return buildString {
            append("""{"columns":""").append(JSONArray(columns)).append(""","data":[""")
            rows.joinTo(this, ",") { it.second }
            append("]}")
        }.toByteArray()
    }

    /** The add-on's `_revlog_lower_limit`: reviews newer than [dayRange] days, all for 0. */
    private fun Collection.revlogLowerLimit(dayRange: Int): Long {
        if (dayRange <= 0) return 0
        val rollover = getPreferences().scheduling.rollover
        val today = (TimeManager.time.intTime() - rollover * 3600L) / DAY_SECONDS.toDouble()
        return ((today - dayRange) * DAY_SECONDS * 1000).toLong()
    }

    private fun Collection.fsrsPresetData(cids: List<Long>): ByteArray {
        val out = JSONObject()
        // one preset lookup per preset, not per card
        for (cards in backend.getFsrsPresetIdsForCards(cids).groupBy({ it.presetId }, { it.cardId }).values) {
            val p = backend.getFsrsPresetForCard(cards.first())
            val preset =
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("fsrsVersion", p.fsrsVersionValue)
                    .put("params", JSONArray(p.paramsList))
                    .put("desiredRetention", p.desiredRetention.toDouble())
                    .put("historicalRetention", p.historicalRetention.toDouble())
                    .put("ignoreRevlogsBeforeDate", p.ignoreRevlogsBeforeDate)
                    .put("fsrsDynamicDesiredRetentionEnabled", p.fsrsDynamicDesiredRetentionEnabled)
                    .put("fsrsDynamicDesiredRetentionParams", JSONArray(p.fsrsDynamicDesiredRetentionParamsList))
                    .put("fsrsDynamicDesiredRetentionWeights", JSONArray(p.fsrsDynamicDesiredRetentionWeightsList))
                    .put("fsrsDynamicDesiredRetentionAvgDrs", JSONArray(p.fsrsDynamicDesiredRetentionAvgDrsList))
                    .put("fsrsDynamicDesiredRetentionFsrsEqWeights", JSONArray(p.fsrsDynamicDesiredRetentionFsrsEqWeightsList))
                    .put("fsrsDynamicDesiredRetentionFsrsEqDrs", JSONArray(p.fsrsDynamicDesiredRetentionFsrsEqDrsList))
                    .put("fsrsDynamicDesiredRetentionMin", p.fsrsDynamicDesiredRetentionMin.toDouble())
                    .put("fsrsDynamicDesiredRetentionMax", p.fsrsDynamicDesiredRetentionMax.toDouble())
            cards.forEach { out.put(it.toString(), preset) }
        }
        return out.toString().toByteArray()
    }

    /** The page's asked-for reviews: its ids, or every review of its cards in its period. */
    private fun Collection.askedReviews(request: JSONObject): List<Long> =
        longs(request.optJSONArray("revlog_ids") ?: JSONArray()).ifEmpty {
            val lower = revlogLowerLimit(request.optInt("day_range"))
            longs(request.optJSONArray("cids") ?: JSONArray()).chunked(10_000).flatMap { chunk ->
                db.queryLongList("select id from revlog where cid in (${chunk.joinToString(",")}) and id > $lower")
            }
        }

    private fun predictions(
        column: String,
        rows: Map<Long, Number>,
    ): ByteArray {
        val data = rows.entries.joinToString(",") { "[${it.key},${it.value}]" }
        return """{"column":${if (rows.isEmpty()) "null" else "\"$column\""},"data":[$data]}""".toByteArray()
    }

    // The core's FSRS predictions per review (the fork's cache, a file next to the collection attached to
    // it). Rows: validation_fold = FSRS trained only on the reviews before that fold (the test folds, as the
    // desktop's calibration graph); final_fit = each preset's parameters over the whole history (written with
    // the folds; a newer one means new parameters); post_optimization = made when the card was answered.
    private const val FSRS_TABLE = "search_stats_fsrs_review_retrievability"
    private const val FSRS_CACHE = "retrievability_cache.$FSRS_TABLE"

    /** The FSRS settings ([fsrsSettings]) the test folds were last made with. */
    internal const val FOLDS_KEY = "kuma3_sse_fsrs_folds"

    /** Asked before RWKV's whole-history replay when it would take longer (it changes the review order). */
    private const val CONFIRM_MS = 5 * 60_000L

    // ponytail: per-review times measured on a Tab S7+ (3.5M reviews: FSRS folds 1.5 min, RWKV recording
    // 47 min big / 7 min small), only for the first estimate; the progress then gives the real rate
    private const val FSRS_MS_PER_REVIEW = 0.026
    private const val RWKV_BIG_MS_PER_REVIEW = 0.80
    private const val RWKV_SMALL_MS_PER_REVIEW = 0.12

    // The calibration job ([calibrationStatus]): FSRS test folds, then RWKV over the whole history. One at a
    // time; `version` goes up when either has new predictions (the page then reloads its graphs).
    @Volatile private var fsrsSince = 0L

    @Volatile private var rwkvSince = 0L

    @Volatile private var rwkvConfirmed = false

    @Volatile private var rwkvOff = false

    @Volatile private var version = 0

    @Volatile private var reviewCount = 0L

    internal fun Collection.fsrsSettings(): Long =
        decks
            .allConfig()
            .joinToString(";") { config ->
                val json = config.jsonObject
                json
                    .keys()
                    .asSequence()
                    .filter { "fsrs" in it.lowercase() || it == "ignoreRevlogsBeforeDate" }
                    .sorted()
                    .joinToString { "$it=${json.get(it)}" }
            }.hashCode()
            .toLong()

    /** The test folds were made with the current FSRS settings (and RWKV can be scored on their reviews). */
    private fun Collection.foldsFresh() = AnkiDroidApp.sharedPrefs().getLong(FOLDS_KEY, 0) == fsrsSettings()

    /**
     * FSRS's prediction before each of [ids], the newest row per review (the add-on's
     * `_read_fsrs_calibration_retrievability_cache`): a test-fold or answer prediction, unless a newer final
     * fit (new parameters) replaced it.
     */
    private fun Collection.fsrsRows(ids: List<Long>): Map<Long, Double> {
        val out = HashMap<Long, Double>()
        for (chunk in ids.chunked(10_000)) {
            db
                .query(
                    """
                    with latest as (
                        select revlog_id, max(updated_at) as updated_at from $FSRS_CACHE
                        where revlog_id in (${chunk.joinToString(",")})
                            and sample_role in ('final_fit','validation_fold','post_optimization')
                        group by revlog_id)
                    select c.revlog_id, c.prediction from $FSRS_CACHE c
                    join latest l on l.revlog_id = c.revlog_id and l.updated_at = c.updated_at
                    where c.sample_role in ('validation_fold','post_optimization')
                    """,
                ).use { cursor -> while (cursor.moveToNext()) out[cursor.getLong(0)] = cursor.getDouble(1) }
        }
        return out
    }

    /**
     * FSRS's prediction before each asked-for review: only its test folds (honest, as the desktop), none
     * while they are being made ([calibrationStatus] shows the progress). The page's own FSRS replay of the
     * period shown is not used (it put long-known cards back to new: the flat line nyawo saw).
     */
    private fun Collection.fsrsReviewRetrievability(request: JSONObject): ByteArray {
        val ids = askedReviews(request)
        val rows = if (foldsFresh()) fsrsRows(ids) else emptyMap()
        Timber.i("FSRS calibration: %d of %d asked-for reviews", rows.size, ids.size)
        return predictions(FSRS_TABLE, rows)
    }

    /**
     * RWKV's prediction before each asked-for review that FSRS has a test-fold prediction for, so both lines
     * are on the same reviews (the desktop's test_fold role). Recorded by the calibration job's replay of the
     * whole history; never started from here.
     */
    private fun Collection.rwkvReviewRetrievability(request: JSONObject): ByteArray {
        val ids = askedReviews(request)
        if (!foldsFresh()) return predictions("rwkv_offline", emptyMap())
        val out = backend.rwkvOfflineCalibration(reviewIds = ids, allReviews = true, checkOnly = true)
        if (!out.available) return predictions("rwkv_offline", emptyMap())
        val fsrs = fsrsRows(ids).keys
        val rows =
            (0 until out.reviewIdsCount)
                .filter { out.getReviewIds(it) in fsrs }
                .associate { out.getReviewIds(it) to out.getPredictions(it) }
        Timber.i("RWKV calibration: %d of %d asked-for reviews", rows.size, ids.size)
        return predictions("rwkv_offline", rows)
    }

    /**
     * The reviews the calibration graphs compare, `[review id, card id, recalled]`: those of the page's cards
     * in its period that come a day or more after the card's previous review (the add-on's rule; cram and
     * manual entries are skipped). The page's FSRS replay of every review picked them before, which takes
     * minutes for millions of reviews; this is one query.
     */
    private fun Collection.calibrationSamples(request: JSONObject): ByteArray {
        val lower = revlogLowerLimit(request.optInt("day_range"))
        // day numbers from the rollover, kept positive for the integer division
        val day = { column: String -> "(($column / 1000 - ${sched.dayCutoff}) + 864000000) / 86400" }
        val out = StringBuilder("""{"data":[""")
        var count = 0
        for (chunk in longs(request.getJSONArray("cids")).chunked(10_000)) {
            db
                .query(
                    """
                    select id, cid, ease from (
                        select id, cid, ease, lag(id) over (partition by cid order by id) as prev from revlog
                        where cid in (${chunk.joinToString(",")}) and ease > 0 and not (type = 3 and factor = 0))
                    where prev is not null and id > $lower and ${day("id")} > ${day("prev")}
                    """,
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        if (count++ > 0) out.append(',')
                        out
                            .append('[')
                            .append(cursor.getLong(0))
                            .append(',')
                            .append(cursor.getLong(1))
                            .append(',')
                            .append(if (cursor.getInt(2) > 1) 1 else 0)
                            .append(']')
                    }
                }
        }
        Timber.i("calibration: %d reviews to compare", count)
        return out.append("]}").toString().toByteArray()
    }

    private fun minutes(ms: Double): String {
        val context = AnkiDroidApp.instance
        val min = (ms / 60_000).toInt()
        return if (min < 1) {
            context.getString(R.string.kuma3_cal_under_minute)
        } else {
            context.getString(R.string.kuma3_cal_minutes, min + 1)
        }
    }

    /** Time left from the rate so far, or [estimate] before there is any. */
    private fun left(
        since: Long,
        done: Long,
        total: Long,
        estimate: Double,
    ): Double {
        if (since == 0L || done <= 0 || total <= 0) return estimate
        val elapsed = (SystemClock.elapsedRealtime() - since).toDouble()
        return elapsed / done * (total - done)
    }

    private fun rwkvMsPerReview() =
        if (AnkiDroidApp.sharedPrefs().getString("kuma3_rwkv_model", "small") != "big") {
            RWKV_SMALL_MS_PER_REVIEW
        } else {
            RWKV_BIG_MS_PER_REVIEW
        }

    private fun status(
        phase: String,
        done: Long = 0,
        total: Long = 0,
        text: String = "",
        button: String = "",
    ): ByteArray =
        JSONObject()
            .put("phase", phase)
            .put("done", done)
            .put("total", total)
            .put("text", text)
            .put("button", button)
            .put("version", version)
            .toString()
            .toByteArray()

    private fun Collection.rwkvRecorded() =
        backend.rwkvOfflineCalibration(reviewIds = emptyList(), allReviews = true, checkOnly = true).available

    /**
     * The calibration graphs' progress bar (Kuma3CalibrationProgress.svelte in the page, polled each second),
     * which also runs the job: the FSRS test folds when missing or made with other FSRS settings (minutes;
     * the statistics stay usable), then RWKV's replay of the whole history (only once per model: it is saved
     * with the RWKV state and kept up to date by answers), asked for first when it would take long.
     */
    private fun Collection.calibrationStatus(): ByteArray {
        val context = AnkiDroidApp.instance
        // asked each second: counted once per job (only for the estimates)
        val reviews = reviewCount.takeIf { it > 0 } ?: db.queryLongScalar("select count() from revlog").also { reviewCount = it }
        val rwkvEstimate = if (rwkvOff) 0.0 else reviews * rwkvMsPerReview()
        if (fsrsSince != 0L || !foldsFresh()) {
            if (fsrsSince == 0L) startFolds(fsrsSettings())
            val progress = CollectionManager.getBackend().latestProgress()
            val (done, total) =
                progress.computeParams
                    .takeIf { progress.hasComputeParams() }
                    ?.let { it.current.toLong() to it.total.toLong() } ?: (0L to 0L)
            val rwkvLeft = if (rwkvOff || rwkvRecorded()) 0.0 else rwkvEstimate
            val fsrsLeft = left(fsrsSince, done, total, reviews * FSRS_MS_PER_REVIEW)
            return status("fsrs", done, total, context.getString(R.string.kuma3_cal_fsrs, minutes(fsrsLeft + rwkvLeft)))
        }
        if (rwkvOff) return status("done")
        val rwkv = backend.rwkvOfflineCalibration(reviewIds = emptyList(), allReviews = true, checkOnly = true)
        if (rwkv.available) {
            if (rwkvSince != 0L) {
                rwkvSince = 0L
                version++
            }
            reviewCount = 0L
            return status("done")
        }
        if (rwkv.computing) {
            val percent = if (rwkv.replayTotal > 0) rwkv.replayDone * 100 / rwkv.replayTotal else 0
            val text =
                if (rwkvSince != 0L) {
                    val rwkvLeft = left(rwkvSince, rwkv.replayDone, rwkv.replayTotal, rwkvEstimate)
                    context.getString(R.string.kuma3_cal_rwkv, percent, minutes(rwkvLeft))
                } else {
                    // another rebuild (start, model change): the job's replay follows it
                    context.getString(R.string.kuma3_cal_rwkv_wait, percent)
                }
            return status("rwkv", rwkv.replayDone, rwkv.replayTotal, text)
        }
        if (rwkvEstimate > CONFIRM_MS && !rwkvConfirmed) {
            val text = context.getString(R.string.kuma3_cal_confirm, minutes(rwkvEstimate))
            return status("confirm", text = text, button = context.getString(R.string.kuma3_cal_start))
        }
        startRwkv()
        return status("rwkv", 0, reviews, context.getString(R.string.kuma3_cal_rwkv, 0, minutes(rwkvEstimate)))
    }

    /** The page's "Start" for a long RWKV replay. */
    private fun Collection.calibrationStart(): ByteArray {
        rwkvConfirmed = true
        return calibrationStatus()
    }

    private fun startFolds(settings: Long) {
        fsrsSince = SystemClock.elapsedRealtime()
        applicationScope.launch(Dispatchers.IO) {
            try {
                // beside the app's collection queue: the backend holds the collection only briefly
                val rows = CollectionManager.getBackend().rebuildFsrsReviewRetrievabilityCache()
                Timber.i("FSRS calibration: test folds made, %d rows", rows)
                AnkiDroidApp
                    .sharedPrefs()
                    .edit()
                    .putLong(FOLDS_KEY, settings)
                    .apply()
                version++
            } catch (e: Exception) {
                Timber.w(e, "FSRS calibration: test folds failed")
            } finally {
                fsrsSince = 0L
            }
        }
    }

    /** RWKV's replay of the whole history, on the backend's build thread with the rebuild notification. */
    private fun Collection.startRwkv() {
        val out = backend.rwkvOfflineCalibration(reviewIds = emptyList(), allReviews = true, checkOnly = false)
        if (out.started) {
            rwkvSince = SystemClock.elapsedRealtime()
            RwkvOffline.watchRebuild(AnkiDroidApp.instance, out.replayTotal)
        } else if (!out.computing && !out.available) {
            rwkvOff = true // RWKV not loaded: FSRS only
        }
    }

    private fun writeConfig(request: JSONObject): ByteArray {
        val prefs = AnkiDroidApp.sharedPrefs()
        val saved = prefs.getString(PREFS_KEY, null)?.let(::JSONObject) ?: JSONObject()
        saved.put(request.getString("key"), request.get("value"))
        prefs.edit().putString(PREFS_KEY, saved.toString()).apply()
        return ByteArray(0)
    }
}
