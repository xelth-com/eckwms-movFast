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
 */
class ScanApiService(private val context: Context) {
    private val TAG = "ScanApiService"

    // API Key for authentication with the server buffer
    // Configured for public demo mode - scans will be visible on pda.repair/eckwms
    private val API_KEY = "public-demo-key-for-eckwms-app"

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
     * @param barcode Отсканированный штрих-код
     * @param barcodeType Тип штрих-кода (QR_CODE, CODE_128, и т.д.)
     * @return Результат обработки штрих-кода
     */
    suspend fun processScan(barcode: String, barcodeType: String, orderId: String? = null): ScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing scan: $barcode (type: $barcodeType)")

        try {
            val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
            val url = URL("$baseUrl/eckwms/api/scan")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-API-Key", API_KEY)
            connection.doOutput = true

            // Создаем JSON запрос в новом формате для buffer API
            val payloadJson = JSONObject().apply {
                put("deviceId", deviceId)
                put("payload", barcode)
                put("type", barcodeType)
            }
            val payloadBytes = payloadJson.toString().toByteArray()
            val crc = CRC32()
            crc.update(payloadBytes)
            val checksum = crc.value.toString(16).padStart(8, '0')

            val jsonRequest = JSONObject().apply {
                put("deviceId", deviceId)
                put("payload", barcode)
                put("type", barcodeType)
                put("checksum", checksum)
                orderId?.let { put("orderId", it) }
            }

            Log.d(TAG, "Sending request: $jsonRequest")

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

                // Parse the JSON response to extract checksum
                val responseJson = JSONObject(response)
                val checksum = responseJson.optString("checksum", "")
                val message = responseJson.optString("message", "Scan buffered successfully")

                Log.d(TAG, "Extracted checksum: $checksum")

                return@withContext ScanResult.Success(
                    type = "scan",
                    message = message,
                    data = response,
                    checksum = checksum
                )
            } else {
                // Обрабатываем ошибку
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"

                Log.e(TAG, "Server error: $errorMessage")
                return@withContext ScanResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan: ${e.message}", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Uploads an image to the server with optional barcode data
     * @param bitmap The image to upload
     * @param deviceId The device identifier
     * @param scanMode The scan mode ("dumb" or "mlkit")
     * @param barcodeData Optional barcode data from ML Kit analysis
     * @return Result of the upload operation
     */
    suspend fun uploadImage(bitmap: Bitmap, deviceId: String, scanMode: String, barcodeData: String?, quality: Int, orderId: String? = null): ScanResult = withContext(Dispatchers.IO) {
        val boundary = "Boundary-${System.currentTimeMillis()}"
        val baseUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        val url = URL("$baseUrl/eckwms/api/upload/image")
        val connection = url.openConnection() as HttpURLConnection
        val outputStream: java.io.OutputStream
        val writer: java.io.PrintWriter

        try {
            connection.requestMethod = "POST"
            // connection.setRequestProperty("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VySWQiOiJkZWZhdWx0X3VzZXJfaWQiLCJyb2xlIjoidXNlciIsImlhdCI6MTcxNjM4ODgzMywiZXhwIjoxNzE2MzkyNDMzfQ.8G7Db-25I152G2ny9hYf842t-G9e-m4R2fP6a_o4J4Y") // Temporarily disabled for debugging
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connection.doOutput = true

            outputStream = connection.outputStream
            writer = java.io.PrintWriter(java.io.OutputStreamWriter(outputStream, "UTF-8"), true)

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
            Log.d(TAG, "Compressed image size: ${imageBytes.size} bytes, Checksum: $imageChecksum")

            // Send imageChecksum
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"imageChecksum\"").append("\r\n")
            writer.append("\r\n").append(imageChecksum).append("\r\n").flush()

            // Send image file
            writer.append("--$boundary").append("\r\n")
            writer.append("Content-Disposition: form-data; name=\"image\"; filename=\"upload.webp\"").append("\r\n")
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
                Log.d(TAG, "Image upload successful: $response")
                return@withContext ScanResult.Success("upload", "Image uploaded", response)
            } else {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $responseCode"
                Log.e(TAG, "Image upload failed: $error")
                return@withContext ScanResult.Error("Upload failed: $error")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading image: ${e.message}", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown upload error")
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
        timestamp: Long
    ): ScanResult = withContext(Dispatchers.IO) {
        val serverUrl = com.xelth.eckwms_movfast.utils.SettingsManager.getServerUrl()
        Log.d(TAG, "Registering device with server: $serverUrl")

        try {
            val url = URL("$serverUrl/eckwms/api/device/register")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            val jsonRequest = JSONObject().apply {
                put("deviceId", deviceId)
                put("publicKey", publicKeyBase64)
                put("signature", signature)
                put("timestamp", timestamp)
            }

            Log.d(TAG, "Sending registration request: $jsonRequest")

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
            val url = URL("$globalServerUrl/api/pairing/get-instance-info")
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
        val imageUrls: List<String> = emptyList()
    ) : ScanResult()

    /**
     * Ошибка
     */
    data class Error(val message: String) : ScanResult()
}