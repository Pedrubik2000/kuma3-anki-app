// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.heatmap

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.DeckPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One row below the decks (DeckPicker puts it in a ConcatAdapter after the deck list): the
 * heatmap with its year/month buttons, caption and stats line. Reloads whenever the deck list
 * reloads its counts (on resume, after a sync).
 */
class HeatmapFooter(
    private val deckPicker: DeckPicker,
) : RecyclerView.Adapter<HeatmapFooter.Holder>() {
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
        deckPicker.lifecycleScope.launch {
            deckPicker.repeatOnLifecycle(Lifecycle.State.STARTED) {
                deckPicker.viewModel.flowOfDecksReloaded.collect { reload() }
            }
        }
    }

    private suspend fun reload() {
        try {
            val fresh = HeatmapData.load()
            if (fresh.sameAs(data)) return
            data = fresh
            @Suppress("NotifyDataSetChanged") // a single row
            notifyDataSetChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "heatmap unavailable")
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
