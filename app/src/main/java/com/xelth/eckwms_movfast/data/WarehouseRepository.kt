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
     * Saves an image upload transaction to local history and sync queue
     * @param imagePath Local file path to the captured image
     * @param imageSize Size of the image in bytes
     * @param imageId Client-generated UUID for deduplication
     * @param orderId Optional order ID context
     * @return Database ID of the saved transaction
     */
    suspend fun saveImageUpload(
        imagePath: String,
        imageSize: Long,
        imageId: String,
        orderId: String? = null
    ): Long = withContext(Dispatchers.IO) {
        // 1. Save image transaction to local database
        val scanEntity = ScanEntity(
            transactionType = "IMAGE_UPLOAD",
            barcode = "IMAGE",  // Placeholder for display
            timestamp = System.currentTimeMillis(),
            status = "PENDING",
            type = "camera",
            imagePath = imagePath,
            imageSize = imageSize,
            imageId = imageId,
            orderId = orderId
        )
        val scanId = db.scanDao().insertScan(scanEntity)

        // 2. Add to sync queue
        val payload = JSONObject().apply {
            put("imagePath", imagePath)
            put("imageSize", imageSize)
            put("imageId", imageId)  // Include imageId for sync worker
            orderId?.let { put("orderId", it) }
        }.toString()

        val queueEntity = SyncQueueEntity(
            type = "image_upload",  // NEW type
            payload = payload,
            scanId = scanId
        )
        db.syncQueueDao().addToQueue(queueEntity)

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
                    transactionType = if (entity.transactionType == "IMAGE_UPLOAD") {
                        com.xelth.eckwms_movfast.ui.data.TransactionType.IMAGE_UPLOAD
                    } else {
                        com.xelth.eckwms_movfast.ui.data.TransactionType.BARCODE_SCAN
                    },
                    barcode = entity.barcode,
                    timestamp = entity.timestamp,
                    status = parseStatus(entity.status),
                    type = entity.type,
                    checksum = entity.checksum,
                    imagePath = entity.imagePath,
                    imageSize = entity.imageSize
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
                transactionType = if (entity.transactionType == "IMAGE_UPLOAD") {
                    com.xelth.eckwms_movfast.ui.data.TransactionType.IMAGE_UPLOAD
                } else {
                    com.xelth.eckwms_movfast.ui.data.TransactionType.BARCODE_SCAN
                },
                barcode = entity.barcode,
                timestamp = entity.timestamp,
                status = parseStatus(entity.status),
                type = entity.type,
                checksum = entity.checksum,
                imagePath = entity.imagePath,
                imageSize = entity.imageSize
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
