// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * The board as a table, drawn like the add-on's: used below the decks (LeaderboardFooter.kt) and
 * in the board dialog (LeaderboardDialogs.kt).
 */

internal fun boardTitle(board: Leaderboard.Board): String {
    val updated =
        if (board.updatedMillis > 0) {
            " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(board.updatedMillis))
        } else {
            ""
        }
    return board.group.ifEmpty { "Leaderboard" } + updated
}

/**
 * The board, with as many of the add-on's columns as fit in [maxWidth] pixels: all of them; then
 * without "Past 31 days" and the country's name (its flag stays); then also without retention
 * and with short headers; then (phones) tighter, with the streak as days only; then without the
 * time. If even that is too wide, it scrolls sideways.
 */
internal fun boardTable(
    context: Context,
    board: Leaderboard.Board,
    maxWidth: Int,
): View {
    var table = tableWithColumns(context, board, level = 0)
    for (level in 1..4) {
        table.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        if (maxWidth <= 0 || table.measuredWidth <= maxWidth) break
        table = tableWithColumns(context, board, level)
    }
    return HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(table)
    }
}

private fun tableWithColumns(
    context: Context,
    board: Leaderboard.Board,
    level: Int,
): TableLayout {
    val density = context.resources.displayMetrics.density
    val cell = ((if (level >= 3) 4 else 7) * density).toInt()

    fun text(
        value: String,
        row: Leaderboard.Row?,
        medal: Boolean,
        start: Boolean = false,
        icon: Drawable? = null,
    ) = TextView(context).apply {
        text = value
        textSize = if (row == null) 12f else 14f
        maxLines = 1
        setPadding(cell, cell * 3 / 4, cell, cell * 3 / 4)
        gravity = (if (start) Gravity.START else Gravity.END) or Gravity.CENTER_VERTICAL
        if (row == null) alpha = 0.7f
        if (row?.me == true) setTypeface(typeface, Typeface.BOLD)
        if (medal) {
            setTextColor(Color.BLACK)
        } else if (row?.active == false) {
            alpha = 0.55f
        }
        if (icon != null) {
            setCompoundDrawables(icon, null, null, null)
            compoundDrawablePadding = (6 * density).toInt()
        }
    }

    val showMonth = level == 0
    val showCountryName = level == 0
    val showRetention = level <= 1
    val showTime = level <= 3
    val table = TableLayout(context)
    table.addView(
        TableRow(context).apply {
            fun header(
                title: String,
                start: Boolean = false,
            ) = addView(text(title, null, medal = false, start = start))
            header("Rank")
            header("Username", start = true)
            header(if (level < 2) "Reviews today" else "Reviews")
            if (showTime) header(if (level < 2) "Minutes today" else "Time")
            header("Streak")
            if (showMonth) header("Past 31 days")
            if (showRetention) header(if (level == 0) "Retention %" else "Ret. %")
            header(if (showCountryName) "Country" else "", start = true)
        },
    )
    val total = board.rows.size
    board.rows.forEachIndexed { index, row ->
        val medal = row.active && row.rank <= 3
        val (band, letter) = grade(row.rank, total)
        val shield =
            row.league?.let { league ->
                // the league name comes from the server: it must not be part of a format string
                val number = String.format(Locale.US, "%02d", SHIELD_NUMBER.getValue(band))
                picture(context, "league_shields/${league.lowercase(Locale.US)}_$number.png", 22)
            }
        val country = Leaderboard.countries[row.country]
        val secondsPerCard = if (row.reviews > 0) (row.minutes * 60).toLong() / row.reviews else 0
        val minutes = row.minutes.toInt()
        table.addView(
            TableRow(context).apply {
                setBackgroundColor(
                    when {
                        medal && row.rank == 1 -> GOLD
                        medal && row.rank == 2 -> SILVER
                        medal && row.rank == 3 -> BRONZE
                        row.me -> MINE
                        index % 2 == 1 -> ZEBRA
                        else -> Color.TRANSPARENT
                    },
                )
                addView(text("$letter  ${row.rank}", row, medal, icon = shield))
                addView(text(row.name, row, medal, start = true))
                addView(text(String.format(Locale.US, "%,d rev (%dsec)", row.reviews, secondsPerCard), row, medal))
                if (showTime) addView(text("⏱ %02d:%02d".format(minutes / 60, minutes % 60), row, medal))
                addView(text(if (level >= 3) shortStreak(row.streak) else streakText(row.streak), row, medal))
                if (showMonth) {
                    addView(text(String.format(Locale.US, "%,d /d (%,d rev)", row.month / 31, row.month), row, medal))
                }
                if (showRetention) addView(text(String.format(Locale.US, "%.1f%%", row.retention), row, medal))
                // "Country" is the add-on's value for "not set"
                val countryName = country?.name ?: row.country.takeUnless { it == "Country" } ?: ""
                addView(
                    text(
                        if (showCountryName) countryName else "",
                        row,
                        medal,
                        start = true,
                        icon = country?.flag?.let { picture(context, "country/$it", 16) },
                    ),
                )
            },
        )
    }
    return table
}

/** Row colours of the add-on's boards: gold, silver, bronze, then alternating. */
private const val GOLD = 0xFFFFD700.toInt()
private const val SILVER = 0xFFC0C0C0.toInt()
private const val BRONZE = 0xFFCD8C62.toInt()
private const val ZEBRA = 0x14808080
private const val MINE = 0x332196F3

/** League shield picture number for each grade band (the add-on's `ranks_file_number`). */
private val SHIELD_NUMBER = mapOf(10 to 12, 20 to 11, 30 to 10, 40 to 9, 50 to 8, 60 to 7, 70 to 6, 80 to 5, 90 to 2, 100 to 1)

private val pictures = HashMap<String, Bitmap?>()

/** A picture from assets/leaderboard (flags, league shields), sized for a text line. */
private fun picture(
    context: Context,
    path: String,
    heightDp: Int,
): Drawable? {
    val bitmap =
        pictures.getOrPut(path) {
            try {
                context.assets.open("leaderboard/$path").use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
        } ?: return null
    val height = (heightDp * context.resources.displayMetrics.density).toInt()
    val width = height * bitmap.width / maxOf(1, bitmap.height)
    return BitmapDrawable(context.resources, bitmap).apply { setBounds(0, 0, width, height) }
}

/** The streak as days only, for narrow screens: "🌳 40d". */
private fun shortStreak(days: Int): String =
    if (days <= 0) "0" else (if (days >= 31) "🌳 " else "🌱 ") + String.format(Locale.US, "%,dd", days)

/** "1y 8m 9d (617d)", as the add-on writes streaks. */
private fun streakText(days: Int): String {
    if (days <= 0) return "0"
    val years = days / 365
    var rest = days % 365
    var months = 0
    for (length in intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)) {
        if (rest < length) break
        rest -= length
        months++
    }
    val repeated = days.toString().let { it.length >= 3 && it.all { digit -> digit == it[0] } }
    val party =
        when {
            days % 365 == 0 -> "🍰"
            days % 100 == 0 || days in intArrayOf(7, 31, 60) || repeated -> "🎉"
            else -> ""
        }
    val tree = if (days >= 31) "🌳 " else "🌱 "
    val total = String.format(Locale.US, "%,d", days)
    if (years == 0 && months == 0) return "$tree${total}d$party"
    val parts = (if (years > 0) "${years}y " else "") + (if (months > 0) "${months}m " else "") + "${rest}d "
    return "$tree$parts(${total}d)$party"
}

/**
 * The add-on's grade for a position on a board (its `compute_user_rank`): the band
 * (10, 20, ... 100) and its letter.
 */
internal fun grade(
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
