// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.heatmap

import com.ichi2.anki.CollectionManager.withCol
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Review heatmap for the whole collection, like the desktop "Review Heatmap" add-on: one calendar
 * year, greens for reviews done, greys for cards due on future days, a stats line below.
 *
 * Days are Anki days (the collection's day rollover), as offsets from today: 0 is today, -1
 * yesterday, 1 tomorrow.
 */
class HeatmapData(
    val today: LocalDate,
    /** Reviews per past day (offset <= 0). */
    val past: Map<Int, Int>,
    /** Cards due per day (offset >= 0; overdue and learning cards count as today). */
    val future: Map<Int, Int>,
) {
    private val studied = past.filter { (offset, count) -> offset <= 0 && count > 0 }

    val pastAverage: Double = studied.values.average().takeUnless { it.isNaN() } ?: 1.0
    val futureAverage: Double =
        future
            .filter { (offset, count) -> offset > 0 && count > 0 }
            .values
            .average()
            .takeUnless { it.isNaN() } ?: 1.0

    /** Reviews per studied day. */
    val dailyAverage: Int = if (studied.isEmpty()) 0 else Math.round(studied.values.sum().toDouble() / studied.size).toInt()

    /** Studied days as a share of the days since the first review. */
    val daysLearnedPercent: Int =
        if (studied.isEmpty()) 0 else Math.round(100.0 * studied.size / (1 - studied.keys.min())).toInt()

    val longestStreak: Int
    val currentStreak: Int

    init {
        var longest = 0
        var run = 0
        if (studied.isNotEmpty()) {
            for (offset in studied.keys.min()..0) {
                run = if (offset in studied) run + 1 else 0
                longest = maxOf(longest, run)
            }
        }
        longestStreak = longest
        // a streak is not broken by today not being studied yet
        var current = 0
        var offset = if (0 in studied) 0 else -1
        while (offset in studied) {
            current++
            offset--
        }
        currentStreak = current
    }

    /** Same days and counts: nothing to redraw. */
    fun sameAs(other: HeatmapData?) = other != null && other.today == today && other.past == past && other.future == future

    companion object {
        suspend fun load(): HeatmapData =
            withCol {
                val dayStart = sched.dayCutoff - 86_400 // start of today's Anki day
                val shift = 86_400L * 100_000 // keeps the division flooring for past days
                val past = HashMap<Int, Int>()
                db.query("select (id / 1000 - ? + ?) / 86400, count() from revlog where ease > 0 group by 1", dayStart, shift).use {
                    while (it.moveToNext()) past[(it.getLong(0) - 100_000).toInt()] = it.getInt(1)
                }
                val today = sched.today
                val future = HashMap<Int, Int>()
                // review and day-learning cards: due is a day number
                db.query("select due, count() from cards where queue in (2, 3) and due <= ? group by due", today + 400).use {
                    while (it.moveToNext()) {
                        val offset = maxOf(0, it.getInt(0) - today)
                        future[offset] = (future[offset] ?: 0) + it.getInt(1)
                    }
                }
                val learning = db.queryScalar("select count() from cards where queue = 1")
                if (learning > 0) future[0] = (future[0] ?: 0) + learning
                HeatmapData(
                    today = Instant.ofEpochSecond(dayStart).atZone(ZoneId.systemDefault()).toLocalDate(),
                    past = past,
                    future = future,
                )
            }
    }
}
