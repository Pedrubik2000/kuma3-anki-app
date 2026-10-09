// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/*
 * The board, drawn like the add-on's: a table below the decks (LeaderboardFooter.kt, a few rows)
 * and a scrolling list in the board dialog (LeaderboardDialogs.kt, the whole group, which can have
 * over a thousand members: only the rows on screen are drawn).
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
 * [rows] of a board of [total] members as a table, with as many of the add-on's columns as fit in
 * [maxWidth] pixels: all of them; then without "Past 31 days" and the country's name (its flag
 * stays); then also without retention and with short headers; then (phones) tighter, with the
 * streak as days only; then without the time. If even that is too wide, it scrolls sideways.
 * Every row is drawn: keep [rows] short.
 */
internal fun boardTable(
    context: Context,
    rows: List<Leaderboard.Row>,
    total: Int,
    maxWidth: Int,
): View {
    val (table, _) = fittingTable(context, rows, total, maxWidth)
    // a scroller takes every tap for itself (the board below the decks opens the dialog on a
    // tap): only use one when the table really is too wide
    if (maxWidth <= 0 || table.measuredWidth <= maxWidth) return table
    return HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(table)
    }
}

/**
 * The whole [board] as a list that draws only the rows on screen, at most [maxHeight] pixels
 * high, opened at the user's row. The columns are sized from a sample (the top rows, the user and
 * the longest names); a longer name elsewhere is shortened with "…".
 */
internal fun boardList(
    context: Context,
    board: Leaderboard.Board,
    maxWidth: Int,
    maxHeight: Int,
): View {
    val rows = board.rows
    val total = rows.size
    val withMedals = Leaderboard.showMedals
    val sample =
        (rows.take(30) + rows.filter { it.me } + rows.sortedByDescending { it.displayName(withMedals).length }.take(10))
            .distinct()
    val (table, level) = fittingTable(context, sample, total, maxWidth)
    val header = table.getChildAt(0) as TableRow
    val widths = IntArray(header.childCount) { header.getChildAt(it).measuredWidth }
    val rowHeight = (table.getChildAt(1)?.measuredHeight ?: header.measuredHeight).coerceAtLeast(1)

    fun line(cells: List<TextView>) =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            cells.forEachIndexed { i, cell ->
                addView(cell, LinearLayout.LayoutParams(widths.getOrElse(i) { ViewGroup.LayoutParams.WRAP_CONTENT }, rowHeight))
            }
        }

    val list =
        RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter =
                object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = rows.size

                    override fun onCreateViewHolder(
                        parent: ViewGroup,
                        viewType: Int,
                    ) = object : RecyclerView.ViewHolder(
                        android.widget.FrameLayout(parent.context).apply {
                            layoutParams =
                                RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        },
                    ) {}

                    override fun onBindViewHolder(
                        holder: RecyclerView.ViewHolder,
                        position: Int,
                    ) {
                        val frame = holder.itemView as android.widget.FrameLayout
                        val row = rows[position]
                        frame.removeAllViews()
                        frame.addView(line(cells(context, row, total, level)).apply { setBackgroundColor(rowColor(row)) })
                    }
                }
            val me = rows.indexOfFirst { it.me }
            if (me > 0) (layoutManager as LinearLayoutManager).scrollToPositionWithOffset(me, rowHeight * 3)
        }
    val height = minOf(maxHeight, rowHeight * rows.size)
    return HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(line(cells(context, null, total, level)))
                addView(list, LinearLayout.LayoutParams(widths.sum(), height))
            },
        )
    }
}

/** The table of [rows] with the most columns that fit in [maxWidth] (measured), and its level. */
private fun fittingTable(
    context: Context,
    rows: List<Leaderboard.Row>,
    total: Int,
    maxWidth: Int,
): Pair<TableLayout, Int> {
    var level = 0
    var table = tableWithColumns(context, rows, total, level)
    while (true) {
        table.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        if (maxWidth <= 0 || table.measuredWidth <= maxWidth || level == 4) break
        level++
        table = tableWithColumns(context, rows, total, level)
    }
    return table to level
}

private fun tableWithColumns(
    context: Context,
    rows: List<Leaderboard.Row>,
    total: Int,
    level: Int,
): TableLayout {
    val table = TableLayout(context)
    table.addView(TableRow(context).apply { cells(context, null, total, level).forEach { addView(it) } })
    for (row in rows) {
        table.addView(
            TableRow(context).apply {
                setBackgroundColor(rowColor(row))
                cells(context, row, total, level).forEach { addView(it) }
            },
        )
    }
    return table
}

/** Gold, silver and bronze for the first three active members, the user's colour, then stripes. */
private fun rowColor(row: Leaderboard.Row): Int {
    val medal = row.active && row.rank <= 3
    return when {
        medal && row.rank == 1 -> GOLD
        medal && row.rank == 2 -> SILVER
        medal && row.rank == 3 -> BRONZE
        row.me -> MINE
        row.rank % 2 == 0 -> ZEBRA
        else -> Color.TRANSPARENT
    }
}

/** The cells of one row ([row] null: the header) at a column [level] (see [boardTable]). */
private fun cells(
    context: Context,
    row: Leaderboard.Row?,
    total: Int,
    level: Int,
): List<TextView> {
    val density = context.resources.displayMetrics.density
    val cell = ((if (level >= 3) 4 else 7) * density).toInt()
    val medal = row != null && row.active && row.rank <= 3

    fun text(
        value: String,
        start: Boolean = false,
        icon: Drawable? = null,
    ) = TextView(context).apply {
        text = value
        textSize = if (row == null) 12f else 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
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
    if (row == null) {
        return listOfNotNull(
            text("Rank"),
            text("Username", start = true),
            text(if (level < 2) "Reviews today" else "Reviews"),
            text(if (level < 2) "Minutes today" else "Time").takeIf { showTime },
            text("Streak"),
            text("Past 31 days").takeIf { showMonth },
            text(if (level == 0) "Retention %" else "Ret. %").takeIf { showRetention },
            text(if (showCountryName) "Country" else "", start = true),
        )
    }
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
    // "Country" is the add-on's value for "not set"
    val countryName = country?.name ?: row.country.takeUnless { it == "Country" } ?: ""
    return listOfNotNull(
        text("$letter  ${row.rank}", icon = shield),
        text(row.displayName(Leaderboard.showMedals), start = true),
        text(String.format(Locale.US, "%,d rev (%dsec)", row.reviews, secondsPerCard)),
        text("⏱ %02d:%02d".format(minutes / 60, minutes % 60)).takeIf { showTime },
        text(if (level >= 3) shortStreak(row.streak) else streakText(row.streak)),
        text(String.format(Locale.US, "%,d /d (%,d rev)", row.month / 31, row.month)).takeIf { showMonth },
        text(String.format(Locale.US, "%.1f%%", row.retention)).takeIf { showRetention },
        text(
            if (showCountryName) countryName else "",
            start = true,
            icon = country?.flag?.let { picture(context, "country/$it", 16) },
        ),
    )
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
