// app/src/main/java/com/xelth/eckwms_movfast/data/surreal/WmsDb.kt
package com.xelth.eckwms_movfast.data.surreal

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Embedded SurrealDB (SurrealKV backend, pure Rust, same 3.2.1 engine as the
 * mesh nodes) — JNI bridge, spike stage. The engine is single-writer with a
 * file lock: ONLY the main process may open it (the `:trips` process keeps
 * using Room). One instance per process, lives until process death.
 */
object WmsDb {
    private const val TAG = "WmsDb"

    @Volatile private var loaded = false
    @Volatile private var opened = false

    external fun open(path: String): Boolean
    external fun query(sql: String): String
    external fun close()

    /** Load the native lib + open the DB under filesDir. False if the .so is
     *  missing (e.g. an ABI we didn't ship) or the engine failed to open. */
    fun init(context: Context): Boolean {
        if (opened) return true
        synchronized(this) {
            if (opened) return true
            if (!loaded) {
                try {
                    System.loadLibrary("wmsdb")
                    loaded = true
                } catch (e: UnsatisfiedLinkError) {
                    Log.w(TAG, "libwmsdb.so not available: ${e.message}")
                    return false
                }
            }
            val dir = File(context.filesDir, "surreal")
            dir.mkdirs()
            opened = open(dir.absolutePath)
            if (!opened) Log.e(TAG, "engine open failed at $dir")
            return opened
        }
    }

    /**
     * Startup smoke test with timings — the spike's measuring stick.
     * Call from a background thread; logs open/write/read latency to logcat.
     */
    fun smokeTest(context: Context) {
        try {
            val t0 = System.currentTimeMillis()
            if (!init(context)) return
            val tOpen = System.currentTimeMillis()

            val write = query(
                "CREATE spike_probe SET at = time::now(), source = 'startup-smoke'"
            )
            val tWrite = System.currentTimeMillis()

            val read = query("SELECT count() AS n FROM spike_probe GROUP ALL")
            val tRead = System.currentTimeMillis()

            Log.i(
                TAG,
                "SMOKE open=${tOpen - t0}ms write=${tWrite - tOpen}ms " +
                    "read=${tRead - tWrite}ms | write=$write | read=$read"
            )
        } catch (e: Throwable) {
            Log.e(TAG, "smoke test failed", e)
        }
    }
}
