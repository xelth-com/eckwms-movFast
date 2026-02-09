package com.xelth.eckwms_movfast.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.InventoryRecordEntity
import com.xelth.eckwms_movfast.data.local.entity.LocationEntity
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
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

        // NOTE: Do NOT add to sync queue here. Only add if direct upload fails.
        // This prevents double upload (direct + sync worker).

        scanId
    }

    /**
     * Adds an EXISTING scan to the sync queue for background retry.
     * Used when immediate hybrid delivery fails — no new DB entry created.
     */
    suspend fun addScanToSyncQueue(scanId: Long) = withContext(Dispatchers.IO) {
        val scan = db.scanDao().getScanById(scanId) ?: return@withContext

        Log.d(TAG, "Queueing existing scan #$scanId for background sync")

        val payload = JSONObject().apply {
            put("barcode", scan.barcode)
            put("type", scan.type)
            scan.orderId?.let { put("orderId", it) }
        }.toString()

        val queueEntity = SyncQueueEntity(
            type = "scan",
            payload = payload,
            scanId = scanId
        )
        db.syncQueueDao().addToQueue(queueEntity)

        // Trigger sync worker
        SyncManager.scheduleSync(context)
    }

    suspend fun addImageUploadToSyncQueue(scanId: Long) = withContext(Dispatchers.IO) {
        val scan = db.scanDao().getScanById(scanId)
        if (scan == null || scan.imagePath == null || scan.imageId == null) {
            Log.w("WarehouseRepository", "Cannot add to sync queue: scan not found or missing data")
            return@withContext
        }

        // Add to sync queue for retry
        val payload = JSONObject().apply {
            put("imagePath", scan.imagePath)
            put("imageSize", scan.imageSize)
            put("imageId", scan.imageId)
            scan.orderId?.let { put("orderId", it) }
        }.toString()

        val queueEntity = SyncQueueEntity(
            type = "image_upload",
            payload = payload,
            scanId = scanId
        )
        db.syncQueueDao().addToQueue(queueEntity)

        // Trigger sync worker
        SyncManager.scheduleSync(context)
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
     * Logs a raw scan to the audit trail (Журнал событий)
     * This creates an immutable record of every scan event before any processing logic
     * @return Scan ID for later status updates
     */
    suspend fun logRawScan(barcode: String, type: String, orderId: String? = null): Long = withContext(Dispatchers.IO) {
        Log.d(TAG, "Logging raw scan to audit trail: $barcode")

        val scanEntity = ScanEntity(
            barcode = barcode,
            timestamp = System.currentTimeMillis(),
            status = "RAW", // Initial audit state before routing
            type = type,
            orderId = orderId
        )
        val scanId = db.scanDao().insertScan(scanEntity)
        Log.d(TAG, "Raw scan logged with ID: $scanId")

        scanId
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
     * Update scan status by string (for routing status tracking)
     */
    suspend fun updateScanStatusString(scanId: Long, status: String, checksum: String? = null) =
        withContext(Dispatchers.IO) {
            db.scanDao().updateScanStatus(scanId, status, checksum)
            Log.d(TAG, "Updated scan $scanId status to $status")
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

    // --- FAT CLIENT: Offline Reference Data ---

    /**
     * Look up a product by barcode or SKU in the local offline cache.
     * For 'i' smart codes, also extracts EAN and tries that.
     */
    suspend fun getLocalProduct(barcode: String): ProductEntity? = withContext(Dispatchers.IO) {
        // Try exact barcode/SKU match first
        val byBarcode = db.referenceDao().getProductByBarcode(barcode)
        if (byBarcode != null) return@withContext byBarcode

        // Fallback: decode 'i' smart code to extract EAN
        if (barcode.startsWith("i", ignoreCase = true) && barcode.length == 19) {
            try {
                val upper = barcode.uppercase()
                val splitCharIdx = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(upper[1])
                if (splitCharIdx > 0) {
                    val dataPart = upper.substring(2)
                    if (dataPart.length >= splitCharIdx) {
                        val ean = dataPart.substring(dataPart.length - splitCharIdx)
                        return@withContext db.referenceDao().getProductByBarcode(ean)
                    }
                }
            } catch (_: Exception) {}
        }
        null
    }

    /** Update product quantity in local cache after inventory count (upsert) */
    suspend fun updateLocalProductQty(barcode: String, qty: Double) = withContext(Dispatchers.IO) {
        val rows = db.referenceDao().updateProductQty(barcode, qty)
        if (rows == 0) {
            // Product not in local DB yet (smart code or unsynced). Create stub.
            val stub = ProductEntity(
                id = System.nanoTime(),
                defaultCode = barcode,
                name = "Item $barcode",
                barcode = barcode,
                qtyAvailable = qty,
                active = true,
                lastUpdated = System.currentTimeMillis()
            )
            db.referenceDao().insertProducts(listOf(stub))
            Log.d(TAG, "Created local stub product for $barcode with qty $qty")
        } else {
            Log.d(TAG, "Updated local qty for $barcode to $qty")
        }
    }

    /**
     * Look up a location by barcode in the local offline cache.
     * For 'p' smart codes (p + 18-digit Odoo ID), also tries lookup by extracted ID.
     */
    suspend fun getLocalLocation(barcode: String): LocationEntity? = withContext(Dispatchers.IO) {
        // Try exact barcode match first
        val byBarcode = db.referenceDao().getLocationByBarcode(barcode)
        if (byBarcode != null) return@withContext byBarcode

        // Fallback: extract Odoo ID from 'p' smart code
        if (barcode.startsWith("p") && barcode.length == 19) {
            try {
                val idStr = barcode.substring(1).trimStart('0')
                if (idStr.isNotEmpty()) {
                    val id = idStr.toLong()
                    return@withContext db.referenceDao().getLocationById(id)
                }
            } catch (_: NumberFormatException) {}
        }
        null
    }

    /**
     * Force refresh of reference data (Products & Locations) from server.
     * Called by SyncWorker periodically and by DatabaseViewerScreen manually.
     * @return true if at least one dataset was updated
     */
    suspend fun refreshReferenceData(): Boolean = withContext(Dispatchers.IO) {
        val api = com.xelth.eckwms_movfast.api.ScanApiService(context)
        var updated = false
        try {
            val prods = api.fetchProducts()
            if (prods.isNotEmpty()) {
                db.referenceDao().insertProducts(prods) // REPLACE strategy = upsert
                Log.d(TAG, "Refreshed ${prods.size} products")
                updated = true
            }

            val locs = api.fetchLocations()
            if (locs.isNotEmpty()) {
                db.referenceDao().insertLocations(locs)
                Log.d(TAG, "Refreshed ${locs.size} locations")
                updated = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh reference data", e)
        }
        updated
    }

    // --- INVENTORY RECORDS (PDA source of truth) ---

    /** Save counted inventory for a location. Replaces any previous records. */
    suspend fun saveInventoryRecords(locationBarcode: String, records: List<InventoryRecordEntity>) = withContext(Dispatchers.IO) {
        db.referenceDao().clearInventoryRecords(locationBarcode)
        if (records.isNotEmpty()) {
            db.referenceDao().insertInventoryRecords(records)
        }
        Log.d(TAG, "Saved ${records.size} inventory records for $locationBarcode")
    }

    /** Load previously counted inventory for a location */
    suspend fun getInventoryRecords(locationBarcode: String): List<InventoryRecordEntity> = withContext(Dispatchers.IO) {
        db.referenceDao().getInventoryRecords(locationBarcode)
    }

    // --- AVATAR LOOKUP ---

    /**
     * Retrieves an avatar bitmap for a given internal Smart Code (e.g., "i00001", "p00005").
     * Queries entity_attachments by res_id directly (the server stores full Smart Code as res_id).
     */
    suspend fun getAvatarForEntity(internalId: String): Bitmap? = withContext(Dispatchers.IO) {
        Log.d(TAG, "📷 Looking up avatar for: $internalId")

        // Query by full Smart Code (server stores it as res_id)
        val bytes = db.attachmentDao().getAvatarByResId(internalId)

        if (bytes == null) {
            Log.d(TAG, "📷 No avatar found in DB for: $internalId")
            return@withContext null
        }

        Log.d(TAG, "📷 Avatar found in DB for: $internalId (${bytes.size} bytes)")
        try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode avatar bitmap for $internalId", e)
            null
        }
    }

    /**
     * Parses internal IDs (Smart Codes) into Odoo Model + ID pairs.
     * i000...01 -> product, 1
     * p000...05 -> location, 5
     * b000...09 -> package, 9
     */
    private fun parseSmartCode(code: String): Pair<String, String>? {
        if (code.length < 2) return null

        val prefix = code[0].lowercaseChar()
        val idPart = code.substring(1)
        val idLong = idPart.toLongOrNull() ?: return null
        val idStr = idLong.toString()

        return when (prefix) {
            'i' -> "product" to idStr
            'p' -> "location" to idStr
            'b' -> "package" to idStr
            else -> null
        }
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
