// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import com.ichi2.anki.CollectionManager.withCol

/** Time spent answering cards today, per deck, shown on the deck list's rows. */
object DeckTimes {
    /**
     * Milliseconds answered today (since Anki's day rollover) by deck id. A card counts for its
     * home deck, and every deck includes its sub-decks, like the due counts.
     */
    suspend fun load(): Map<Long, Long> =
        withCol {
            val dayStartMillis = (sched.dayCutoff - 86_400) * 1000
            val own = HashMap<Long, Long>()
            db
                .query(
                    "select case when c.odid != 0 then c.odid else c.did end, sum(r.time) " +
                        "from revlog r join cards c on c.id = r.cid where r.id >= ? group by 1",
                    dayStartMillis,
                ).use {
                    while (it.moveToNext()) own[it.getLong(0)] = it.getLong(1)
                }
            if (own.isEmpty()) return@withCol emptyMap()

            val names = decks.allNamesAndIds(skipEmptyDefault = false, includeFiltered = true)
            val idByName = names.associate { it.name to it.id }
            val nameById = names.associate { it.id to it.name }
            val total = HashMap<Long, Long>()
            for ((deckId, millis) in own) {
                var name = nameById[deckId] ?: continue
                while (true) {
                    idByName[name]?.let { id -> total[id] = (total[id] ?: 0) + millis }
                    if (!name.contains("::")) break
                    name = name.substringBeforeLast("::")
                }
            }
            total
        }

    /** "40s", "6m", "1h 5m"; empty for no time. */
    fun format(millis: Long): String {
        if (millis <= 0) return ""
        val seconds = Math.round(millis / 1000.0)
        if (seconds < 60) return "${maxOf(1, seconds)}s"
        val minutes = Math.round(seconds / 60.0)
        return if (minutes < 60) "${minutes}m" else "${minutes / 60}h ${minutes % 60}m"
    }
}
