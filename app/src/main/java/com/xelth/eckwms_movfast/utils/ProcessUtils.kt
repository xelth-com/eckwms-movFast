package com.xelth.eckwms_movfast.utils

import android.app.Application
import android.content.Context

/**
 * Which process are we in? The trip stack (recording FGS + auto-detect
 * receivers) runs in the small `:trips` process so a memory-starved drive
 * (Google Maps + music) can kill the heavy UI process without touching the
 * recording. Several shared singletons must branch on this.
 */
object ProcessUtils {
    const val TRIPS_PROCESS_SUFFIX = ":trips"

    fun processName(): String = Application.getProcessName()

    fun isTripsProcess(): Boolean = processName().endsWith(TRIPS_PROCESS_SUFFIX)

    fun isMainProcess(context: Context): Boolean = processName() == context.packageName

    /** Short tag for log files: "main", "trips", or the custom suffix. */
    fun shortTag(context: Context): String = when {
        isMainProcess(context) -> "main"
        else -> processName().substringAfterLast(':', "other")
    }
}
