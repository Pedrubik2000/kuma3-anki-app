// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.kuma3

import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.common.time.TimeManager

/**
 * Keeps the deck list footers' results (forecast, time per deck, heatmap) until what they read
 * changes, so tapping between decks doesn't recompute them. The collection's own modified time
 * can't be the key: selecting a deck saves the current deck and bumps it.
 */
object Kuma3Cache {
    private class Entry(
        val key: List<Long>,
        val madeAt: Long,
        val value: Any?,
    )

    private val entries = HashMap<String, Entry>()

    /** Newest review, newest card change (reviews, sync, edits), newest options change, and the day. */
    private suspend fun fingerprint(): List<Long> =
        withCol {
            db
                .query(
                    "select (select coalesce(max(id), 0) from revlog), (select coalesce(max(mod), 0) from cards), " +
                        "(select coalesce(max(mtime_secs), 0) from deck_config)",
                ).use {
                    it.moveToFirst()
                    listOf(it.getLong(0), it.getLong(1), it.getLong(2), sched.today.toLong())
                }
        }

    /** [load]'s last result while the fingerprint is the same and it is younger than [maxAgeMs]. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(
        name: String,
        maxAgeMs: Long = Long.MAX_VALUE,
        load: suspend () -> T,
    ): T {
        val key = fingerprint()
        val now = TimeManager.time.intTimeMS()
        synchronized(entries) { entries[name] }
            ?.takeIf { it.key == key && now - it.madeAt < maxAgeMs }
            ?.let { return it.value as T }
        val value = load()
        synchronized(entries) { entries[name] = Entry(key, now, value) }
        return value
    }
}
