// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kuma3

import android.app.Activity
import android.content.Intent
import com.ichi2.anki.CollectionManager
import com.ichi2.anki.common.destinations.BrowserDestination
import com.ichi2.anki.common.destinations.DeckOptionsDestination
import com.ichi2.anki.common.destinations.NoteEditorDestination
import com.ichi2.anki.common.destinations.PreferencesDestination
import com.ichi2.anki.common.destinations.StatisticsDestination
import com.ichi2.anki.common.destinations.navigate
import com.ichi2.anki.common.ui.TransitionDirection
import com.ichi2.anki.notetype.ManageNotetypes
import com.ichi2.anki.preferences.PreferencesActivity
import timber.log.Timber

/**
 * Opens one of kuma3's own screens for another app (kuma3 Skins' side menu), so those screens aren't rebuilt there:
 * `Intent("com.ichi2.anki.kuma3.OPEN_SCREEN").setPackage(<kuma3>).putExtra("screen", …)`, handled by [com.ichi2.anki.IntentHandler].
 *
 * screen = `stats` | `browser` (optional `search`, `deck_id`) | `add` (optional `deck_id`) | `edit` (`note_id` and the
 * card's template `ord`: the note editor on that card) | `deck_options` (optional
 * `deck_id`, else the current deck) | `settings` | `kuma3_settings` | `note_types`. It only opens a screen: nothing
 * goes back to the caller, and Back returns to it.
 */
object Kuma3Screens {
    const val ACTION = "com.ichi2.anki.kuma3.OPEN_SCREEN"

    /** The manifest's permission-guarded alias: an explicit intent straight to IntentHandler is ignored. */
    const val ENTRY = "com.ichi2.anki.kuma3.Kuma3ScreenEntry"

    fun open(
        activity: Activity,
        intent: Intent,
    ) = with(activity) {
        val screen = intent.getStringExtra("screen")
        val deckId = intent.getLongExtra("deck_id", 0).takeIf { it != 0L }
        Timber.i("kuma3: opening screen %s (deck %s)", screen, deckId)
        when (screen) {
            "stats" -> navigate(StatisticsDestination)
            "browser" -> {
                val search = intent.getStringExtra("search")
                navigate(
                    when {
                        search != null -> BrowserDestination.Search(query = search, allDecks = deckId == null)
                        deckId != null -> BrowserDestination.ToDeck(deckId)
                        else -> BrowserDestination.Open
                    },
                )
            }
            "add" -> navigate(NoteEditorDestination.AddNote(deckId))
            "edit" -> {
                val col = CollectionManager.getColUnsafe()
                val noteId = intent.getLongExtra("note_id", 0)
                val ord = intent.getIntExtra("ord", 0)
                val card = col.cardIdsOfNote(nid = noteId).map { col.getCard(it) }.firstOrNull { it.ord == ord }
                if (card != null) {
                    navigate(NoteEditorDestination.EditSelection(listOf(card.id), TransitionDirection.DEFAULT))
                } else {
                    Timber.w("kuma3: no card %d of note %d", ord, noteId)
                }
            }
            "deck_options" -> {
                val decks = CollectionManager.getColUnsafe().decks
                val did = deckId ?: decks.getCurrentId()
                navigate(DeckOptionsDestination(deckId = did, isFiltered = decks.isFiltered(did)))
            }
            "settings" -> navigate(PreferencesDestination.Root)
            "kuma3_settings" -> startActivity(PreferencesActivity.getIntent(this, Kuma3SettingsFragment::class))
            "note_types" -> startActivity(Intent(this, ManageNotetypes::class.java))
            else -> Timber.w("kuma3: unknown screen %s", screen)
        }
    }
}
