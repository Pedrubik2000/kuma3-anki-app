// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import net.ankiweb.rsdroid.Backend
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * RWKV-Instant on the phone.
 *
 * The backend does the work: once [prepare] has handed it the model, it replays the review
 * history and scores review cards itself whenever a review queue or the deck counts are built
 * (see `rslib/src/scheduler/rwkv/offline.rs`). Nothing else in the app calls RWKV.
 */
object RwkvOffline {
    private const val MODEL_ASSET = "rwkv/model.bin"

    /** Size of the bundled model (the desktop fork's `RWKV_trained_on_5000_10000.bin`). */
    private const val MODEL_BYTES = 11_082_637L

    /**
     * Must run on the collection queue, after the collection is opened.
     * On failure the standard review order applies.
     */
    fun prepare(
        context: Context,
        backend: Backend,
    ) {
        try {
            val model = installModel(context)
            val replayed = backend.rwkvPrepareOffline(modelPath = model.absolutePath)
            Timber.i("RWKV prepared: %s reviews replayed", replayed)
            showToast(context, "RWKV ready ($replayed reviews)")
        } catch (e: Exception) {
            Timber.w(e, "RWKV preparation failed; using the standard scheduler")
            showToast(context, "RWKV unavailable: standard review order")
        }
    }

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

    private fun showToast(
        context: Context,
        text: String,
    ) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }
}
