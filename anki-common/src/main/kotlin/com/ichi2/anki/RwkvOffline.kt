// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import net.ankiweb.rsdroid.Backend
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * RWKV-Instant on the phone.
 *
 * The backend does the work: once [prepare] has handed it the model, it loads the model state and
 * brings it up to date with the review history on its own thread (the collection stays usable, with
 * the standard order, meanwhile), and scores review cards itself whenever a review queue or the
 * deck counts are built (see `rslib/src/scheduler/rwkv/offline.rs`). Nothing else in the app calls
 * RWKV. The backend keeps the state in `collection.rwkv-offline` next to the collection, so a start
 * normally replays only the reviews since it was saved.
 */
object RwkvOffline {
    private const val MODEL_ASSET = "rwkv/model.bin"

    /** Size of the bundled model (the desktop fork's `RWKV_trained_on_5000_10000.bin`). */
    private const val MODEL_BYTES = 11_082_637L

    /**
     * "Rebuilding RWKV" is shown only when this many reviews are replayed: a rebuild of the state
     * (first start, after an update), not a normal start.
     */
    private const val TOAST_AFTER_REPLAYED = 200L

    /**
     * How often, and how long, [refreshWhenReady] checks whether the backend's build is done. A full
     * rebuild of a 200k-review history takes ~35 s on a Pixel 8a; 10 minutes covers slow phones.
     */
    private const val READY_POLL_MS = 500L
    private const val READY_POLLS = 1200

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcher: Job? = null
    private val readyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the backend's model state is installed; the deck list redraws its counts. */
    val ready: SharedFlow<Unit> = readyFlow

    /**
     * Must run on the collection queue, after the collection is opened.
     * On failure the standard review order applies.
     */
    fun prepare(
        context: Context,
        backend: Backend,
    ) {
        try {
            val started = SystemClock.elapsedRealtime()
            val model = installModel(context)
            val replayed = backend.rwkvPrepareOffline(modelPath = model.absolutePath)
            Timber.i("RWKV prepare: replaying %s reviews in the background; %d ms", replayed, SystemClock.elapsedRealtime() - started)
            if (replayed >= TOAST_AFTER_REPLAYED && toastEnabled(context)) {
                showToast(context, "Rebuilding RWKV ($replayed reviews): standard order until done")
            }
            refreshWhenReady()
        } catch (e: Exception) {
            Timber.w(e, "RWKV preparation failed; using the standard scheduler")
            showToast(context, "RWKV unavailable: standard review order")
        }
    }

    /**
     * The deck list was drawn with the standard counts while the backend built the model state on its
     * own thread: once that is done, the status call installs it, and [ready] redraws the deck list
     * with RWKV's counts. Not a ChangeManager change: an open reviewer would swap its current card (its
     * queue switches to RWKV's at the next answer anyway).
     */
    private fun refreshWhenReady() {
        watcher?.cancel()
        watcher =
            scope.launch {
                // ponytail: polls (the backend can't call back); a callback from the build thread if it matters
                repeat(READY_POLLS) {
                    delay(READY_POLL_MS)
                    val ready =
                        CollectionManager.withOpenColOrNull {
                            backend.rwkvOfflineInstantPassStep(restart = false, statusOnly = true).available
                        } ?: return@launch
                    if (ready) {
                        Timber.i("RWKV ready: refreshing the deck counts")
                        readyFlow.tryEmit(Unit)
                        return@launch
                    }
                }
            }
    }

    /**
     * Settings > kuma3 > 'Rebuilding RWKV' message (R.string.kuma3_rwkv_toast_key in the app module,
     * which this module cannot see; the app's default preferences file).
     */
    private fun toastEnabled(context: Context) =
        context
            .getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            .getBoolean("kuma3_rwkv_toast", true)

    private fun installModel(context: Context): File {
        val dir = File(context.noBackupFilesDir, "rwkv")
        val model = File(dir, "model.bin")
        if (model.length() == MODEL_BYTES) return model

        dir.mkdirs()
        val tmp = File(dir, "model.bin.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (tmp.length() != MODEL_BYTES) throw IOException("unexpected RWKV model size: ${tmp.length()}")
        model.delete()
        if (!tmp.renameTo(model)) throw IOException("could not install the RWKV model")
        return model
    }

    // the app's showThemedToast is in the app module, which this module cannot see
    @SuppressLint("DirectToastMakeTextUsage")
    private fun showToast(
        context: Context,
        text: String,
    ) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
