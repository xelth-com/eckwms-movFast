// app/src/main/java/com/xelth/eckwms_movfast/MainActivity.kt
package com.xelth.eckwms_movfast

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.ui.screens.SimpleImageViewerSection
import com.xelth.eckwms_movfast.ui.theme.EckwmsmovFastTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    // Ссылка на ScannerManager
    private lateinit var scannerManager: ScannerManager

    // Ссылка на сервис API сканирования
    private lateinit var scanApiService: ScanApiService

    // LiveData для деталей результата обработки сервером
    private val _scanResultDetails = MutableLiveData<String>()
    private val scanResultDetails: LiveData<String> = _scanResultDetails

    // LiveData для статуса отправки на сервер
    private val _serverRequestStatus = MutableLiveData<RequestStatus>()
    private val serverRequestStatus: LiveData<RequestStatus> = _serverRequestStatus

    // Added LiveData for image URLs
    private val _imageUrls = MutableLiveData<List<String>>(emptyList())
    private val imageUrls: LiveData<List<String>> = _imageUrls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Получение ScannerManager из приложения
        scannerManager = (application as EckwmsApp).scannerManager

        // Инициализация ScanApiService
        scanApiService = ScanApiService(this)
        // Предоставляем ScanApiService доступ к ScannerManager для получения типа штрих-кодов
        scanApiService.setScannerManager(scannerManager)

        // Наблюдаем за результатами сканирования от ScannerManager
        scannerManager.scanResult.observeForever { barcode ->
            if (barcode != null && barcode.isNotEmpty()) {
                Log.d(TAG, "Scanner detected barcode: $barcode")
                handleScannedBarcode(barcode)
            }
        }

        setContent {
            EckwmsmovFastTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Наблюдаем за результатами сканирования через LiveData
                    val latestResultDetails by scanResultDetails.observeAsState()
                    val latestRequestStatus by serverRequestStatus.observeAsState()
                    val serverImageUrls by imageUrls.observeAsState(emptyList())

                    MainContent(
                        scannedBarcode = null,
                        resultDetails = latestResultDetails,
                        requestStatus = latestRequestStatus,
                        imageUrls = serverImageUrls,
                        modifier = Modifier.padding(innerPadding),
                        onOpenScannerSettings = {
                            // Запускаем ScannerActivity
                            val intent = Intent(this@MainActivity, ScannerActivity::class.java)
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    /**
     * Обработка отсканированного штрих-кода и отправка на сервер
     */
    private fun handleScannedBarcode(barcode: String) {
        // Обновляем статус отправки
        _serverRequestStatus.postValue(RequestStatus.LOADING)
        _scanResultDetails.postValue("")
        _imageUrls.postValue(emptyList()) // Clear previous images

        // Отправляем на сервер
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d(TAG, "Sending barcode to server: $barcode")
                val result = scanApiService.processScan(barcode)

                withContext(Dispatchers.Main) {
                    when (result) {
                        is ScanResult.Success -> {
                            // Обрабатываем успешный результат
                            Log.d(TAG, "Server response success: ${result.data}")
                            _serverRequestStatus.postValue(RequestStatus.SUCCESS)

                            // Update image URLs if available
                            if (result.imageUrls.isNotEmpty()) {
                                Log.d(TAG, "Received ${result.imageUrls.size} images from server")
                                _imageUrls.postValue(result.imageUrls)
                            }

                            try {
                                // Формируем удобный для отображения текст
                                val formattedMessage = formatSuccessMessage(result)
                                _scanResultDetails.postValue(formattedMessage)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing success response: ${e.message}")
                                _scanResultDetails.postValue("Response received but error parsing details: ${e.message}")
                            }
                        }
                        is ScanResult.Error -> {
                            // Обрабатываем ошибку
                            Log.e(TAG, "Server response error: ${result.message}")
                            _serverRequestStatus.postValue(RequestStatus.ERROR)
                            _scanResultDetails.postValue("Error: ${result.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending to server", e)
                withContext(Dispatchers.Main) {
                    _serverRequestStatus.postValue(RequestStatus.ERROR)
                    _scanResultDetails.postValue("Error sending to server: ${e.message}")
                }
            }
        }
    }

    /**
     * Форматирует сообщение об успешном ответе
     */
    private fun formatSuccessMessage(result: ScanResult.Success): String {
        val responseData = JSONObject(result.data)

        val type = result.type
        val message = result.message

        val stringBuilder = StringBuilder()
        stringBuilder.append("Type: ${type.uppercase()}\n\n")
        stringBuilder.append("$message\n\n")

        // Добавляем дополнительные данные, если они есть
        if (responseData.has("serialNumber")) {
            stringBuilder.append("SN: ${responseData.getString("serialNumber")}\n")
        }

        if (responseData.has("created")) {
            val timestamp = responseData.getLong("created")
            val date = java.util.Date(timestamp * 1000L)
            stringBuilder.append("Created: ${date}\n")
        }

        if (responseData.has("class")) {
            stringBuilder.append("Class: ${responseData.getString("class")}\n")
        }

        // Information about images
        if (result.imageUrls.isNotEmpty()) {
            stringBuilder.append("\nImages: ${result.imageUrls.size}\n")
        }

        // Информация о буферах
        try {
            if (responseData.has("buffers")) {
                val buffers = responseData.getJSONObject("buffers")

                val items = buffers.getJSONArray("items")
                val boxes = buffers.getJSONArray("boxes")
                val places = buffers.getJSONArray("places")

                if (items.length() > 0) {
                    stringBuilder.append("\nItems in buffer: ${items.length()}\n")
                    for (i in 0 until items.length()) {
                        stringBuilder.append("- ${items.getString(i)}\n")
                    }
                }

                if (boxes.length() > 0) {
                    stringBuilder.append("\nBoxes in buffer: ${boxes.length()}\n")
                    for (i in 0 until boxes.length()) {
                        stringBuilder.append("- ${boxes.getString(i)}\n")
                    }
                }

                if (places.length() > 0) {
                    stringBuilder.append("\nPlaces in buffer: ${places.length()}\n")
                    for (i in 0 until places.length()) {
                        stringBuilder.append("- ${places.getString(i)}\n")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing buffers: ${e.message}")
        }

        return stringBuilder.toString()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Отписываемся от обсервера, чтобы избежать утечек памяти
        scannerManager.scanResult.removeObservers(this)
    }
}

/**
 * Статус запроса к серверу
 */
enum class RequestStatus {
    IDLE,      // Нет активного запроса
    LOADING,   // Запрос выполняется
    SUCCESS,   // Успешный ответ
    ERROR      // Ошибка
}

@Composable
fun MainContent(
    scannedBarcode: String?,
    resultDetails: String?,
    requestStatus: RequestStatus?,
    imageUrls: List<String>,
    modifier: Modifier = Modifier,
    onOpenScannerSettings: () -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Заголовок приложения
        Text(
            text = "ECKWMS Scanner",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Кнопка для настроек сканера
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Scanner Configuration",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Configure scanner settings, test API functions, and view scan images",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onOpenScannerSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Open Scanner Settings")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Divider()
        Spacer(modifier = Modifier.height(16.dp))

        // Отображение деталей результата
        if (resultDetails != null && resultDetails.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Server Response:",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Divider()
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = resultDetails,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // Image viewer section that displays images from the server
        if (imageUrls.isNotEmpty() || requestStatus == RequestStatus.SUCCESS) {
            Spacer(modifier = Modifier.height(16.dp))
            SimpleImageViewerSection(
                imageUrls = imageUrls,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Spacer to push content to the top
        Spacer(modifier = Modifier.weight(1f))

        // Информация о сервере
        Text(
            text = "Server: https://pda.repair/",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}