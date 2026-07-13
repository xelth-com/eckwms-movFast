// app/src/main/java/com/xelth/eckwms_movfast/api/ScanApiService.kt
package com.xelth.eckwms_movfast.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.CRC32
import com.xelth.eckwms_movfast.data.local.entity.LocationEntity
import com.xelth.eckwms_movfast.data.local.entity.ProductEntity
import com.xelth.eckwms_movfast.scanners.ScannerManager

/**
 * Сервис для взаимодействия с API сканирования штрих-кодов
 * Updated: URL construction now relies on SettingsManager base URL without hardcoded prefix
 */
/** A planned trip-purpose candidate for the start picker (Level A). */
data class PurposeCandidate(
    val purposeRef: String,   // visit_task:xxx | order:yyy — sent back as purpose_ref
    val label: String,        // destination / customer name
    val address: String?,
    val distanceKm: Double?,  // present when a position was supplied
    val overdue: Boolean,
    val lat: Double? = null,  // geocoded position (for the planning map markers)
    val lng: Double? = null
)

/** A registered vehicle (Fahrtenbuch) returned by /api/vehicles. */
data class Vehicle(
    val id: String,
    val plate: String,
    val label: String?,
    val photoId: String?,
    val active: Boolean
)

/** A trip destination row (ticket) for the interactive console (trip mode). */
data class Destination(
    val purposeRef: String,   // order:<id>
    val label: String,        // customer name (or order no.)
    val address: String?,
    val city: String?,
    val lat: Double?,
    val lng: Double?
)

/** A city with waiting tickets — a city button in the trip grid. */
data class CityCount(val city: String, val count: Int)

data class DestinationsResult(val cities: List<CityCount>, val results: List<Destination>)

/** Result of POST /api/voice/resolve (Voice Commands P2 — Gemini fallback). */
data class VoiceResolveResult(
    val action: String?,     // matched grid action (one of the supplied), or null
    val needsAudio: Boolean, // true → re-send WITH audio for a multimodal re-listen
    val reason: String,
    val source: String       // "gemini" | "off"
)

/**
 * Outcome of a server write that the offline SyncWorker may retry.
 * Distinguishes a permanent server rejection (4xx — retrying is pointless and
 * would silently mask the rejection) from a transient failure (network / 5xx —
 * worth retrying). See picking_confirm/picking_validate handling.
 */
enum class SyncOutcome { SUCCESS, REJECTED, FAILED }

class ScanApiService(private val context: Context) {
    private val TAG = "ScanApiService"

    // Ссылка на ScannerManager для получения информации о типе штрих-кода
    private var scannerManager: ScannerManager? = null

    // Canonical device identifier for server attribution: the server-minted UUID
    // once known, else the raw ANDROID_ID (see SettingsManager.getDeviceId). A
    // property getter (not a cached `by lazy`) so it picks up the UUID the instant
    // the server assigns it via register-device / /api/status.
    private val deviceId: String
        get() = com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceId(context)

    // Установка ScannerManager извне
    fun setScannerManager(manager: ScannerManager) {
        this.scannerManager = manager
    }

    /**
     * Ensures the base URL contains the /E microservice prefix.
     * Local IPs like http://192.168.0.206:3210/E already have it.
     * Domain URLs like https://pda.repair may not — this adds /E if missing.
     */
    private fun ensureApiPrefix(baseUrl: String): String {
        val url = baseUrl.removeSuffix("/")
        // Already has /E or /e prefix
        if (url.endsWith("/E") || url.endsWith("/e") || url.contains("/E/") || url.contains("/e/")) {
            return url
        }
        return "$url/E"
    }

    /**
     * Отправляет отсканированный штрих-код на сервер
     * Implements Immediate Failover: Local -> Global
     * @param barcode Отсканированный штрих-код
     * @param barcodeType Тип штрих-кода (QR_CODE, CODE_128, и т.д.)
     * @return Результат обработки штрих-кода
     */
    suspend fun processScan(barcode: String, barcodeType: String, orderId: String? = null): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        // 1. Try Active URL first
        var result = internalProcessScan(activeUrl, barcode, barcodeType, orderId)

        // 2. If failed (Network Error) AND Active != Global, try Global immediately
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ Scan to $activeUrl failed. Failover to Global: $globalUrl")
            result = internalProcessScan(globalUrl, barcode, barcodeType, orderId)

            if (result is ScanResult.Success) {
                // Global failover success - but keep local as active for next attempt
                Log.i(TAG, "✅ Global failover success (keeping local as active for next attempt)")
            }
        }

        return@withContext result
    }

    /**
     * Internal helper for scan processing to avoid code duplication
     */
    private suspend fun internalProcessScan(baseUrl: String, barcode: String, barcodeType: String, orderId: String?): ScanResult {
        Log.d(TAG, "Processing scan: $barcode (type: $barcodeType) to $baseUrl")

        try {
            val finalUrl = "$baseUrl/api/scan"
            Log.e(TAG, "Target URL for Scan: $finalUrl")
            val url = URL(finalUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000 // 5 sec timeout for faster failover
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            // Create JSON request - Go server expects 'barcode' field
            val payloadJson = JSONObject().apply {
                put("deviceId", deviceId)
                put("barcode", barcode)
                put("type", barcodeType)
            }
            val payloadBytes = payloadJson.toString().toByteArray()
            val crc = CRC32()
            crc.update(payloadBytes)
            val checksum = crc.value.toString(16).padStart(8, '0')

            val jsonRequest = JSONObject().apply {
                put("deviceId", deviceId)
                put("barcode", barcode)
                put("type", barcodeType)
                put("checksum", checksum)
                orderId?.let { put("orderId", it) }
            }

            Log.d(TAG, "Sending request to $url: $jsonRequest")

            // Отправляем запрос
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            // Получаем ответ
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                // Читаем успешный ответ
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Server response: $response")

                // Parse the JSON response to extract checksum and ai_interaction
                val responseJson = JSONObject(response)
                val responseChecksum = responseJson.optString("checksum", "")
                val message = responseJson.optString("message", "Scan buffered successfully")

                Log.d(TAG, "Extracted checksum: $responseChecksum")

                // Parse AI interaction if present
                var aiInteraction = if (responseJson.has("ai_interaction") && !responseJson.isNull("ai_interaction")) {
                    try {
                        val aiJson = responseJson.getJSONObject("ai_interaction")
                        val id = aiJson.optString("id", null)
                        val type = aiJson.optString("type", "info")
                        val aiMessage = aiJson.optString("message", "")
                        val aiBarcode = aiJson.optString("barcode", barcode)

                        // Support both "options" (Standard) and "suggestedActions" (Gemini AI)
                        val options = if (aiJson.has("options")) {
                            val optionsArray = aiJson.getJSONArray("options")
                            (0 until optionsArray.length()).map { optionsArray.getString(it) }
                        } else if (aiJson.has("suggestedActions")) {
                            val optionsArray = aiJson.getJSONArray("suggestedActions")
                            (0 until optionsArray.length()).map { optionsArray.getString(it) }
                        } else null

                        val data = if (aiJson.has("data")) {
                            val dataJson = aiJson.getJSONObject("data")
                            dataJson.keys().asSequence().associateWith { dataJson.get(it) }
                        } else null

                        com.xelth.eckwms_movfast.ui.data.AiInteraction(
                            id = id,
                            type = type,
                            message = aiMessage,
                            options = options,
                            data = data,
                            barcode = aiBarcode
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse ai_interaction: ${e.message}")
                        null
                    }
                } else null

                // Synthesize AiInteraction for ambiguous collisions
                if (aiInteraction == null && responseJson.optString("type") == "ambiguous") {
                    try {
                        val dataObj = responseJson.optJSONObject("data")
                        val candidatesArray = dataObj?.optJSONArray("candidates")
                        if (candidatesArray != null && candidatesArray.length() > 0) {
                            val optionsList = mutableListOf<String>()
                            for (i in 0 until candidatesArray.length()) {
                                val c = candidatesArray.getJSONObject(i)
                                optionsList.add(c.optString("title", "Unknown"))
                            }
                            aiInteraction = com.xelth.eckwms_movfast.ui.data.AiInteraction(
                                id = "collision_" + System.currentTimeMillis(),
                                type = "question",
                                message = responseJson.optString("message", "Multiple matches found. Select one:"),
                                options = optionsList,
                                data = mapOf("raw_candidates" to dataObj.toString()),
                                barcode = barcode
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to build ambiguous interaction: ${e.message}")
                    }
                }

                if (aiInteraction != null) {
                    Log.d(TAG, "AI Interaction detected: ${aiInteraction.type} - ${aiInteraction.message}")
                }

                return ScanResult.Success(
                    type = "scan",
                    message = message,
                    data = response,
                    checksum = responseChecksum,
                    aiInteraction = aiInteraction
                )
            } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                // 401 Unauthorized
                Log.w(TAG, "401 Unauthorized on scan")
                return ScanResult.Error("Authentication required")
            } else {
                // Обрабатываем ошибку
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"

                Log.e(TAG, "Server error: $errorMessage")
                return ScanResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection failed to $baseUrl: ${e.message}")
            return ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Process scan with message ID for deduplication (used by HybridMessageSender)
     * Implements Immediate Failover: Local -> Global
     * @param barcode The barcode value
     * @param barcodeType The type of barcode
     * @param msgId The unique message ID for deduplication
     * @return Result of the scan operation
     */
    suspend fun processScanWithId(barcode: String, barcodeType: String, msgId: String): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        // 1. Try Active URL first
        var result = internalProcessScanWithId(activeUrl, barcode, barcodeType, msgId)

        // 2. If failed AND Active != Global, try Global immediately
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ ScanWithID to $activeUrl failed. Failover to Global: $globalUrl")
            result = internalProcessScanWithId(globalUrl, barcode, barcodeType, msgId)

            if (result is ScanResult.Success) {
                // Global failover success - but keep local as active for next attempt
                Log.i(TAG, "✅ Global failover success for ScanWithID (keeping local as active for next attempt)")
            }
        }

        return@withContext result
    }

    /**
     * Internal helper for scan processing with message ID
     */
    private suspend fun internalProcessScanWithId(baseUrl: String, barcode: String, barcodeType: String, msgId: String): ScanResult {
        Log.d(TAG, "Processing scan with ID: $barcode (type: $barcodeType, msgId: $msgId) to $baseUrl")

        try {
            val finalUrl = "$baseUrl/api/scan"
            Log.e(TAG, "Target URL for ScanWithID: $finalUrl")
            val url = URL(finalUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000 // 5 sec timeout for faster failover
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            // Create JSON request with msgId for deduplication - Go server expects 'barcode'
            val payloadJson = JSONObject().apply {
                put("deviceId", deviceId)
                put("barcode", barcode)
                put("type", barcodeType)
                put("msgId", msgId)
            }
            val payloadBytes = payloadJson.toString().toByteArray()
            val crc = CRC32()
            crc.update(payloadBytes)
            val checksum = crc.value.toString(16).padStart(8, '0')

            val jsonRequest = JSONObject().apply {
                put("deviceId", deviceId)
                put("barcode", barcode)
                put("type", barcodeType)
                put("checksum", checksum)
                put("msgId", msgId)
            }

            Log.d(TAG, "Sending request with msgId to $url: $jsonRequest")

            // Send request
            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            // Get response
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Server response (msgId=$msgId): $response")

                val responseJson = JSONObject(response)
                val responseChecksum = responseJson.optString("checksum", "")
                val message = responseJson.optString("message", "Scan buffered successfully")

                Log.d(TAG, "Extracted checksum (msgId=$msgId): $responseChecksum")

                // Parse AI interaction if present
                var aiInteraction = if (responseJson.has("ai_interaction") && !responseJson.isNull("ai_interaction")) {
                    try {
                        val aiJson = responseJson.getJSONObject("ai_interaction")
                        val id = aiJson.optString("id", null)
                        val type = aiJson.optString("type", "info")
                        val aiMessage = aiJson.optString("message", "")
                        val aiBarcode = aiJson.optString("barcode", barcode)

                        // Support both "options" (Standard) and "suggestedActions" (Gemini AI)
                        val options = if (aiJson.has("options")) {
                            val optionsArray = aiJson.getJSONArray("options")
                            (0 until optionsArray.length()).map { optionsArray.getString(it) }
                        } else if (aiJson.has("suggestedActions")) {
                            val optionsArray = aiJson.getJSONArray("suggestedActions")
                            (0 until optionsArray.length()).map { optionsArray.getString(it) }
                        } else null

                        val data = if (aiJson.has("data")) {
                            val dataJson = aiJson.getJSONObject("data")
                            dataJson.keys().asSequence().associateWith { dataJson.get(it) }
                        } else null

                        com.xelth.eckwms_movfast.ui.data.AiInteraction(
                            id = id,
                            type = type,
                            message = aiMessage,
                            options = options,
                            data = data,
                            barcode = aiBarcode
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse ai_interaction (msgId=$msgId): ${e.message}")
                        null
                    }
                } else null

                // Synthesize AiInteraction for ambiguous collisions
                if (aiInteraction == null && responseJson.optString("type") == "ambiguous") {
                    try {
                        val dataObj = responseJson.optJSONObject("data")
                        val candidatesArray = dataObj?.optJSONArray("candidates")
                        if (candidatesArray != null && candidatesArray.length() > 0) {
                            val optionsList = mutableListOf<String>()
                            for (i in 0 until candidatesArray.length()) {
                                val c = candidatesArray.getJSONObject(i)
                                optionsList.add(c.optString("title", "Unknown"))
                            }
                            aiInteraction = com.xelth.eckwms_movfast.ui.data.AiInteraction(
                                id = "collision_" + System.currentTimeMillis(),
                                type = "question",
                                message = responseJson.optString("message", "Multiple matches found. Select one:"),
                                options = optionsList,
                                data = mapOf("raw_candidates" to dataObj.toString()),
                                barcode = barcode
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to build ambiguous interaction (msgId=$msgId): ${e.message}")
                    }
                }

                if (aiInteraction != null) {
                    Log.d(TAG, "AI Interaction detected (msgId=$msgId): ${aiInteraction.type} - ${aiInteraction.message}")
                }

                return ScanResult.Success(
                    type = "scan",
                    message = message,
                    data = response,
                    checksum = responseChecksum,
                    aiInteraction = aiInteraction
                )
            } else {
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"

                Log.e(TAG, "Server error (msgId=$msgId): $errorMessage")
                return ScanResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connection failed to $baseUrl (msgId=$msgId): ${e.message}")
            return ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Pulls sync data from the server (authenticated device pull).
     * Used to fetch file_resources, attachments, and other entity types.
     */
    suspend fun pullSync(since: String? = null, entityTypes: List<String>): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = internalPullSync(activeUrl, since, entityTypes)

        // Auto-retry on 401
        if (result is ScanResult.Error && result.message.contains("401")) {
            val newToken = performSilentAuth()
            if (newToken != null) {
                result = internalPullSync(activeUrl, since, entityTypes)
            }
        }

        // Failover to Global
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ pullSync failed on $activeUrl. Failover to Global.")
            result = internalPullSync(globalUrl, since, entityTypes)
        }

        return@withContext result
    }

    private suspend fun internalPullSync(baseUrl: String, since: String?, entityTypes: List<String>): ScanResult {
        try {
            val url = URL("$baseUrl/api/sync/pull")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val jsonRequest = JSONObject().apply {
                val typesArray = JSONArray()
                entityTypes.forEach { typesArray.put(it) }
                put("entity_types", typesArray)
                if (since != null) put("since", since)
            }

            Log.d(TAG, "Pull Sync request: $jsonRequest to $url")

            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return ScanResult.Success("sync_pull", "Sync Data Received", response)
            } else {
                return ScanResult.Error("HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in pullSync", e)
            return ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Optimized upload that streams file directly without Bitmap decoding
     * Saves ~40MB RAM per upload and avoids double-compression
     * Implements Immediate Failover: Local -> Global
     * Auto-retries on 401 (token expired)
     * @param filePath Path to the image file on disk
     * @param deviceId The device identifier
     * @param scanMode The scan mode ("sync_worker", "direct_upload", etc.)
     * @param barcodeData Optional barcode data
     * @param orderId Optional order ID
     * @param existingImageId Optional pre-generated image ID (for retry scenarios)
     * @return Result of the upload operation
     */
    suspend fun uploadImageFile(filePath: String, deviceId: String, scanMode: String, barcodeData: String?, orderId: String? = null, existingImageId: String? = null, avatarPath: String? = null): ScanResult = withContext(Dispatchers.IO) {
        val imageId = existingImageId ?: UUID.randomUUID().toString()
        val file = java.io.File(filePath)

        if (!file.exists()) {
            return@withContext ScanResult.Error("File not found: $filePath")
        }

        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        Log.d(TAG, "uploadImageFile START - activeUrl: $activeUrl, file: ${file.name}, imageId: $imageId, avatar: ${avatarPath != null}")

        // 1. Try Active URL
        var result = internalUploadFileStream(activeUrl, file, deviceId, scanMode, barcodeData, orderId, imageId, avatarPath)

        // 1.1 Auto-Retry on 401 (Token Expired)
        if (result is ScanResult.Error && result.message.contains("401")) {
            Log.w(TAG, "🔑 401 Unauthorized - attempting silent re-auth...")
            val newToken = performSilentAuth()
            if (newToken != null) {
                Log.i(TAG, "🔄 Token refreshed, retrying upload...")
                result = internalUploadFileStream(activeUrl, file, deviceId, scanMode, barcodeData, orderId, imageId, avatarPath)
            }
        }

        // 2. Failover to Global
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ UploadFile failed. Failover to Global. ImageID: $imageId")
            result = internalUploadFileStream(globalUrl, file, deviceId, scanMode, barcodeData, orderId, imageId, avatarPath)

            // 2.1 Auto-Retry on 401 for Global too
            if (result is ScanResult.Error && result.message.contains("401")) {
                val newToken = performSilentAuth()
                if (newToken != null) {
                    result = internalUploadFileStream(globalUrl, file, deviceId, scanMode, barcodeData, orderId, imageId, avatarPath)
                }
            }
        }

        // 3. Relay fallback (mobile data, LAN master unreachable) — mirrors
        //    uploadImage: land the bytes as an `image_upload` mesh-task.
        if (result is ScanResult.Error &&
            com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId().isNotEmpty()
        ) {
            val bytes = try { file.readBytes() } catch (e: Exception) { null }
            if (bytes != null &&
                uploadImageBytesViaRelay(bytes, deviceId, scanMode, barcodeData, orderId, imageId)
            ) {
                result = ScanResult.Success("upload", "Image uploaded via relay",
                    JSONObject().put("relay", true).put("image_id", imageId).toString())
            }
        }

        Log.d(TAG, "uploadImageFile END - result: ${result::class.simpleName}, imageId: $imageId")
        return@withContext result
    }

    /**
     * Internal helper - streams file bytes directly to network
     * No Bitmap decoding = Low RAM usage, no compression overhead
     */
    private suspend fun internalUploadFileStream(
        baseUrl: String,
        file: java.io.File,
        deviceId: String,
        scanMode: String,
        barcodeData: String?,
        orderId: String?,
        imageId: String,
        avatarPath: String? = null
    ): ScanResult {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val finalUrl = "$baseUrl/api/upload/image"
        Log.e(TAG, "Target URL for File Stream Upload: $finalUrl (ImageID: $imageId, File: ${file.name})")

        val url = URL(finalUrl)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15000
            connection.readTimeout = 60000
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.setRequestProperty("X-Idempotency-Key", imageId)
            connection.setRequestProperty("X-Image-ID", imageId)

            // Add Target Instance header for Smart Routing
            val homeInstanceId = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
            if (homeInstanceId.isNotEmpty()) {
                connection.setRequestProperty("X-Target-Instance", homeInstanceId)
            }

            connection.doOutput = true
            connection.setChunkedStreamingMode(0) // Efficient streaming

            val outputStream = connection.outputStream
            val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)

            // Write form fields
            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"imageId\"\r\n\r\n")
            writer.append("$imageId\r\n")

            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"deviceId\"\r\n\r\n")
            writer.append("$deviceId\r\n")

            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"scanMode\"\r\n\r\n")
            writer.append("$scanMode\r\n")

            if (barcodeData != null) {
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"barcodeData\"\r\n\r\n")
                writer.append("$barcodeData\r\n")
            }

            if (orderId != null) {
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"orderId\"\r\n\r\n")
                writer.append("$orderId\r\n")
            }

            // Stream file directly
            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"image\"; filename=\"${file.name}\"\r\n")
            writer.append("Content-Type: image/webp\r\n\r\n")
            writer.flush()

            val fileInputStream = java.io.FileInputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalBytes = 0L
            while (fileInputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalBytes += bytesRead
            }
            outputStream.flush()
            fileInputStream.close()

            Log.d(TAG, "Streamed $totalBytes bytes from ${file.name}")

            writer.append("\r\n")

            // Stream avatar file if provided
            if (avatarPath != null) {
                val avatarFile = java.io.File(avatarPath)
                if (avatarFile.exists()) {
                    writer.append("--$boundary\r\n")
                    writer.append("Content-Disposition: form-data; name=\"avatar\"; filename=\"${avatarFile.name}\"\r\n")
                    writer.append("Content-Type: image/webp\r\n\r\n")
                    writer.flush()

                    val avatarStream = java.io.FileInputStream(avatarFile)
                    var avatarBytesRead: Int
                    while (avatarStream.read(buffer).also { avatarBytesRead = it } != -1) {
                        outputStream.write(buffer, 0, avatarBytesRead)
                    }
                    outputStream.flush()
                    avatarStream.close()

                    writer.append("\r\n")
                    Log.d(TAG, "Attached avatar: ${avatarFile.length()} bytes")
                }
            }

            writer.append("--$boundary--\r\n")
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "File stream upload successful: $response")

                val responseJson = JSONObject(response)
                val message = responseJson.optString("message", "File uploaded successfully")

                return ScanResult.Success("upload", message, response)
            } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.e(TAG, "401 Unauthorized - token expired")
                return ScanResult.Error("401 Unauthorized")
            } else {
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "HTTP $responseCode"
                Log.e(TAG, "Upload failed: $errorMessage")
                return ScanResult.Error("Upload failed: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "File stream upload error: ${e.message}", e)
            return ScanResult.Error(e.message ?: "Upload stream error")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Uploads an image to the server with optional barcode data
     * Implements Immediate Failover: Local -> Global
     * Modified to require client-side generated imageId for deduplication
     * @param bitmap The image to upload
     * @param deviceId The device identifier
     * @param scanMode The scan mode ("dumb" or "mlkit")
     * @param barcodeData Optional barcode data from ML Kit analysis
     * @param quality Image compression quality
     * @param orderId Optional order ID
     * @param existingImageId Optional pre-generated image ID (for retry scenarios)
     * @return Result of the upload operation
     */
    suspend fun uploadImage(bitmap: Bitmap, deviceId: String, scanMode: String, barcodeData: String?, quality: Int, orderId: String? = null, existingImageId: String? = null): ScanResult = withContext(Dispatchers.IO) {
        // Generate ID at the Edge if not provided (Source of Truth)
        val imageId = existingImageId ?: UUID.randomUUID().toString()

        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        Log.d(TAG, "uploadImage START - activeUrl: $activeUrl, globalUrl: $globalUrl, scanMode: $scanMode, imageId: $imageId, Thread: ${Thread.currentThread().name}")

        // 1. Try Active URL first
        var result = internalUploadImage(activeUrl, bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)

        Log.d(TAG, "uploadImage FIRST_ATTEMPT result: ${result::class.simpleName}, imageId: $imageId")

        // 1.1 Auto-retry on 401 (token expired)
        if (result is ScanResult.Error && result.message.contains("401")) {
            Log.w(TAG, "🔑 uploadImage 401 - attempting silent re-auth...")
            val newToken = performSilentAuth()
            if (newToken != null) {
                result = internalUploadImage(activeUrl, bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)
            }
        }

        // 2. If failed AND Active != Global, try Global immediately with SAME imageId
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ Upload to $activeUrl failed. Failover to Global. ImageID: $imageId")
            // Retry with SAME imageId - critical for deduplication!
            result = internalUploadImage(globalUrl, bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)

            Log.d(TAG, "uploadImage FAILOVER result: ${result::class.simpleName}, imageId: $imageId")

            if (result is ScanResult.Success) {
                // Global failover success - but keep local as active for next attempt
                Log.i(TAG, "✅ Global upload success (keeping local as active for next attempt)")
            }
        }

        // 3. Still failed (both direct URLs unreachable — e.g. phone on mobile data,
        //    LAN master out of reach) → relay fallback: land the photo in CAS through
        //    the relay polygon, mirroring uploadTrip. Reuses the SAME imageId.
        if (result is ScanResult.Error &&
            com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId().isNotEmpty()
        ) {
            if (uploadImageViaRelay(bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)) {
                result = ScanResult.Success("upload", "Image uploaded via relay",
                    JSONObject().put("relay", true).put("image_id", imageId).toString())
            }
        }

        Log.d(TAG, "uploadImage END - final result: ${result::class.simpleName}, imageId: $imageId")

        return@withContext result
    }

    /** Relay fallback for image upload — the phone can't reach the LAN master's
     *  /api/upload/image (mobile data), so it base64s the WEBP into an
     *  `image_upload` mesh-task on the relay polygon targeting the master (device
     *  JWT gated). Mirrors [uploadTripViaRelay]; reuses the caller's imageId so the
     *  CAS dedupe is stable across a later direct re-upload. */
    private suspend fun uploadImageViaRelay(
        bitmap: Bitmap, deviceId: String, scanMode: String, barcodeData: String?,
        quality: Int, orderId: String?, imageId: String
    ): Boolean {
        val bytes = try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "Image relay: compress failed (${e.message})"); return false
        }
        return uploadImageBytesViaRelay(bytes, deviceId, scanMode, barcodeData, orderId, imageId)
    }

    /** Byte-level relay upload — shared by the bitmap path above and the
     *  file-based path ([uploadImageFile], e.g. queued receipt photos). */
    private suspend fun uploadImageBytesViaRelay(
        bytes: ByteArray, deviceId: String, scanMode: String, barcodeData: String?,
        orderId: String?, imageId: String
    ): Boolean {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("image_id", imageId)
            put("scan_mode", scanMode)
            barcodeData?.let { put("barcode_data", it) }
            orderId?.let { put("order_id", it) }
            put("file_name", "$imageId.webp")
            put("mime_type", "image/webp")
            put("image_b64", b64)
        }
        return dispatchMeshTaskWithReAuth("image_upload", body).outcome == MeshOutcome.OK
    }

    /**
     * Internal helper for image upload
     * @param imageId Required client-generated unique ID for deduplication
     */
    private suspend fun internalUploadImage(baseUrl: String, bitmap: Bitmap, deviceId: String, scanMode: String, barcodeData: String?, quality: Int, orderId: String?, imageId: String): ScanResult {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val finalUrl = "$baseUrl/api/upload/image"
        Log.e(TAG, "Target URL for Image Upload: $finalUrl (ImageID: $imageId)")

        val url = URL(finalUrl)
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000 // 10s for upload
            connection.readTimeout = 30000 // 30s for upload response
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Add Idempotency Key header (modern standard)
            connection.setRequestProperty("X-Idempotency-Key", imageId)
            // Also send as header for proxy routing hints
            connection.setRequestProperty("X-Image-ID", imageId)

            // Add Target Instance header for Smart Routing
            // This tells any server (even a Blind Relay) where this data belongs
            val homeInstanceId = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
            if (homeInstanceId.isNotEmpty()) {
                connection.setRequestProperty("X-Target-Instance", homeInstanceId)
                Log.d(TAG, "Smart Routing: X-Target-Instance=$homeInstanceId")
            }

            connection.doOutput = true

            val outputStream = connection.outputStream
            val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)

            // 1. Send Image ID FIRST (The most important field for deduplication)
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"imageId\"").append("\r\n")
            writer.append("\r\n").append(imageId).append("\r\n").flush()

            // Send deviceId
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"deviceId\"").append("\r\n")
            writer.append("\r\n").append(deviceId).append("\r\n").flush()

            // Send scanMode
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"scanMode\"").append("\r\n")
            writer.append("\r\n").append(scanMode).append("\r\n").flush()

            // Send barcodeData if available
            barcodeData?.let {
                writer.append("--$boundary").append("\r\n")
                writer.append("Content-Disposition: form-data; name=\"barcodeData\"").append("\r\n")
                writer.append("\r\n").append(it).append("\r\n").flush()
            }

            // Send orderId if available
            orderId?.let {
                writer.append("--$boundary").append("\r\n")
                writer.append("Content-Disposition: form-data; name=\"orderId\"").append("\r\n")
                writer.append("\r\n").append(it).append("\r\n").flush()
            }

            // Compress image and calculate checksum
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
            val imageBytes = stream.toByteArray()

            val crc = CRC32()
            crc.update(imageBytes)
            val imageChecksum = crc.value.toString(16).padStart(8, '0')
            Log.d(TAG, "Compressed image size: ${imageBytes.size} bytes, Checksum: $imageChecksum, ImageID: $imageId")

            // Send imageChecksum
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"imageChecksum\"").append("\r\n")
            writer.append("\r\n").append(imageChecksum).append("\r\n").flush()

            // Send image file with imageId as filename
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"image\"; filename=\"${imageId}.webp\"").append("\r\n")
            writer.append("Content-Type: image/webp").append("\r\n")
            writer.append("\r\n").flush()
            outputStream.write(imageBytes)
            outputStream.flush()
            writer.append("\r\n").flush()

            // End of multipart data
            writer.append("--$boundary--").append("\r\n").flush()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Image upload successful (ImageID: $imageId): $response")
                return ScanResult.Success("upload", "Image uploaded", response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.e(TAG, "Image upload failed (ImageID: $imageId): $error")
                return ScanResult.Error("Upload failed: $error")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Upload error to $baseUrl (ImageID: $imageId): ${e.message}")
            return ScanResult.Error(e.message ?: "Unknown upload error")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Registers the device with the server for secure pairing
     * @param publicKeyBase64 The device's Ed25519 public key in Base64 format
     * @param signature The signature of the pairing request signed with the device's private key
     * @param timestamp The timestamp used in the signature
     * @return Result of the registration operation
     */
    /**
     * Resolve a short-lived onboarding CODE (typed instead of scanning a QR) into a
     * pairing QR payload, via the well-known onboarding resolver(s) (9eck.com, then
     * xelth.com). The server maps an unexpired code → an `ECK$…` QR string; the caller
     * then runs the normal pairing flow with it (direct or relay-forwarded).
     *
     * Walk semantics: transport error / 5xx → next resolver; **404 = invalid/expired**
     * (authoritative, stop); 2xx with a non-blank `qr` → success. Returns the QR string,
     * or null if the code is invalid/expired or every resolver is unreachable.
     *
     * Server contract: `POST {resolver}/E/pair/code` body `{"code":"<CODE>"}` →
     * `200 {"qr":"ECK$2$…"}` (free onboarding QR carries 9eck.com in its relay URLs) /
     * `404` invalid-or-expired.
     */
    suspend fun resolvePairingCode(code: String): String? = withContext(Dispatchers.IO) {
        val resolvers = com.xelth.eckwms_movfast.utils.SettingsManager.getOnboardingResolvers()
        for (base in resolvers) {
            try {
                val url = URL("${base.removeSuffix("/")}/E/pair/code")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.doOutput = true
                OutputStreamWriter(connection.outputStream, "UTF-8").use {
                    it.write(JSONObject().put("code", code).toString())
                }
                val responseCode = connection.responseCode
                when {
                    responseCode == HttpURLConnection.HTTP_OK -> {
                        val resp = connection.inputStream.bufferedReader().use { it.readText() }
                        val qr = JSONObject(resp).optString("qr", "")
                        Log.d(TAG, "Code resolved via $base (qr len=${qr.length})")
                        return@withContext qr.ifBlank { null }
                    }
                    responseCode == HttpURLConnection.HTTP_NOT_FOUND -> {
                        Log.w(TAG, "Pairing code rejected by $base (404 invalid/expired)")
                        return@withContext null // authoritative — don't try the next resolver
                    }
                    else -> Log.w(TAG, "Code resolve $base HTTP $responseCode — trying next resolver")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Code resolve via $base failed: ${e.message} — trying next resolver")
            }
        }
        return@withContext null
    }

    /**
     * Fetch a single-use challenge nonce from the server (GET /api/auth/device-challenge).
     * The device folds it into the signed registration message so a captured
     * register-device request can't be replayed. Returns null if the server
     * doesn't issue one (unreachable / error) — the caller then can't register.
     */
    private suspend fun fetchDeviceChallenge(baseUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${baseUrl.removeSuffix("/")}/api/auth/device-challenge")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "device-challenge from $baseUrl returned HTTP $code")
                return@withContext null
            }
            val resp = connection.inputStream.bufferedReader().use { it.readText() }
            val nonce = JSONObject(resp).optString("nonce", "")
            nonce.ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "device-challenge fetch from $baseUrl failed: ${e.message}")
            null
        }
    }

    suspend fun registerDevice(
        publicKeyBase64: String,
        // Legacy nonce-less signature from the caller. IGNORED now that the server
        // requires a challenge nonce — we re-sign internally over the fresh nonce.
        // Kept in the signature only so the many existing call sites compile unchanged.
        @Suppress("UNUSED_PARAMETER") signature: String,
        timestamp: Long,
        inviteToken: String? = null,
        // The exact id we sign over in `{"deviceId":..,"devicePublicKey":..,"nonce":..}`.
        // MUST match what we put in the body or the server rejects the signature.
        // Defaults to the canonical `deviceId` (what performSilentAuth signs); the
        // pairing flows pass their raw ANDROID_ID here since that's what they sign.
        signedDeviceId: String = deviceId
    ): ScanResult = withContext(Dispatchers.IO) {
        val serverUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        Log.d(TAG, "Registering device with server: $serverUrl")

        try {
            // Replay-proof auth: fetch a fresh single-use nonce and sign
            // {deviceId,devicePublicKey,nonce} over it. The server verifies the
            // signature AND consumes the nonce, so a captured request is useless.
            val nonce = fetchDeviceChallenge(serverUrl)
            if (nonce == null) {
                return@withContext ScanResult.Error("Could not obtain auth challenge from server")
            }
            val signedMessage =
                "{\"deviceId\":\"$signedDeviceId\",\"devicePublicKey\":\"$publicKeyBase64\",\"nonce\":\"$nonce\"}"
            val freshSignature = android.util.Base64.encodeToString(
                com.xelth.eckwms_movfast.utils.CryptoManager.sign(signedMessage.toByteArray()),
                android.util.Base64.NO_WRAP
            )

            val url = URL("${serverUrl.removeSuffix("/")}/api/internal/register-device")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            // Server expects: deviceId, deviceName, devicePublicKey, signature, nonce.
            // `deviceId` is the SIGNED id (signedDeviceId) so the Ed25519 check over
            // {"deviceId":..,"devicePublicKey":..,"nonce":..} passes; the server
            // resolves it to the canonical device UUID via the public-key anchor.
            val jsonRequest = JSONObject().apply {
                put("deviceId", signedDeviceId)
                put("deviceName", android.os.Build.MODEL)  // Added: device model name
                put("devicePublicKey", publicKeyBase64)
                put("signature", freshSignature)
                put("nonce", nonce)
                // Note: timestamp not used by server, but included for compatibility
                put("timestamp", timestamp)
                if (inviteToken != null) {
                    put("inviteToken", inviteToken)
                }
            }

            Log.d(TAG, "Sending registration request to $url: $jsonRequest")

            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Device registration successful: $response")

                // SECURE KEY SYNC: Extract enc_key + canonical device UUID from response
                try {
                    val json = JSONObject(response)
                    val encKey = json.optString("enc_key", "")
                    if (encKey.isNotEmpty()) {
                        Log.i(TAG, "🔐 Received encryption key from server during registration")
                        com.xelth.eckwms_movfast.utils.SettingsManager.saveEncKey(encKey)
                    }
                    // Adopt the server-minted canonical UUID — from here on getDeviceId()
                    // returns it (uploads/scans/trips/JWT subject all key off the UUID).
                    val deviceUuid = json.optString("device_uuid", "")
                    if (deviceUuid.isNotEmpty()) {
                        Log.i(TAG, "🆔 Server assigned device UUID: $deviceUuid")
                        com.xelth.eckwms_movfast.utils.SettingsManager.saveDeviceUuid(deviceUuid)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse enc_key/device_uuid from registration", e)
                }

                return@withContext ScanResult.Success(
                    type = "registration",
                    message = "Device registered successfully",
                    data = response
                )
            } else {
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"
                Log.e(TAG, "Device registration failed: $errorMessage")
                return@withContext ScanResult.Error("Registration failed: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering device: ${e.message}", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown registration error")
        }
    }

    /**
     * Checks the current status of a device with the server
     * @param deviceId The device identifier
     * @return Result containing the device status information
     */
    suspend fun checkDeviceStatus(deviceId: String): ScanResult = withContext(Dispatchers.IO) {
        val serverUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        Log.d(TAG, "Checking device status with server: $serverUrl")

        try {
            // Fix: Removed hardcoded /E/ prefix, removed trailing slash to prevent double slashes
            val url = URL("${serverUrl.removeSuffix("/")}/api/status")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            // Add auth token for protected endpoint
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val responseCode = connection.responseCode

            when (responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    Log.d(TAG, "Device status check successful: $response")

                    // SECURE KEY ROTATION: Check for updated enc_key in heartbeat
                    // + DYNAMIC CONFIG: Parse repair_order_prefix from server
                    try {
                        val json = JSONObject(response)
                        val encKey = json.optString("enc_key", "")
                        if (encKey.isNotEmpty()) {
                            val currentKey = com.xelth.eckwms_movfast.utils.SettingsManager.getEncKey()
                            if (encKey != currentKey) {
                                Log.i(TAG, "🔐 Encryption key rotated by server")
                                com.xelth.eckwms_movfast.utils.SettingsManager.saveEncKey(encKey)
                            }
                        }
                        // Canonical device UUID — lets an already-paired device that
                        // still authenticates with its legacy ANDROID_ID adopt its
                        // server-minted UUID on the next heartbeat (no re-pairing).
                        val deviceUuid = json.optString("device_uuid", "")
                        if (deviceUuid.isNotEmpty()) {
                            val currentUuid = com.xelth.eckwms_movfast.utils.SettingsManager.getDeviceUuid()
                            if (deviceUuid != currentUuid) {
                                Log.i(TAG, "🆔 Device UUID assigned/updated by server: $deviceUuid")
                                com.xelth.eckwms_movfast.utils.SettingsManager.saveDeviceUuid(deviceUuid)
                            }
                        }
                        // Dynamic repair order prefix from server config
                        val repairPrefix = json.optString("repair_order_prefix", "")
                        if (repairPrefix.isNotEmpty()) {
                            val currentPrefix = com.xelth.eckwms_movfast.utils.SettingsManager.getRepairOrderPrefix()
                            if (repairPrefix != currentPrefix) {
                                Log.i(TAG, "📋 Repair order prefix updated: $currentPrefix → $repairPrefix")
                                com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairOrderPrefix(repairPrefix)
                            }
                        }
                        // Dynamic pairing-code prefix from server config (default "ECK")
                        val pairingPrefix = json.optString("pairing_prefix", "")
                        if (pairingPrefix.isNotEmpty()) {
                            val currentPairing = com.xelth.eckwms_movfast.utils.SettingsManager.getPairingPrefix()
                            if (pairingPrefix != currentPairing) {
                                Log.i(TAG, "🔗 Pairing prefix updated: $currentPairing → $pairingPrefix")
                                com.xelth.eckwms_movfast.utils.SettingsManager.savePairingPrefix(pairingPrefix)
                            }
                        }
                        // Dynamic QR prefixes from server config
                        val qrPrefixesArr = json.optJSONArray("qr_prefixes")
                        if (qrPrefixesArr != null && qrPrefixesArr.length() > 0) {
                            val prefixes = (0 until qrPrefixesArr.length()).map { qrPrefixesArr.getString(it) }
                            Log.i(TAG, "QR prefixes from server: $prefixes")
                            com.xelth.eckwms_movfast.utils.SettingsManager.saveQrPrefixes(prefixes)
                        }
                        val qrSuffix = json.optString("qr_tenant_suffix", "")
                        if (qrSuffix.isNotEmpty()) {
                            com.xelth.eckwms_movfast.utils.SettingsManager.saveQrTenantSuffix(qrSuffix)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse config from status check", e)
                    }

                    // Server returns {"status":"active","repair_order_prefix":"CS-DE-",...}
                    return@withContext ScanResult.Success(
                        type = "device_status",
                        message = "Server is running",
                        data = response
                    )
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    Log.w(TAG, "Device status check: 401 - attempting silent re-auth...")
                    val newToken = performSilentAuth()
                    if (newToken != null) {
                        // Retry with new token
                        connection.disconnect()
                        val retryUrl = URL("${serverUrl.removeSuffix("/")}/api/status")
                        val retryConn = retryUrl.openConnection() as HttpURLConnection
                        retryConn.requestMethod = "GET"
                        retryConn.setRequestProperty("Accept", "application/json")
                        retryConn.setRequestProperty("Authorization", "Bearer $newToken")
                        retryConn.connectTimeout = 5000
                        retryConn.readTimeout = 5000
                        val retryCode = retryConn.responseCode
                        if (retryCode == HttpURLConnection.HTTP_OK) {
                            val response = retryConn.inputStream.bufferedReader().use { it.readText() }
                            Log.i(TAG, "✅ Device status check OK after re-auth")

                            // Also check for enc_key after re-auth
                            try {
                                val json = JSONObject(response)
                                val encKey = json.optString("enc_key", "")
                                if (encKey.isNotEmpty()) {
                                    com.xelth.eckwms_movfast.utils.SettingsManager.saveEncKey(encKey)
                                }
                            } catch (_: Exception) { }

                            return@withContext ScanResult.Success(type = "device_status", message = "Server is running", data = response)
                        }
                        retryConn.disconnect()
                    }
                    return@withContext ScanResult.Error("Authentication required")
                }
                HttpURLConnection.HTTP_FORBIDDEN -> {
                    Log.w(TAG, "Device status check: 403 Forbidden - device is blocked")
                    return@withContext ScanResult.Error("Device is blocked by server")
                }
                else -> {
                    val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                        ?: "Error code: $responseCode"
                    Log.e(TAG, "Device status check failed: $errorMessage")
                    return@withContext ScanResult.Error("Status check failed: $errorMessage")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device status: ${e.message}", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown error checking device status")
        }
    }

    /**
     * Gets instance connection information from the global server
     * @param globalServerUrl The global server URL
     * @param instanceId The instance ID from the QR code
     * @param clientIp The client's local IP address for diagnostics
     * @return Result containing the list of connection candidates
     */
    suspend fun getInstanceInfo(
        globalServerUrl: String,
        instanceId: String,
        clientIp: String?
    ): ScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Getting instance info from global server: $globalServerUrl")

        try {
            // Note: This endpoint is on the Global Server (Node.js) which uses /ECK/ prefix
            // Do NOT change to /api/ - global server runs on different architecture
            val url = URL("$globalServerUrl/ECK/api/internal/get-instance-info")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val jsonRequest = JSONObject().apply {
                put("instance_id", instanceId)
                put("client_ip", clientIp ?: "unknown")
            }

            Log.d(TAG, "Sending instance info request: $jsonRequest")

            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Instance info response: $response")
                return@withContext ScanResult.Success(
                    type = "instance_info",
                    message = "Instance info retrieved successfully",
                    data = response
                )
            } else {
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"
                Log.e(TAG, "Get instance info failed: $errorMessage")
                return@withContext ScanResult.Error("Failed to get instance info: $errorMessage")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting instance info: ${e.message}", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown error getting instance info")
        }
    }

    /**
     * Submits a generic document to the server
     * @param type The type of document (e.g., "ManualRestockOrder")
     * @param payload The document payload as a JSON string
     * @param format The format of the document (e.g., "json")
     * @return Result of the submission operation
     */
    /**
     * Voice Commands P2 — resolve an unmatched spoken command via the server's
     * Gemini fallback. Called only on a LOCAL registry miss. [audioWavBase64] is
     * sent only on the escalation pass (when a prior response set needsAudio).
     * Returns null on transport failure (caller treats as "KI nicht verfügbar").
     */
    suspend fun resolveVoice(
        mode: String,
        text: String,
        commands: List<com.xelth.eckwms_movfast.voice.VoiceCommand>,
        audioWavBase64: String?,
    ): VoiceResolveResult? = withContext(Dispatchers.IO) {
        try {
            val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
            val url = URL("$baseUrl/api/voice/resolve")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8000
            connection.readTimeout = 25000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty(
                "Authorization",
                "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken()
            )
            connection.doOutput = true

            val cmds = JSONArray()
            commands.forEach { c ->
                cmds.put(JSONObject().apply {
                    put("action", c.action)
                    put("description", c.description)
                })
            }
            val body = JSONObject().apply {
                put("mode", mode)
                put("text", text)
                put("commands", cmds)
                if (!audioWavBase64.isNullOrEmpty()) put("audio_wav_base64", audioWavBase64)
            }
            OutputStreamWriter(connection.outputStream, "UTF-8").use { it.write(body.toString()); it.flush() }

            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val resp = connection.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(resp)
                VoiceResolveResult(
                    action = if (o.isNull("action")) null else o.optString("action").ifBlank { null },
                    needsAudio = o.optBoolean("needs_audio", false),
                    reason = o.optString("reason", ""),
                    source = o.optString("source", "gemini")
                )
            } else {
                Log.w(TAG, "resolveVoice HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveVoice failed: ${e.message}")
            null
        }
    }

    suspend fun submitDocument(type: String, payload: String, format: String): ScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Submitting document of type '$type'")
        try {
            val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
            val url = URL("$baseUrl/api/documents")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            val jsonRequest = JSONObject().apply {
                put("type", type)
                put("payload", payload)
                put("format", format)
            }

            Log.d(TAG, "Sending document: $jsonRequest")

            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_CREATED || responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Document submitted successfully: $response")
                ScanResult.Success("document_submit", "Document submitted successfully", response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.e(TAG, "Document submission failed: $error")
                ScanResult.Error("Document submission failed: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error submitting document: ${e.message}", e)
            ScanResult.Error(e.message ?: "Unknown error during document submission")
        }
    }

    /**
     * Sends user's response to an AI interaction back to the server
     * @param interactionId The unique ID of the interaction being responded to
     * @param response The user's chosen response/option
     * @param barcode The barcode context for this interaction
     * @return Result of the response submission
     */
    suspend fun sendAiResponse(interactionId: String?, response: String, barcode: String?): ScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Sending AI response: interactionId=$interactionId, response=$response, barcode=$barcode")

        try {
            val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
            // Fix: Removed hardcoded /E/ prefix
            val url = URL("$baseUrl/api/ai/respond")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            // Add auth token for protected endpoint
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val jsonRequest = JSONObject().apply {
                put("deviceId", deviceId)
                if (interactionId != null) {
                    put("interactionId", interactionId)
                }
                put("response", response)
                if (barcode != null) {
                    put("barcode", barcode)
                }
                put("timestamp", System.currentTimeMillis())
            }

            Log.d(TAG, "Sending AI response request: $jsonRequest")

            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "AI response sent successfully: $responseText")
                ScanResult.Success("ai_response", "AI response sent successfully", responseText)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.e(TAG, "AI response submission failed: $error")
                ScanResult.Error("AI response failed: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending AI response: ${e.message}", e)
            ScanResult.Error(e.message ?: "Unknown error sending AI response")
        }
    }

    /**
     * Fetches recent shipments from the server for receiving workflow
     * Implements Immediate Failover: Local -> Global
     */
    suspend fun getShipments(limit: Int = 100): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = internalGetShipments(activeUrl, limit)

        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ getShipments failed on $activeUrl. Failover to Global.")
            result = internalGetShipments(globalUrl, limit)
        }

        return@withContext result
    }

    private suspend fun internalGetShipments(baseUrl: String, limit: Int): ScanResult {
        try {
            val url = URL("$baseUrl/api/delivery/shipments?limit=$limit")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.connectTimeout = 5000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Fetched shipments: ${response.take(200)}...")
                return ScanResult.Success("shipments", "Fetched shipments", response)
            } else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val newToken = performSilentAuth()
                if (newToken != null) {
                    connection.disconnect()
                    val retryUrl = URL("$baseUrl/api/delivery/shipments?limit=$limit")
                    val retry = retryUrl.openConnection() as HttpURLConnection
                    retry.requestMethod = "GET"
                    retry.setRequestProperty("Accept", "application/json")
                    retry.setRequestProperty("Authorization", "Bearer $newToken")
                    retry.connectTimeout = 5000
                    retry.readTimeout = 10000
                    val retryCode = retry.responseCode
                    if (retryCode == HttpURLConnection.HTTP_OK) {
                        val response = retry.inputStream.bufferedReader().use { it.readText() }
                        return ScanResult.Success("shipments", "Fetched shipments", response)
                    }
                    retry.disconnect()
                }
                return ScanResult.Error("401 Unauthorized")
            } else {
                return ScanResult.Error("HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching shipments from $baseUrl", e)
            return ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Fetches warehouse map data (blueprint)
     */
    suspend fun getWarehouseMap(warehouseId: String): ScanResult = withContext(Dispatchers.IO) {
        val serverUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        Log.d(TAG, "Fetching map for warehouse: $warehouseId")

        try {
            val url = URL("${serverUrl.removeSuffix("/")}/api/warehouse/$warehouseId/map")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext ScanResult.Success(
                    type = "map_data",
                    message = "Map fetched",
                    data = response
                )
            } else {
                return@withContext ScanResult.Error("HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching map", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Internal helper for uploadImage with explicit token (used for retry)
     * @param imageId Required client-generated unique ID for deduplication
     */
    private suspend fun uploadImageWithToken(
        bitmap: Bitmap,
        deviceId: String,
        scanMode: String,
        barcodeData: String?,
        quality: Int,
        orderId: String?,
        token: String,
        imageId: String
    ): ScanResult = withContext(Dispatchers.IO) {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        var baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length - 1)
        }
        val finalUrl = "$baseUrl/api/upload/image"
        val url = URL(finalUrl)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")  // Use provided token
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            // Add Idempotency Key header
            connection.setRequestProperty("X-Idempotency-Key", imageId)
            connection.setRequestProperty("X-Image-ID", imageId)

            // Add Target Instance header for Smart Routing
            val homeInstanceId = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
            if (homeInstanceId.isNotEmpty()) {
                connection.setRequestProperty("X-Target-Instance", homeInstanceId)
            }

            connection.doOutput = true

            val outputStream = connection.outputStream
            val writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)

            // Send Image ID first
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"imageId\"").append("\r\n")
            writer.append("\r\n").append(imageId).append("\r\n").flush()

            // Send deviceId
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"deviceId\"").append("\r\n")
            writer.append("\r\n").append(deviceId).append("\r\n").flush()

            // Send scanMode
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"scanMode\"").append("\r\n")
            writer.append("\r\n").append(scanMode).append("\r\n").flush()

            // Send barcodeData if available
            barcodeData?.let {
                writer.append("--$boundary").append("\r\n")
                writer.append("Content-Disposition: form-data; name=\"barcodeData\"").append("\r\n")
                writer.append("\r\n").append(it).append("\r\n").flush()
            }

            // Send orderId if available
            orderId?.let {
                writer.append("--$boundary").append("\r\n")
                writer.append("Content-Disposition: form-data; name=\"orderId\"").append("\r\n")
                writer.append("\r\n").append(it).append("\r\n").flush()
            }

            // Compress and send image
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, stream)
            val imageBytes = stream.toByteArray()

            val crc = CRC32()
            crc.update(imageBytes)
            val imageChecksum = crc.value.toString(16).padStart(8, '0')

            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"imageChecksum\"").append("\r\n")
            writer.append("\r\n").append(imageChecksum).append("\r\n").flush()

            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"image\"; filename=\"${imageId}.webp\"").append("\r\n")
            writer.append("Content-Type: image/webp").append("\r\n")
            writer.append("\r\n").flush()
            outputStream.write(imageBytes)
            outputStream.flush()
            writer.append("\r\n").flush()

            writer.append("--$boundary--").append("\r\n").flush()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Image upload retry successful (ImageID: $imageId): $response")
                return@withContext ScanResult.Success("upload", "Image uploaded", response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                return@withContext ScanResult.Error("Upload retry failed: $error")
            }
        } catch (e: Exception) {
            return@withContext ScanResult.Error(e.message ?: "Upload retry error")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Performs silent re-authentication using stored crypto keys
     * Called automatically when receiving 401 Unauthorized
     * @return New JWT token if successful, null otherwise
     */
    private suspend fun performSilentAuth(): String? {
        return try {
            Log.i(TAG, "🔄 Attempting silent re-authentication...")
            val crypto = com.xelth.eckwms_movfast.utils.CryptoManager
            val pubKey = crypto.getPublicKeyBase64()
            val ts = System.currentTimeMillis()

            // Create signature for registration
            val sigData = "{\"deviceId\":\"$deviceId\",\"devicePublicKey\":\"$pubKey\"}"
            val signature = android.util.Base64.encodeToString(
                crypto.sign(sigData.toByteArray()),
                android.util.Base64.NO_WRAP
            )

            // Call registerDevice to get new token
            val result = registerDevice(pubKey, signature, ts)
            if (result is ScanResult.Success) {
                val json = JSONObject(result.data)
                val token = json.optString("token")
                if (token.isNotEmpty()) {
                    com.xelth.eckwms_movfast.utils.SettingsManager.saveAuthToken(token)
                    Log.i(TAG, "✅ Silent re-auth successful, new token saved")
                    return token
                }
            }
            Log.e(TAG, "❌ Silent re-auth failed: no token in response")
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Silent re-auth exception: ${e.message}", e)
            null
        }
    }

    /**
     * Send a repair workflow event to the server.
     * Used when a barcode scan or photo reference is linked to a device being repaired.
     */
    suspend fun sendRepairEvent(
        targetDeviceId: String,
        eventType: String,
        data: String
    ): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = internalSendRepairEvent(activeUrl, targetDeviceId, eventType, data)

        // Auto-retry on 401 (token expired)
        if (result is ScanResult.Error && result.message.contains("401")) {
            Log.w(TAG, "🔑 Repair event 401 - attempting silent re-auth...")
            val newToken = performSilentAuth()
            if (newToken != null) {
                Log.i(TAG, "🔄 Token refreshed, retrying repair event...")
                result = internalSendRepairEvent(activeUrl, targetDeviceId, eventType, data)
            }
        }

        // Failover to Global
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ Repair event to $activeUrl failed. Failover to Global.")
            result = internalSendRepairEvent(globalUrl, targetDeviceId, eventType, data)

            // Auto-retry on 401 for Global too
            if (result is ScanResult.Error && result.message.contains("401")) {
                val newToken = performSilentAuth()
                if (newToken != null) {
                    result = internalSendRepairEvent(globalUrl, targetDeviceId, eventType, data)
                }
            }
        }

        return@withContext result
    }

    private suspend fun internalSendRepairEvent(
        baseUrl: String,
        targetDeviceId: String,
        eventType: String,
        data: String
    ): ScanResult {
        val finalUrl = "$baseUrl/api/repair/event"
        Log.d(TAG, "Sending repair event to $finalUrl: $eventType -> $targetDeviceId")

        try {
            val url = URL(finalUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val jsonRequest = JSONObject().apply {
                put("source_device_id", deviceId)
                put("target_device_id", targetDeviceId)
                put("event_type", eventType)
                put("data", data)
                // Multi-user audit trail
                val actingId = com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.value?.id
                val ownerId = com.xelth.eckwms_movfast.ui.viewmodels.UserManager.viewingUser.value?.id
                if (!actingId.isNullOrEmpty()) put("acting_user_id", actingId)
                if (!ownerId.isNullOrEmpty()) put("owner_user_id", ownerId)
            }

            val outputStream = connection.outputStream
            val writer = OutputStreamWriter(outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Repair event response: $response")
                return ScanResult.Success(type = "repair", message = "Event logged", data = response)
            } else {
                val errorResponse = try {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
                } catch (e: Exception) { "Error reading response" }
                Log.e(TAG, "Repair event failed ($responseCode): $errorResponse")
                return ScanResult.Error("Server error: $responseCode")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Repair event exception: ${e.message}")
            return ScanResult.Error("Network error: ${e.message}")
        }
    }

    /**
     * Fetch all products from server for offline cache.
     * Server returns raw JSON array of ProductProduct objects.
     * Implements failover: Local -> Global
     */
    suspend fun fetchProducts(): List<ProductEntity> = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = internalFetchProducts(activeUrl)
        if (result.isEmpty() && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "fetchProducts failed on $activeUrl, failover to global")
            result = internalFetchProducts(globalUrl)
        }
        return@withContext result
    }

    private suspend fun internalFetchProducts(baseUrl: String): List<ProductEntity> {
        try {
            val url = URL("$baseUrl/api/items")
            Log.d(TAG, "Fetching products from: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val list = mutableListOf<ProductEntity>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    // Odoo can return "false" instead of null for empty fields
                    val barcode = obj.opt("barcode")?.let {
                        if (it is Boolean || it.toString() == "false") null else it.toString()
                    }
                    val defaultCode = obj.opt("default_code")?.let {
                        if (it is Boolean || it.toString() == "false") "" else it.toString()
                    } ?: ""

                    list.add(ProductEntity(
                        id = obj.optString("id", ""),
                        defaultCode = defaultCode,
                        name = obj.optString("name", ""),
                        barcode = barcode,
                        qtyAvailable = obj.optDouble("qty_available", 0.0),
                        listPrice = obj.optDouble("list_price", 0.0),
                        weight = obj.optDouble("weight", 0.0),
                        active = obj.optBoolean("active", true)
                    ))
                }
                Log.i(TAG, "Fetched ${list.size} products from $baseUrl")
                return list
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val newToken = performSilentAuth()
                if (newToken != null) {
                    return internalFetchProducts(baseUrl) // retry once
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching products from $baseUrl", e)
        }
        return emptyList()
    }

    /**
     * Fetch all warehouse locations from server for offline cache.
     * Server returns raw JSON array of StockLocation objects.
     * Implements failover: Local -> Global
     */
    suspend fun fetchLocations(): List<LocationEntity> = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = internalFetchLocations(activeUrl)
        if (result.isEmpty() && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "fetchLocations failed on $activeUrl, failover to global")
            result = internalFetchLocations(globalUrl)
        }
        return@withContext result
    }

    private suspend fun internalFetchLocations(baseUrl: String): List<LocationEntity> {
        try {
            val url = URL("$baseUrl/api/warehouse")
            Log.d(TAG, "Fetching locations from: $url")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val list = mutableListOf<LocationEntity>()

                // Recursively flatten location tree (server may return nested children)
                fun parseLocation(obj: JSONObject, parentId: String?) {
                    val barcode = obj.opt("barcode")?.let {
                        if (it is Boolean || it.toString() == "false") null else it.toString()
                    }
                    list.add(LocationEntity(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        completeName = obj.optString("complete_name", ""),
                        barcode = barcode,
                        usage = obj.optString("usage", ""),
                        parentId = parentId,
                        active = obj.optBoolean("active", true)
                    ))
                    // Parse children if present
                    val children = obj.optJSONArray("children")
                    if (children != null) {
                        for (j in 0 until children.length()) {
                            parseLocation(children.getJSONObject(j), obj.optString("id", ""))
                        }
                    }
                }

                for (i in 0 until jsonArray.length()) {
                    parseLocation(jsonArray.getJSONObject(i), null)
                }
                Log.i(TAG, "Fetched ${list.size} locations from $baseUrl")
                return list
            } else if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                val newToken = performSilentAuth()
                if (newToken != null) {
                    return internalFetchLocations(baseUrl)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching locations from $baseUrl", e)
        }
        return emptyList()
    }

    /**
     * Upload an image linked to a repair workflow (includes target_device_id in metadata).
     */
    suspend fun uploadRepairImage(
        bitmap: Bitmap,
        targetDeviceId: String,
        quality: Int = 80
    ): ScanResult {
        // Use existing upload with repair-specific scan mode and barcode data containing the target
        return uploadImage(
            bitmap = bitmap,
            deviceId = deviceId,
            scanMode = "repair_photo",
            barcodeData = targetDeviceId,
            quality = quality
        )
    }

    // ============ AI Image Analysis ============

    /**
     * Triggers AI analysis for a specific file ID.
     * Implements Immediate Failover: Local -> Global
     */
    suspend fun analyzeImage(imageId: String): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = internalAnalyzeImage(activeUrl, imageId)

        // Auto-retry on 401
        if (result is ScanResult.Error && result.message.contains("401")) {
            val newToken = performSilentAuth()
            if (newToken != null) {
                result = internalAnalyzeImage(activeUrl, imageId)
            }
        }

        // Failover to Global
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ Analysis failed on $activeUrl. Failover to Global.")
            result = internalAnalyzeImage(globalUrl, imageId)
        }

        return@withContext result
    }

    private suspend fun internalAnalyzeImage(baseUrl: String, imageId: String): ScanResult {
        try {
            val url = URL("$baseUrl/api/ai/analyze-image")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val jsonRequest = JSONObject().apply {
                put("file_id", imageId)
            }

            val writer = OutputStreamWriter(connection.outputStream, "UTF-8")
            writer.write(jsonRequest.toString())
            writer.flush()
            writer.close()

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return ScanResult.Success("ai_analysis", "Analysis Complete", response)
            } else if (responseCode == 503) {
                return ScanResult.Error("AI Service Unavailable (503). Check server keys.")
            } else {
                val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                return ScanResult.Error(errBody)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing image", e)
            return ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    // ============ File & Attachment API methods ============

    /**
     * Fetch attachments for a specific entity (e.g. product photos).
     * Uses the /api/attachments/{model}/{id} endpoint.
     * Server auto-resolves EAN to smart code for products.
     */
    suspend fun fetchAttachments(resModel: String, resId: String): List<com.xelth.eckwms_movfast.ui.data.AttachmentInfo> = withContext(Dispatchers.IO) {
        val encodedId = java.net.URLEncoder.encode(resId, "UTF-8")
        val result = authenticatedGetWithFailover("/api/attachments/$resModel/$encodedId")
        if (result is ScanResult.Success) {
            try {
                val jsonArray = JSONArray(result.data)
                val list = mutableListOf<com.xelth.eckwms_movfast.ui.data.AttachmentInfo>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(com.xelth.eckwms_movfast.ui.data.AttachmentInfo(
                        id = obj.optString("id", ""),
                        fileId = obj.optString("file_id", ""),
                        mimeType = obj.optString("mime_type", ""),
                        isMain = obj.optBoolean("is_main", false),
                        createdAt = obj.optString("created_at", "")
                    ))
                }
                Log.i(TAG, "Fetched ${list.size} attachments for $resModel:$resId")
                return@withContext list
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing attachments", e)
            }
        }
        return@withContext emptyList()
    }

    // ============ QC & Explorer API methods ============

    suspend fun fetchDiscrepancies(status: String?, limit: Int): ScanResult = withContext(Dispatchers.IO) {
        val params = buildString {
            append("?limit=$limit")
            if (status != null) append("&status=$status")
        }
        authenticatedGetWithFailover("/api/inventory/discrepancies$params")
    }

    suspend fun fetchDiscrepancyStats(): ScanResult = withContext(Dispatchers.IO) {
        authenticatedGetWithFailover("/api/inventory/discrepancies/stats")
    }

    suspend fun reviewDiscrepancy(id: String, notes: String): ScanResult = withContext(Dispatchers.IO) {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")
        val body = org.json.JSONObject().apply { put("status", "reviewed"); put("notes", notes) }.toString()

        var result = authenticatedPut("$activeUrl/api/inventory/discrepancies/$id/review", body)
        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            result = authenticatedPut("$globalUrl/api/inventory/discrepancies/$id/review", body)
        }
        result
    }

    suspend fun fetchExplorerData(path: String): ScanResult = withContext(Dispatchers.IO) {
        authenticatedGetWithFailover(path)
    }

    private suspend fun authenticatedGetWithFailover(apiPath: String): ScanResult {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = authenticatedGet("$activeUrl$apiPath")

        // Auto-retry on 401 (token expired)
        if (result is ScanResult.Error && result.message.contains("401")) {
            Log.w(TAG, "🔑 API 401 — attempting silent re-auth...")
            val newToken = performSilentAuth()
            if (newToken != null) {
                result = authenticatedGet("$activeUrl$apiPath")
            }
        }

        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ API call to $activeUrl failed, failover to $globalUrl")
            result = authenticatedGet("$globalUrl$apiPath")
        }
        return result
    }

    private fun authenticatedGet(urlStr: String): ScanResult {
        return try {
            Log.d(TAG, "authenticatedGet: $urlStr")
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            val code = connection.responseCode
            if (code in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                ScanResult.Success(type = "api", message = "OK", data = response)
            } else {
                val errBody = try { connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: Exception) { null }
                Log.w(TAG, "authenticatedGet $code: $urlStr → $errBody")
                ScanResult.Error("HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "authenticatedGet error: $urlStr → ${e.message}")
            ScanResult.Error(e.message ?: "Network error")
        }
    }

    private suspend fun authenticatedPostWithFailover(apiPath: String, jsonBody: String): ScanResult {
        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        var result = authenticatedPost("$activeUrl$apiPath", jsonBody)

        // Auto-retry on 401 (token expired)
        if (result is ScanResult.Error && result.message.contains("401")) {
            Log.w(TAG, "🔑 API POST 401 — attempting silent re-auth...")
            val newToken = performSilentAuth()
            if (newToken != null) {
                result = authenticatedPost("$activeUrl$apiPath", jsonBody)
            }
        }

        if (result is ScanResult.Error && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ API POST to $activeUrl failed, failover to $globalUrl")
            result = authenticatedPost("$globalUrl$apiPath", jsonBody)
        }
        return result
    }

    private fun authenticatedPost(urlStr: String, jsonBody: String): ScanResult {
        return try {
            Log.d(TAG, "authenticatedPost: $urlStr")
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(jsonBody) }
            val code = connection.responseCode
            if (code in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                ScanResult.Success(type = "api", message = "OK", data = response)
            } else {
                val errBody = try { connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: Exception) { null }
                Log.w(TAG, "authenticatedPost $code: $urlStr → $errBody")
                ScanResult.Error("HTTP $code")
            }
        } catch (e: Exception) {
            Log.w(TAG, "authenticatedPost error: $urlStr → ${e.message}")
            ScanResult.Error(e.message ?: "Network error")
        }
    }

    // ============ Trips API (Fahrtenbuch) ============

    /**
     * Upload a recorded trip (cell-tower track + odometer readings).
     * Payload built by TripManager.buildUploadJson(), idempotent by trip_uuid.
     */
    suspend fun uploadTrip(payload: String): Boolean = withContext(Dispatchers.IO) {
        val result = authenticatedPostWithFailover("/api/trips", payload)
        if (result is ScanResult.Success) return@withContext true
        // Off-LAN (driver on mobile data): the LAN master is unreachable over
        // HTTP, so deliver the trip through the relay mesh-queue instead — same
        // channel pairing uses. Works for periodic open checkpoints AND the final
        // ended trip; the master's trip_upload handler also drives the live marker.
        return@withContext uploadTripViaRelay(payload)
    }

    /** Deliver a trip payload to the (NAT'd) master via the relay polygon as a
     *  `trip_upload` mesh-task. Returns true on the master's ok ack. Adds the
     *  device JWT as `token` for auth parity with the HTTP path; a token
     *  rejection triggers ONE relay-based silent re-auth + retry (the relay
     *  path never sees an HTTP 401, so the usual refresh hook can't fire). */
    private suspend fun uploadTripViaRelay(payload: String): Boolean {
        val body = try { JSONObject(payload) } catch (e: Exception) { return false }
        return dispatchMeshTaskWithReAuth("trip_upload", body).outcome == MeshOutcome.OK
    }

    private enum class MeshOutcome { OK, BAD_TOKEN, FAIL }
    private data class MeshAck(val outcome: MeshOutcome, val ack: JSONObject? = null)

    /** One polygon sweep of a mesh-task dispatch: queue on a relay, wait for the
     *  master's ack, walk to the next relay on silence. BAD_TOKEN = the master
     *  answered but rejected the device JWT — re-auth, don't walk further.
     *  `answered:true` in a non-ok ack (e.g. wrong PIN) is also definitive. */
    private suspend fun dispatchMeshTask(
        master: String, meshId: String, polygon: List<String>, kind: String, body: JSONObject
    ): MeshAck {
        for (raw in polygon) {
            val relayBase = raw.trimEnd('/')
            val relay = com.xelth.eckwms_movfast.sync.RelayClient(relayBase, deviceId, meshId)
            when (val outcome = relay.meshDispatch(master, kind, body)) {
                is com.xelth.eckwms_movfast.sync.RelayDispatch.Ok -> {
                    val res = pollRelayResult(relay, outcome.taskId)
                    if (res != null && res.optBoolean("ok", false)) {
                        Log.i(TAG, "$kind delivered via relay $relayBase")
                        return MeshAck(MeshOutcome.OK, res)
                    }
                    val err = res?.optString("error", "") ?: ""
                    if (err.contains("token", ignoreCase = true)) {
                        Log.w(TAG, "$kind via $relayBase: master rejected device token → re-auth")
                        return MeshAck(MeshOutcome.BAD_TOKEN, res)
                    }
                    if (res != null && res.optBoolean("answered", false)) {
                        // The master DID answer, just negatively (e.g. wrong PIN)
                        // — a definitive result, don't walk more relays.
                        return MeshAck(MeshOutcome.FAIL, res)
                    }
                    Log.w(TAG, "$kind relay via $relayBase: no ok ack (${err.ifBlank { "timeout" }}) — next relay")
                }
                is com.xelth.eckwms_movfast.sync.RelayDispatch.Fatal -> {
                    Log.w(TAG, "$kind relay rejected by $relayBase: ${outcome.message}")
                    return MeshAck(MeshOutcome.FAIL)
                }
                is com.xelth.eckwms_movfast.sync.RelayDispatch.Retryable -> {
                    Log.w(TAG, "$kind relay $relayBase unavailable (${outcome.message}) — next relay")
                }
            }
        }
        return MeshAck(MeshOutcome.FAIL)
    }

    /** Dispatch a device-JWT-gated mesh task with ONE re-auth retry on a token
     *  rejection — the shared skeleton of every phone→master relay call
     *  (trip_upload / image_upload / users_active / users_verify_pin). */
    private suspend fun dispatchMeshTaskWithReAuth(kind: String, body: JSONObject): MeshAck {
        val master = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
        if (master.isEmpty()) return MeshAck(MeshOutcome.FAIL)
        val polygon = com.xelth.eckwms_movfast.utils.SettingsManager.relayFallbackCandidates()
        if (polygon.isEmpty()) return MeshAck(MeshOutcome.FAIL)
        val meshId = com.xelth.eckwms_movfast.utils.SettingsManager.getMeshId() ?: ""
        var token = com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken()
        if (token.isEmpty()) token = relaySilentReAuth() ?: return MeshAck(MeshOutcome.FAIL)
        body.put("token", token)
        val first = dispatchMeshTask(master, meshId, polygon, kind, body)
        if (first.outcome != MeshOutcome.BAD_TOKEN) return first
        val fresh = relaySilentReAuth() ?: return MeshAck(MeshOutcome.FAIL)
        body.put("token", fresh)
        return dispatchMeshTask(master, meshId, polygon, kind, body)
    }

    /** Relay-based silent re-auth (the mesh-queue twin of [performSilentAuth]):
     *  the master rejected/misses our device JWT and HTTP is unreachable (mobile
     *  data), so re-register over the SAME relay polygon pairing uses —
     *  `device_challenge` → sign {deviceId, publicKey, nonce} → `device_register`.
     *  The master resolves the device by its Ed25519 public key, so the existing
     *  UUID is reused and a fresh JWT comes back. Field case 2026-07-13: trips
     *  piled up behind "invalid or missing device token" acks forever, because
     *  only the HTTP 401 path could refresh the token. */
    private suspend fun relaySilentReAuth(): String? {
        val master = com.xelth.eckwms_movfast.utils.SettingsManager.getHomeInstanceId()
        if (master.isEmpty()) return null
        val polygon = com.xelth.eckwms_movfast.utils.SettingsManager.relayFallbackCandidates()
        if (polygon.isEmpty()) return null
        val meshId = com.xelth.eckwms_movfast.utils.SettingsManager.getMeshId() ?: ""
        return try {
            val crypto = com.xelth.eckwms_movfast.utils.CryptoManager
            val pubKey = crypto.getPublicKeyBase64()
            // Pairing signs with the raw ANDROID_ID (the server re-resolves the
            // canonical UUID by public key) — mirror it exactly.
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver, android.provider.Settings.Secure.ANDROID_ID
            ) ?: return null

            // Phase 1: single-use challenge nonce from the master (replay-proof).
            var nonce: String? = null
            for (raw in polygon) {
                val relay = com.xelth.eckwms_movfast.sync.RelayClient(raw.trimEnd('/'), deviceId, meshId)
                val outcome = relay.meshDispatch(master, "device_challenge", JSONObject())
                if (outcome is com.xelth.eckwms_movfast.sync.RelayDispatch.Ok) {
                    nonce = pollRelayResult(relay, outcome.taskId)
                        ?.optString("nonce", "")?.takeIf { it.isNotBlank() }
                    if (nonce != null) break
                }
            }
            if (nonce == null) {
                Log.w(TAG, "Relay re-auth: no challenge nonce from master")
                return null
            }

            // Phase 2: nonce-bound signed registration → fresh token.
            val sigData =
                "{\"deviceId\":\"$androidId\",\"devicePublicKey\":\"$pubKey\",\"nonce\":\"$nonce\"}"
            val signature = android.util.Base64.encodeToString(
                crypto.sign(sigData.toByteArray()), android.util.Base64.NO_WRAP
            )
            val payload = JSONObject().apply {
                put("deviceId", androidId)
                put("devicePublicKey", pubKey)
                put("signature", signature)
                put("nonce", nonce)
            }
            for (raw in polygon) {
                val relay = com.xelth.eckwms_movfast.sync.RelayClient(raw.trimEnd('/'), deviceId, meshId)
                val outcome = relay.meshDispatch(master, "device_register", payload)
                if (outcome is com.xelth.eckwms_movfast.sync.RelayDispatch.Ok) {
                    val res = pollRelayResult(relay, outcome.taskId)
                    val token = res?.optString("token", "")?.takeIf { it.isNotBlank() }
                    if (token != null) {
                        com.xelth.eckwms_movfast.utils.SettingsManager.saveAuthToken(token)
                        Log.i(TAG, "✅ Relay re-auth: fresh device token saved")
                        return token
                    }
                }
            }
            Log.w(TAG, "Relay re-auth: master never returned a token")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Relay re-auth failed: ${e.message}")
            null
        }
    }

    /** Poll a dispatched relay task for the master's ack (null on timeout). */
    private suspend fun pollRelayResult(
        relay: com.xelth.eckwms_movfast.sync.RelayClient,
        taskId: String,
        timeoutMs: Long = 30_000
    ): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val r = relay.meshResult(taskId).getOrNull()
            if (r != null) return r
            kotlinx.coroutines.delay(2000)
        }
        return null
    }

    /** Fire-and-forget live position of an in-progress BUSINESS trip → the
     *  dashboard's moving car marker. The server persists nothing (DSGVO: the
     *  live track is transient; only the sealed aggregate is retained). The
     *  caller must gate this on live-share consent and a non-private purpose. */
    suspend fun postTripLive(
        tripId: String,
        lat: Double,
        lng: Double,
        heading: Double? = null,
        speedKmh: Double? = null,
        plate: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("trip_uuid", tripId)
            put("device_id", deviceId)
            put("lat", lat)
            put("lng", lng)
            heading?.let { put("heading", it) }
            speedKmh?.let { put("speed_kmh", it) }
            if (!plate.isNullOrBlank()) put("vehicle_plate", plate)
        }.toString()
        authenticatedPostWithFailover("/api/trips/live", body) is ScanResult.Success
    }

    /** Trip-mode console data: cities with waiting tickets + the result list for
     *  a typed query (fuzzy over ALL tickets) or a selected city. */
    suspend fun fetchDestinations(
        query: String? = null,
        city: String? = null,
        ai: Boolean = false
    ): DestinationsResult? = withContext(Dispatchers.IO) {
        val enc = { s: String -> java.net.URLEncoder.encode(s, "UTF-8") }
        val params = StringBuilder("?device_id=$deviceId")
        if (!query.isNullOrBlank()) params.append("&q=").append(enc(query))
        if (!city.isNullOrBlank()) params.append("&city=").append(enc(city))
        if (ai) params.append("&ai=true")
        val result = authenticatedGetWithFailover("/api/trips/destinations$params")
        if (result !is ScanResult.Success) return@withContext null
        try {
            val root = JSONObject(result.data)
            val cities = mutableListOf<CityCount>()
            root.optJSONArray("cities")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val c = o.optString("city", "")
                    if (c.isNotBlank()) cities.add(CityCount(c, o.optInt("count", 0)))
                }
            }
            val results = mutableListOf<Destination>()
            root.optJSONArray("results")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    results.add(
                        Destination(
                            purposeRef = o.optString("purpose_ref", ""),
                            label = o.optString("label", ""),
                            address = if (o.isNull("address")) null else o.optString("address", null),
                            city = if (o.isNull("city")) null else o.optString("city", null),
                            lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                            lng = if (o.isNull("lng")) null else o.optDouble("lng")
                        )
                    )
                }
            }
            DestinationsResult(cities, results)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDestinations parse error: ${e.message}")
            null
        }
    }

    /** Planned purpose candidates for the trip-start picker (Level A). Sourced
     *  from open visit_tasks; pass a rough position to rank the nearest first. */
    suspend fun fetchPurposeCandidates(
        lat: Double? = null,
        lng: Double? = null
    ): List<PurposeCandidate>? = withContext(Dispatchers.IO) {
        val pos = if (lat != null && lng != null) "&lat=$lat&lng=$lng" else ""
        val result = authenticatedGetWithFailover("/api/trips/purpose-candidates?device_id=$deviceId$pos")
        if (result !is ScanResult.Success) return@withContext null
        try {
            val arr = JSONArray(result.data)
            val list = mutableListOf<PurposeCandidate>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    PurposeCandidate(
                        purposeRef = o.optString("purpose_ref", ""),
                        label = o.optString("label", ""),
                        address = if (o.isNull("address")) null else o.optString("address", null),
                        distanceKm = if (o.isNull("distance_km")) null else o.optDouble("distance_km"),
                        overdue = o.optBoolean("overdue", false),
                        lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                        lng = if (o.isNull("lng")) null else o.optDouble("lng")
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "fetchPurposeCandidates parse error: ${e.message}")
            null
        }
    }

    /** Registered vehicles (Fahrtenbuch). The PDA mirrors these for offline
     *  selection at trip start. */
    suspend fun fetchVehicles(): List<Vehicle>? = withContext(Dispatchers.IO) {
        val result = authenticatedGetWithFailover("/api/vehicles")
        if (result !is ScanResult.Success) return@withContext null
        try {
            val arr = JSONArray(result.data)
            val list = mutableListOf<Vehicle>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Vehicle(
                        id = o.optString("id", ""),
                        plate = o.optString("plate", ""),
                        label = if (o.isNull("label")) null else o.optString("label", null),
                        photoId = if (o.isNull("photo_file_id")) null else o.optString("photo_file_id", null),
                        active = o.optBoolean("active", true)
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "fetchVehicles parse error: ${e.message}")
            null
        }
    }

    /** Register/dedupe a vehicle by plate (+ optional plate-photo CAS id).
     *  Returns the server row (with its id) or null on failure/offline. */
    suspend fun createVehicle(plate: String, photoId: String?): Vehicle? = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("plate", plate)
            if (photoId != null) put("photo_file_id", photoId)
        }.toString()
        val result = authenticatedPostWithFailover("/api/vehicles", body)
        if (result !is ScanResult.Success) return@withContext null
        try {
            val o = JSONObject(result.data)
            Vehicle(
                id = o.optString("id", ""),
                plate = o.optString("plate", plate),
                label = if (o.isNull("label")) null else o.optString("label", null),
                photoId = if (o.isNull("photo_file_id")) null else o.optString("photo_file_id", null),
                active = o.optBoolean("active", true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "createVehicle parse error: ${e.message}")
            null
        }
    }

    /**
     * Download the resolved cell-tower cache for on-device resolution.
     * Mast positions, not personal data. Returns parsed rows or null.
     */
    suspend fun fetchCellCache(limit: Int = 5000): List<com.xelth.eckwms_movfast.data.local.entity.CellTowerEntity>? =
        withContext(Dispatchers.IO) {
            val result = authenticatedGetWithFailover("/api/cells/cache?limit=$limit")
            if (result !is ScanResult.Success) return@withContext null
            try {
                val arr = JSONArray(result.data)
                val list = mutableListOf<com.xelth.eckwms_movfast.data.local.entity.CellTowerEntity>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val key = o.optString("key", "")
                    if (key.isEmpty() || o.isNull("lat") || o.isNull("lng")) continue
                    list.add(
                        com.xelth.eckwms_movfast.data.local.entity.CellTowerEntity(
                            key = key,
                            lat = o.getDouble("lat"),
                            lng = o.getDouble("lng"),
                            rangeM = o.optDouble("range_m", 1500.0)
                        )
                    )
                }
                list
            } catch (e: Exception) {
                Log.e(TAG, "fetchCellCache parse error: ${e.message}")
                null
            }
        }

    // ============ Visits API (check-in/check-out model) ============

    /** Pull open visit tasks for the daily plan (due today or overdue). */
    suspend fun fetchVisits(): List<com.xelth.eckwms_movfast.data.local.entity.VisitTaskEntity>? = withContext(Dispatchers.IO) {
        val result = authenticatedGetWithFailover("/api/visits?device_id=$deviceId")
        if (result !is ScanResult.Success) return@withContext null
        try {
            val arr = JSONArray(result.data)
            val list = mutableListOf<com.xelth.eckwms_movfast.data.local.entity.VisitTaskEntity>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    com.xelth.eckwms_movfast.data.local.entity.VisitTaskEntity(
                        id = o.getString("id"),
                        title = o.optString("title", ""),
                        address = o.optString("address", null),
                        lat = if (o.isNull("lat")) null else o.optDouble("lat"),
                        lng = if (o.isNull("lng")) null else o.optDouble("lng"),
                        dueDate = o.optString("due_date", ""),
                        status = o.optString("status", "open"),
                        note = o.optString("note", null)
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "fetchVisits parse error: ${e.message}")
            null
        }
    }

    /** Send a confirmed check-in/check-out event. Payload is the queued JSON
     *  {visit_id, kind, ts, lat?, lng?, accuracy_m?}. */
    suspend fun pushVisitEvent(payload: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val p = JSONObject(payload)
            val visitId = p.getString("visit_id")
            val kind = p.getString("kind") // checkin | checkout
            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("ts", p.optString("ts"))
                if (p.has("lat")) put("lat", p.getDouble("lat"))
                if (p.has("lng")) put("lng", p.getDouble("lng"))
                if (p.has("accuracy_m")) put("accuracy_m", p.getDouble("accuracy_m"))
                val userId = com.xelth.eckwms_movfast.ui.viewmodels.UserManager.currentUser.value?.id
                if (!userId.isNullOrEmpty()) put("user_id", userId)
            }
            authenticatedPostWithFailover("/api/visits/$visitId/$kind", body.toString()) is ScanResult.Success
        } catch (e: Exception) {
            Log.e(TAG, "pushVisitEvent error: ${e.message}")
            false
        }
    }

    // ============ CRM API ============

    /**
     * Fetch current CRM entity data (company/person/opp) for CrmEntityScreen.
     * Returns the parsed JSON object or null when unreachable/not found.
     */
    suspend fun fetchCrmEntity(entityType: String, entityId: String): JSONObject? = withContext(Dispatchers.IO) {
        val result = authenticatedGetWithFailover("/api/crm/$entityType/$entityId")
        if (result is ScanResult.Success) {
            try {
                return@withContext JSONObject(result.data)
            } catch (e: Exception) {
                Log.e(TAG, "fetchCrmEntity: bad JSON: ${e.message}")
            }
        }
        return@withContext null
    }

    /**
     * Push a queued offline CRM edit to the server.
     * Payload is the queueCrmUpdate() JSON: {entity_type, entity_id, changes, timestamp}.
     */
    suspend fun pushCrmUpdate(payload: String): Boolean = withContext(Dispatchers.IO) {
        // Attach deviceId so the server can audit + enforce device status
        val body = try {
            JSONObject(payload).apply { put("deviceId", deviceId) }.toString()
        } catch (e: Exception) {
            payload
        }
        val result = authenticatedPostWithFailover("/api/crm/update", body)
        return@withContext result is ScanResult.Success
    }

    private fun authenticatedPut(urlStr: String, jsonBody: String): ScanResult {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "PUT"
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(jsonBody) }
            if (connection.responseCode in 200..299) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                ScanResult.Success(type = "api", message = "OK", data = response)
            } else {
                ScanResult.Error("HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            ScanResult.Error(e.message ?: "Network error")
        }
    }

    // ============ Picking API ============

    data class PickingRouteResponse(
        val lines: List<com.xelth.eckwms_movfast.data.local.entity.PickLineEntity>,
        val path: List<com.xelth.eckwms_movfast.ui.data.PathPoint>
    )

    suspend fun fetchActivePickings(): List<com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity>? = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/pickings/active")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val list = mutableListOf<com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(com.xelth.eckwms_movfast.data.local.entity.PickingOrderEntity(
                        id = obj.optString("id", ""),
                        name = obj.optString("name", ""),
                        state = obj.optString("state", "assigned"),
                        partnerName = obj.optString("partner_name", null),
                        origin = obj.optString("origin", null),
                        priority = obj.optString("priority", "0"),
                        scheduledDate = try { java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).parse(obj.optString("scheduled_date"))?.time ?: 0L } catch (e: Exception) { 0L },
                        locationId = obj.optString("location_id", ""),
                        locationDestId = obj.optString("location_dest_id", ""),
                        lineCount = obj.optInt("line_count", 0),
                        pickedCount = obj.optInt("picked_count", 0)
                    ))
                }
                Log.i(TAG, "Fetched ${list.size} active pickings")
                return@withContext list
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching active pickings", e)
        }
        return@withContext null
    }

    suspend fun fetchPickingRoute(pickingId: String): PickingRouteResponse? = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/pickings/$pickingId/route")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                // Parse lines
                val linesArray = json.optJSONArray("lines") ?: JSONArray()
                val lines = mutableListOf<com.xelth.eckwms_movfast.data.local.entity.PickLineEntity>()
                for (i in 0 until linesArray.length()) {
                    val obj = linesArray.getJSONObject(i)
                    lines.add(com.xelth.eckwms_movfast.data.local.entity.PickLineEntity(
                        id = obj.optString("id", ""),
                        pickingId = obj.optString("picking_id", ""),
                        productId = obj.optString("product_id", ""),
                        productName = obj.optString("product_name", ""),
                        productBarcode = obj.optString("product_barcode", null),
                        productCode = obj.optString("product_code", null),
                        qtyDemand = obj.optDouble("qty_demand", 0.0),
                        qtyDone = obj.optDouble("qty_done", 0.0),
                        locationId = obj.optString("location_id", ""),
                        locationName = obj.optString("location_name", ""),
                        locationBarcode = obj.optString("location_barcode", null),
                        rackId = if (obj.has("rack_id") && !obj.isNull("rack_id")) obj.optString("rack_id") else null,
                        rackName = obj.optString("rack_name", null),
                        rackX = obj.optInt("rack_x", 0),
                        rackY = obj.optInt("rack_y", 0),
                        rackWidth = obj.optInt("rack_width", 0),
                        rackHeight = obj.optInt("rack_height", 0),
                        state = obj.optString("state", "assigned"),
                        sequence = obj.optInt("sequence", 0)
                    ))
                }

                // Parse route path
                val routeObj = json.optJSONObject("route")
                val pathArray = routeObj?.optJSONArray("path") ?: JSONArray()
                val path = mutableListOf<com.xelth.eckwms_movfast.ui.data.PathPoint>()
                for (i in 0 until pathArray.length()) {
                    val pt = pathArray.getJSONObject(i)
                    path.add(com.xelth.eckwms_movfast.ui.data.PathPoint(pt.getInt("x"), pt.getInt("y")))
                }

                Log.i(TAG, "Fetched route for picking $pickingId: ${lines.size} lines, ${path.size} path points")
                return@withContext PickingRouteResponse(lines, path)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching picking route", e)
        }
        return@withContext null
    }

    suspend fun confirmPickLine(pickingId: String, lineId: String, qtyDone: Double, productBarcode: String, locationBarcode: String): SyncOutcome = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/pickings/$pickingId/lines/$lineId/confirm")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val body = JSONObject().apply {
                put("qty_done", qtyDone)
                put("scanned_product_barcode", productBarcode)
                put("scanned_location_barcode", locationBarcode)
            }
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }

            val outcome = classifyWrite(connection.responseCode)
            when (outcome) {
                SyncOutcome.SUCCESS -> Log.i(TAG, "Confirmed pick line $lineId (qty=$qtyDone)")
                SyncOutcome.REJECTED -> Log.w(TAG, "Confirm REJECTED (no retry): HTTP ${connection.responseCode}")
                SyncOutcome.FAILED -> Log.w(TAG, "Confirm failed (retryable): HTTP ${connection.responseCode}")
            }
            return@withContext outcome
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming pick line", e)
            return@withContext SyncOutcome.FAILED
        }
    }

    suspend fun validatePicking(pickingId: String): SyncOutcome = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/pickings/$pickingId/validate")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write("{}") }

            val outcome = classifyWrite(connection.responseCode)
            when (outcome) {
                SyncOutcome.SUCCESS -> Log.i(TAG, "Validated picking $pickingId")
                SyncOutcome.REJECTED -> Log.w(TAG, "Validate REJECTED (no retry): HTTP ${connection.responseCode}")
                SyncOutcome.FAILED -> Log.w(TAG, "Validate failed (retryable): HTTP ${connection.responseCode}")
            }
            return@withContext outcome
        } catch (e: Exception) {
            Log.e(TAG, "Error validating picking", e)
            return@withContext SyncOutcome.FAILED
        }
    }

    /**
     * Put-away (stocktake): record the counted on-hand of a part on a shelf.
     * `op` = "set" (absolute counted qty, default) or "add" (increment). Mirrors
     * the picking-confirm write path (2xx ok / 4xx reject / else retry).
     */
    suspend fun putAway(itemBarcode: String, shelfBarcode: String, warehouse: String, qty: Double, op: String = "set"): SyncOutcome = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/warehouse/put-away")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val body = JSONObject().apply {
                put("item_barcode", itemBarcode)
                put("shelf_barcode", shelfBarcode)
                put("warehouse", warehouse)
                put("qty", qty)
                put("op", op)
            }
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }

            val outcome = classifyWrite(connection.responseCode)
            when (outcome) {
                SyncOutcome.SUCCESS -> Log.i(TAG, "Put-away $itemBarcode -> $shelfBarcode ($op $qty)")
                SyncOutcome.REJECTED -> Log.w(TAG, "Put-away REJECTED (no retry): HTTP ${connection.responseCode}")
                SyncOutcome.FAILED -> Log.w(TAG, "Put-away failed (retryable): HTTP ${connection.responseCode}")
            }
            return@withContext outcome
        } catch (e: Exception) {
            Log.e(TAG, "Error in put-away", e)
            return@withContext SyncOutcome.FAILED
        }
    }

    /**
     * Fetch the soll/ist reconciliation for a warehouse (Exact scraped stock vs
     * counted bins). Returns the raw JSON body (`{warehouse, summary, lines:[…]}`)
     * for the caller to parse, or null on failure.
     */
    suspend fun fetchReconcile(warehouse: String): String? = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/warehouse/reconcile?warehouse=" + java.net.URLEncoder.encode(warehouse, "UTF-8"))
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                return@withContext connection.inputStream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching reconcile", e)
        }
        return@withContext null
    }

    /** Map an HTTP status to a sync outcome: 2xx ok, 4xx permanent reject, else retry. */
    private fun classifyWrite(code: Int): SyncOutcome = when {
        code in 200..299 -> SyncOutcome.SUCCESS
        code in 400..499 -> SyncOutcome.REJECTED
        else -> SyncOutcome.FAILED
    }

    // --- Multi-User API ---

    suspend fun fetchActiveUsers(): List<com.xelth.eckwms_movfast.ui.viewmodels.AppUser>? = withContext(Dispatchers.IO) {
        // 1. Direct HTTP with failover: active URL → silent re-auth on 401 →
        //    global. (The old direct call skipped re-auth entirely — a stale
        //    device JWT made this 401 forever, same root cause as the stranded
        //    trips of 2026-07-13.)
        val result = authenticatedGetWithFailover("/api/users/active")
        val jsonArray: JSONArray? = when {
            result is ScanResult.Success -> try { JSONArray(result.data) } catch (e: Exception) {
                Log.e(TAG, "Error parsing active users", e); null
            }
            else -> {
                // 2. Off-LAN (phone on another subnet / LTE, master NAT'd, no
                //    global URL) → the mesh path, same as trips/photos: a
                //    `users_active` mesh-task through the relay polygon.
                Log.w(TAG, "fetchActiveUsers HTTP failed (${(result as? ScanResult.Error)?.message}) — trying relay mesh")
                val ack = dispatchMeshTaskWithReAuth("users_active", JSONObject())
                if (ack.outcome == MeshOutcome.OK) ack.ack?.optJSONArray("users") else null
            }
        }
        if (jsonArray == null) return@withContext null
        try {
            val list = mutableListOf<com.xelth.eckwms_movfast.ui.viewmodels.AppUser>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(com.xelth.eckwms_movfast.ui.viewmodels.AppUser(
                    id = obj.getString("id"),
                    username = obj.optString("username", ""),
                    name = obj.optString("name", ""),
                    role = obj.optString("role", "user"),
                    mustChangePassword = obj.optBoolean("mustChangePassword", false)
                ))
            }
            Log.i(TAG, "Fetched ${list.size} active users")
            return@withContext list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing active users", e)
        }
        return@withContext null
    }

    suspend fun verifyUserPin(userId: String, pin: String): com.xelth.eckwms_movfast.ui.viewmodels.PinAuthResult = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        var transportFailed = false
        try {
            val url = URL("$baseUrl/api/users/verify-pin")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true

            val body = JSONObject().apply {
                put("userId", userId)
                put("pin", pin)
            }
            connection.outputStream.bufferedWriter().use { it.write(body.toString()) }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                // Seeded staff accounts must set their own password; the flag may be
                // top-level or nested in a "user" object. Absent → false (no forced flow).
                val must = try {
                    val respBody = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(respBody)
                    json.optBoolean(
                        "mustChangePassword",
                        json.optJSONObject("user")?.optBoolean("mustChangePassword", false) ?: false
                    )
                } catch (_: Exception) { false }
                return@withContext com.xelth.eckwms_movfast.ui.viewmodels.PinAuthResult(ok = true, mustChangePassword = must)
            }
            // HTTP answered (e.g. 401 wrong PIN) — definitive, no relay retry.
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying user PIN (transport)", e)
            transportFailed = true
        }
        // Off-LAN: the LAN master is unreachable — verify over the relay mesh
        // (`users_verify_pin` mesh-task; the bcrypt check runs on the master).
        if (transportFailed) {
            val body = JSONObject().apply {
                put("userId", userId)
                put("pin", pin)
            }
            val ack = dispatchMeshTaskWithReAuth("users_verify_pin", body)
            if (ack.outcome == MeshOutcome.OK) {
                val must = ack.ack?.optBoolean("mustChangePassword", false) ?: false
                return@withContext com.xelth.eckwms_movfast.ui.viewmodels.PinAuthResult(ok = true, mustChangePassword = must)
            }
        }
        return@withContext com.xelth.eckwms_movfast.ui.viewmodels.PinAuthResult(ok = false, mustChangePassword = false)
    }

    /**
     * Self-service password change. Posts {oldPassword,newPassword} to
     * POST {base}/api/auth/change-password with the device Bearer JWT (the same
     * token every other authenticated call uses). Server enforces the real rules
     * (>=8 chars, != old, old verifies) and answers 200 {success:true} or
     * 400/401 {success:false,error}. On a transport failure we fail over to the
     * global relay URL, mirroring [authenticatedPostWithFailover].
     */
    suspend fun changePassword(
        oldPassword: String,
        newPassword: String
    ): com.xelth.eckwms_movfast.ui.viewmodels.ChangePasswordResult = withContext(Dispatchers.IO) {
        val jsonBody = JSONObject().apply {
            put("oldPassword", oldPassword)
            put("newPassword", newPassword)
        }.toString()

        val activeUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        val globalUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getGlobalServerUrl().removeSuffix("/")

        // null == transport failure (retryable); non-null == server answered (final).
        var result = postChangePassword("$activeUrl/api/auth/change-password", jsonBody)
        if (result == null && globalUrl.isNotEmpty() && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ change-password to $activeUrl unreachable, failover to $globalUrl")
            result = postChangePassword("$globalUrl/api/auth/change-password", jsonBody)
        }
        return@withContext result
            ?: com.xelth.eckwms_movfast.ui.viewmodels.ChangePasswordResult(false, "Network error — could not reach server")
    }

    /** One change-password POST. Returns null only on a transport failure (so the
     *  caller may fail over); any HTTP response (2xx or 4xx) is parsed to a result. */
    private fun postChangePassword(
        urlStr: String,
        jsonBody: String
    ): com.xelth.eckwms_movfast.ui.viewmodels.ChangePasswordResult? {
        return try {
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(jsonBody) }

            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            }
            val json = try { JSONObject(text) } catch (_: Exception) { null }
            val success = code in 200..299 && (json?.optBoolean("success", true) ?: true)
            if (success) {
                com.xelth.eckwms_movfast.ui.viewmodels.ChangePasswordResult(true, null)
            } else {
                val err = json?.optString("error", "").takeIf { !it.isNullOrEmpty() }
                    ?: "Password change failed (HTTP $code)"
                com.xelth.eckwms_movfast.ui.viewmodels.ChangePasswordResult(false, err)
            }
        } catch (e: Exception) {
            Log.w(TAG, "changePassword error: $urlStr → ${e.message}")
            null
        }
    }
}

/**
 * Класс результата сканирования
 */
sealed class ScanResult {
    /**
     * Успешный результат
     */
    data class Success(
        val type: String,
        val message: String,
        val data: String,
        val checksum: String = "", // Checksum returned by the server buffer
        val imageUrls: List<String> = emptyList(),
        val aiInteraction: com.xelth.eckwms_movfast.ui.data.AiInteraction? = null
    ) : ScanResult()

    /**
     * Ошибка
     */
    data class Error(val message: String) : ScanResult()
}