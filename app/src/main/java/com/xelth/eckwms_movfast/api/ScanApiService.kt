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
    private val BASE_URL = "https://pda.repair/api/scan"

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
            val url = URL("$BASE_URL/process")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true

            // Создаем JSON запрос с barcode и type
            val jsonRequest = JSONObject().apply {
                put("barcode", barcode)

                // Используем ScannerManager для получения типа напрямую, если доступен
                val barcodeType = scannerManager?.getLastBarcodeType() ?: guessType(barcode)

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

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Читаем успешный ответ
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                if (jsonResponse.has("success") && jsonResponse.getBoolean("success")) {
                    // Existing code to get message and type
                    val message = jsonResponse.optString("text", "Successful scan")
                    val type = jsonResponse.optString("contentType", "unknown")

                    // Parse image URLs from the response
                    val imageUrls = mutableListOf<String>()
                    if (jsonResponse.has("images")) {
                        try {
                            val imagesArray = jsonResponse.getJSONArray("images")
                            for (i in 0 until imagesArray.length()) {
                                imageUrls.add(imagesArray.getString(i))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing image URLs: ${e.message}")
                        }
                    }

                    return@withContext ScanResult.Success(
                        type = type,
                        message = message,
                        data = response,
                        imageUrls = imageUrls
                    )
                } else {
                    return@withContext ScanResult.Error(
                        jsonResponse.optString("text", "Unknown error")
                    )
                }
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

    /**
     * Определяет предполагаемый формат штрих-кода
     * Возвращает тип по стандарту GS1 (QR_CODE, DATAMATRIX, CODE_128 и т.д.)
     */
    private fun guessType(barcode: String): String {
        return when {
            // Формат QR кода обычно включает определенные символы и может иметь разную длину
            barcode.contains(":") && barcode.contains("/") -> "QR_CODE"
            // DataMatrix обычно имеет определенную структуру
            barcode.matches(Regex("^[0-9]{4}[A-Z]{2}\\d+$")) -> "DATAMATRIX"
            // Коды EAN/UPC состоят только из цифр и имеют определенную длину
            barcode.length == 13 && barcode.all { it.isDigit() } -> "EAN_13"
            barcode.length == 8 && barcode.all { it.isDigit() } -> "EAN_8"
            barcode.length == 12 && barcode.all { it.isDigit() } -> "UPC_A"
            // CODE_128 может содержать любые символы
            barcode.length > 5 && barcode.any { !it.isLetterOrDigit() } -> "CODE_128"
            // CODE_39 обычно содержит только заглавные буквы, цифры и некоторые символы
            barcode.matches(Regex("^[A-Z0-9 \\-\\.$/+%*]+$")) -> "CODE_39"
            // Для семизначных кодов предметов используем CODE_128
            barcode.length == 7 && barcode.all { it.isDigit() } -> "CODE_128"
            // Для внутренних кодов ECKWMS можно определить формат
            barcode.matches(Regex("^[ibpou][0-9]{18}$")) -> "CODE_128"
            barcode.startsWith("RMA") -> "CODE_128"
            // По умолчанию - неизвестный формат
            else -> "UNKNOWN"
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