package com.xelth.eckwms_movfast.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.data.local.AppDatabase
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

            // Load bitmap
            val bitmap = android.graphics.BitmapFactory.decodeFile(imagePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap: $imagePath")
                scanId?.let {
                    database.scanDao().updateScanStatus(it, "FAILED")
                }
                return true // Fail permanently
            }

            val deviceId = com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceId(applicationContext)
            val quality = com.xelth.eckwms_movfast.utils.SettingsManager.getImageQuality()

            Log.d(TAG, "Uploading image from background: $imagePath (imageId: $imageId)")

            // Upload with SAME imageId for deduplication
            val result = apiService.uploadImage(bitmap, deviceId, "sync_worker", null, quality, orderId, imageId)

            bitmap.recycle() // Free memory

            when (result) {
                is ScanResult.Success -> {
                    scanId?.let {
                        database.scanDao().updateScanStatus(it, "CONFIRMED")
                        Log.d(TAG, "Updated image upload $it status to CONFIRMED")
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
