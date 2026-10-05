// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.kuma3.Kuma3Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * One row below the decks (DeckPicker puts it in a ConcatAdapter after the deck list): the group
 * board, at most [Leaderboard.maxUsers] rows ([Leaderboard.homeRows]). Empty until signed in and a
 * board was received. Tapping it opens the board dialog.
 */
class LeaderboardFooter(
    private val deckPicker: DeckPicker,
) : RecyclerView.Adapter<LeaderboardFooter.Holder>() {
    class Holder(
        val frame: FrameLayout,
    ) : RecyclerView.ViewHolder(frame)

    companion object {
        private var current = WeakReference<LeaderboardFooter>(null)

        /** Redraw the board below the decks from the last server reply. */
        fun refresh() {
            current.get()?.reload()
        }
    }

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

    init {
        current = WeakReference(this)
        // also whenever the deck list reloads (on resume, e.g. back from Settings > kuma3; after a
        // sync): the board is cached, so this is cheap
        deckPicker.lifecycleScope.launch {
            deckPicker.repeatOnLifecycle(Lifecycle.State.STARTED) {
                deckPicker.viewModel.flowOfDecksReloaded.collect { readBoard() }
            }
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

    /**
     * Read the board in the background (the saved reply is large), then redraw, even when the
     * board is the same: an option (rows, medals, focus) changed.
     */
    private fun reload() {
        deckPicker.lifecycleScope.launch { readBoard(redraw = true) }
    }

    private suspend fun readBoard(redraw: Boolean = false) {
        val fresh =
            try {
                // Settings > kuma3 > Leaderboard
                if (Leaderboard.isSignedIn && Kuma3Settings.leaderboard) Leaderboard.board() else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "leaderboard not read")
                null
            }
        if (fresh == board && !redraw) return
        board = fresh
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
        // The board is built from a saved server reply. If it cannot be drawn, leave the row
        // empty: an exception here would crash the deck list on every start.
        val table =
            try {
                boardTable(context, Leaderboard.homeRows(board), board.rows.size, maxWidth = (list?.width ?: 0) - margin * 2)
            } catch (e: Exception) {
                Timber.w(e, "leaderboard not shown")
                return
            }
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
                        addView(table)
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
                setOnClickListener { showLeaderboard(deckPicker) }
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }
}
