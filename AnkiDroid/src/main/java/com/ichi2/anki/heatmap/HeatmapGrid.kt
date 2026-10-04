// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.heatmap

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Review Heatmap's greens for past days. */
internal val GREENS = intArrayOf(0xFFD6E685.toInt(), 0xFF8CC665.toInt(), 0xFF44A340.toInt(), 0xFF1E6823.toInt())

/** Greys for cards due on future days. */
private val GREYS = intArrayOf(0xFFD4D4D4.toInt(), 0xFFB4B4B4.toInt(), 0xFF8C8C8C.toInt(), 0xFF666666.toInt())
internal const val EMPTY = 0x22808080
private const val OUTLINE = 0xFF333333.toInt()

/**
 * A run of days (a calendar year, or one month) as a grid: a column per week (Monday on top), a
 * square per day. A month uses the same layout with larger squares.
 */
@SuppressLint("ViewConstructor")
internal class HeatmapGrid(
    context: Context,
    private val onDayTapped: (String) -> Unit,
) : View(context) {
    var data: HeatmapData? = null
    private var firstDay: LocalDate = LocalDate.now().withDayOfYear(1)
    private var dayCount = firstDay.lengthOfYear()
    private var selected: LocalDate? = null

    /** Show [days] days starting at [first]. */
    fun show(
        first: LocalDate,
        days: Int,
    ) {
        firstDay = first
        dayCount = days
        selected = null
        requestLayout()
        invalidate()
    }

    private val density = resources.displayMetrics.density

    /** A month: few columns, so the squares can be large. */
    private val zoomed get() = weeks <= 6
    private val gap get() = (if (zoomed) 4 else 2) * density
    private var cell = 14 * density
    private var gridLeft = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
            color = OUTLINE
        }

    /** Days before the first day in its week, so weeks start on Monday. */
    private val lead get() = (firstDay.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    private val weeks get() = (lead + dayCount + 6) / 7

    override fun onMeasure(
        widthSpec: Int,
        heightSpec: Int,
    ) {
        val width = MeasureSpec.getSize(widthSpec)
        cell =
            if (zoomed) {
                // a month: squares of up to 34dp
                ((width - gap * (weeks - 1)) / weeks).coerceIn(4 * density, 34 * density)
            } else {
                // a year: squares of up to 14dp that fit 54 weeks in the available width
                ((width - gap * 53) / 54f).coerceIn(4 * density, 14 * density)
            }
        setMeasuredDimension(width, (cell * 7 + gap * 6).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val data = data ?: return
        gridLeft = (width - (weeks * cell + (weeks - 1) * gap)) / 2f
        var day = firstDay
        for (index in lead until lead + dayCount) {
            val x = gridLeft + (index / 7) * (cell + gap)
            val y = (index % 7) * (cell + gap)
            val offset = ChronoUnit.DAYS.between(data.today, day).toInt()
            fill.color =
                if (offset <= 0) {
                    level(data.past[offset] ?: 0, data.pastAverage)?.let { GREENS[it] } ?: EMPTY
                } else {
                    level(data.future[offset] ?: 0, data.futureAverage)?.let { GREYS[it] } ?: EMPTY
                }
            canvas.drawRect(x, y, x + cell, y + cell, fill)
            if (offset == 0 || day == selected) canvas.drawRect(x, y, x + cell, y + cell, outline)
            day = day.plusDays(1)
        }
    }

    /** Four shades relative to the average day; null for an empty day. */
    private fun level(
        count: Int,
        average: Double,
    ): Int? =
        when {
            count <= 0 -> null
            count < average * 0.5 -> 0
            count < average -> 1
            count < average * 1.5 -> 2
            else -> 3
        }

    @SuppressLint("ClickableViewAccessibility") // the squares are described by the caption they set
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val data = data ?: return false
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP) return false
        val column = ((event.x - gridLeft) / (cell + gap)).toInt()
        val row = (event.y / (cell + gap)).toInt().coerceIn(0, 6)
        val index = column * 7 + row - lead
        if (event.x < gridLeft || index < 0 || index >= dayCount) return true
        val day = firstDay.plusDays(index.toLong())
        selected = day
        invalidate()
        val offset = ChronoUnit.DAYS.between(data.today, day).toInt()
        val date = day.format(DateTimeFormatter.ofPattern("EEEE MMMM d, yyyy", Locale.getDefault()))
        val count = (if (offset <= 0) data.past[offset] else data.future[offset]) ?: 0
        onDayTapped(
            when {
                offset <= 0 && count == 0 -> "No reviews on $date"
                offset <= 0 -> String.format(Locale.US, "%,d review%s on %s", count, if (count == 1) "" else "s", date)
                count == 0 -> "Nothing due on $date"
                else -> String.format(Locale.US, "%,d card%s due on %s", count, if (count == 1) "" else "s", date)
            },
        )
        return true
    }
}
