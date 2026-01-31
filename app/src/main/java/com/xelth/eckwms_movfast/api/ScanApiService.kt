// app/src/main/java/com/xelth/eckwms_movfast/api/ScanApiService.kt
package com.xelth.eckwms_movfast.api

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.CRC32
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
                // If Global worked, update settings to avoid future timeouts
                Log.i(TAG, "✅ Global failover success. Updating active server setting.")
                com.xelth.eckwms_movfast.utils.SettingsManager.saveServerUrl(globalUrl)
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
                val aiInteraction = if (responseJson.has("ai_interaction")) {
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
                Log.i(TAG, "✅ Global failover success for ScanWithID. Updating active server setting.")
                com.xelth.eckwms_movfast.utils.SettingsManager.saveServerUrl(globalUrl)
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
                val aiInteraction = if (responseJson.has("ai_interaction")) {
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

        // 1. Try Active URL first
        var result = internalUploadImage(activeUrl, bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)

        // 2. If failed AND Active != Global, try Global immediately with SAME imageId
        if (result is ScanResult.Error && activeUrl != globalUrl) {
            Log.w(TAG, "⚠️ Upload to $activeUrl failed. Failover to Global. ImageID: $imageId")
            // Retry with SAME imageId - critical for deduplication!
            result = internalUploadImage(globalUrl, bitmap, deviceId, scanMode, barcodeData, quality, orderId, imageId)

            if (result is ScanResult.Success) {
                Log.i(TAG, "✅ Global upload success. Updating active server setting.")
                com.xelth.eckwms_movfast.utils.SettingsManager.saveServerUrl(globalUrl)
            }
        }

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
            // Fix: Removed hardcoded /E/ prefix, removed trailing slash to prevent double slashes
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
                    // Go server returns {"status":"running","version":"1.0.0"}
                    return@withContext ScanResult.Success(
                        type = "device_status",
                        message = "Server is running",
                        data = response
                    )
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    Log.w(TAG, "Device status check: 401 Unauthorized - need authentication")
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