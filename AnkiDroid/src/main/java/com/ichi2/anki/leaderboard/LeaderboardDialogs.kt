// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.launchCatchingTask
import com.ichi2.anki.withProgress

/** Deck list menu > Leaderboard (and a tap on the board below the decks): sign in, or show the board. */
fun showLeaderboard(deckPicker: DeckPicker) = deckPicker.openLeaderboard()

private fun DeckPicker.openLeaderboard() {
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
        LeaderboardFooter.refresh()
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
            LeaderboardFooter.refresh()
            openLeaderboard()
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
                LeaderboardFooter.refresh()
            }
        }.show()
}
