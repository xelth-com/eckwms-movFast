package com.xelth.eckwms_movfast.trips

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tiny persistent file logger for the trip stack ("the little log program").
 *
 * logcat is useless for field post-mortems on this device (MtkCam spam churns
 * the main buffer in seconds, and off-LAN there is no adb at all), so the
 * events that explain a dead drive are appended to a per-process file under
 * `filesDir/trip_logs/` — pullable later via `run-as` even weeks after the
 * fact. Every entry ALSO goes to logcat, so live debugging is unchanged.
 *
 * On process start [logProcessStart] records WHY the previous incarnation of
 * this process died (ApplicationExitInfo: LOW_MEMORY / CRASH / ANR / ...) —
 * the direct answer to "did the OS kill the recording under load?".
 */
object TripLog {
    private const val MAX_BYTES = 512 * 1024L
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    @Volatile private var logFile: File? = null

    fun init(context: Context, processTag: String) {
        try {
            val dir = File(context.filesDir, "trip_logs")
            dir.mkdirs()
            logFile = File(dir, "$processTag.log")
        } catch (e: Exception) {
            Log.w("TripLog", "init failed: ${e.message}")
        }
        // A crash must reach the file BEFORE the process dies — the sticky
        // restart that follows would otherwise be indistinguishable from an
        // OOM kill in the post-mortem.
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            write("E", "Crash", "uncaught on ${t.name}: ${Log.getStackTraceString(e)}")
            prev?.uncaughtException(t, e)
        }
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); write("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); write("W", tag, msg) }
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e(tag, msg, tr)
        write("E", tag, if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg)
    }

    private fun write(level: String, tag: String, msg: String) {
        val f = logFile ?: return
        try {
            synchronized(lock) {
                if (f.length() > MAX_BYTES) {
                    val old = File(f.parentFile, f.name + ".1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText("${fmt.format(Date())} $level/$tag: $msg\n")
            }
        } catch (_: Exception) {
            // Never let logging take down the recorder.
        }
    }

    /** Log this process's start plus the exit reasons of its LAST deaths. */
    fun logProcessStart(context: Context) {
        val proc = com.xelth.eckwms_movfast.utils.ProcessUtils.processName()
        i("TripLog", "── process start: $proc (pid ${android.os.Process.myPid()}) ──")
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.processName == proc }
                .take(3)
                .forEach { info ->
                    i(
                        "TripLog",
                        "prev exit: ${reasonName(info.reason)} status=${info.status} " +
                            "importance=${info.importance} rss=${info.rss / 1024}MB " +
                            "at=${fmt.format(Date(info.timestamp))} desc=${info.description ?: "-"}"
                    )
                }
        } catch (e: Exception) {
            w("TripLog", "exit reasons unavailable: ${e.message}")
        }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }
}
