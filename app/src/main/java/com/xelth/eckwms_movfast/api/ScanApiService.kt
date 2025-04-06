// app/src/main/java/com/xelth/eckwms_movfast/api/ScanApiService.kt
// Полный файл сервиса API для отправки результатов сканирования на сервер
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

/**
 * Сервис для взаимодействия с API сканирования штрих-кодов
 */
class ScanApiService(private val context: Context) {
    private val TAG = "ScanApiService"

    // Базовый URL для API
    private val BASE_URL = "https://pda.repair/api/scan"

    // Уникальный идентификатор устройства
    private val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting Android ID: ${e.message}")
            UUID.randomUUID().toString()
        }
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

            // Создаем запрос JSON
            val jsonRequest = JSONObject().apply {
                put("barcode", barcode)
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

                if (jsonResponse.getBoolean("success")) {
                    val data = jsonResponse.getJSONObject("data")

                    return@withContext ScanResult.Success(
                        type = data.optString("type", "unknown"),
                        message = data.optString("message", ""),
                        data = data.toString()
                    )
                } else {
                    return@withContext ScanResult.Error(
                        jsonResponse.optString("message", "Unknown error")
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
     * Получает последние сканирования с сервера
     * @return Результат получения истории сканирований
     */
    suspend fun getRecentScans(): ScanResult = withContext(Dispatchers.IO) {
        try {
            val url = URL("$BASE_URL/recent")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")

            // Получаем ответ
            val responseCode = connection.responseCode

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Читаем успешный ответ
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return@withContext ScanResult.Success(
                    type = "recent_scans",
                    message = "Recent scans retrieved",
                    data = response
                )
            } else {
                // Обрабатываем ошибку
                val errorMessage = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "Error code: $responseCode"

                return@withContext ScanResult.Error(errorMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent scans: ${e.message}", e)
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
        val data: String
    ) : ScanResult()

    /**
     * Ошибка
     */
    data class Error(val message: String) : ScanResult()
}