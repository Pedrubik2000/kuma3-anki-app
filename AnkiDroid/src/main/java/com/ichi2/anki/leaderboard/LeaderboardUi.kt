// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.withProgress
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.util.Date
import java.util.Locale

object LeaderboardUi {
    private var footer = WeakReference<LeaderboardFooterAdapter>(null)

    /** Deck list menu > Leaderboard. */
    fun show(deckPicker: DeckPicker) = deckPicker.showLeaderboard()

    /** The deck list's adapter, followed by the board (one extra row below the last deck). */
    fun withFooter(
        deckPicker: DeckPicker,
        decks: RecyclerView.Adapter<*>,
    ): RecyclerView.Adapter<*> {
        val adapter = LeaderboardFooterAdapter { show(deckPicker) }
        footer = WeakReference(adapter)
        adapter.refresh()
        val heatmapAdapter =
            HeatmapFooterAdapter(deckPicker) { times ->
                (decks as? com.ichi2.anki.widgets.DeckAdapter)?.timesToday = times
            }
        heatmap = WeakReference(heatmapAdapter)
        // decks, then the review heatmap, then the board
        return ConcatAdapter(decks, heatmapAdapter, adapter)
    }

    private var heatmap = WeakReference<HeatmapFooterAdapter>(null)

    /** Redraw the board below the decks from the last server reply (and the heatmap, after a sync). */
    fun refreshFooter() {
        footer.get()?.refresh()
        heatmap.get()?.refresh()
    }
}

/** One row below the decks: the group board. Empty until signed in and a board was received. */
private class LeaderboardFooterAdapter(
    private val onTap: () -> Unit,
) : RecyclerView.Adapter<LeaderboardFooterAdapter.Holder>() {
    class Holder(
        val frame: FrameLayout,
    ) : RecyclerView.ViewHolder(frame)

    private var board: Leaderboard.Board? = null
    private var list: RecyclerView? = null
    private var listWidth = 0

    /** Redraw when the deck list's width changes (rotation, the divider between the panes). */
    private val onListLayout =
        View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view.width != listWidth) {
                listWidth = view.width
                @Suppress("NotifyDataSetChanged") // a single row
                view.post { notifyDataSetChanged() }
            }
        }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        list = recyclerView
        listWidth = recyclerView.width
        recyclerView.addOnLayoutChangeListener(onListLayout)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        recyclerView.removeOnLayoutChangeListener(onListLayout)
        list = null
    }

    fun refresh() {
        board = if (Leaderboard.isSignedIn) Leaderboard.cachedBoard() else null
        @Suppress("NotifyDataSetChanged") // a single row
        notifyDataSetChanged()
    }

    override fun getItemCount() = if (board == null) 0 else 1

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
        val board = board ?: return
        val context = holder.frame.context
        val density = context.resources.displayMetrics.density
        val margin = (12 * density).toInt()
        holder.frame.removeAllViews()
        holder.frame.addView(
            // centred in the deck list, like the "Studied N cards" line; the caption starts at
            // the table's left edge
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(margin, margin * 2, margin, margin)
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            TextView(context).apply {
                                text = boardTitle(board)
                                textSize = 13f
                                alpha = 0.7f
                                setPadding((8 * density).toInt(), 0, 0, 0)
                            },
                        )
                        addView(boardTable(context, board, maxWidth = (list?.width ?: 0) - margin * 2))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                setOnClickListener { onTap() }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
}

private fun boardTitle(board: Leaderboard.Board): String {
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
private fun boardTable(
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
        val (band, letter) = Leaderboard.grade(row.rank, total)
        val shield =
            row.league?.let { league ->
                picture(
                    context,
                    "league_shields/${league.lowercase(Locale.US)}_%02d.png".format(SHIELD_NUMBER.getValue(band)),
                    22,
                )
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

private fun DeckPicker.showLeaderboard() {
    if (!Leaderboard.isSignedIn) {
        showLeaderboardSignIn()
        return
    }
    val board = Leaderboard.cachedBoard()
    if (board == null) refreshLeaderboard() else showBoard(board)
}

private fun DeckPicker.refreshLeaderboard() {
    launchCatchingTask {
        val board = withProgress("Leaderboard…") { Leaderboard.sync() }
        LeaderboardUi.refreshFooter()
        showBoard(board)
    }
}

private fun DeckPicker.showLeaderboardSignIn() {
    val padding = (20 * resources.displayMetrics.density).toInt()
    val username = EditText(this).apply { hint = "Leaderboard username" }
    val password =
        EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
    val form =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(username)
            addView(password)
        }
    AlertDialog
        .Builder(this)
        .setTitle("Anki Leaderboard")
        .setMessage(
            "Sign in with the account of the Leaderboard add-on. Your review counts are then " +
                "uploaded after each sync.",
        ).setView(form)
        .setPositiveButton("Sign in") { _, _ ->
            launchCatchingTask {
                withProgress("Signing in…") {
                    Leaderboard.signIn(username.text.toString().trim(), password.text.toString())
                }
                if (Leaderboard.groups.size > 1) chooseLeaderboardGroup() else refreshLeaderboard()
            }
        }.setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun DeckPicker.chooseLeaderboardGroup() {
    val groups = Leaderboard.groups
    if (groups.isEmpty()) {
        refreshLeaderboard()
        return
    }
    AlertDialog
        .Builder(this)
        .setTitle("Group to show")
        .setItems(groups.toTypedArray()) { _, which ->
            Leaderboard.group = groups[which]
            LeaderboardUi.refreshFooter()
            showLeaderboard()
        }.show()
}

private fun DeckPicker.showBoard(board: Leaderboard.Board) {
    val padding = (8 * resources.displayMetrics.density).toInt()
    AlertDialog
        .Builder(this)
        .setTitle(boardTitle(board))
        .setView(
            ScrollView(this).apply {
                setPadding(padding, padding, padding, 0)
                // dialogs are about four fifths of the screen wide
                addView(boardTable(context, board, maxWidth = resources.displayMetrics.widthPixels * 3 / 4))
            },
        ).setPositiveButton("Refresh") { _, _ -> refreshLeaderboard() }
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton("Options") { _, _ -> showLeaderboardOptions() }
        .show()
}

private fun DeckPicker.showLeaderboardOptions() {
    AlertDialog
        .Builder(this)
        .setTitle("Signed in as ${Leaderboard.username}")
        .setItems(arrayOf("Change group", "Sign out")) { _, which ->
            if (which == 0) {
                chooseLeaderboardGroup()
            } else {
                Leaderboard.signOut()
                LeaderboardUi.refreshFooter()
            }
        }.show()
}
