// app/src/main/java/com/xelth/eckwms_movfast/scanners/XCScannerWrapper.kt
package com.xelth.eckwms_movfast.scanners

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tools.XCImage
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xcheng.scanner.LicenseState
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.ScannerResult
import com.xcheng.scanner.TextCaseType
import com.xcheng.scanner.XcBarcodeScanner

/**
 * Kotlin wrapper for XCScanner SDK
 * Provides a more Kotlin-friendly interface to interact with the scanner
 */
object XCScannerWrapper {
    private const val TAG = "XCScannerWrapper"
    private var isInitialized = false
    private var scanResultCallback: ((String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Обновленный метод инициализации
    fun initialize(context: Context, callback: (String) -> Unit) {
        if (isInitialized) {
            Log.d(TAG, "Scanner already initialized")
            scanResultCallback = callback // Обновляем колбэк в любом случае
            return
        }

        scanResultCallback = callback
        Log.d(TAG, "⭐ Initializing XcBarcodeScanner...")

        // Используем анонимный класс вместо лямбды для лучшей обработки
        XcBarcodeScanner.init(context, object : ScannerResult {
            override fun onResult(result: String) {
                Log.d(TAG, "⭐⭐⭐ SCAN RESULT RECEIVED FROM SCANNER: $result")

                // Всегда выполняем колбэк в основном потоке
                mainHandler.post {
                    try {
                        scanResultCallback?.let { callback ->
                            Log.d(TAG, "Forwarding result to UI: $result")
                            callback(result)
                        } ?: Log.e(TAG, "scanResultCallback is null!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in scan result callback", e)
                    }
                }
            }
        })

        isInitialized = true
        Log.d(TAG, "Scanner initialization complete")

        // Проверка состояния лицензии
        val licenseState = XcBarcodeScanner.getLicenseState()
        Log.d(TAG, "⭐ License state: ${getLicenseStateString(licenseState)}")

        if (licenseState != LicenseState.ACTIVED) {
            Log.d(TAG, "Attempting to activate license")
            XcBarcodeScanner.activateLicense()
        }
    }

    /**
     * Deinitialize the scanner SDK
     * @param context Application context
     */
    fun deinitialize(context: Context) {
        if (!isInitialized) return
        XcBarcodeScanner.deInit(context)
        isInitialized = false
        scanResultCallback = null
        Log.d(TAG, "Scanner deinitialized")
    }

    /**
     * Start scanning
     */
    fun startScan() {
        if (!isInitialized) {
            Log.e(TAG, "⚠️ Scanner not initialized")
            return
        }
        Log.d(TAG, "⭐ STARTING SCAN")
        XcBarcodeScanner.startScan()
    }

    /**
     * Stop scanning
     */
    fun stopScan() {
        if (!isInitialized) return
        Log.d(TAG, "⭐ STOPPING SCAN")
        XcBarcodeScanner.stopScan()
    }

    /**
     * Suspend scan service
     * This releases camera resources
     */
    fun suspendScanService() {
        if (!isInitialized) return
        XcBarcodeScanner.suspendScanService()
        Log.d(TAG, "Scan service suspended")
    }

    /**
     * Resume scan service
     */
    fun resumeScanService() {
        if (!isInitialized) return
        XcBarcodeScanner.resumeScanService()
        Log.d(TAG, "Scan service resumed")
    }

    /**
     * Check if scan service is suspended
     * @return true if suspended, false otherwise
     */
    fun isScanServiceSuspending(): Boolean {
        return XcBarcodeScanner.isScanServiceSuspending()
    }

    /**
     * Get service version
     * @return Service version string
     */
    fun getServiceVersion(): String {
        return XcBarcodeScanner.getServiceVersion()
    }

    /**
     * Enable or disable specific barcode type
     * @param barcodeType Type of barcode from BarcodeType
     * @param enabled true to enable, false to disable
     */
    fun enableBarcodeType(barcodeType: String, enabled: Boolean) {
        if (!isInitialized) return
        XcBarcodeScanner.enableBarcodeType(barcodeType, enabled)
        Log.d(TAG, "Barcode type $barcodeType ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Check if specific barcode type is enabled
     * @param barcodeType Type of barcode from BarcodeType
     * @return true if enabled, false if disabled
     */
    fun isBarcodeTypeEnabled(barcodeType: String): Boolean {
        return XcBarcodeScanner.isBarcodeTypeEnabled(barcodeType)
    }

    /**
     * Set output method for scan results
     * @param outputMethod Output method from OutputMethod
     */
    fun setOutputMethod(outputMethod: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setOutputMethod(outputMethod)
        Log.d(TAG, "Output method set to $outputMethod")
    }

    /**
     * Set notification type for successful scans
     * @param notificationType Notification type from NotificationType
     */
    fun setSuccessNotification(notificationType: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setSuccessNotification(notificationType)
        Log.d(TAG, "Success notification set to $notificationType")
    }

    /**
     * Enable or disable LED indicator for successful scans
     * @param enabled true to enable, false to disable
     */
    fun enableSuccessIndicator(enabled: Boolean) {
        if (!isInitialized) return
        XcBarcodeScanner.enableSuccessIndicator(enabled)
        Log.d(TAG, "Success indicator ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set scan timeout in seconds
     * @param seconds Timeout in seconds (1-9)
     */
    fun setTimeout(seconds: Int) {
        if (!isInitialized) return
        val limitedSeconds = seconds.coerceIn(1, 9)
        XcBarcodeScanner.setTimeout(limitedSeconds)
        Log.d(TAG, "Timeout set to $limitedSeconds seconds")
    }

    /**
     * Set aimer light mode
     * @param aimerMode Mode from AimerMode
     */
    fun setAimerLightsMode(aimerMode: Int) {
        if (!isInitialized) return
        XcBarcodeScanner.setAimerLightsMode(aimerMode)
        Log.d(TAG, "Aimer lights mode set to $aimerMode")
    }

    /**
     * Set flash light mode
     * @param flashMode Mode from FlashMode
     */
    fun setFlashLightsMode(flashMode: Int) {
        if (!isInitialized) return
        XcBarcodeScanner.setFlashLightsMode(flashMode)
        Log.d(TAG, "Flash lights mode set to $flashMode")
    }

    /**
     * Set text case for scan results
     * @param textCase Text case from TextCaseType
     */
    fun setTextCase(textCase: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setTextCase(textCase)
        Log.d(TAG, "Text case set to $textCase")
    }

    /**
     * Set text prefix for scan results
     * @param prefix Prefix string
     */
    fun setTextPrefix(prefix: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setTextPrefix(prefix)
        Log.d(TAG, "Text prefix set to $prefix")
    }

    /**
     * Set text prefix2 for scan results
     * @param prefix2 Second prefix string
     */
    fun setTextPrefix2(prefix2: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setTextPrefix2(prefix2)
        Log.d(TAG, "Text prefix2 set to $prefix2")
    }

    /**
     * Set text suffix for scan results
     * @param suffix Suffix string
     */
    fun setTextSuffix(suffix: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setTextSuffix(suffix)
        Log.d(TAG, "Text suffix set to $suffix")
    }

    /**
     * Set loop scan interval in milliseconds
     * @param ms Interval in milliseconds
     */
    fun setLoopScanInterval(ms: Int) {
        if (!isInitialized) return
        XcBarcodeScanner.setLoopScanInterval(ms)
        Log.d(TAG, "Loop scan interval set to $ms ms")
    }

    /**
     * Check if loop scan is running
     * @return true if running, false otherwise
     */
    fun isLoopScanRunning(): Boolean {
        return XcBarcodeScanner.isLoopScanRunning()
    }

    /**
     * Start loop scan
     */
    fun startLoopScan() {
        if (!isInitialized) return
        XcBarcodeScanner.startLoopScan()
        Log.d(TAG, "Loop scan started")
    }

    /**
     * Stop loop scan
     */
    fun stopLoopScan() {
        if (!isInitialized) return
        XcBarcodeScanner.stopLoopScan()
        Log.d(TAG, "Loop scan stopped")
    }

    /**
     * Set multi-barcode scanning options
     * @param numberOfBarcodes Number of barcodes to scan (1-20)
     * @param fixedNumber true for fixed number, false for variable number
     */
    fun setMultiBarcodes(numberOfBarcodes: Int, fixedNumber: Boolean) {
        if (!isInitialized) return
        val limitedNumber = numberOfBarcodes.coerceIn(1, 20)
        XcBarcodeScanner.setMultiBarcodes(limitedNumber, fixedNumber)
        Log.d(TAG, "Multi barcodes set to $limitedNumber, fixed: $fixedNumber")
    }

    /**
     * Set scan region size
     * @param regionSize Region size from RegionSizeType
     */
    fun setScanRegionSize(regionSize: Int) {
        if (!isInitialized) return
        XcBarcodeScanner.setScanRegionSize(regionSize)
        Log.d(TAG, "Scan region size set to $regionSize")
    }



    /**
     * Получает последнее отсканированное изображение
     * @return XCImage или null, если изображение недоступно
     */
    fun getLastDecodeImage(): com.tools.XCImage? {
        if (!isInitialized) return null
        return com.xcheng.scanner.XcBarcodeScanner.getLastDecodeImage()
    }

    /**
     * Converts license state code to a readable string
     * @param state License state code
     * @return Human-readable license state
     */
    private fun getLicenseStateString(state: Int): String {
        return when(state) {
            LicenseState.INACTIVE -> "Inactive"
            LicenseState.ACTIVATING -> "Activating"
            LicenseState.ACTIVED -> "Activated"
            LicenseState.INVALID -> "Invalid"
            LicenseState.NETWORK_ISSUE -> "Network Issue"
            LicenseState.EXPIRED -> "Expired"
            else -> "Unknown state: $state"
        }
    }
}