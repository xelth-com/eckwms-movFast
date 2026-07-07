// app/src/main/java/com/xelth/eckwms_movfast/scanners/XCScannerWrapper.kt
package com.xelth.eckwms_movfast.scanners

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import com.tools.ScanInfo
import com.tools.XCImage
import com.xcheng.scanner.AimerMode
import com.xcheng.scanner.BarcodeType
import com.xcheng.scanner.FlashMode
import com.xcheng.scanner.LicenseState
import com.xcheng.scanner.NotificationType
import com.xcheng.scanner.OutputMethod
import com.xcheng.scanner.RegionSizeType
import com.xcheng.scanner.ScannerResult
import com.xcheng.scanner.ScannerSymResult
import com.xcheng.scanner.TextCaseType
import com.xcheng.scanner.XcBarcodeScanner

/**
 * Kotlin wrapper for XCScanner SDK version 1.1.4
 * Provides a more Kotlin-friendly interface to interact with the scanner
 */
object XCScannerWrapper {
    private const val TAG = "XCScannerWrapper"
    private var isInitialized = false
    private var scanResultCallback: ((String, String) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Обновленный метод инициализации с ScannerSymResult
    fun initialize(context: Context, callback: (String, String) -> Unit) {
        if (isInitialized) {
            Log.d(TAG, "Scanner already initialized")
            scanResultCallback = callback // Обновляем колбэк в любом случае
            return
        }

        scanResultCallback = callback
        Log.d(TAG, "⭐ Initializing XcBarcodeScanner with ScannerSymResult...")

        // Используем ScannerSymResult для получения типа и штрих-кода
        XcBarcodeScanner.init(context, object : ScannerSymResult {
            override fun onResult(sym: String, barCode: String) {
                Log.d(TAG, "⭐⭐⭐ SCAN RESULT: type=$sym, barcode=$barCode")

                // Всегда выполняем колбэк в основном потоке
                mainHandler.post {
                    try {
                        scanResultCallback?.let { callback ->
                            Log.d(TAG, "Forwarding to UI: type=$sym, barcode=$barCode")
                            callback(sym, barCode)
                        } ?: Log.e(TAG, "scanResultCallback is null!")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in scan result callback", e)
                    }
                }
            }

            override fun scanInfo(info: ScanInfo?) {
                // Дополнительная информация о скане (опциональный метод)
                if (info != null) {
                    Log.d(TAG, "Scan info received: $info")
                }
            }
        })

        isInitialized = true
        Log.d(TAG, "Scanner initialization complete")

        // Deliver scans ONLY via the SDK callback above — force output to NONE so the
        // scanner does NOT also emit keyboard (IME) events. The default keyboard-fill
        // output injects soft-keyboard key events that crash Jetpack Compose
        // (IllegalStateException in FocusOwnerImpl.dispatchInterceptedSoftKeyboardEvent)
        // when they arrive during a focus transition / recomposition.
        try {
            XcBarcodeScanner.setOutputMethod(OutputMethod.NONE)
            Log.d(TAG, "Output method forced to NONE (SDK-callback only, no keyboard events)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to force OutputMethod.NONE: ${e.message}")
        }

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
     * How many app camera screens currently hold the scan engine suspended for
     * CameraX/ML-Kit coexistence. Every [suspendScanService] must be balanced by a
     * [resumeScanService]; while this is > 0 the watchdog must NOT auto-resume (a
     * screen legitimately owns the camera). See [ScannerManager] stuck-suspend check.
     */
    private val cameraHoldCount = java.util.concurrent.atomic.AtomicInteger(0)
    fun cameraHoldCount(): Int = cameraHoldCount.get()

    /**
     * Suspend scan service
     * This releases camera resources (so a CameraX/ML-Kit screen can use the camera).
     */
    fun suspendScanService() {
        if (!isInitialized) return
        val holds = cameraHoldCount.incrementAndGet()
        XcBarcodeScanner.suspendScanService()
        Log.d(TAG, "Scan service suspended (camera holds=$holds)")
    }

    /**
     * Resume scan service (balances a prior [suspendScanService]).
     */
    fun resumeScanService() {
        if (!isInitialized) return
        val holds = cameraHoldCount.updateAndGet { if (it > 0) it - 1 else 0 }
        XcBarcodeScanner.resumeScanService()
        Log.d(TAG, "Scan service resumed (camera holds=$holds)")
    }

    /**
     * Resume the engine for RECOVERY without touching [cameraHoldCount] — used by the
     * watchdog / app-foreground pre-warm to un-stick a scan engine the *vendor* left
     * suspended (screen-off / low-battery power management in `E3Util`), which our
     * hold counter never accounted for.
     */
    fun forceResumeScanService() {
        if (!isInitialized) return
        try {
            XcBarcodeScanner.resumeScanService()
            Log.d(TAG, "Scan service force-resumed (recovery)")
        } catch (e: Throwable) {
            Log.w(TAG, "forceResumeScanService failed: ${e.message}")
        }
    }

    /**
     * Check if scan service is suspended
     * @return true if suspended, false otherwise
     */
    fun isScanServiceSuspending(): Boolean {
        return XcBarcodeScanner.isScanServiceSuspending()
    }

    /** [isScanServiceSuspending] that swallows a dead-binder throw (returns false). */
    fun isScanServiceSuspendingSafe(): Boolean {
        return try {
            isInitialized && XcBarcodeScanner.isScanServiceSuspending()
        } catch (e: Throwable) {
            Log.w(TAG, "isScanServiceSuspendingSafe: binder call failed → ${e.message}")
            false
        }
    }

    /**
     * Get service version
     * @return Service version string
     */
    fun getServiceVersion(): String {
        return XcBarcodeScanner.getServiceVersion()
    }

    // ---------------------------------------------------------------------
    // Health probes (used by ScannerManager's watchdog)
    // ---------------------------------------------------------------------

    /**
     * Liveness probe: query the scan service's version over the binder.
     * Returns null if the call throws (binder to the vendor `com.xcheng.scannere3`
     * process is dead) OR the service is not initialized. A blank string is also
     * treated as "no answer". A non-blank version means the service replied.
     */
    fun getServiceVersionSafe(): String? {
        if (!isInitialized) return null
        return try {
            XcBarcodeScanner.getServiceVersion().takeIf { it.isNotBlank() }
        } catch (e: Throwable) {
            Log.w(TAG, "getServiceVersionSafe: binder call failed → ${e.message}")
            null
        }
    }

    /**
     * Read the license state, swallowing any binder failure.
     * @return one of the [LicenseState] constants, or [LicenseState.INVALID]-like
     *         sentinel [LICENSE_UNREACHABLE] (-999) if the call itself threw.
     */
    fun getLicenseStateSafe(): Int {
        return try {
            XcBarcodeScanner.getLicenseState()
        } catch (e: Throwable) {
            Log.w(TAG, "getLicenseStateSafe: binder call failed → ${e.message}")
            LICENSE_UNREACHABLE
        }
    }

    /** Ask the SDK to (re)activate the license; best-effort. */
    fun activateLicenseSafe() {
        try {
            XcBarcodeScanner.activateLicense()
        } catch (e: Throwable) {
            Log.w(TAG, "activateLicenseSafe failed → ${e.message}")
        }
    }

    /** Sentinel returned by [getLicenseStateSafe] when the binder call throws. */
    const val LICENSE_UNREACHABLE = -999

    /**
     * Rebind the AIDL connection to the vendor scan service — the *minimal* reset
     * that revives a hung scanner WITHOUT restarting our whole process.
     *
     * Why the manual static-nulling is essential (verified by disassembling the
     * vendor SDK `xcscanner_qrcode_v1.3.56.1.7`):
     *   - `XcBarcodeScanner` keeps the binder (`a`), the ServiceConnection (`d`)
     *     and the "initialized" flag (`e`) as **static** fields — they outlive our
     *     Activities and only reset on process death.
     *   - `init()` does `if (d != null) return;` → a second init() never rebinds.
     *   - `deInit()` calls `unbindService(d)` *before* nulling `d`, and is NOT
     *     exception-safe: once the vendor process has died, `unbindService` throws
     *     "Service not registered", so `d` is left non-null.
     *   - Net effect: after the vendor restarts, plain init()/deInit()+init() is a
     *     no-op and the scanner stays dead until the app process is restarted (which
     *     is the only thing that used to reset the static `d`).
     * So we force `a/d/c/e` back to a clean slate here, guaranteeing the following
     * init() actually calls bindService() and repopulates the binder.
     */
    fun forceReinitialize(context: Context) {
        val cb = scanResultCallback
        Log.w(TAG, ">>> forceReinitialize: rebind scan service (deInit → force-null statics → init)")
        try {
            if (isInitialized) XcBarcodeScanner.deInit(context)
        } catch (e: Throwable) {
            Log.w(TAG, "forceReinitialize: deInit threw → ${e.message}")
        }
        // Guarantee a clean slate even if deInit's unbindService() threw and left
        // the static ServiceConnection set (which would make init() a no-op).
        try {
            XcBarcodeScanner.a = null
            XcBarcodeScanner.d = null
            XcBarcodeScanner.c = null
            XcBarcodeScanner.e = false
        } catch (e: Throwable) {
            Log.w(TAG, "forceReinitialize: could not reset SDK statics → ${e.message}")
        }
        isInitialized = false
        if (cb != null) {
            initialize(context, cb)
        } else {
            Log.e(TAG, "forceReinitialize: no callback retained — cannot re-init")
        }
    }

    /**
     * Get SDK version
     * @return SDK version string
     */
    fun getSdkVersion(context: Context): String {
        return XcBarcodeScanner.getSdkVersion(context)
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
     * Set text suffix2 for scan results
     * @param suffix2 Second suffix string
     */
    fun setTextSuffix2(suffix2: String) {
        if (!isInitialized) return
        XcBarcodeScanner.setTextSuffix1(suffix2) // Note: Method name is different in the SDK
        Log.d(TAG, "Text suffix2 set to $suffix2")
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
     */
    fun setMultiBarcodes(numberOfBarcodes: Int) {
        if (!isInitialized) return
        val limitedNumber = numberOfBarcodes.coerceIn(1, 20)
        XcBarcodeScanner.setMultiBarcodes(limitedNumber)
        Log.d(TAG, "Multi barcodes set to $limitedNumber")
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
     * Load settings from storage
     */
    fun loadSettings() {
        if (!isInitialized) return
        XcBarcodeScanner.loadSettings()
        Log.d(TAG, "Settings loaded")
    }

    /**
     * Save settings to storage
     */
    fun saveSettings() {
        if (!isInitialized) return
        XcBarcodeScanner.saveSettings()
        Log.d(TAG, "Settings saved")
    }

    /**
     * Reset settings to default
     */
    fun resetSettings() {
        if (!isInitialized) return
        XcBarcodeScanner.resetSettings()
        Log.d(TAG, "Settings reset to default")
    }

// Replace the DataMatrix functions in XCScannerWrapper.kt with these versions

    /**
     * Set DataMatrix barcode format with separators (dummy implementation)
     * Note: This API does not seem available in current scanner SDK
     * @param withSeparators true to include separators, false to exclude
     */
    fun setDataMatrixWithSeparators(withSeparators: Boolean) {
        if (!isInitialized) return
        try {
            // Try reflection as a fallback if the direct method is not available
            val method = XcBarcodeScanner::class.java.getDeclaredMethod("setDataMatrixWithSeparators", Boolean::class.java)
            method.invoke(null, withSeparators)
            Log.d(TAG, "DataMatrix with separators: $withSeparators")
        } catch (e: Exception) {
            Log.w(TAG, "setDataMatrixWithSeparators not supported in this scanner version: ${e.message}")
        }
    }

    /**
     * Set maximum output length for DataMatrix barcodes (dummy implementation)
     * Note: This API does not seem available in current scanner SDK
     * @param maxLength Maximum length for output
     */
    fun setDataMatrixMaxOutputLength(maxLength: Int) {
        if (!isInitialized) return
        try {
            // Try reflection as a fallback if the direct method is not available
            val method = XcBarcodeScanner::class.java.getDeclaredMethod("setDataMatrixMaxOutputLength", Int::class.java)
            method.invoke(null, maxLength)
            Log.d(TAG, "DataMatrix max output length: $maxLength")
        } catch (e: Exception) {
            Log.w(TAG, "setDataMatrixMaxOutputLength not supported in this scanner version: ${e.message}")
        }
    }

    /**
     * Check if the device supports image capture
     * @return true if supported, false otherwise
     */
    fun isImageCaptureSupported(): Boolean {
        if (!isInitialized) return false

        // Check if the scanner service is available
        if (XcBarcodeScanner.a == null) {
            Log.d(TAG, "Image capture not supported: Scanner service reference is null")
            return false
        }

        return try {
            // Try a test call - this won't work if the device doesn't support images
            val testImage = getLastDecodeImage()
            testImage != null
        } catch (e: Exception) {
            Log.d(TAG, "Image capture not supported: ${e.message}")
            false
        }
    }

    /**
     * Gets the last decoded image from the scanner
     * ВАЖНО: Исправлена проблема с null-reference и обработкой ошибок
     * @return XCImage или null, если изображение недоступно
     */
    fun getLastDecodeImage(): XCImage? {
        if (!isInitialized) {
            Log.e(TAG, "Cannot get last decode image - scanner not initialized")
            return null
        }

        // Важная проверка на null сервиса сканера, которая отсутствует в SDK
        if (XcBarcodeScanner.a == null) {
            Log.e(TAG, "Scanner service reference is null - image retrieval not possible")
            return null
        }

        try {
            // Now it's safe to call the method with a non-null scanner service
            Log.d(TAG, "Calling XcBarcodeScanner.getLastDecodeImage()...")
            val result = XcBarcodeScanner.getLastDecodeImage()
            Log.d(TAG, "getLastDecodeImage() returned: ${if (result == null) "null" else "image ${result.width}x${result.height}"}")
            return result
        } catch (e: IllegalArgumentException) {
            // This specific error occurs when trying to read from Parcel
            Log.e(TAG, "Error parsing image data from scanner service (Ask Gemini)", e)
            return null
        } catch (e: RemoteException) {
            Log.e(TAG, "Remote service error while getting image", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Error getting last decode image", e)
            return null
        }
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