package com.xelth.eckwms_movfast.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.data.local.entity.AttachmentEntity
import com.xelth.eckwms_movfast.data.local.entity.FileResourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val TAG = "SyncWorker"
    private val database = AppDatabase.getInstance(applicationContext)
    private val apiService = ScanApiService(applicationContext)
    private val MAX_RETRIES = 3

    override suspend fun doWork(): Result {
        Log.d(TAG, "Sync worker started")

        // 1. PULL Metadata (Avatars, Attachments) — non-fatal
        try {
            pullMetadataSync()
        } catch (e: Exception) {
            Log.w(TAG, "Metadata pull failed (non-fatal): ${e.message}")
        }

        // 2. Fat Client: refresh reference data (products/locations) on every sync cycle
        try {
            val repository = com.xelth.eckwms_movfast.data.WarehouseRepository.getInstance(applicationContext)
            val refSuccess = repository.refreshReferenceData()
            Log.d(TAG, "Reference data sync: ${if (refSuccess) "OK" else "skipped/failed"}")
        } catch (e: Exception) {
            Log.w(TAG, "Reference sync error (non-fatal): ${e.message}")
        }

        // 3. Process Outgoing Queue
        return try {
            val job = database.syncQueueDao().getNextJob()

            if (job == null) {
                Log.d(TAG, "No jobs in queue")
                return Result.success()
            }

            Log.d(TAG, "Processing job: id=${job.id}, type=${job.type}, retries=${job.retries}")

            when (job.type) {
                "scan" -> {
                    val result = processScanJob(job.payload, job.scanId)
                    if (result) {
                        database.syncQueueDao().deleteJob(job)
                        Log.d(TAG, "Job ${job.id} completed successfully")
                        Result.success()
                    } else {
                        handleRetry(job)
                    }
                }
                "image_upload" -> {
                    val result = processImageUploadJob(job.payload, job.scanId)
                    if (result) {
                        database.syncQueueDao().deleteJob(job)
                        Log.d(TAG, "Image upload job ${job.id} completed successfully")
                        Result.success()
                    } else {
                        handleRetry(job)
                    }
                }
                "repair_event" -> {
                    val result = processRepairEventJob(job.payload)
                    if (result) {
                        database.syncQueueDao().deleteJob(job)
                        Log.d(TAG, "Repair event job ${job.id} completed successfully")
                        Result.success()
                    } else {
                        handleRetry(job)
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown job type: ${job.type}")
                    database.syncQueueDao().deleteJob(job)
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during sync: ${e.message}", e)
            Result.retry()
        }
    }

    private suspend fun pullMetadataSync() = withContext(Dispatchers.IO) {
        val entities = listOf("file_resources", "attachments")
        val result = apiService.pullSync(null, entities)

        if (result is ScanResult.Success) {
            val json = JSONObject(result.data)

            // 1. Process File Resources
            val filesArray = json.optJSONArray("file_resources")
            var filesSynced = 0
            if (filesArray != null && filesArray.length() > 0) {
                val fileEntities = mutableListOf<FileResourceEntity>()
                for (i in 0 until filesArray.length()) {
                    val obj = filesArray.getJSONObject(i)

                    // Decode Base64 avatar_data if present
                    val avatarBase64 = obj.optString("avatar_data", "")
                    val avatarBytes = if (avatarBase64.isNotEmpty()) {
                        try { Base64.decode(avatarBase64, Base64.DEFAULT) } catch (e: Exception) { null }
                    } else null

                    fileEntities.add(FileResourceEntity(
                        id = obj.getString("id"),
                        hash = obj.getString("hash"),
                        originalName = obj.optString("originalName", ""),
                        mimeType = obj.optString("mimeType", ""),
                        sizeBytes = obj.optLong("sizeBytes", 0),
                        storagePath = obj.optString("storagePath", ""),
                        avatarData = avatarBytes,
                        createdAt = System.currentTimeMillis()
                    ))
                }
                database.fileResourceDao().insertFileResources(fileEntities)
                filesSynced = fileEntities.size
            }

            // 2. Process Attachments
            val attArray = json.optJSONArray("attachments")
            var attSynced = 0
            if (attArray != null && attArray.length() > 0) {
                val attEntities = mutableListOf<AttachmentEntity>()
                for (i in 0 until attArray.length()) {
                    val obj = attArray.getJSONObject(i)
                    attEntities.add(AttachmentEntity(
                        id = obj.getString("id"),
                        fileResourceId = obj.getString("file_resource_id"),
                        resModel = obj.getString("res_model"),
                        resId = obj.getString("res_id"),
                        isMain = obj.optBoolean("is_main", false),
                        tags = obj.optString("tags", ""),
                        createdAt = System.currentTimeMillis()
                    ))
                }
                database.attachmentDao().insertAttachments(attEntities)
                attSynced = attEntities.size
            }

            if (filesSynced > 0 || attSynced > 0) {
                Log.i(TAG, "✅ Synced $filesSynced files and $attSynced attachments")
            }
        }
    }

    private suspend fun processScanJob(payload: String, scanId: Long?): Boolean {
        return try {
            val json = JSONObject(payload)
            val barcode = json.getString("barcode")
            val type = json.getString("type")
            val orderId = if (json.has("orderId")) json.getString("orderId") else null

            Log.d(TAG, "Sending scan to server: $barcode ($type)")

            val result = apiService.processScan(barcode, type, orderId)

            when (result) {
                is ScanResult.Success -> {
                    // Update local scan status if scanId is available
                    scanId?.let {
                        database.scanDao().updateScanStatus(
                            id = it,
                            status = "BUFFERED",
                            checksum = result.checksum
                        )
                        Log.d(TAG, "Updated local scan $it status to BUFFERED")
                    }
                    true
                }
                is ScanResult.Error -> {
                    Log.e(TAG, "Server error: ${result.message}")
                    scanId?.let {
                        database.scanDao().updateScanStatus(it, "FAILED")
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan job: ${e.message}", e)
            false
        }
    }

    private suspend fun processImageUploadJob(payload: String, scanId: Long?): Boolean {
        return try {
            // Check if scan is already CONFIRMED (direct upload succeeded)
            scanId?.let {
                val scan = database.scanDao().getScanById(it)
                if (scan?.status == "CONFIRMED") {
                    Log.d(TAG, "Scan $it already CONFIRMED (direct upload succeeded), skipping sync worker upload")
                    return true // Job complete, remove from queue
                }
            }

            val json = JSONObject(payload)
            val imagePath = json.getString("imagePath")
            val imageId = json.getString("imageId")  // Read imageId from payload
            val orderId = if (json.has("orderId")) json.getString("orderId") else null

            Log.d(TAG, "Processing image upload job - imageId: $imageId")

            val file = java.io.File(imagePath)
            if (!file.exists()) {
                Log.e(TAG, "Image file not found: $imagePath")
                scanId?.let {
                    database.scanDao().updateScanStatus(it, "FAILED")
                }
                return true // Fail permanently (cannot retry if file missing)
            }

            // OPTIMIZATION: Stream directly from file! No bitmap decoding needed.
            // This saves ~40MB RAM per upload and avoids double-compression.
            val deviceId = com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceId(applicationContext)

            Log.d(TAG, "🚀 Streaming image from background: $imagePath (imageId: $imageId)")

            // Use new streaming method - no Bitmap allocation!
            val result = apiService.uploadImageFile(imagePath, deviceId, "sync_worker", null, orderId, imageId)

            when (result) {
                is ScanResult.Success -> {
                    scanId?.let {
                        database.scanDao().updateScanStatus(it, "CONFIRMED")
                        Log.d(TAG, "Updated image upload $it status to CONFIRMED")
                    }
                    // Cleanup: delete local file after confirmed upload
                    if (file.delete()) {
                        Log.d(TAG, "Cleanup: deleted ${file.name} after confirmed upload")
                    }
                    true
                }
                is ScanResult.Error -> {
                    Log.e(TAG, "Image upload failed: ${result.message}")
                    scanId?.let {
                        database.scanDao().updateScanStatus(it, "FAILED")
                    }
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing image upload job: ${e.message}", e)
            false
        }
    }

    private suspend fun processRepairEventJob(payload: String): Boolean {
        return try {
            val json = JSONObject(payload)
            val targetDeviceId = json.getString("targetDeviceId")
            val eventType = json.getString("eventType")
            val data = json.getString("data")

            Log.d(TAG, "Sending repair event: $eventType -> $targetDeviceId")

            val result = apiService.sendRepairEvent(targetDeviceId, eventType, data)
            when (result) {
                is ScanResult.Success -> {
                    Log.d(TAG, "✅ Repair event delivered: $eventType")
                    true
                }
                is ScanResult.Error -> {
                    Log.e(TAG, "❌ Repair event failed: ${result.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing repair event job: ${e.message}", e)
            false
        }
    }

    private suspend fun handleRetry(job: com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity): Result {
        return if (job.retries < MAX_RETRIES) {
            database.syncQueueDao().updateRetries(job.id, job.retries + 1)
            Log.d(TAG, "Job ${job.id} will be retried (attempt ${job.retries + 1}/$MAX_RETRIES)")
            Result.retry()
        } else {
            Log.e(TAG, "Job ${job.id} exceeded max retries, removing from queue")
            job.scanId?.let {
                database.scanDao().updateScanStatus(it, "FAILED")
            }
            database.syncQueueDao().deleteJob(job)
            Result.failure()
        }
    }
}
