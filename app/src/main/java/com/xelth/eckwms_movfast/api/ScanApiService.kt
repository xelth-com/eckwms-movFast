// app/src/main/java/com/xelth/eckwms_movfast/api/ScanApiService.kt
package com.xelth.eckwms_movfast.api

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import com.xelth.eckwms_movfast.scanners.ScannerManager

/**
 * Сервис для взаимодействия с API сканирования штрих-кодов
 */
class ScanApiService(private val context: Context) {
    private val TAG = "ScanApiService"

    // Базовый URL для API
    private val BASE_URL = "https://pda.repair/eckwms/api"

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
     * @return Результат обработки штрих-кода
     */
    suspend fun processScan(barcode: String): ScanResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Processing scan: $barcode")

        try {
            val url = URL("$BASE_URL/scan")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            // Создаем JSON запрос с barcode, type и deviceId
            val jsonRequest = JSONObject().apply {
                put("barcode", barcode)

                // Используем ScannerManager для получения типа напрямую из SDK (ScannerSymResult)
                val barcodeType = scannerManager?.getLastBarcodeType() ?: "UNKNOWN"
                Log.d(TAG, "Barcode type for server: $barcodeType")

                put("type", barcodeType)
                put("deviceId", deviceId)
            }

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

                return@withContext ScanResult.Success(
                    type = "scan",
                    message = "Scan confirmed by server",
                    data = response,
                    imageUrls = emptyList()
                )
            } else {
                // Обрабатываем ошибку
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"

                return@withContext ScanResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing scan: ${e.message}", e)
            return@withContext ScanResult.Error(e.message ?: "Unknown error")
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
        val imageUrls: List<String> = emptyList() // Added this field for image URLs
    ) : ScanResult()

    /**
     * Ошибка
     */
    data class Error(val message: String) : ScanResult()
}