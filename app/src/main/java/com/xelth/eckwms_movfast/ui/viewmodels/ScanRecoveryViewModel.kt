package com.xelth.eckwms_movfast.ui.viewmodels

import android.app.Application
import android.graphics.Bitmap
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
import com.xelth.eckwms_movfast.utils.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.xelth.eckwms_movfast.ui.data.Workflow
import kotlinx.serialization.json.Json
import org.json.JSONObject

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
    private val repository: com.xelth.eckwms_movfast.data.WarehouseRepository = (application as EckwmsApp).repository
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

    private val _activeOrderId = MutableLiveData<String?>(null)
    val activeOrderId: LiveData<String?> = _activeOrderId

    // --- DEVICE PAIRING ---
    private val _pairingStatus = MutableLiveData<String>("Ready to pair device with server.")
    val pairingStatus: LiveData<String> = _pairingStatus

    private val _isPairing = MutableLiveData<Boolean>(false)
    val isPairing: LiveData<Boolean> = _isPairing
    // --- END DEBUG ---

    // --- WORKFLOW ENGINE ---
    private var workflowEngine: WorkflowEngine? = null
    private val _workflowState = MutableLiveData<WorkflowState>()
    val workflowState: LiveData<WorkflowState> = _workflowState

    private var hardwareScanJob: Job? = null
    private val recoveryImages = mutableListOf<Bitmap>()
    private val RECOVERY_IMAGE_COUNT = 3

    // Для предотвращения дубликатов
    private var lastProcessedBarcode: String? = null
    private var lastProcessedTime: Long = 0

    // Observer для scanResult - должен быть переменной для корректного удаления в onCleared()
    private val scanResultObserver = androidx.lifecycle.Observer<String> {
        addLog(">>> scanResultObserver triggered: barcode=$it")
        if (it != null) {
            val currentTime = System.currentTimeMillis()

            // Проверка на дубликаты: игнорируем если тот же штрих-код пришел менее чем через 2 секунды
            if (it == lastProcessedBarcode && (currentTime - lastProcessedTime) < 2000) {
                addLog("Ignoring duplicate scan: $it (timeDiff=${currentTime - lastProcessedTime}ms)")
                return@Observer
            }

            lastProcessedBarcode = it
            lastProcessedTime = currentTime

            val barcodeType = scannerManager.getLastBarcodeType() ?: "UNKNOWN"

            // IRON FUNCTION: Hardware scanner ALWAYS works, regardless of UI state
            addLog("Hardware scan SUCCESS: $it (type: $barcodeType, workflow: ${isWorkflowActive()}, state: ${_scanState.value})")

            // Route to appropriate handler (workflow or normal)
            val wasSpecialCommand = handleGeneralScanResult(it, barcodeType)

            // Update UI state if NOT in workflow mode (AFTER handleGeneralScanResult to avoid race)
            // Only show in UI if it wasn't a special command (like order ID)
            if (!isWorkflowActive() && !wasSpecialCommand) {
                _scannedBarcode.postValue(it)
                _scanState.postValue(ScanState.SUCCESS)

                // Auto-reset to IDLE after a short delay
                viewModelScope.launch {
                    delay(1000)
                    if (_scanState.value == ScanState.SUCCESS) {
                        _scanState.postValue(ScanState.IDLE)
                        addLog("Auto-reset to IDLE for continuous scanning")
                    }
                }
            }
        }
    }

    init {
        addLog("ViewModel Initialized.")
        scannerManager.scanResult.observeForever(scanResultObserver)

        // Load scan history from local database
        viewModelScope.launch {
            loadScanHistory()
        }
    }

    /**
     * Public method to add log messages (used both internally and externally)
     */
    fun addLog(message: String) {
        Log.d(TAG, message)
        val currentLog = _debugLog.value ?: emptyList()
        _debugLog.postValue(currentLog + message)
    }

    // Loop scan removed - hardware scanner works automatically with physical button
    // No need for programmatic loop scan
    @Deprecated("Hardware scanner works automatically")
    fun startHardwareScan() {
        addLog("Hardware scanner is always ready - use physical button to scan")
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

        processImageWithMlKit(image) { success, barcodeValue, barcodeType ->
            if (success) {
                addLog("ML Kit single image SUCCESS: $barcodeValue (type: $barcodeType)")
                _scannedBarcode.postValue(barcodeValue)
                _scanState.postValue(ScanState.SUCCESS)

                // Send scan to server with ML Kit detected type
                sendScanToServer(barcodeValue, barcodeType)
            } else {
                addLog("ML Kit single image FAILED: $barcodeValue")
                _errorMessage.postValue(barcodeValue)
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
                val result = processImageWithMlKitSuspending(image)
                if (result != null) {
                    val (barcodeValue, barcodeType) = result
                    addLog("Multi-image recovery SUCCESS: $barcodeValue (type: $barcodeType)")
                    _scannedBarcode.postValue(barcodeValue)
                    _scanState.postValue(ScanState.SUCCESS)

                    // Send scan to server with ML Kit detected type
                    sendScanToServer(barcodeValue, barcodeType)

                    return@launch
                }
            }
            addLog("Multi-image recovery FAILED.")
            _errorMessage.postValue("Could not decode barcode from any of the images.")
            _scanState.postValue(ScanState.FAILURE)
        }
    }

    /**
     * Process an image with ML Kit and return (success, barcodeValue, barcodeType)
     * Now correctly handles camera images without rotation and returns ML Kit detected type
     */
    private fun processImageWithMlKit(bitmap: Bitmap, callback: (Boolean, String, String) -> Unit) {
        addLog("Starting ML Kit analysis on ${bitmap.width}x${bitmap.height} image")
        // NOTE: Camera images are already correctly oriented, no rotation needed
        val image = InputImage.fromBitmap(bitmap, 0)

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
                    val barcodeValue = barcode.rawValue ?: ""
                    val barcodeType = getBarcodeTypeName(barcode.format)
                    addLog("Barcode details - Format: $barcodeType, Value: $barcodeValue")
                    callback(true, barcodeValue, barcodeType)
                } else {
                    addLog("No barcode detected in image")
                    callback(false, "No barcode found in image.", "UNKNOWN")
                }
            }
            .addOnFailureListener { e ->
                addLog("ML Kit analysis failed with exception: ${e.message}")
                callback(false, "ML Kit analysis failed: ${e.message}", "UNKNOWN")
            }
    }

    /**
     * Convert ML Kit barcode format to string type name
     */
    private fun getBarcodeTypeName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_CODE_93 -> "CODE_93"
            Barcode.FORMAT_CODABAR -> "CODABAR"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            Barcode.FORMAT_EAN_8 -> "EAN_8"
            Barcode.FORMAT_ITF -> "ITF"
            Barcode.FORMAT_UPC_A -> "UPC_A"
            Barcode.FORMAT_UPC_E -> "UPC_E"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_AZTEC -> "AZTEC"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            else -> "UNKNOWN"
        }
    }

    /**
     * Suspending version that returns a Pair of (barcodeValue, barcodeType) or null
     */
    private suspend fun processImageWithMlKitSuspending(bitmap: Bitmap): Pair<String, String>? {
        return kotlin.coroutines.suspendCoroutine { continuation ->
            processImageWithMlKit(bitmap) { success, barcodeValue, barcodeType ->
                continuation.resumeWith(Result.success(if (success) Pair(barcodeValue, barcodeType) else null))
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

    fun setUploadImageResolution(resolution: Int) {
        SettingsManager.saveImageResolution(resolution)
        addLog("Image resolution set to: $resolution")
    }

    fun setUploadImageQuality(quality: Int) {
        SettingsManager.saveImageQuality(quality)
        addLog("Image quality set to: $quality")
    }

    // --- WORKFLOW METHODS ---
    fun startWorkflow(workflowJson: String) {
        try {
            val workflow = Json.decodeFromString<Workflow>(workflowJson)
            workflowEngine = WorkflowEngine(workflow) { logMessage -> addLog(logMessage) }
            workflowEngine?.state?.observeForever { newState ->
                addLog("[ViewModel] Observed new workflow state. Active: ${newState.isActive}, Step: ${newState.currentStep?.stepId}, Instruction: '${newState.instruction}'")
                _workflowState.postValue(newState)
            }
            workflowEngine?.start()
        } catch (e: Exception) {
            addLog("❌ Failed to parse or start workflow: ${e.message}")
            Log.e(TAG, "Workflow Error", e)
        }
    }

    fun onBarcodeScannedForWorkflow(barcode: String) {
        addLog("[ViewModel] onBarcodeScannedForWorkflow called with barcode: $barcode")
        if (_workflowState.value?.isActive == true) {
            // Send to workflow engine
            workflowEngine?.onBarcodeScanned(barcode)

            // ALSO send to server (important!)
            val barcodeType = scannerManager.getLastBarcodeType() ?: "UNKNOWN"
            addLog("[ViewModel] Sending workflow barcode to server: $barcode ($barcodeType)")
            sendScanToServer(barcode, barcodeType)
        }
    }

    fun onImageCapturedForWorkflow(bitmap: Bitmap) {
        if (_workflowState.value?.isActive == true) {
            addLog("[ViewModel] Workflow image capture - uploading to server...")

            // Signal workflow engine that image was captured
            workflowEngine?.onImageCaptured()

            // Upload to server
            val deviceId = android.provider.Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown-android-id"

            // Create a mutable copy to avoid "recycled bitmap" errors
            val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            addLog("Workflow Upload: Created bitmap copy (${bitmapCopy.width}x${bitmapCopy.height})")

            // Resize to reduce file size
            val maxResolution = SettingsManager.getImageResolution()
            val resizedBitmap = resizeBitmap(bitmapCopy, maxResolution)
            addLog("Workflow Upload: Resized to (${resizedBitmap.width}x${resizedBitmap.height})")

            val quality = SettingsManager.getImageQuality()

            // Get current workflow step info for context
            val currentStep = _workflowState.value?.currentStep
            val uploadReason = currentStep?.upload?.reason ?: "workflow_image"

            performUpload(resizedBitmap, deviceId, "workflow", uploadReason, quality, _activeOrderId.value)
        }
    }

    fun endWorkflowLoop() {
        workflowEngine?.endLoop()
    }

    fun isWorkflowActive(): Boolean = _workflowState.value?.isActive ?: false

    /**
     * Workflow scan - hardware scanner works automatically
     */
    @Deprecated("Hardware scanner works automatically")
    fun startWorkflowScan() {
        addLog("[ViewModel] Workflow scan ready - use physical button to scan")
    }

    /**
     * Sends a scanned barcode to the repository (offline-first)
     * The repository will save locally and queue for background sync
     */
    fun sendScanToServer(barcode: String, barcodeType: String) {
        addLog("Saving scan for sync: $barcode")

        // Create a new scan history item with PENDING status
        val scanItem = ScanHistoryItem(
            barcode = barcode,
            timestamp = System.currentTimeMillis(),
            status = ScanStatus.PENDING,
            type = barcodeType
        )

        // Save to repository (offline-first)
        viewModelScope.launch {
            try {
                // Repository will save locally and add to sync queue
                val scanId = repository.saveAndSyncScan(scanItem, _activeOrderId.value)
                addLog("Scan saved locally with ID: $scanId, queued for sync")

                // Update UI with scan from database
                loadScanHistory()
            } catch (e: Exception) {
                addLog("Error saving scan: ${e.message}")
                _errorMessage.postValue("Failed to save scan: ${e.message}")
            }
        }
    }

    /**
     * Load scan history from local database
     */
    private suspend fun loadScanHistory() {
        try {
            val scans = repository.getAllScans()
            _scanHistory.postValue(scans)
            addLog("Loaded ${scans.size} scans from database")
        } catch (e: Exception) {
            addLog("Error loading scan history: ${e.message}")
        }
    }

    /**
     * Handles scanned data from camera or other sources
     * This centralizes the processing of all scan results
     * NOTE: UI state is updated by hardware scanner observer, not here!
     */
    fun handleScannedData(barcode: String, type: String) {
        addLog("Processing scan for server: $barcode (type: $type)")

        // Send scan to server
        sendScanToServer(barcode, type)
    }

    /**
     * Central point for handling any scan result from any source (hardware or camera).
     * It decides whether to route the data to the workflow engine or the standard process.
     * @return true if the barcode was handled as a special command (e.g., order ID), false otherwise
     */
    fun handleGeneralScanResult(barcode: String, type: String): Boolean {
        // Check if the scanned barcode is an Order ID to activate the session
        if (barcode.startsWith("CS-DE-") && barcode.length > 10) {
            _activeOrderId.postValue(barcode)
            addLog("ACTIVE ORDER SET: $barcode")
            // Do not process as a regular scan, just set the state
            return true // Indicate this was a special command
        }

        addLog("[ViewModel] handleGeneralScanResult: Processing '$barcode' from source '$type'")
        if (isWorkflowActive()) {
            addLog("[ViewModel] Workflow is active - routing to workflow engine")
            onBarcodeScannedForWorkflow(barcode)
        } else {
            addLog("[ViewModel] No workflow active - routing to standard scan handler")
            handleScannedData(barcode, type)
        }
        return false // Normal scan, should be displayed
    }

    /**
     * Uploads the last captured image to the server
     * @param scanMode Either "dumb" (direct upload) or "mlkit" (with ML Kit analysis first)
     */
    fun uploadLastImage(scanMode: String) {
        val imageToUpload = _singleRecoveryImage.value
        if (imageToUpload == null) {
            addLog("Upload failed: No image captured yet.")
            _errorMessage.postValue("No image available to upload.")
            return
        }

        addLog("Starting image upload in '$scanMode' mode...")
        val deviceId = android.provider.Settings.Secure.getString(getApplication<Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown-android-id"

        viewModelScope.launch {
            if (scanMode == "mlkit") {
                addLog("Analyzing image with ML Kit before upload...")
                processImageWithMlKit(imageToUpload) { success, barcodeValue, barcodeType ->
                    if (success) {
                        addLog("ML Kit found barcode: $barcodeValue. Uploading with data.")
                        val quality = SettingsManager.getImageQuality()
                        performUpload(imageToUpload, deviceId, scanMode, "$barcodeType: $barcodeValue", quality, _activeOrderId.value)
                    } else {
                        addLog("ML Kit found no barcode. Uploading image only.")
                        val quality = SettingsManager.getImageQuality()
                        performUpload(imageToUpload, deviceId, scanMode, null, quality, _activeOrderId.value)
                    }
                }
            } else {
                val quality = SettingsManager.getImageQuality()
                performUpload(imageToUpload, deviceId, scanMode, null, quality, _activeOrderId.value)
            }
        }
    }

    /**
     * Direct capture and upload - simplified path for debugging
     */
    fun captureAndUploadImage(bitmap: Bitmap) {
        addLog("Direct Upload: Starting direct image upload...")
        val deviceId = android.provider.Settings.Secure.getString(getApplication<Application>().contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown-android-id"

        // Create a mutable copy to avoid "recycled bitmap" errors
        val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
        addLog("Direct Upload: Created bitmap copy (${bitmapCopy.width}x${bitmapCopy.height})")

        // Resize to reduce file size and avoid SSL timeout
        val maxResolution = SettingsManager.getImageResolution()
        val resizedBitmap = resizeBitmap(bitmapCopy, maxResolution)
        addLog("Direct Upload: Resized to (${resizedBitmap.width}x${resizedBitmap.height})")

        val quality = SettingsManager.getImageQuality()
        performUpload(resizedBitmap, deviceId, "direct_upload", null, quality, _activeOrderId.value)
    }

    /**
     * Resize bitmap to fit within maxDimension while maintaining aspect ratio
     */
    private fun resizeBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxDimension && height <= maxDimension) {
            return bitmap
        }

        val scale = if (width > height) {
            maxDimension.toFloat() / width
        } else {
            maxDimension.toFloat() / height
        }

        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)

        // Recycle original if it's different from the resized one
        if (resized != bitmap) {
            bitmap.recycle()
        }

        return resized
    }

    /**
     * Performs the actual upload operation
     */
    private fun performUpload(bitmap: Bitmap, deviceId: String, scanMode: String, barcodeData: String?, quality: Int, orderId: String? = null) {
        viewModelScope.launch {
            try {
                val result = scanApiService.uploadImage(bitmap, deviceId, scanMode, barcodeData, quality, orderId)
                when (result) {
                    is ScanResult.Success -> {
                        addLog("Upload successful. Server response: ${result.data}")
                        _errorMessage.postValue("Image uploaded successfully.")
                    }
                    is ScanResult.Error -> {
                        addLog("Upload failed: ${result.message}")
                        _errorMessage.postValue("Upload failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                addLog("Exception during upload: ${e.message}")
                _errorMessage.postValue("Upload failed due to an exception.")
            } finally {
                // Recycle bitmap copy to free memory
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    addLog("Direct Upload: Recycled bitmap copy")
                }
            }
        }
    }

    fun endActiveOrderSession() {
        _activeOrderId.postValue(null)
        addLog("Active order session ended.")
    }

    fun reset() {
        addLog("Resetting state to IDLE.")
        _scanState.postValue(ScanState.IDLE)
        _scannedBarcode.postValue(null)
        _errorMessage.postValue(null)
        _recoveryImagesPreview.postValue(emptyList())
        _singleRecoveryImage.postValue(null)
        _allDiagnosticImages.postValue(emptyList())
        recoveryImages.clear()
    }

    /**
     * Handles the pairing QR code scanned from the server
     * QR code contains JSON with server URL and other pairing information
     */
    fun handlePairingQrCode(qrData: String) {
        addLog("Processing pairing QR code...")
        _isPairing.postValue(true)
        _pairingStatus.postValue("Processing pairing QR code...")

        viewModelScope.launch {
            try {
                // Parse QR code JSON
                val qrJson = JSONObject(qrData)
                val serverUrl = qrJson.getString("serverUrl")
                val pairingToken = qrJson.optString("pairingToken", "")

                addLog("Server URL from QR: $serverUrl")
                addLog("Pairing token: ${if (pairingToken.isNotEmpty()) "present" else "not present"}")

                // Save server URL to settings
                SettingsManager.saveServerUrl(serverUrl)
                _pairingStatus.postValue("Server URL configured. Initializing crypto...")

                // Initialize CryptoManager
                com.xelth.eckwms_movfast.utils.CryptoManager.initialize(getApplication())
                addLog("CryptoManager initialized")

                // Generate or retrieve key pair
                val keyPair = com.xelth.eckwms_movfast.utils.CryptoManager.getOrCreateKeyPair()
                val publicKeyBase64 = com.xelth.eckwms_movfast.utils.CryptoManager.getPublicKeyBase64()
                addLog("Public key (Base64): $publicKeyBase64")

                _pairingStatus.postValue("Generating signature...")

                // Create signature data: deviceId + timestamp
                val timestamp = System.currentTimeMillis()
                val deviceId = android.provider.Settings.Secure.getString(
                    getApplication<Application>().contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID
                ) ?: "unknown-android-id"

                val signatureData = "$deviceId:$timestamp"
                val signature = com.xelth.eckwms_movfast.utils.CryptoManager.sign(signatureData.toByteArray())
                val signatureBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
                addLog("Signature created (Base64): ${signatureBase64.take(32)}...")

                _pairingStatus.postValue("Registering device with server...")

                // Register device with server
                val result = scanApiService.registerDevice(
                    serverUrl = serverUrl,
                    publicKeyBase64 = publicKeyBase64,
                    signature = signatureBase64,
                    timestamp = timestamp
                )

                when (result) {
                    is ScanResult.Success -> {
                        addLog("Device pairing successful!")
                        _pairingStatus.postValue("Success! Device paired with server.")
                        _errorMessage.postValue("Device successfully paired with eckWMS server.")
                    }
                    is ScanResult.Error -> {
                        addLog("Device pairing failed: ${result.message}")
                        _pairingStatus.postValue("Error: ${result.message}")
                        _errorMessage.postValue("Pairing failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                addLog("Error during pairing: ${e.message}")
                _pairingStatus.postValue("Error: ${e.message}")
                _errorMessage.postValue("Pairing error: ${e.message}")
                Log.e(TAG, "Pairing error", e)
            } finally {
                _isPairing.postValue(false)
            }
        }
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