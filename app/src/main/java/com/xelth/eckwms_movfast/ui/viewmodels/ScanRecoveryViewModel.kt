package com.xelth.eckwms_movfast.ui.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.xelth.eckwms_movfast.EckwmsApp
import com.xelth.eckwms_movfast.utils.FileUtils
import com.xelth.eckwms_movfast.scanners.ScannerManager
import com.xelth.eckwms_movfast.scanners.getLastDecodedImage
import com.xelth.eckwms_movfast.api.ScanApiService
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.ui.data.ScanHistoryItem
import com.xelth.eckwms_movfast.ui.data.ScanStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Функция для поворота изображения на 180 градусов
private fun rotateBitmap180(bitmap: Bitmap): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(180f)
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

enum class ScanState {
    IDLE, // Waiting for user action
    HW_SCANNING, // Hardware scanner is active
    HW_SCAN_FAILED, // Hardware scan timed out or failed, ready for chance 2
    ML_ANALYSIS_SINGLE, // ML Kit is analyzing a single image
    ML_ANALYSIS_FAILED, // ML Kit failed on single image, ready for chance 3
    RECOVERY_SESSION_ACTIVE, // User is collecting multiple images
    RECOVERY_ANALYSIS, // ML Kit is analyzing collected images
    SUCCESS, // Barcode found
    FAILURE // All attempts failed
}

data class RecoveryStatus(val imagesCollected: Int, val totalImages: Int)

class ScanRecoveryViewModel private constructor(application: Application) : AndroidViewModel(application) {

    private val TAG = "ScanRecoveryVM"
    private val scannerManager: ScannerManager = (application as EckwmsApp).scannerManager
    private val scanApiService: ScanApiService = ScanApiService(application).apply {
        setScannerManager(scannerManager)
    }

    private val _scanState = MutableLiveData<ScanState>(ScanState.IDLE)
    val scanState: LiveData<ScanState> = _scanState

    private val _scannedBarcode = MutableLiveData<String?>(null)
    val scannedBarcode: LiveData<String?> = _scannedBarcode

    private val _recoveryStatus = MutableLiveData<RecoveryStatus>()
    val recoveryStatus: LiveData<RecoveryStatus> = _recoveryStatus

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    // Scan history management
    private val _scanHistory = MutableLiveData<List<ScanHistoryItem>>(emptyList())
    val scanHistory: LiveData<List<ScanHistoryItem>> = _scanHistory

    // --- DEBUG ---
    private val _debugLog = MutableLiveData<List<String>>(emptyList())
    val debugLog: LiveData<List<String>> = _debugLog

    private val _recoveryImagesPreview = MutableLiveData<List<Bitmap>>(emptyList())
    val recoveryImagesPreview: LiveData<List<Bitmap>> = _recoveryImagesPreview

    private val _singleRecoveryImage = MutableLiveData<Bitmap?>(null)
    val singleRecoveryImage: LiveData<Bitmap?> = _singleRecoveryImage

    private val _allDiagnosticImages = MutableLiveData<List<Bitmap>>(emptyList())
    val allDiagnosticImages: LiveData<List<Bitmap>> = _allDiagnosticImages

    private val _debugPanelEnabled = MutableLiveData<Boolean>(true)
    val debugPanelEnabled: LiveData<Boolean> = _debugPanelEnabled
    // --- END DEBUG ---

    private var hardwareScanJob: Job? = null
    private val recoveryImages = mutableListOf<Bitmap>()
    private val RECOVERY_IMAGE_COUNT = 3

    // Для предотвращения дубликатов
    private var lastProcessedBarcode: String? = null
    private var lastProcessedTime: Long = 0

    // Observer для scanResult - должен быть переменной для корректного удаления в onCleared()
    private val scanResultObserver = androidx.lifecycle.Observer<String> {
        // Обрабатываем только в состояниях IDLE или HW_SCANNING
        // И только если это новый штрих-код (не дубликат)
        if (it != null && (_scanState.value == ScanState.HW_SCANNING || _scanState.value == ScanState.IDLE)) {
            val currentTime = System.currentTimeMillis()

            // Проверка на дубликаты: игнорируем если тот же штрих-код пришел менее чем через 2 секунды
            if (it == lastProcessedBarcode && (currentTime - lastProcessedTime) < 2000) {
                addLog("Ignoring duplicate scan: $it")
                return@Observer
            }

            lastProcessedBarcode = it
            lastProcessedTime = currentTime

            addLog("Hardware scan SUCCESS: $it (state: ${_scanState.value})")
            hardwareScanJob?.cancel()
            scannerManager.stopLoopScan()
            _scannedBarcode.postValue(it)
            _scanState.postValue(ScanState.SUCCESS)

            // Send scan to server
            val barcodeType = scannerManager.getLastBarcodeType() ?: "UNKNOWN"
            addLog("Barcode type from scanner: $barcodeType")
            sendScanToServer(it, barcodeType)

            // Auto-reset to IDLE after a short delay to allow continuous scanning
            viewModelScope.launch {
                delay(1000) // 1 second delay
                if (_scanState.value == ScanState.SUCCESS) {
                    _scanState.postValue(ScanState.IDLE)
                    addLog("Auto-reset to IDLE for continuous scanning")
                }
            }
        }
    }

    init {
        addLog("ViewModel Initialized.")
        scannerManager.scanResult.observeForever(scanResultObserver)
    }

    /**
     * Public method to add log messages (used both internally and externally)
     */
    fun addLog(message: String) {
        Log.d(TAG, message)
        val currentLog = _debugLog.value ?: emptyList()
        _debugLog.postValue(currentLog + message)
    }

    fun startHardwareScan() {
        addLog("Attempting hardware scan...")
        _scanState.value = ScanState.HW_SCANNING
        _errorMessage.value = null
        _scannedBarcode.value = null
        scannerManager.startLoopScan(500)

        hardwareScanJob = viewModelScope.launch {
            delay(5000)
            if (_scanState.value == ScanState.HW_SCANNING) {
                addLog("Hardware scan TIMEOUT.")
                scannerManager.stopLoopScan()
                _scanState.postValue(ScanState.HW_SCAN_FAILED)
            }
        }
    }

    /**
     * Request to start single image recovery - prepares state for camera capture
     */
    fun requestSingleImageRecovery() {
        addLog("Requesting single image recovery via camera")
        _scanState.value = ScanState.ML_ANALYSIS_SINGLE
        _errorMessage.value = null
    }

    /**
     * Process a captured image from camera for single recovery
     */
    fun processCapturedImageForSingleRecovery(image: Bitmap) {
        addLog("Processing captured image for single recovery: ${image.width}x${image.height}")

        // Store image for UI display
        _singleRecoveryImage.postValue(image)
        updateAllDiagnosticImages()

        // Save diagnostic image
        val savedUri = FileUtils.saveBitmapToPictures(getApplication(), image, "single_recovery")
        addLog("Diagnostic image saved: $savedUri")

        processImageWithMlKit(image) { success, result ->
            if (success) {
                addLog("ML Kit single image SUCCESS: $result")
                _scannedBarcode.postValue(result)
                _scanState.postValue(ScanState.SUCCESS)

                // Send scan to server
                val barcodeType = scannerManager.getLastBarcodeType() ?: "UNKNOWN"
                sendScanToServer(result, barcodeType)
            } else {
                addLog("ML Kit single image FAILED: $result")
                _errorMessage.postValue(result)
                _scanState.postValue(ScanState.ML_ANALYSIS_FAILED)
            }
        }
    }

    /**
     * Legacy method for backward compatibility - now redirects to camera capture
     * @deprecated Use requestSingleImageRecovery() and processCapturedImageForSingleRecovery() instead
     */
    @Deprecated("Use camera-based recovery methods instead")
    fun trySingleImageRecovery() {
        addLog("Chance 2: ML Kit single image analysis.")
        _scanState.value = ScanState.ML_ANALYSIS_SINGLE
        val image = scannerManager.getLastDecodedImage()
        if (image != null) {
            processCapturedImageForSingleRecovery(image)
        } else {
            addLog("ML Kit FAILED: Could not get image from scanner.")
            _errorMessage.postValue("Could not get image from scanner.")
            _scanState.postValue(ScanState.ML_ANALYSIS_FAILED)
        }
    }

    /**
     * Request to start recovery session - prepares state for multi-image camera capture
     */
    fun requestRecoverySession() {
        addLog("Requesting multi-image recovery session via camera")
        recoveryImages.clear()
        _recoveryImagesPreview.value = emptyList()
        _recoveryStatus.value = RecoveryStatus(0, RECOVERY_IMAGE_COUNT)
        _scanState.value = ScanState.RECOVERY_SESSION_ACTIVE
        _errorMessage.value = null
    }

    /**
     * Process a captured image from camera for recovery session
     */
    fun processCapturedImageForRecoverySession(image: Bitmap) {
        if (_scanState.value != ScanState.RECOVERY_SESSION_ACTIVE) {
            addLog("Warning: Received image but not in recovery session state")
            return
        }

        addLog("Processing captured image for recovery session: ${image.width}x${image.height}")

        // Save diagnostic image
        val savedUri = FileUtils.saveBitmapToPictures(getApplication(), image, "recovery_${recoveryImages.size + 1}")
        addLog("Diagnostic recovery image saved: $savedUri")

        recoveryImages.add(image)
        _recoveryImagesPreview.postValue(ArrayList(recoveryImages)) // Post a copy
        _recoveryStatus.postValue(RecoveryStatus(recoveryImages.size, RECOVERY_IMAGE_COUNT))
        updateAllDiagnosticImages()
        addLog("Collected image ${recoveryImages.size}/$RECOVERY_IMAGE_COUNT.")

        if (recoveryImages.size >= RECOVERY_IMAGE_COUNT) {
            processRecoveryImages()
        }
    }

    /**
     * Legacy method for backward compatibility - now uses camera capture
     * @deprecated Use requestRecoverySession() and processCapturedImageForRecoverySession() instead
     */
    @Deprecated("Use camera-based recovery methods instead")
    fun startRecoverySession() {
        requestRecoverySession()
    }

    /**
     * Legacy method for backward compatibility - now uses camera capture
     * @deprecated Use processCapturedImageForRecoverySession() instead
     */
    @Deprecated("Use camera-based recovery methods instead")
    fun captureImageForRecovery() {
        if (_scanState.value != ScanState.RECOVERY_SESSION_ACTIVE) return
        addLog("Capturing image for recovery...")
        scannerManager.startScan()
        viewModelScope.launch {
            delay(500)
            val image = scannerManager.getLastDecodedImage()
            if (image != null) {
                processCapturedImageForRecoverySession(image)
            } else {
                addLog("Image capture FAILED.")
                _errorMessage.postValue("Failed to capture image. Please try again.")
            }
        }
    }

    private fun processRecoveryImages() {
        addLog("Processing ${recoveryImages.size} collected images...")
        _scanState.value = ScanState.RECOVERY_ANALYSIS
        viewModelScope.launch(Dispatchers.IO) {
            for ((index, image) in recoveryImages.withIndex()) {
                addLog("Analyzing image ${index + 1}...")
                val found = processImageWithMlKitSuspending(image)
                if (found != null) {
                    addLog("Multi-image recovery SUCCESS: $found")
                    _scannedBarcode.postValue(found)
                    _scanState.postValue(ScanState.SUCCESS)

                    // Send scan to server
                    val barcodeType = scannerManager.getLastBarcodeType() ?: "UNKNOWN"
                    sendScanToServer(found, barcodeType)

                    return@launch
                }
            }
            addLog("Multi-image recovery FAILED.")
            _errorMessage.postValue("Could not decode barcode from any of the images.")
            _scanState.postValue(ScanState.FAILURE)
        }
    }

    private fun processImageWithMlKit(bitmap: Bitmap, callback: (Boolean, String) -> Unit) {
        addLog("Starting ML Kit analysis on ${bitmap.width}x${bitmap.height} image")
        // Поворачиваем изображение на 180 градусов, так как они отображаются вверх ногами
        val rotatedBitmap = rotateBitmap180(bitmap)
        addLog("Rotated image for ML Kit analysis: ${rotatedBitmap.width}x${rotatedBitmap.height}")
        val image = InputImage.fromBitmap(rotatedBitmap, 0)

        // Configure scanner to detect all barcode formats
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_CODE_93,
                Barcode.FORMAT_CODABAR,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_ITF,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()

        val scanner = BarcodeScanning.getClient(options)
        addLog("ML Kit scanner configured for all formats")

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                addLog("ML Kit analysis completed. Found ${barcodes.size} barcodes")
                if (barcodes.isNotEmpty()) {
                    val barcode = barcodes[0]
                    addLog("Barcode details - Format: ${barcode.format}, Value: ${barcode.rawValue}")
                    callback(true, barcode.rawValue ?: "")
                } else {
                    addLog("No barcode detected in image")
                    callback(false, "No barcode found in image.")
                }
            }
            .addOnFailureListener { e ->
                addLog("ML Kit analysis failed with exception: ${e.message}")
                callback(false, "ML Kit analysis failed: ${e.message}")
            }
    }

    private suspend fun processImageWithMlKitSuspending(bitmap: Bitmap): String? {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            processImageWithMlKit(bitmap) { success, result ->
                continuation.resumeWith(Result.success(if (success) result else null))
            }
        }
    }

    private fun updateAllDiagnosticImages() {
        val allImages = mutableListOf<Bitmap>()

        // Add single recovery image if available
        _singleRecoveryImage.value?.let {
            allImages.add(it)
            addLog("Added single recovery image to UI display")
        }

        // Add all recovery session images
        allImages.addAll(recoveryImages)
        if (recoveryImages.isNotEmpty()) {
            addLog("Added ${recoveryImages.size} recovery session images to UI display")
        }

        addLog("Total diagnostic images for UI: ${allImages.size}")
        _allDiagnosticImages.postValue(allImages)
    }

    fun setDebugPanelEnabled(enabled: Boolean) {
        _debugPanelEnabled.value = enabled
        addLog("Debug panel ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Sends a scanned barcode to the server and updates the scan history
     */
    fun sendScanToServer(barcode: String, barcodeType: String) {
        addLog("Sending scan to server: $barcode")

        // Create a new scan history item with PENDING status
        val scanItem = ScanHistoryItem(
            barcode = barcode,
            timestamp = System.currentTimeMillis(),
            status = ScanStatus.PENDING,
            type = barcodeType
        )

        // Add to history list
        val currentHistory = _scanHistory.value?.toMutableList() ?: mutableListOf()
        currentHistory.add(0, scanItem) // Add to the beginning of the list
        _scanHistory.postValue(currentHistory)

        // Send to server
        viewModelScope.launch {
            try {
                val result = scanApiService.processScan(barcode, barcodeType)

                // Update the status based on the result
                val updatedHistory = _scanHistory.value?.toMutableList() ?: mutableListOf()
                val index = updatedHistory.indexOfFirst { it.id == scanItem.id }

                if (index != -1) {
                    when (result) {
                        is ScanResult.Success -> {
                            addLog("Server buffered scan: $barcode (checksum: ${result.checksum})")
                            updatedHistory[index].status = ScanStatus.BUFFERED
                            updatedHistory[index].checksum = result.checksum
                        }
                        is ScanResult.Error -> {
                            addLog("Server rejected scan: ${result.message}")
                            updatedHistory[index].status = ScanStatus.FAILED
                            _errorMessage.postValue("Server error: ${result.message}")
                        }
                    }
                    _scanHistory.postValue(updatedHistory)
                }
            } catch (e: Exception) {
                addLog("Error sending scan to server: ${e.message}")

                // Update status to FAILED
                val updatedHistory = _scanHistory.value?.toMutableList() ?: mutableListOf()
                val index = updatedHistory.indexOfFirst { it.id == scanItem.id }
                if (index != -1) {
                    updatedHistory[index].status = ScanStatus.FAILED
                    _scanHistory.postValue(updatedHistory)
                }
                _errorMessage.postValue("Network error: ${e.message}")
            }
        }
    }

    /**
     * Handles scanned data from camera or other sources
     * This centralizes the processing of all scan results
     */
    fun handleScannedData(barcode: String, type: String) {
        val currentTime = System.currentTimeMillis()

        // Проверка на дубликаты: игнорируем если тот же штрих-код пришел менее чем через 2 секунды
        if (barcode == lastProcessedBarcode && (currentTime - lastProcessedTime) < 2000) {
            addLog("Ignoring duplicate camera scan: $barcode")
            return
        }

        lastProcessedBarcode = barcode
        lastProcessedTime = currentTime

        addLog("Camera scan SUCCESS: $barcode (type: $type)")
        _scannedBarcode.postValue(barcode)
        _scanState.postValue(ScanState.SUCCESS)

        // Send scan to server
        sendScanToServer(barcode, type)

        // Auto-reset to IDLE after a short delay to allow continuous scanning
        viewModelScope.launch {
            delay(1000) // 1 second delay
            if (_scanState.value == ScanState.SUCCESS) {
                _scanState.postValue(ScanState.IDLE)
                addLog("Auto-reset to IDLE for continuous scanning")
            }
        }
    }

    fun reset() {
        addLog("Resetting state to IDLE.")
        hardwareScanJob?.cancel()
        scannerManager.stopLoopScan()
        _scanState.postValue(ScanState.IDLE)
        _scannedBarcode.postValue(null)
        _errorMessage.postValue(null)
        _recoveryImagesPreview.postValue(emptyList())
        _singleRecoveryImage.postValue(null)
        _allDiagnosticImages.postValue(emptyList())
        recoveryImages.clear()
    }

    override fun onCleared() {
        super.onCleared()
        // NOTE: onCleared is commented out because this is now a singleton ViewModel
        // that persists for the app's lifetime. Removing the observer would break
        // functionality when switching between activities.
        //
        // Удаляем тот же самый observer, который был добавлен в init
        // scannerManager.scanResult.removeObserver(scanResultObserver)
        // reset()
    }

    companion object {
        @Volatile
        private var INSTANCE: ScanRecoveryViewModel? = null

        /**
         * Returns the singleton instance of ScanRecoveryViewModel
         * Thread-safe implementation using double-checked locking
         */
        fun getInstance(application: Application): ScanRecoveryViewModel {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScanRecoveryViewModel(application).also { INSTANCE = it }
            }
        }

        class Factory(private val application: Application) : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(ScanRecoveryViewModel::class.java)) {
                    return getInstance(application) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class")
            }
        }
    }
}