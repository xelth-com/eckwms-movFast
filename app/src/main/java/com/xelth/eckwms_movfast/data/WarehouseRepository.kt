package com.xelth.eckwms_movfast.data

import android.content.Context
import android.util.Log
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.ScanEntity
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity
import com.xelth.eckwms_movfast.sync.SyncManager
import com.xelth.eckwms_movfast.ui.data.ScanHistoryItem
import com.xelth.eckwms_movfast.ui.data.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

class WarehouseRepository(
    private val db: AppDatabase,
    private val context: Context
) {
    private val TAG = "WarehouseRepository"

    /**
     * Save scan locally and add to sync queue
     */
    suspend fun saveAndSyncScan(item: ScanHistoryItem, orderId: String? = null): Long = withContext(Dispatchers.IO) {
        Log.d(TAG, "Saving scan: ${item.barcode}")

        // 1. Save scan to local database
        val scanEntity = ScanEntity(
            barcode = item.barcode,
            timestamp = item.timestamp,
            status = item.status.name,
            type = item.type,
            checksum = item.checksum,
            orderId = orderId
        )
        val scanId = db.scanDao().insertScan(scanEntity)
        Log.d(TAG, "Scan saved with ID: $scanId")

        // 2. Add to sync queue
        val payload = JSONObject().apply {
            put("barcode", item.barcode)
            put("type", item.type)
            orderId?.let { put("orderId", it) }
        }.toString()

        val queueEntity = SyncQueueEntity(
            type = "scan",
            payload = payload,
            scanId = scanId
        )
        val queueId = db.syncQueueDao().addToQueue(queueEntity)
        Log.d(TAG, "Added to sync queue with ID: $queueId")

        // 3. Trigger sync worker
        SyncManager.scheduleSync(context)

        scanId
    }

    /**
     * Get all scans as a Flow for real-time updates
     */
    fun getAllScansFlow(): Flow<List<ScanHistoryItem>> {
        return db.scanDao().getAllScansFlow().map { entities ->
            entities.map { entity ->
                ScanHistoryItem(
                    id = entity.id,
                    barcode = entity.barcode,
                    timestamp = entity.timestamp,
                    status = parseStatus(entity.status),
                    type = entity.type,
                    checksum = entity.checksum
                )
            }
        }
    }

    /**
     * Get all scans as a list (one-time fetch)
     */
    suspend fun getAllScans(): List<ScanHistoryItem> = withContext(Dispatchers.IO) {
        db.scanDao().getAllScans().map { entity ->
            ScanHistoryItem(
                id = entity.id,
                barcode = entity.barcode,
                timestamp = entity.timestamp,
                status = parseStatus(entity.status),
                type = entity.type,
                checksum = entity.checksum
            )
        }
    }

    /**
     * Update scan status (called by SyncWorker after successful sync)
     */
    suspend fun updateScanStatus(scanId: Long, status: ScanStatus, checksum: String? = null) =
        withContext(Dispatchers.IO) {
            db.scanDao().updateScanStatus(scanId, status.name, checksum)
            Log.d(TAG, "Updated scan $scanId status to ${status.name}")
        }

    /**
     * Get sync queue size
     */
    suspend fun getSyncQueueSize(): Int = withContext(Dispatchers.IO) {
        db.syncQueueDao().getQueueSize()
    }

    /**
     * Clean up old scans (e.g., older than 30 days)
     */
    suspend fun cleanupOldScans(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        db.scanDao().deleteOldScans(cutoffTime)
        Log.d(TAG, "Cleaned up scans older than $daysToKeep days")
    }

    private fun parseStatus(status: String): ScanStatus {
        return try {
            ScanStatus.valueOf(status)
        } catch (e: IllegalArgumentException) {
            ScanStatus.PENDING
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: WarehouseRepository? = null

        fun getInstance(context: Context): WarehouseRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WarehouseRepository(
                    AppDatabase.getInstance(context),
                    context.applicationContext
                ).also { INSTANCE = it }
            }
        }
    }
}
