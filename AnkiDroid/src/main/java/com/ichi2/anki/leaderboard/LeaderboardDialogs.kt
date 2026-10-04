// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.leaderboard

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
    launchCatchingTask {
        val board = Leaderboard.board()
        if (board == null) refreshLeaderboard() else showBoard(board)
    }
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
                "uploaded after each sync and when you leave the reviewer.",
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
    val metrics = resources.displayMetrics
    val padding = (8 * metrics.density).toInt()
    AlertDialog
        .Builder(this)
        .setTitle(boardTitle(board))
        .setView(
            FrameLayout(this).apply {
                setPadding(padding, padding, padding, 0)
                // dialogs are about four fifths of the screen wide; the list scrolls inside
                addView(boardList(context, board, maxWidth = metrics.widthPixels * 3 / 4, maxHeight = metrics.heightPixels * 3 / 5))
            },
        ).setPositiveButton("Refresh") { _, _ -> refreshLeaderboard() }
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton("Options") { _, _ -> showLeaderboardOptions() }
        .show()
}

/** The account and the add-on's options (Leaderboard > Config on the desktop). */
private fun DeckPicker.showLeaderboardOptions() {
    fun onOff(value: Boolean) = if (value) "on" else "off"
    val items =
        arrayOf(
            "Change group",
            "Users on the home screen: ${Leaderboard.maxUsers}",
            "Focus on me: ${onOff(Leaderboard.focusOnUser)}",
            "League medals next to names: ${onOff(Leaderboard.showMedals)}",
            "Upload when leaving reviews: ${onOff(Leaderboard.uploadAfterReviews)}",
            "Sign out",
        )
    AlertDialog
        .Builder(this)
        .setTitle("Signed in as ${Leaderboard.username}")
        .setItems(items) { _, which ->
            when (which) {
                0 -> chooseLeaderboardGroup()
                1 -> askLeaderboardMaxUsers()
                2 -> Leaderboard.focusOnUser = !Leaderboard.focusOnUser
                3 -> Leaderboard.showMedals = !Leaderboard.showMedals
                4 -> Leaderboard.uploadAfterReviews = !Leaderboard.uploadAfterReviews
                else -> Leaderboard.signOut()
            }
            LeaderboardFooter.refresh()
            // after a switch, show the options again with its new value
            if (which in 2..4) showLeaderboardOptions()
        }.show()
}

private fun DeckPicker.askLeaderboardMaxUsers() {
    val padding = (20 * resources.displayMetrics.density).toInt()
    val number =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Leaderboard.maxUsers.toString())
            selectAll()
        }
    AlertDialog
        .Builder(this)
        .setTitle("Users on the home screen")
        .setMessage("How many rows the board below the decks shows (1 to ${Leaderboard.MAX_HOME_USERS}).")
        .setView(
            FrameLayout(this).apply {
                setPadding(padding, 0, padding, 0)
                addView(number)
            },
        ).setPositiveButton(android.R.string.ok) { _, _ ->
            number.text
                .toString()
                .toIntOrNull()
                ?.let { Leaderboard.maxUsers = it }
            LeaderboardFooter.refresh()
            showLeaderboardOptions()
        }.setNegativeButton(android.R.string.cancel) { _, _ -> showLeaderboardOptions() }
        .show()
}
