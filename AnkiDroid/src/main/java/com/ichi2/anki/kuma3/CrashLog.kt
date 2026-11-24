// SPDX-License-Identifier: GPL-3.0-or-later
package com.ichi2.anki.kuma3

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Local crash log (ACRA stays off, nothing is sent): `filesDir/crash/last_crash.txt` and `prev_crash.txt`.
 * Java crashes are written by [install]'s handler; native crashes and ANRs of the last run are read from
 * the system's exit reasons on start. The dialog ([showIfUnseen], [show]) lets the user copy or share it.
 */
object CrashLog {
    private const val PREFS = "kuma3_crash"
    private const val PREF_EXIT_SEEN = "kuma3_last_exit_seen"
    private const val PREF_FILE_SEEN = "kuma3_crash_seen_mtime"
    private const val SHARE_AS_FILE_OVER = 100_000
    private const val MAX_TRACE = 200_000
    private const val MAX_COPY = 100_000

    /** The DeckPicker that last asked [showIfUnseen]: told when the exit-reason scan writes a file. */
    @Volatile private var deckPicker: WeakReference<Activity>? = null

    private fun dir(context: Context) = File(context.filesDir, "crash")

    private fun latest(context: Context) = File(dir(context), "last_crash.txt").takeIf { it.isFile }

    private fun time(millis: Long = TimeManager.time.intTimeMS()) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(millis)

    private fun header(context: Context): String {
        val version =
            try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
            } catch (e: Throwable) {
                "?"
            }
        return "Time: ${time()}\nPackage: ${context.packageName}\nVersion: $version\n" +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, SDK ${Build.VERSION.SDK_INT}\n"
    }

    /** Keeps the previous file as prev_crash.txt (at most two files). */
    @Synchronized
    private fun write(
        context: Context,
        text: String,
    ) {
        val d = dir(context)
        d.mkdirs()
        val last = File(d, "last_crash.txt")
        if (last.exists()) last.renameTo(File(d, "prev_crash.txt"))
        last.writeText(text)
    }

    /** The app's own last 400 logcat lines; gives up after about 1.5 s. */
    private fun logcat(): String =
        try {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "400", "--pid", Process.myPid().toString()))
            val sb = StringBuffer()
            val reader =
                Thread {
                    try {
                        p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
                    } catch (_: Exception) {
                    }
                }
            reader.start()
            reader.join(1500)
            p.destroy()
            sb.toString()
        } catch (_: Throwable) {
            ""
        }

    /** Call as early as possible in Application.onCreate (not in the ACRA process). */
    fun install(context: Context) {
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(
                    app,
                    header(app) + "Thread: ${thread.name}\n\n${Log.getStackTraceString(error)}\n\n--- logcat ---\n${logcat()}",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Thread { readExitReasons(app) }.start()
        }
    }

    @RequiresApi(30)
    private fun readTrace(r: ApplicationExitInfo): String {
        val out = ByteArrayOutputStream()
        r.traceInputStream?.use {
            val buf = ByteArray(8192)
            while (out.size() < MAX_TRACE) {
                val n = it.read(buf, 0, minOf(buf.size, MAX_TRACE - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toString("UTF-8")
    }

    /** Native crashes and ANRs newer than the last one looked at; the first run only sets the mark. */
    @RequiresApi(30)
    private fun readExitReasons(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(PREF_EXIT_SEEN, -1L)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            var wrote = false
            if (seen >= 0) {
                for (r in reasons.filter { it.timestamp > seen }.sortedBy { it.timestamp }) {
                    try {
                        val isAnr = r.reason == ApplicationExitInfo.REASON_ANR
                        if (!isAnr && r.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) continue
                        val sb = StringBuilder(header(context))
                        sb.append("Exit time: ").append(time(r.timestamp))
                        sb.append("\nReason: ").append(if (isAnr) "ANR" else "native crash")
                        sb.append("\nDescription: ").append(r.description)
                        sb.append("\nImportance: ").append(r.importance).append('\n')
                        if (isAnr) {
                            try {
                                sb.append("\n--- trace ---\n").append(readTrace(r))
                            } catch (_: Throwable) {
                            }
                        }
                        write(context, sb.toString())
                        wrote = true
                    } catch (_: Throwable) {
                    }
                }
            }
            val newest = reasons.maxOfOrNull { it.timestamp } ?: 0L
            prefs.edit { putLong(PREF_EXIT_SEEN, maxOf(newest, seen, 0L)) }
            if (wrote) {
                Handler(Looper.getMainLooper()).post {
                    val a = deckPicker?.get() ?: return@post
                    if ((a as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) showIfUnseen(a)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** DeckPicker: one dialog for a crash file the user has not seen. */
    fun showIfUnseen(activity: Activity) {
        deckPicker = WeakReference(activity)
        val ctx = activity.applicationContext
        val f = latest(ctx) ?: return
        if (ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(PREF_FILE_SEEN, 0L) == f.lastModified()) return
        show(activity)
    }

    /** Settings > About: the same dialog, or a toast when nothing was saved. */
    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val ctx = activity.applicationContext
        val f = latest(ctx)
        if (f == null) {
            showThemedToast(activity, R.string.kuma3_crash_none, true)
            return
        }
        val text = f.readText()
        val mtime = f.lastModified()
        AlertDialog
            .Builder(activity)
            .setTitle(R.string.kuma3_crash_title)
            .setMessage(R.string.kuma3_crash_message)
            .setPositiveButton(R.string.kuma3_crash_copy) { _, _ ->
                try {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("kuma3 crash log", text.take(MAX_COPY)))
                    showThemedToast(activity, R.string.kuma3_crash_copied, true)
                } catch (_: Throwable) {
                    showThemedToast(activity, R.string.kuma3_crash_copy_failed, true)
                }
            }.setNeutralButton(R.string.kuma3_crash_share) { _, _ -> share(activity, f, text) }
            .setNegativeButton(R.string.kuma3_crash_close, null)
            .setOnDismissListener { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(PREF_FILE_SEEN, mtime) } }
            .show()
    }

    private fun share(
        activity: Activity,
        f: File,
        text: String,
    ) {
        try {
            val ctx = activity.applicationContext
            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            if (text.length > SHARE_AS_FILE_OVER) {
                val copy = File(ctx.cacheDir, "kuma3_crash_log.txt")
                f.copyTo(copy, overwrite = true)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.apkgfileprovider", copy)
                send.putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                send.putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(send, null))
        } catch (_: Exception) {
            showThemedToast(activity, R.string.kuma3_crash_share_failed, true)
        }
    }
}
