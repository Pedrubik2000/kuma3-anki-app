// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import anki.deck_config.RwkvProfileSettings
import com.ichi2.anki.ankicommon.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * A bundled model: the asset, the installed file's name and the asset's size. [installModel]
     * checks only the size, so a new model of the same size needs a new [file] name.
     */
    private class Model(
        val asset: String,
        val file: String,
        val bytes: Long,
    )

    /**
     * A model size of the desktop fork (build 115): its two-head model (RWKV-Curve's intervals and
     * S90, and RWKV-Instant when it runs alone) and its Instant model. The backend picks which run
     * from the presets' schedulers, as the desktop. [desktopKey] is the desktop's profile value.
     */
    private class Size(
        val key: String,
        val desktopKey: String,
        val twoHead: Model,
        val instant: Model,
    )

    private val SIZES =
        listOf(
            // the show-time big model (SM91s2) and the show-time instant-only big model (SM99)
            Size(
                "big",
                "legacy",
                Model("rwkv/big.bin", "rwkv-sm91s2.bin", 11_082_637L),
                Model("rwkv/big-instant.bin", "rwkv-sm99.bin", 11_082_637L),
            ),
            // the show-time retrain (SM92) and the Small Instant-Only model (SM98); the default
            Size(
                "small",
                "sm72e25",
                Model("rwkv/small.bin", "rwkv-sm92.bin", 1_351_553L),
                Model("rwkv/small-instant.bin", "rwkv-sm98.bin", 1_351_553L),
            ),
            // the Tiny two-head model (SM106) and the Tiny Instant-Only model (SM101): one card layer
            Size(
                "tiny",
                "tiny",
                Model("rwkv/tiny.bin", "rwkv-sm106.bin", 1_270_749L),
                Model("rwkv/tiny-instant.bin", "rwkv-sm101.bin", 1_270_749L),
            ),
        )
    private const val DEFAULT_SIZE = "small"

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

    /** How long [prepareAfterOpen] leaves the collection to a first screen that isn't the deck list. */
    private const val FIRST_SCREEN_MS = 10_000L
    private const val READY_POLLS = 1200

    /** The progress notification, also [RwkvRebuildService]'s. */
    const val PROGRESS_NOTIFICATION_ID = 0x6b33 // "k3"
    private const val READY_NOTIFICATION_ID = 0x6b34

    /** The app module's NotificationChannel.SYNC (low importance), which this module cannot see. */
    private const val PROGRESS_CHANNEL = "Synchronization"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var watcher: Job? = null
    private val readyFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the backend's model state is installed; the deck list redraws its counts. */
    val ready: SharedFlow<Unit> = readyFlow

    /**
     * Queues [prepare] behind the open of the collection: whoever opened it (often the main
     * thread, e.g. BootService) doesn't wait for the backend's read of the review history, seconds
     * on a big collection (4M reviews: ~7 s on a Pixel 8a). It waits for the deck list's first
     * load ([deckListShown], at most [FIRST_SCREEN_MS]), which would otherwise queue behind it
     * ("Processing..." for 2-3 s at each start on a Tab S7+ with 3.8M reviews); [ready] redraws
     * the list with RWKV's counts anyway. [now]: don't wait (a settings change).
     */
    fun prepareAfterOpen(
        context: Context,
        now: Boolean = false,
    ) {
        scope.launch {
            if (!now) withTimeoutOrNull(FIRST_SCREEN_MS) { deckListShownFlow.first() }
            CollectionManager.withOpenColOrNull { prepare(context, backend) }
        }
    }

    private val deckListShownFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The deck list has drawn its counts: [prepareAfterOpen] may take the collection. */
    fun deckListShown() {
        deckListShownFlow.tryEmit(Unit)
    }

    /**
     * Must run on the collection queue, after the collection is opened.
     * On failure the standard review order applies.
     */
    private fun prepare(
        context: Context,
        backend: Backend,
    ) {
        try {
            val started = SystemClock.elapsedRealtime()
            val size = size(context)
            val twoHead = installModel(context, size.twoHead)
            val instant = installModel(context, size.instant)
            removeOtherModels(context, setOf(twoHead, instant))
            val replayed =
                backend.rwkvPrepareOffline(
                    modelPath = twoHead.absolutePath,
                    statePrecision = statePrecision(context),
                    // every model of the family is leak-free (SM72's input rule)
                    modelKind = "sm72",
                    instantModelPath = instant.absolutePath,
                    singleModel = singleModel(context),
                )
            Timber.i("RWKV prepare: replaying %s reviews in the background; %d ms", replayed, SystemClock.elapsedRealtime() - started)
            val announced = replayed >= TOAST_AFTER_REPLAYED && toastEnabled(context)
            if (announced) {
                showToast(context, context.getString(R.string.kuma3_rwkv_rebuilding, replayed))
            }
            refreshWhenReady(context, replayed, announced)
        } catch (e: Exception) {
            Timber.w(e, "RWKV preparation failed; using the standard scheduler")
            showToast(context, context.getString(R.string.kuma3_rwkv_unavailable))
        }
    }

    /**
     * A rebuild started elsewhere (the statistics' RWKV calibration, `pages/SearchStatsExtended.kt`): the
     * same progress notification and deck list redraw as one from [prepare].
     */
    fun watchRebuild(
        context: Context,
        replaying: Long,
    ) = refreshWhenReady(context, replaying, announced = true)

    /**
     * The deck list was drawn with the standard counts while the backend built the model state on its
     * own thread: once that is done, the status call installs it, and [ready] redraws the deck list
     * with RWKV's counts. Not a ChangeManager change: an open reviewer would swap its current card (its
     * queue switches to RWKV's at the next answer anyway).
     */
    private fun refreshWhenReady(
        context: Context,
        replayed: Long,
        announced: Boolean,
    ) {
        val previous = watcher
        watcher =
            scope.launch {
                // its `finally` stops the service: before this one starts it
                previous?.cancelAndJoin()
                val started = SystemClock.elapsedRealtime()
                val progress = ReplayProgress(context)
                // a rebuild, not a normal start: keep the app running until it is done
                val service = replayed >= TOAST_AFTER_REPLAYED && RwkvRebuildService.start(context)
                try {
                    // ponytail: polls (the backend can't call back); a callback from the build thread if it matters
                    var polls = 0
                    var lastDone = -1L
                    var stalledPolls = 0
                    while (true) {
                        delay(READY_POLL_MS)
                        val status =
                            try {
                                CollectionManager.withOpenColOrNull {
                                    backend.rwkvOfflineInstantPassStep(restart = false, statusOnly = true)
                                } ?: return@launch
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // an uncaught error here, on this scope, kills the app
                                Timber.w(e, "RWKV status failed; stopped waiting")
                                return@launch
                            }
                        if (status.available) {
                            Timber.i("RWKV ready: refreshing the deck counts")
                            readyFlow.tryEmit(Unit)
                            if (announced) {
                                val seconds = (SystemClock.elapsedRealtime() - started + 999) / 1000
                                notifyReady(context, context.getString(R.string.kuma3_rwkv_rebuilt, replayed, seconds))
                            }
                            return@launch
                        }
                        progress.update(status.replayDone, status.replayTotal)
                        if (status.replayDone == lastDone) stalledPolls++ else stalledPolls = 0
                        lastDone = status.replayDone
                        // a replay still under way may take longer than the polls (4M reviews: ~10 min); one that
                        // stopped moving (a build that died mid-replay leaves its total set) is given up, or the
                        // poll and the rebuild service's wake lock kept a phone awake all night
                        if (++polls >= READY_POLLS && (status.replayTotal == 0L || stalledPolls >= READY_POLLS)) {
                            Timber.w(
                                "RWKV not ready after %d polls (replay %d/%d); stopped waiting",
                                polls,
                                status.replayDone,
                                status.replayTotal,
                            )
                            return@launch
                        }
                    }
                } finally {
                    progress.cancel()
                    if (service) RwkvRebuildService.stop(context)
                }
            }
    }

    /**
     * A notification with a progress bar while the backend replays the review history (the first
     * start, after an update): "Preparing RWKV: 42% · about 6 min left". Silent, on the app's
     * low-importance sync channel; not shown if notifications are off (the toast still is).
     */
    private class ReplayProgress(
        private val context: Context,
    ) {
        private val manager = NotificationManagerCompat.from(context)
        private var firstDone = -1L
        private var firstAt = 0L
        private var shownPercent = -1

        @SuppressLint("MissingPermission") // checked in canNotify
        fun update(
            done: Long,
            total: Long,
        ) {
            if (total == 0L || !canNotify()) return
            val now = SystemClock.elapsedRealtime()
            if (firstDone < 0) {
                firstDone = done
                firstAt = now
            }
            val percent = (done * 100 / total).toInt()
            if (percent == shownPercent) return
            shownPercent = percent
            val rate = (done - firstDone).toDouble() / (now - firstAt).coerceAtLeast(1)
            val left =
                if (done > firstDone) {
                    val minutes = ((total - done) / rate / 60_000).toInt()
                    if (minutes < 1) {
                        context.getString(R.string.kuma3_rwkv_left_under_minute)
                    } else {
                        context.getString(R.string.kuma3_rwkv_left_minutes, minutes)
                    }
                } else {
                    ""
                }
            val notification =
                notification(
                    context,
                    context.getString(R.string.kuma3_rwkv_preparing_percent, percent) + left,
                    context.getString(R.string.kuma3_rwkv_first_start, total),
                ).setProgress(100, percent, false)
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .build()
            manager.notify(PROGRESS_NOTIFICATION_ID, notification)
        }

        fun cancel() {
            if (shownPercent >= 0) manager.cancel(PROGRESS_NOTIFICATION_ID)
        }

        private fun canNotify() = canNotify(context, manager)
    }

    /** A notification on the RWKV channel with the sync icon. */
    private fun notification(
        context: Context,
        title: String,
        text: String,
    ) = NotificationCompat
        .Builder(context, PROGRESS_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle(title)
        .setContentText(text)

    /** [RwkvRebuildService]'s notification until [ReplayProgress] shows the first percentage. */
    fun preparingNotification(context: Context): Notification =
        notification(
            context,
            context.getString(R.string.kuma3_rwkv_preparing),
            context.getString(R.string.kuma3_rwkv_standard_until_done),
        ).setProgress(0, 0, true)
            .setOngoing(true)
            .setSilent(true)
            .build()

    /**
     * The end of a rebuild that [prepare] announced: a notification (tapping it dismisses it), or a
     * toast when the app can't post notifications.
     */
    @SuppressLint("MissingPermission") // checked in canNotify
    private fun notifyReady(
        context: Context,
        text: String,
    ) {
        val manager = NotificationManagerCompat.from(context)
        val ready = context.getString(R.string.kuma3_rwkv_ready)
        if (!canNotify(context, manager)) return showToast(context, ready)
        val notification = notification(context, ready, text).setAutoCancel(true).build()
        manager.notify(READY_NOTIFICATION_ID, notification)
    }

    private fun canNotify(
        context: Context,
        manager: NotificationManagerCompat,
    ) = manager.areNotificationsEnabled() &&
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )

    /**
     * Settings > kuma3 > 'Rebuilding RWKV' message (R.string.kuma3_rwkv_toast_key in the app module,
     * which this module cannot see; the app's default preferences file).
     */
    private fun toastEnabled(context: Context) = kuma3Prefs(context).getBoolean("kuma3_rwkv_toast", true)

    /**
     * Settings > kuma3 > RWKV state precision (R.string.kuma3_rwkv_precision_key): "f32", "int8" or
     * "int4" (card states; the backend keeps note states int8, whose int4 drift overflowed on long
     * histories); anything else: the backend's int8 default. Desktop defaults to int4 cards / int8
     * notes.
     */
    private fun statePrecision(context: Context) =
        kuma3Prefs(context).getString("kuma3_rwkv_precision", null)?.takeIf { it == "f32" || it == "int4" } ?: ""

    /**
     * Settings > kuma3 > RWKV model (R.string.kuma3_rwkv_model_key): "big", "small" or "tiny"; Small
     * when unset, as the desktop. A change rebuilds the state: the backend keys it by the model files.
     */
    private fun size(context: Context): Size {
        val key = kuma3Prefs(context).getString("kuma3_rwkv_model", null)
        return SIZES.firstOrNull { it.key == key } ?: SIZES.first { it.key == DEFAULT_SIZE }
    }

    /** Settings > kuma3 > Use one model for RWKV (R.string.kuma3_rwkv_single_model_key). */
    private fun singleModel(context: Context) = kuma3Prefs(context).getBoolean("kuma3_rwkv_single_model", false)

    /**
     * The model choice as the desktop's Deck Options show it (its "Model size" section): kuma3's
     * settings in the desktop's terms.
     */
    fun profileSettings(context: Context): RwkvProfileSettings =
        RwkvProfileSettings
            .newBuilder()
            .setModel(size(context).desktopKey)
            .setStatePrecision(
                when (statePrecision(context)) {
                    "f32" -> "f32"
                    "int4" -> "cards_int4_notes_int8"
                    else -> "int8"
                },
            ).setSingleModel(singleModel(context))
            .build()

    /**
     * Stores a Deck Options save of the model choice in kuma3's settings; true when it changed (the
     * caller prepares again). Older desktop small keys are Small, as the desktop saves them.
     */
    fun saveProfileSettings(
        context: Context,
        settings: RwkvProfileSettings,
    ): Boolean {
        val size = SIZES.firstOrNull { it.desktopKey == settings.model }?.key ?: DEFAULT_SIZE
        if (size == size(context).key && settings.singleModel == singleModel(context)) return false
        kuma3Prefs(context)
            .edit()
            .putString("kuma3_rwkv_model", size)
            .putBoolean("kuma3_rwkv_single_model", settings.singleModel)
            .apply()
        return true
    }

    private fun kuma3Prefs(context: Context) = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    private fun installModel(
        context: Context,
        bundled: Model,
    ): File {
        val dir = File(context.noBackupFilesDir, "rwkv")
        val model = File(dir, bundled.file)
        if (model.length() == bundled.bytes) return model

        dir.mkdirs()
        val tmp = File(dir, "${bundled.file}.tmp")
        context.assets.open(bundled.asset).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (tmp.length() != bundled.bytes) throw IOException("unexpected RWKV model size: ${tmp.length()}")
        model.delete()
        if (!tmp.renameTo(model)) throw IOException("could not install the RWKV model")
        return model
    }

    /**
     * Only the chosen size's two models stay installed (Big's are 11 MB each); this also removes
     * the models of earlier versions (model.bin, model-small-sm72.bin, ...).
     */
    private fun removeOtherModels(
        context: Context,
        keep: Set<File>,
    ) {
        File(context.noBackupFilesDir, "rwkv").listFiles()?.filter { it.isFile && it !in keep }?.forEach { it.delete() }
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
