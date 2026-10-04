// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import com.ichi2.anki.libanki.Collection
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The numbers the leaderboard uploads, computed as in the add-on's Stats.py. */
internal class Stats(
    val streak: Int,
    val cards: Int,
    val minutes: Double,
    val month: Int,
    val retention: Double,
    /** Null outside a league season. */
    val league: League?,
)

internal class League(
    val reviews: Int,
    val minutes: Double,
    val retention: Double,
    val daysPercent: Double,
)

/** The current league season, from the server. */
internal class Season(
    val start: LocalDateTime,
    val end: LocalDateTime,
)

/** Start of the leaderboard day containing [now]; its days start at [newDayHour], not Anki's rollover. */
internal fun dayStart(
    now: LocalDateTime,
    newDayHour: Int,
): LocalDateTime {
    val newDay = LocalTime.of(newDayHour, 0)
    val day = if (now.toLocalTime() < newDay) now.toLocalDate().minusDays(1) else now.toLocalDate()
    return day.atTime(newDay)
}

internal fun computeStats(
    col: Collection,
    season: Season?,
    newDayHour: Int,
): Stats {
    val zone = ZoneId.systemDefault()
    val now = LocalDateTime.now()
    val dayStart = dayStart(now, newDayHour)

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

private fun round1(value: Double) = Math.round(value * 10) / 10.0
