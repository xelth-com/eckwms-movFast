// app/src/main/java/com/xelth/eckwms_movfast/scanners/ScannerManager.kt
package com.xelth.eckwms_movfast.scanners

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.TextCaseType

private const val TAG = "ScannerManager"




/**
 * Синглтон для управления сканером на уровне приложения
 */
class ScannerManager private constructor(private val application: Application) {
    // LiveData для передачи результатов сканирования
    private val _scanResult = MutableLiveData<String>()
    val scanResult: LiveData<String> get() = _scanResult

    // LiveData для типа штрих-кода
    private val _barcodeType = MutableLiveData<String>()
    val barcodeType: LiveData<String> get() = _barcodeType

    // Флаг для отслеживания состояния инициализации
    private var isInitialized = false

    // Обработчик главного потока
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Инициализирует сканер с использованием ScannerSymResult
     */
    fun initialize() {
        val timestamp = System.currentTimeMillis()
        Log.d(TAG, "[$timestamp] Starting scanner initialization...")

        if (isInitialized) {
            Log.d(TAG, "[$timestamp] Scanner already initialized")
            return
        }

        Log.d(TAG, "[$timestamp] ⭐ Initializing scanner at application level...")

        // Инициализация XCScannerWrapper с получением типа и штрих-кода
        XCScannerWrapper.initialize(application) { symbology, barcode ->
            val callbackTimestamp = System.currentTimeMillis()
            Log.d(TAG, "[$callbackTimestamp] ⭐ Scan result: type=$symbology, barcode=$barcode")

            // Сохраняем тип штрих-кода
            _barcodeType.postValue(symbology)

            // Сохраняем сам штрих-код
            _scanResult.postValue(barcode)
        }

        // Настройка сканера
        configureScanner()

        isInitialized = true
        val endTimestamp = System.currentTimeMillis()
        Log.d(TAG, "[$endTimestamp] ✓ Scanner successfully initialized (took ${endTimestamp - timestamp}ms)")
    }

    /**
     * Возвращает тип последнего отсканированного штрихкода
     * Использует реальный тип из SDK callback (ScannerSymResult)
     * @return Строковое обозначение типа штрихкода (QRCODE, DATAMATRIX, CODE128, etc.)
     */
    fun getLastBarcodeType(): String {
        return _barcodeType.value ?: "UNKNOWN"
    }

    /**
     * Настраивает сканер с оптимальными параметрами
     */
    private fun configureScanner() {
        Log.d(TAG, "⭐ Настройка сканера...")

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

        // Включить распознавание ВСЕХ доступных типов штрих-кодов
        // 2D коды
        XCScannerWrapper.enableBarcodeType(BarcodeType.QRCODE, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.DATAMATRIX, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.PDF417, true)

        // 1D коды - семейство Code
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE128, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE39, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE93, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODE11, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.CODABAR, true)

        // EAN/UPC семейство
        XCScannerWrapper.enableBarcodeType(BarcodeType.EAN13, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.EAN8, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.UPCA, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.UPCE, true)

        // Другие промышленные коды (если поддерживаются)
        XCScannerWrapper.enableBarcodeType(BarcodeType.GS1_128, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.GS1_DATABAR, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.MSI, true)
        XCScannerWrapper.enableBarcodeType(BarcodeType.AZTEC, true)

        Log.d(TAG, "✓ Включены все доступные типы штрихкодов (2D: QR, DataMatrix, PDF417; 1D: Code128/93/39/11, Codabar, EAN, UPC, GS1, MSI, Aztec)")

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
        Log.d(TAG, ">>> startLoopScan called with interval=$intervalMs")
        XCScannerWrapper.setLoopScanInterval(intervalMs)
        XCScannerWrapper.startLoopScan()
        Log.d(TAG, ">>> startLoopScan completed, isRunning=${XCScannerWrapper.isLoopScanRunning()}")
    }

    /**
     * Останавливает непрерывное сканирование
     */
    fun stopLoopScan() {
        if (!isInitialized) return
        Log.d(TAG, ">>> stopLoopScan called, isRunning before stop=${XCScannerWrapper.isLoopScanRunning()}")
        XCScannerWrapper.stopLoopScan()
        Log.d(TAG, ">>> stopLoopScan completed, isRunning after stop=${XCScannerWrapper.isLoopScanRunning()}")
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