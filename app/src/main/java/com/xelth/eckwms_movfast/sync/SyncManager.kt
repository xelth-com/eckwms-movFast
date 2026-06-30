package com.xelth.eckwms_movfast.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncManager {
    private const val TAG = "SyncManager"
    private const val SYNC_WORK_NAME = "eckwms_sync"

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Schedule an immediate one-time sync
     */
    fun scheduleSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            SYNC_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            syncRequest
        )

        Log.d(TAG, "Sync work scheduled")
    }

    /**
     * Schedule periodic sync every 15 minutes (minimum allowed by WorkManager)
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicSyncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
            15, TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "${SYNC_WORK_NAME}_periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicSyncRequest
        )

        Log.d(TAG, "Periodic sync work scheduled (every 15 minutes)")
    }

    /**
     * Event-driven sync: fire an immediate sync the moment the device regains
     * connectivity, instead of waiting up to 15 min for the next periodic run.
     *
     * `scheduleSync` enqueues a unique one-time job with [ExistingWorkPolicy.KEEP],
     * so a flapping network can't pile up jobs, and the worker is cheap when there's
     * nothing pending. Idempotent — repeated calls are no-ops once registered.
     */
    fun registerConnectivityTrigger(context: Context) {
        if (networkCallback != null) return
        val appContext = context.applicationContext
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager unavailable; skipping connectivity trigger")
            return
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Connectivity regained → scheduling immediate sync")
                scheduleSync(appContext)
                // Re-arm the health monitor's preferred-local sniff so a return to the
                // kiosk LAN switches back to the direct server within one health cycle
                // (instead of waiting out the 5-min cooldown).
                com.xelth.eckwms_movfast.utils.NetworkHealthMonitor.onConnectivityChanged()
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            connectivityManager = cm
            networkCallback = callback
            Log.d(TAG, "Connectivity sync trigger registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register connectivity trigger: ${e.message}")
        }
    }

    /**
     * Cancel all sync work
     */
    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork("${SYNC_WORK_NAME}_periodic")
        networkCallback?.let { cb ->
            try {
                connectivityManager?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister connectivity trigger: ${e.message}")
            }
        }
        networkCallback = null
        connectivityManager = null
        Log.d(TAG, "Sync work cancelled")
    }
}
