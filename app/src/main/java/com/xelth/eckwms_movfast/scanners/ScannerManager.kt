// app/src/main/java/com/xelth/eckwms_movfast/scanners/ScannerManager.kt
package com.xelth.eckwms_movfast.scanners

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.TextCaseType

// Константы для broadcast
private const val SCAN_ACTION = "com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST"
private const val SCAN_DATA_KEY = "EXTRA_BARCODE_DECODING_DATA"
private const val TAG = "ScannerManager"




/**
 * Синглтон для управления сканером на уровне приложения
 */
class ScannerManager private constructor(private val application: Application) {
    // LiveData для передачи результатов сканирования
    private val _scanResult = MutableLiveData<String>()
    val scanResult: LiveData<String> get() = _scanResult

    // BroadcastReceiver для получения результатов сканирования
    private var scanReceiver: BroadcastReceiver? = null

    // Флаг для отслеживания состояния инициализации
    private var isInitialized = false

    // Обработчик главного потока
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Инициализирует сканер и BroadcastReceiver
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Сканер уже инициализирован")
            return
        }

        Log.d(TAG, "⭐ Инициализация сканера на уровне приложения...")

        // Инициализация XCScannerWrapper
        XCScannerWrapper.initialize(application) { result ->
            Log.d(TAG, "⭐ Scan result received via SDK callback: $result")
            _scanResult.postValue(result)
        }

        // Настройка сканера
        configureScanner()

        // Регистрация BroadcastReceiver для получения результатов сканирования
        registerBroadcastReceiver()

        isInitialized = true
        Log.d(TAG, "✓ Сканер успешно инициализирован")
    }

    /**
     * Регистрирует BroadcastReceiver для получения результатов сканирования
     */
    private fun registerBroadcastReceiver() {
        // Отменяем предыдущую регистрацию, если есть
        unregisterBroadcastReceiver()

        // Создаем новый BroadcastReceiver
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Log.d(TAG, "⭐ BroadcastReceiver получил Intent: ${intent.action}")

                // Проверяем, что это именно наш action
                if (intent.action == SCAN_ACTION) {
                    val barcode = intent.getStringExtra(SCAN_DATA_KEY)

                    if (barcode != null) {
                        Log.d(TAG, "⭐⭐⭐ Получен штрих-код через broadcast: $barcode")

                        // Check if this is a duplicate of what we've already processed through the callback
                        if (barcode == _scanResult.value) {
                            Log.d(TAG, "Ignoring duplicate barcode from broadcast - already processed via callback")
                            return
                        }

                        // Отправляем результат через LiveData
                        _scanResult.postValue(barcode)
                    } else {
                        Log.d(TAG, "⚠️ Intent не содержит данных по ключу $SCAN_DATA_KEY")
                        // Проверяем все extras для отладки
                        intent.extras?.keySet()?.forEach { key ->
                            Log.d(TAG, "  Ключ: $key, Значение: ${intent.extras?.get(key)}")
                        }
                    }
                }
            }
        }

        // Регистрируем BroadcastReceiver с правильными флагами для Android 13+
        val intentFilter = IntentFilter(SCAN_ACTION)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(scanReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            Log.d(TAG, "✓ BroadcastReceiver зарегистрирован с флагом RECEIVER_NOT_EXPORTED")
        } else {
            application.registerReceiver(scanReceiver, intentFilter)
            Log.d(TAG, "✓ BroadcastReceiver зарегистрирован")
        }
    }

    /**
     * Отменяет регистрацию BroadcastReceiver
     */
    private fun unregisterBroadcastReceiver() {
        scanReceiver?.let {
            try {
                application.unregisterReceiver(it)
                Log.d(TAG, "✓ BroadcastReceiver отменен")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при отмене регистрации BroadcastReceiver", e)
            }
            scanReceiver = null
        }
    }

    // Add this method directly to ScannerManager.kt class
    // rather than using an extension function

    /**
     * Возвращает тип последнего отсканированного штрихкода
     * @return Строковое обозначение типа штрихкода (QR_CODE, DATAMATRIX, CODE_128, etc.)
     */
    fun getLastBarcodeType(): String {
        // Get barcode type from scanner library if available
        // The XcBarcodeScanner does not provide direct access to barcode type information
        // so we'll use context clues from the barcode itself to determine the type

        val lastBarcode = scanResult.value ?: return "UNKNOWN"

        return when {
            // QR Code typical patterns
            lastBarcode.contains(":") && lastBarcode.contains("/") -> "QR_CODE"

            // DataMatrix typical patterns (often used for small items or components)
            lastBarcode.matches(Regex("^[0-9]{4}[A-Z]{2}\\d+$")) -> "DATAMATRIX"

            // EAN/UPC codes are pure digits with specific lengths
            lastBarcode.length == 13 && lastBarcode.all { it.isDigit() } -> "EAN_13"
            lastBarcode.length == 8 && lastBarcode.all { it.isDigit() } -> "EAN_8"
            lastBarcode.length == 12 && lastBarcode.all { it.isDigit() } -> "UPC_A"

            // ECKWMS internal codes likely use CODE_128
            lastBarcode.matches(Regex("^[ibpou][0-9]{18}$")) -> "CODE_128"
            lastBarcode.length == 7 && lastBarcode.all { it.isDigit() } -> "CODE_128"
            lastBarcode.startsWith("RMA") -> "CODE_128"

            // CODE_39 usually contains only uppercase letters, digits and some symbols
            lastBarcode.matches(Regex("^[A-Z0-9 \\-\\.$/+%*]+$")) -> "CODE_39"

            // If no specific pattern matched, but has special characters, assume CODE_128
            lastBarcode.any { !it.isLetterOrDigit() } -> "CODE_128"

            // Default fallback
            else -> "UNKNOWN"
        }
    }

    /**
     * Настраивает сканер с оптимальными параметрами
     */
    private fun configureScanner() {
        Log.d(TAG, "⭐ Настройка сканера...")

        // Настройка вывода для работы с broadcast
        XCScannerWrapper.setOutputMethod(OutputMethod.BROADCAST)

        // Максимальная область сканирования
        XCScannerWrapper.setScanRegionSize(RegionSizeType.VIEWSIZE_100)

        // Максимальное время сканирования
        XCScannerWrapper.setTimeout(9)

        // Звуковое и вибро-оповещение при успешном сканировании
        XCScannerWrapper.setSuccessNotification(NotificationType.SOUND_VIBRATOR)

        // Включить LED-индикатор при успешном сканировании
        XCScannerWrapper.enableSuccessIndicator(true)

        // Настройка обработки текста
        XCScannerWrapper.setTextCase(TextCaseType.NONE)

        // Включить распознавание всех основных типов штрих-кодов
        XCScannerWrapper.enableBarcodeType(BarcodeType.QRCODE, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE128, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE39, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.EAN13, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.EAN8, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.UPCA, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.UPCE, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.DATAMATRIX, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.PDF417, true)

        Log.d(TAG, "✓ Сканер настроен с оптимальными параметрами")
    }

    /**
     * Останавливает сканирование и освобождает ресурсы сканера при завершении работы приложения
     */
    fun cleanup() {
        if (!isInitialized) return

        // Останавливаем сканирование
        XCScannerWrapper.stopScan()
        if (XCScannerWrapper.isLoopScanRunning()) {
            XCScannerWrapper.stopLoopScan()
        }

        // Очистка ресурсов изображений
        cleanupImageResources()

        // Отменяем регистрацию BroadcastReceiver
        unregisterBroadcastReceiver()

        // Деинициализируем сканер
        XCScannerWrapper.deinitialize(application)

        isInitialized = false
        Log.d(TAG, "✓ Ресурсы сканера освобождены")
    }



    /**
     * Проверяет, инициализирован ли сканер
     * @return true если сканер инициализирован, false в противном случае
     */
    fun isInitialized(): Boolean {
        return isInitialized
    }

    /**
     * Включает/выключает определенный тип штрих-кода
     */
    fun enableBarcodeType(barcodeType: String, enabled: Boolean) {
        if (!isInitialized) initialize()
        XCScannerWrapper.enableBarcodeType(barcodeType, enabled)
    }

    /**
     * Запускает сканирование
     */
    fun startScan() {
        if (!isInitialized) initialize()
        XCScannerWrapper.startScan()
    }

    /**
     * Останавливает сканирование
     */
    fun stopScan() {
        if (!isInitialized) return
        XCScannerWrapper.stopScan()
    }

    /**
     * Запускает непрерывное сканирование
     */
    fun startLoopScan(intervalMs: Int = 500) {
        if (!isInitialized) initialize()
        XCScannerWrapper.setLoopScanInterval(intervalMs)
        XCScannerWrapper.startLoopScan()
    }

    /**
     * Останавливает непрерывное сканирование
     */
    fun stopLoopScan() {
        if (!isInitialized) return
        XCScannerWrapper.stopLoopScan()
    }

    /**
     * Устанавливает режим подсветки
     */
    fun setFlashLightsMode(flashMode: Int) {
        if (!isInitialized) initialize()
        XCScannerWrapper.setFlashLightsMode(flashMode)
    }

    /**
     * Устанавливает режим прицела
     */
    fun setAimerLightsMode(aimerMode: Int) {
        if (!isInitialized) initialize()
        XCScannerWrapper.setAimerLightsMode(aimerMode)
    }

    /**
     * Checks if the device's scanner hardware supports image capture
     * @return true if image capture is supported, false otherwise
     */
    fun isImageCaptureSupported(): Boolean {
        if (!isInitialized()) {
            Log.d(TAG, "Scanner not initialized - cannot check image support")
            return false
        }
        return XCScannerWrapper.isImageCaptureSupported()
    }


    /**
     * Проверяет, запущено ли непрерывное сканирование
     */
    fun isLoopScanRunning(): Boolean {
        return isInitialized && XCScannerWrapper.isLoopScanRunning()
    }

    companion object {
        @Volatile
        private var instance: ScannerManager? = null

        fun getInstance(application: Application): ScannerManager {
            return instance ?: synchronized(this) {
                instance ?: ScannerManager(application).also { instance = it }
            }
        }
    }
}