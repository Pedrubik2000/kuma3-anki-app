// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

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

/** Review Heatmap's greens for past days. */
private val GREENS = intArrayOf(0xFFD6E685.toInt(), 0xFF8CC665.toInt(), 0xFF44A340.toInt(), 0xFF1E6823.toInt())

/** Greys for cards due on future days. */
private val GREYS = intArrayOf(0xFFD4D4D4.toInt(), 0xFFB4B4B4.toInt(), 0xFF8C8C8C.toInt(), 0xFF666666.toInt())
private const val EMPTY = 0x22808080
private const val OUTLINE = 0xFF333333.toInt()

/**
 * A run of days (a calendar year, or one month) as a grid: a column per week (Monday on top), a
 * square per day. A month uses the same layout with larger squares.
 */
@SuppressLint("ViewConstructor")
private class HeatmapGrid(
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

/** One row below the decks: the heatmap with its year buttons, caption and stats line. */
internal class HeatmapFooterAdapter(
    private val deckPicker: DeckPicker,
    /** Receives the time answered today per deck, loaded together with the heatmap. */
    private val onDeckTimes: (Map<Long, Long>) -> Unit,
) : RecyclerView.Adapter<HeatmapFooterAdapter.Holder>() {
    class Holder(
        val frame: FrameLayout,
    ) : RecyclerView.ViewHolder(frame)

    private var data: HeatmapData? = null

    private val prefs = deckPicker.getSharedPreferences("heatmap", Context.MODE_PRIVATE)

    /** One month with large squares instead of the whole year; remembered between runs. */
    private var monthView = prefs.getBoolean("monthView", false)

    /** A day inside the year or month being shown; null for the current one. */
    private var shown: LocalDate? = null

    init {
        // reviews change the picture: reload whenever the deck list comes back to the front
        deckPicker.lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = refresh()
            },
        )
    }

    fun refresh() {
        deckPicker.lifecycleScope.launch {
            try {
                data = HeatmapData.load()
                onDeckTimes(DeckTimes.load())
                @Suppress("NotifyDataSetChanged") // a single row
                notifyDataSetChanged()
            } catch (e: Exception) {
                Timber.w(e, "heatmap unavailable")
            }
        }
    }

    override fun getItemCount() = if (data == null) 0 else 1

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ) = Holder(
        FrameLayout(parent.context).apply {
            layoutParams =
                RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        },
    )

    override fun onBindViewHolder(
        holder: Holder,
        position: Int,
    ) {
        val data = data ?: return
        val context = holder.frame.context
        val density = context.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        val caption =
            TextView(context).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                alpha = 0.75f
                setPadding(0, margin / 2, 0, 0)
            }
        val grid = HeatmapGrid(context) { text -> caption.text = text }.apply { this.data = data }
        lateinit var zoom: TextView

        /** Draw the year or month around [shown] and name it under the grid. */
        fun showPeriod() {
            val day = shown ?: data.today
            if (monthView) {
                val month = YearMonth.from(day)
                grid.show(month.atDay(1), month.lengthOfMonth())
                caption.text = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
            } else {
                grid.show(LocalDate.of(day.year, 1, 1), day.lengthOfYear())
                caption.text = day.year.toString()
            }
            zoom.text = if (monthView) "Year" else "Month"
        }

        /** < and > move one month in month view, one year in year view. */
        fun move(steps: Long) {
            val day = shown ?: data.today
            shown = if (monthView) day.withDayOfMonth(1).plusMonths(steps) else day.withDayOfYear(1).plusYears(steps)
        }

        fun button(
            label: String,
            action: () -> Unit,
        ) = TextView(context).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setBackgroundColor(EMPTY)
            minWidth = (40 * density).toInt()
            setPadding(margin, margin / 3, margin, margin / 3)
            setOnClickListener {
                action()
                showPeriod()
            }
        }
        zoom =
            button("") {
                monthView = !monthView
                prefs.edit().putBoolean("monthView", monthView).apply()
            }
        val buttons =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, margin / 2)
                val spacing =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = (3 * density).toInt()
                        marginEnd = (3 * density).toInt()
                    }
                addView(button("<") { move(-1) }, spacing)
                addView(button("T") { shown = null }, spacing)
                addView(button(">") { move(1) }, spacing)
                addView(zoom, spacing)
            }
        showPeriod()

        holder.frame.removeAllViews()
        holder.frame.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(margin, margin * 2, margin, 0)
                addView(buttons)
                addView(grid)
                addView(caption)
                addView(
                    TextView(context).apply {
                        text = statsLine(data)
                        textSize = 13f
                        gravity = Gravity.CENTER
                        setPadding(0, margin / 2, 0, 0)
                    },
                )
            },
        )
    }

    /** "Daily average: 44 reviews   Days learned: 25%   ..." with the values in green. */
    private fun statsLine(data: HeatmapData): CharSequence {
        val line = SpannableStringBuilder()

        fun add(
            label: String,
            value: String,
            color: Int,
        ) {
            // no-break spaces inside an item, so a narrow screen wraps between items only
            if (line.isNotEmpty()) line.append("     ")
            line.append("$label: ".replace(' ', ' '))
            val start = line.length
            line.append(value.replace(' ', ' '))
            line.setSpan(ForegroundColorSpan(color), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            line.setSpan(StyleSpan(android.graphics.Typeface.BOLD), start, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        fun days(count: Int) = "$count day" + if (count == 1) "" else "s"
        add("Daily average", String.format(Locale.US, "%,d reviews", data.dailyAverage), GREENS[2])
        add("Days learned", "${data.daysLearnedPercent}%", GREENS[if (data.daysLearnedPercent >= 50) 2 else 1])
        add("Longest streak", days(data.longestStreak), GREENS[2])
        add("Current streak", days(data.currentStreak), GREENS[if (data.currentStreak >= 7) 2 else 1])
        return line
    }
}
