// app/src/main/java/com/xelth/eckwms_movfast/api/ScanApiService.kt
package com.xelth.eckwms_movfast.api

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
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
class ScanApiService(private val context: Context) {
    private val TAG = "ScanApiService"

    // API Key for authentication with the server buffer
    private val API_KEY = "[REDACTED_GENERIC_SECRET]"

    // Ссылка на ScannerManager для получения информации о типе штрих-кода
    private var scannerManager: ScannerManager? = null

    // Уникальный идентификатор устройства
    private val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Android ID: ${e.message}")
            UUID.randomUUID().toString()
        }
    }

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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
            connection.setRequestProperty("X-API-Key", API_KEY)
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
            connection.setRequestProperty("X-API-Key", API_KEY)
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ Upload to $activeUrl failed. Failover to Global. ImageID: $imageId")
            // Retry with SAME imageId - critical for deduplication!
            result = internalUploadImage(globalUrl, bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)

            Log.d(TAG, "uploadImage FAILOVER result: ${result::class.simpleName}, imageId: $imageId")

            if (result is ScanResult.Success) {
                // Global failover success - but keep local as active for next attempt
                Log.i(TAG, "✅ Global upload success (keeping local as active for next attempt)")
            }
        }

        Log.d(TAG, "uploadImage END - final result: ${result::class.simpleName}, imageId: $imageId")

        return@withContext result
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
    suspend fun registerDevice(
        publicKeyBase64: String,
        signature: String,
        timestamp: Long,
        inviteToken: String? = null
    ): ScanResult = withContext(Dispatchers.IO) {
        val serverUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        Log.d(TAG, "Registering device with server: $serverUrl")

        try {
            val url = URL("${serverUrl.removeSuffix("/")}/api/internal/register-device")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            // Go server expects: deviceId, deviceName, devicePublicKey, signature
            val jsonRequest = JSONObject().apply {
                put("deviceId", deviceId)
                put("deviceName", android.os.Build.MODEL)  // Added: device model name
                put("devicePublicKey", publicKeyBase64)
                put("signature", signature)
                // Note: timestamp not used by Go server, but included for compatibility
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

                // SECURE KEY SYNC: Extract enc_key from registration response
                try {
                    val json = JSONObject(response)
                    val encKey = json.optString("enc_key", "")
                    if (encKey.isNotEmpty()) {
                        Log.i(TAG, "🔐 Received encryption key from server during registration")
                        com.xelth.eckwms_movfast.utils.SettingsManager.saveEncKey(encKey)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse enc_key from registration", e)
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
                        // Dynamic repair order prefix from server config
                        val repairPrefix = json.optString("repair_order_prefix", "")
                        if (repairPrefix.isNotEmpty()) {
                            val currentPrefix = com.xelth.eckwms_movfast.utils.SettingsManager.getRepairOrderPrefix()
                            if (repairPrefix != currentPrefix) {
                                Log.i(TAG, "📋 Repair order prefix updated: $currentPrefix → $repairPrefix")
                                com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairOrderPrefix(repairPrefix)
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
    suspend fun submitDocument(type: String, payload: String, format: String): ScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Submitting document of type '$type'")
        try {
            val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
            val url = URL("$baseUrl/api/documents")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-API-Key", API_KEY)
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
            connection.setRequestProperty("X-API-Key", API_KEY)
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

        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
        if (result.isEmpty() && activeUrl != globalUrl) {
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
        if (result.isEmpty() && activeUrl != globalUrl) {
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
        if (result is ScanResult.Error && activeUrl != globalUrl) {
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

        if (result is ScanResult.Error && activeUrl != globalUrl) {
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

        if (result is ScanResult.Error && activeUrl != globalUrl) {
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
        return@withContext result is ScanResult.Success
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

    suspend fun confirmPickLine(pickingId: String, lineId: String, qtyDone: Double, productBarcode: String, locationBarcode: String): Boolean = withContext(Dispatchers.IO) {
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

            val success = connection.responseCode == HttpURLConnection.HTTP_OK
            if (success) Log.i(TAG, "Confirmed pick line $lineId (qty=$qtyDone)")
            else Log.w(TAG, "Confirm failed: HTTP ${connection.responseCode}")
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error confirming pick line", e)
            return@withContext false
        }
    }

    suspend fun validatePicking(pickingId: String): Boolean = withContext(Dispatchers.IO) {
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

            val success = connection.responseCode == HttpURLConnection.HTTP_OK
            if (success) Log.i(TAG, "Validated picking $pickingId")
            else Log.w(TAG, "Validate failed: HTTP ${connection.responseCode}")
            return@withContext success
        } catch (e: Exception) {
            Log.e(TAG, "Error validating picking", e)
            return@withContext false
        }
    }

    // --- Multi-User API ---

    suspend fun fetchActiveUsers(): List<com.xelth.eckwms_movfast.ui.viewmodels.AppUser>? = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
        try {
            val url = URL("$baseUrl/api/users/active")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + com.xelth.eckwms_movfast.utils.SettingsManager.getAuthToken())

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(response)
                val list = mutableListOf<com.xelth.eckwms_movfast.ui.viewmodels.AppUser>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(com.xelth.eckwms_movfast.ui.viewmodels.AppUser(
                        id = obj.getString("id"),
                        username = obj.optString("username", ""),
                        name = obj.optString("name", ""),
                        role = obj.optString("role", "user")
                    ))
                }
                Log.i(TAG, "Fetched ${list.size} active users")
                return@withContext list
            } else {
                Log.w(TAG, "fetchActiveUsers: HTTP ${connection.responseCode}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching active users", e)
        }
        return@withContext null
    }

    suspend fun verifyUserPin(userId: String, pin: String): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl().removeSuffix("/")
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

            return@withContext connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying user PIN", e)
        }
        return@withContext false
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