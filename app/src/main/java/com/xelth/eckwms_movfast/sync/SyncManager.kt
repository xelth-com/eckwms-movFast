package com.xelth.eckwms_movfast.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

object SyncManager {
    private const val TAG = "SyncManager"
    private const val SYNC_WORK_NAME = "eckwms_sync"

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
     * Cancel all sync work
     */
    fun cancelSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork("${SYNC_WORK_NAME}_periodic")
        Log.d(TAG, "Sync work cancelled")
    }
}
