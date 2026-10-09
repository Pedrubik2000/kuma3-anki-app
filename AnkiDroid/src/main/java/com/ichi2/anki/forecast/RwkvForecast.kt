// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.forecast

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.recyclerview.widget.RecyclerView
import anki.scheduler.RwkvOfflineForecastResponse
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.browser.toIntent
import com.ichi2.anki.common.destinations.BrowserDestination
import com.ichi2.anki.kuma3.Kuma3Cache
import com.ichi2.anki.kuma3.Kuma3Settings
import com.ichi2.anki.utils.ext.launchCollectionInLifecycleScope
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * The desktop "RWKV forecast" add-on (anki-rwkv-forecast) on the deck list: under "Studied …
 * today", how many review cards are due / near the line / safe now, and how many more become due
 * in 1 h, in 3 h and by tomorrow's rollover. Every answer moves every card's recall, so the
 * numbers mean "if you stop reviewing now"; they are recomputed whenever the deck list reloads.
 * Tapping a count opens those cards in the browser. The deck options page shows the same per
 * preset (`RwkvForecast.svelte` in the core repo). Recall comes from the backend
 * (`rwkv_offline_forecast` in `rslib/src/scheduler/rwkv/offline.rs`).
 *
 * Also what makes the deck list's number differ from "due": cards still waiting for the repeat
 * spacing (minimum intervening reviews) are left out, and "minimum reviews per day" adds the
 * lowest-recall cards that are not due. due − waiting + minimum = the deck list's review count
 * (each card as its own deck's row counts it).
 *
 * Below the heatmap, a dot graph ([GraphFooter], [GraphView], Settings > kuma3 > RWKV forecast graph): one dot
 * per review card at its recall now, in the counts' colours, with a red line at each desired
 * retention.
 */
object RwkvForecast {
    /** Seconds from now: now, 1 h, 3 h, and -1 = the next day rollover ("tomorrow"). */
    private val OFFSETS = listOf(0L, 3600L, 3 * 3600L, -1L)

    /** "near" = recall within this of the card's target (the add-on's `near_margin`). */
    private const val NEAR_MARGIN = 0.03f

    private val DUE = Color.rgb(0xE5, 0x39, 0x35)
    private val NEAR = Color.rgb(0xFB, 0x8C, 0x00)
    private val SAFE = Color.rgb(0x43, 0xA0, 0x47)
    private val MINIMUM = Color.rgb(0x1E, 0x88, 0xE5)

    private var forecast: RwkvOfflineForecastResponse? = null
    private var graph: GraphFooter? = null

    /** Recompute whenever the deck list reloads its counts (on resume, after reviews or a sync). */
    fun attach(deckPicker: DeckPicker) {
        deckPicker.deckPickerBinding.reviewSummaryTextView.movementMethod = LinkMovementMethod.getInstance()
        with(deckPicker) {
            viewModel.flowOfDecksReloaded.launchCollectionInLifecycleScope {
                try {
                    // Settings > kuma3 > RWKV forecast
                    // recall moves with the clock, so at most a minute old
                    forecast = if (Kuma3Settings.rwkvForecast) Kuma3Cache.get("forecast", 60_000) { load() } else null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "RWKV forecast unavailable")
                    forecast = null
                }
                show(deckPicker, viewModel.flowOfStudiedTodayStats.value)
            }
        }
    }

    private suspend fun load(): RwkvOfflineForecastResponse? =
        withCol { backend.rwkvOfflineForecast(search = "", offsetsSecs = OFFSETS) }
            .takeIf { it.available && it.cardsCount > 0 }

    /** The summary line: "Studied … today", then the forecast when there is one. */
    fun show(
        deckPicker: DeckPicker,
        studiedToday: String,
    ) {
        val text = SpannableStringBuilder(studiedToday)
        forecast?.let { text.append("\n").append(forecastText(deckPicker, it)) }
        deckPicker.deckPickerBinding.reviewSummaryTextView.text = text
        graph?.show(forecast?.cardsList?.takeIf { Kuma3Settings.rwkvForecastGraph })
    }

    /**
     * `due 25 (20 + 5 waiting) | minimum 49 | near 13 | safe 316`
     * `+1 in 1 h · +4 in 3 h · 48 by tomorrow (81 with minimum) · if you stop reviewing now`
     */
    private fun forecastText(
        deckPicker: DeckPicker,
        forecast: RwkvOfflineForecastResponse,
    ): CharSequence {
        val cards = forecast.cardsList

        fun dueAt(i: Int) = cards.filter { it.getRecall(i) < it.targetRetention }.map { it.cardId }
        val now = cards.groupBy(::kindNow)
        val dueNow = dueAt(0).toSet()
        val out = SpannableStringBuilder()

        fun count(
            label: String,
            ids: List<Long>,
            color: Int? = null,
        ) {
            val start = out.length
            out.append(label)
            if (color != null) out.setSpan(ForegroundColorSpan(color), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (ids.isEmpty()) return
            val search = "cid:" + ids.joinToString(",")
            out.setSpan(
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        deckPicker.startActivity(BrowserDestination.Search(query = search, allDecks = true).toIntent(deckPicker))
                    }

                    override fun updateDrawState(ds: TextPaint) {
                        ds.isUnderlineText = false
                    }
                },
                start,
                out.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        val due = now[DUE].orEmpty().map { it.cardId }
        val near = now[NEAR].orEmpty().map { it.cardId }
        val safe = now[SAFE].orEmpty().map { it.cardId }
        val minimum = now[MINIMUM].orEmpty().map { it.cardId }
        val waiting = cards.filter { it.waiting }.map { it.cardId }
        count("due ${due.size}", due, DUE)
        if (waiting.isNotEmpty()) {
            // reviewable now + waiting for the repeat spacing
            val waitingIds = waiting.toSet()
            val ready = due.filter { it !in waitingIds }
            out.append(" (")
            count("${ready.size}", ready)
            out.append(" + ")
            count("${waiting.size} waiting", waiting)
            out.append(")")
        }
        if (minimum.isNotEmpty()) {
            out.append(" | ")
            count("minimum ${minimum.size}", minimum, MINIMUM)
        }
        out.append(" | ")
        count("near ${near.size}", near, NEAR)
        out.append(" | ")
        count("safe ${safe.size}", safe, SAFE)
        out.append("\n")
        val in1h = dueAt(1).filter { it !in dueNow }
        val in3h = dueAt(2).filter { it !in dueNow }
        val tomorrow = dueAt(3)
        count("+${in1h.size} in 1 h", in1h)
        out.append(" · ")
        count("+${in3h.size} in 3 h", in3h)
        out.append(" · ")
        count("${tomorrow.size} by tomorrow", tomorrow)
        if (forecast.hasRolloverWithMinimum() && forecast.rolloverWithMinimum > tomorrow.size) {
            out.append(" (${forecast.rolloverWithMinimum} with minimum)")
        }
        val note = out.length
        out.append(" · if you stop reviewing now")
        out.setSpan(RelativeSizeSpan(0.85f), note, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return out
    }

    /**
     * The graph's row below the heatmap (DeckPicker adds it to the deck list's ConcatAdapter). Let go when the
     * deck list is destroyed: the adapter holds its RecyclerView, and with it the whole activity.
     */
    fun graphFooter(deckPicker: DeckPicker): RecyclerView.Adapter<*> {
        val footer = GraphFooter()
        graph = footer
        deckPicker.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_DESTROY && graph === footer) graph = null
            },
        )
        return footer
    }

    /** One row: a caption and the dot graph; no row without a forecast or with the switch off. */
    private class GraphFooter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var cards: List<RwkvOfflineForecastResponse.Card>? = null

        fun show(cards: List<RwkvOfflineForecastResponse.Card>?) {
            if (cards == this.cards) return
            this.cards = cards
            @Suppress("NotifyDataSetChanged") // a single row
            notifyDataSetChanged()
        }

        override fun getItemCount() = if (cards.isNullOrEmpty()) 0 else 1

        override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int,
        ): RecyclerView.ViewHolder {
            val density = parent.resources.displayMetrics.density
            val layout =
                LinearLayout(parent.context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val pad = (16 * density).toInt()
                    setPadding(pad, pad / 2, pad, pad)
                    addView(
                        TextView(context).apply {
                            text = "RWKV recall now · red line = desired retention"
                            textSize = 12f
                            alpha = 0.7f
                            gravity = android.view.Gravity.CENTER
                        },
                    )
                    addView(
                        GraphView(context),
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                }
            return object : RecyclerView.ViewHolder(layout) {}
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
        ) {
            ((holder.itemView as ViewGroup).getChildAt(1) as GraphView).cards = cards.orEmpty()
        }
    }

    /**
     * The deck options page's dot bar for the whole collection: one dot per card at its recall
     * now (axis 60–100 %), stacked where they meet, a red line at each desired retention.
     */
    private class GraphView(
        context: android.content.Context,
    ) : View(context) {
        var cards: List<RwkvOfflineForecastResponse.Card> = emptyList()
            set(value) {
                field = value
                laidOutFor = 0
                requestLayout()
                invalidate()
            }
        private val density = resources.displayMetrics.density
        private val dot = 5 * density
        private val axis = 12 * density // tick labels
        private val pad = dot // keeps the end dots inside
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 10 * density
                textAlign = Paint.Align.CENTER
            }
        private var laidOutFor = 0 // the width the dots were placed for
        private var dots: List<Triple<Float, Float, Int>> = emptyList() // x, height above the axis, colour
        private var graphHeight = 0f

        private fun x(recall: Float) = pad + (laidOutFor - 2 * pad) * ((recall - AXIS_MIN) / (1 - AXIS_MIN)).coerceIn(0f, 1f)

        private fun layOut(width: Int) {
            if (width == laidOutFor) return
            laidOutFor = width
            val sorted = cards.sortedBy { it.getRecall(0) }
            val bins = sorted.map { ((x(it.getRecall(0)) - pad) / dot).toInt() }
            val tallest =
                bins
                    .groupingBy { it }
                    .eachCount()
                    .values
                    .maxOrNull() ?: 1
            val step = minOf(dot * 1.2f, MAX_HEIGHT_DP * density / maxOf(1, tallest))
            val stacked = HashMap<Int, Int>()
            dots =
                sorted.mapIndexed { i, card ->
                    val n = stacked.merge(bins[i], 1, Int::plus)!!
                    Triple(x(card.getRecall(0)), (n - 1) * step + dot / 2, kindNow(card))
                }
            graphHeight = (tallest - 1) * step + dot + 4 * density
        }

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            if (width == 0 || cards.isEmpty()) return setMeasuredDimension(width, 0)
            layOut(width)
            setMeasuredDimension(width, (graphHeight + axis).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || cards.isEmpty()) return
            layOut(width)
            val base = graphHeight
            paint.color = Color.GRAY
            paint.strokeWidth = 0.5f * density
            canvas.drawLine(pad, base, width - pad, base, paint)
            for ((cx, h, colour) in dots) {
                paint.color = colour
                canvas.drawCircle(cx, base - h, dot / 2, paint)
            }
            paint.color = DUE
            paint.strokeWidth = 1.5f * density
            for (t in cards.map { it.targetRetention }.distinct()) canvas.drawLine(x(t), 0f, x(t), base, paint)
            for (k in 0..4) {
                val v = AXIS_MIN + (1 - AXIS_MIN) * k / 4
                canvas.drawText("${Math.round(v * 100)}%", x(v), base + axis - 2 * dot / 5, text)
            }
        }

        companion object {
            const val AXIS_MIN = 0.6f
            const val MAX_HEIGHT_DP = 60
        }
    }

    /** The card's colour now: due, minimum (added to reach minimum reviews per day), near or safe. */
    private fun kindNow(card: RwkvOfflineForecastResponse.Card): Int {
        val r = card.getRecall(0)
        return when {
            r < card.targetRetention -> DUE
            card.minimumToday -> MINIMUM
            r < card.targetRetention + NEAR_MARGIN -> NEAR
            else -> SAFE
        }
    }
}
