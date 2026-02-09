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
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import com.xelth.eckwms_movfast.utils.SettingsManager
import com.xelth.eckwms_movfast.utils.NetworkHealthMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.xelth.eckwms_movfast.ui.data.Workflow
import kotlinx.serialization.json.Json
import org.json.JSONObject

enum class NavigationCommand { NONE, TO_PAIRING, BACK, TO_MAIN_REPAIR }

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

    fun consumeScannedBarcode() { _scannedBarcode.value = null }

    /** True if the last posted barcode was decrypted from an encrypted ECK QR */
    var lastScanWasEncrypted: Boolean = false
        private set

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

    private val _pairingLog = MutableLiveData<List<String>>(listOf("📱 Ready to scan pairing QR code"))
    val pairingLog: LiveData<List<String>> = _pairingLog

    private val _isPairing = MutableLiveData<Boolean>(false)
    val isPairing: LiveData<Boolean> = _isPairing

    private val _pairingSuccess = MutableLiveData<Boolean>(false)
    val pairingSuccess: LiveData<Boolean> = _pairingSuccess

    private val _navigationCommand = MutableLiveData(NavigationCommand.NONE)
    val navigationCommand: LiveData<NavigationCommand> = _navigationCommand

    // --- NETWORK HEALTH MONITORING ---
    // Start with cached state for instant UI, will update after health check
    private val _networkHealthState = MutableLiveData<NetworkHealthState>(getInitialHealthState())
    val networkHealthState: LiveData<NetworkHealthState> = _networkHealthState

    private var healthCheckJob: Job? = null
    private val HEALTH_CHECK_INTERVAL_MS = 30000L // 30 seconds

    // --- DEVICE REGISTRATION STATUS ---
    private val _deviceRegistrationStatus = MutableLiveData<String>("unknown")
    val deviceRegistrationStatus: LiveData<String> = _deviceRegistrationStatus

    // UI MODE: 'DEBUG' or 'DYNAMIC'
    private val _uiMode = MutableLiveData<String>("DEBUG")
    val uiMode: LiveData<String> = _uiMode

    // Left-handed mode (mirroring)
    private val _isLeftHanded = MutableLiveData<Boolean>(false)
    val isLeftHanded: LiveData<Boolean> = _isLeftHanded

    // Configurable grid row count
    private val _gridRowCount = MutableLiveData<Int>(7)
    val gridRowCount: LiveData<Int> = _gridRowCount

    // Current Dynamic Layout JSON
    private val _currentLayout = MutableLiveData<String>("{ \"components\": [ { \"type\": \"text\", \"content\": \"Waiting for AI Agent...\", \"style\": \"h2\" } ] }")
    val currentLayout: LiveData<String> = _currentLayout

    // AI Interaction from server
    private val _aiInteraction = MutableLiveData<com.xelth.eckwms_movfast.ui.data.AiInteraction?>(null)
    val aiInteraction: LiveData<com.xelth.eckwms_movfast.ui.data.AiInteraction?> = _aiInteraction

    // --- AI IMAGE ANALYSIS ---
    private val _aiAnalysisResult = MutableLiveData<JSONObject?>(null)
    val aiAnalysisResult: LiveData<JSONObject?> = _aiAnalysisResult

    private val _isAnalyzing = MutableLiveData<Boolean>(false)
    val isAnalyzing: LiveData<Boolean> = _isAnalyzing

    private var lastUploadedImageId: String? = null

    fun clearAiAnalysisResult() {
        _aiAnalysisResult.postValue(null)
    }

    // --- REPAIR MODE BRIDGE ---
    // Temporary storage for photo captured during Repair Mode workflow
    private val _repairPhotoBitmap = MutableLiveData<Bitmap?>(null)
    val repairPhotoBitmap: LiveData<Bitmap?> = _repairPhotoBitmap

    fun setRepairPhotoBitmap(bitmap: Bitmap) {
        _repairPhotoBitmap.postValue(bitmap)
    }

    fun consumeRepairPhotoBitmap(): Bitmap? {
        val bmp = _repairPhotoBitmap.value
        _repairPhotoBitmap.postValue(null)
        return bmp
    }

    /** Route a camera-scanned barcode through the same path as hardware scanner */
    fun onCameraBarcode(barcode: String) {
        Log.d(TAG, "Camera barcode routed to repair: $barcode")
        _scannedBarcode.postValue(barcode)
    }

    /** Send a repair event (barcode scan linked to a device) to the server */
    fun sendRepairEvent(targetDeviceId: String, eventType: String, data: String) {
        viewModelScope.launch {
            addLog("Repair event: $eventType -> $targetDeviceId ($data)")
            val result = scanApiService.sendRepairEvent(targetDeviceId, eventType, data)
            when (result) {
                is ScanResult.Success -> addLog("✅ Repair event sent: $eventType")
                is ScanResult.Error -> addLog("❌ Repair event failed: ${result.message}")
            }
        }
    }

    /** Upload a repair photo linked to a device being repaired (robust: disk-first) */
    fun uploadRepairPhoto(targetDeviceId: String, bitmap: Bitmap) {
        viewModelScope.launch {
            addLog("Uploading repair photo for $targetDeviceId")

            val imageId = java.util.UUID.randomUUID().toString()
            val quality = SettingsManager.getImageQuality()
            val ts = System.currentTimeMillis()

            // 1. Save ORIGINAL bitmap to disk (full resolution)
            val tempFile = java.io.File(getApplication<Application>().cacheDir, "repair_$ts.webp")
            val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            withContext(Dispatchers.IO) {
                tempFile.outputStream().use { out ->
                    bitmapCopy.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
                }
            }
            val imagePath = tempFile.absolutePath
            val imageSize = tempFile.length()
            addLog("📷 Original: ${bitmapCopy.width}x${bitmapCopy.height}, ${imageSize/1024}KB")

            // 2. Generate Smart Crop AVATAR (224x224) for DB sync
            val croppedBitmap = smartCrop(bitmapCopy, 224)
            val tempAvatarFile = java.io.File(getApplication<Application>().cacheDir, "avatar_$ts.webp")
            withContext(Dispatchers.IO) {
                tempAvatarFile.outputStream().use { out ->
                    croppedBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
                }
            }
            addLog("✂️ Avatar: ${croppedBitmap.width}x${croppedBitmap.height}, ${tempAvatarFile.length()/1024}KB")

            if (!croppedBitmap.isRecycled) croppedBitmap.recycle()
            if (!bitmap.isRecycled) bitmap.recycle()

            // 3. Create DB record with PENDING status
            val historyId = repository.saveImageUpload(
                imagePath = imagePath,
                imageSize = imageSize,
                imageId = imageId,
                orderId = _activeOrderId.value
            )

            // 4. Upload BOTH original + avatar
            val deviceId = SettingsManager.getDeviceId(getApplication())
            try {
                val result = scanApiService.uploadImageFile(
                    imagePath, deviceId, "repair_photo", targetDeviceId,
                    _activeOrderId.value, imageId, tempAvatarFile.absolutePath
                )
                when (result) {
                    is ScanResult.Success -> {
                        addLog("✅ Photo + avatar uploaded")
                        repository.updateScanStatus(historyId, ScanStatus.CONFIRMED)
                        tempFile.delete()
                        tempAvatarFile.delete()
                    }
                    is ScanResult.Error -> {
                        addLog("❌ Upload failed: ${result.message}, queued")
                        repository.updateScanStatus(historyId, ScanStatus.FAILED)
                        repository.addImageUploadToSyncQueue(historyId)
                    }
                }
            } catch (e: Exception) {
                addLog("❌ Exception: ${e.message}, queued")
                repository.updateScanStatus(historyId, ScanStatus.FAILED)
                repository.addImageUploadToSyncQueue(historyId)
            }
        }
    }

    // --- MAP STATE ---
    private val _warehouseMap = MutableLiveData<com.xelth.eckwms_movfast.ui.data.WarehouseMapResponse?>(null)
    val warehouseMap: LiveData<com.xelth.eckwms_movfast.ui.data.WarehouseMapResponse?> = _warehouseMap
    
    private val _targetRackId = MutableLiveData<Long?>(null)
    val targetRackId: LiveData<Long?> = _targetRackId

    /**
     * Clear the current AI interaction (dismiss dialog/banner)
     */
    fun clearAiInteraction() {
        addLog("Clearing AI interaction")
        _aiInteraction.postValue(null)
    }

    /**
     * Handle user response to AI interaction
     * @param response The user's selected option/response
     */
    fun respondToAiInteraction(response: String) {
        val currentInteraction = _aiInteraction.value
        if (currentInteraction != null) {
            addLog("User responded to AI ${currentInteraction.type}: $response")

            // Normalize response for backend compatibility (Gemini expects "yes")
            val normalizedResponse = if (
                response.equals("yes", ignoreCase = true) ||
                response.equals("confirm", ignoreCase = true) ||
                response.equals("save", ignoreCase = true) ||
                response.equals("да", ignoreCase = true)
            ) "yes" else response.lowercase()

            // Send response back to server via hybrid transport
            viewModelScope.launch {
                try {
                    val result = com.xelth.eckwms_movfast.net.HybridMessageSender.sendAiResponse(
                        apiService = scanApiService,
                        interactionId = currentInteraction.id,
                        response = normalizedResponse,
                        barcode = currentInteraction.barcode
                    )

                    when (result) {
                        is ScanResult.Success -> {
                            addLog("✓ AI response sent successfully via hybrid transport")
                        }
                        is ScanResult.Error -> {
                            addLog("⚠️ Failed to send AI response: ${result.message}")
                            _errorMessage.postValue("Failed to send response: ${result.message}")
                        }
                    }
                } catch (e: Exception) {
                    addLog("❌ Exception sending AI response: ${e.message}")
                    _errorMessage.postValue("Error sending response: ${e.message}")
                }
            }

            // Clear interaction immediately (optimistic UI - don't wait for server)
            clearAiInteraction()
        } else {
            addLog("Warning: Attempted to respond to null AI interaction")
        }
    }

    /**
     * DEBUG ONLY: Manually trigger an AI interaction for testing UI components
     * @param type The type of interaction to trigger (question, confirmation, info, warning, error, success)
     */
    fun debugTriggerAiEvent(type: String) {
        addLog("🧪 DEBUG: Triggering AI event type: $type")

        val interaction = when (type.lowercase()) {
            "question" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "question",
                message = "Do you want to adjust the inventory count for item SKU-12345?",
                options = listOf("Yes, adjust", "No, cancel"),
                data = mapOf("item_sku" to "SKU-12345", "current_count" to 42)
            )
            "confirmation" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "confirmation",
                message = "This action will update 15 items. Are you sure you want to continue?",
                options = listOf("Confirm", "Cancel"),
                data = mapOf("item_count" to 15)
            )
            "info" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "info",
                message = "Item successfully scanned and added to order #CS-DE-2024-001",
                options = null,
                data = mapOf("order_id" to "CS-DE-2024-001")
            )
            "warning" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "warning",
                message = "Low stock alert: Only 3 units remaining for this item",
                options = listOf("View Details", "Dismiss"),
                data = mapOf("remaining_stock" to 3)
            )
            "error" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "error",
                message = "Failed to process scan: Item not found in inventory database",
                options = listOf("Retry", "Report Issue"),
                data = mapOf("error_code" to "ITEM_NOT_FOUND")
            )
            "success" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "success",
                message = "Order completed successfully! 47 items processed.",
                options = listOf("View Order", "Start New"),
                data = mapOf("items_processed" to 47)
            )
            "multi_option" -> com.xelth.eckwms_movfast.ui.data.AiInteraction(
                type = "question",
                message = "Select the location for this item:",
                options = listOf("Warehouse A", "Warehouse B", "Warehouse C", "Storage D"),
                data = null
            )
            else -> {
                addLog("⚠️ Unknown AI event type: $type. Using default info.")
                com.xelth.eckwms_movfast.ui.data.AiInteraction(
                    type = "info",
                    message = "This is a test AI interaction of type: $type",
                    options = null,
                    data = null
                )
            }
        }

        _aiInteraction.postValue(interaction)
    }

    fun toggleUiMode() {
        _uiMode.value = if (_uiMode.value == "DEBUG") "DYNAMIC" else "DEBUG"
        addLog("UI Mode switched to: ${_uiMode.value}")
    }

    fun toggleHandedness() {
        _isLeftHanded.value = !(_isLeftHanded.value ?: false)
        addLog("UI Handedness toggled: ${_isLeftHanded.value}")
    }

    fun setGridRowCount(rows: Int) {
        val clamped = rows.coerceIn(4, 12)
        _gridRowCount.value = clamped
        addLog("Grid rows set to: $clamped")
    }

    fun updateLayout(json: String) {
        _currentLayout.postValue(json)
        _uiMode.postValue("DYNAMIC") // Auto-switch to dynamic when layout arrives
    }

    private val _permissions = MutableLiveData<Set<String>>(emptySet())
    val permissions: LiveData<Set<String>> = _permissions

    fun hasPermission(permission: String): Boolean {
        return SettingsManager.hasPermission(permission)
    }

    fun refreshPermissions() {
        _permissions.postValue(SettingsManager.getPermissions())
    }

    private var statusMonitoringJob: Job? = null
    private val STATUS_CHECK_INTERVAL_MS = 30000L // 30 seconds

    /**
     * Gets the initial health state from cache for instant UI display
     * Since we don't have latency/url data in cache, we return Checking
     * The real health check will run immediately after startup and update the state
     */
    private fun getInitialHealthState(): NetworkHealthState {
        // Always start with Checking - the health check will update it immediately
        return NetworkHealthState.Checking
    }

    var isAutoPairing = false
    var isOnPairingScreen = false

    fun setPairingScreenActive(active: Boolean) {
        android.util.Log.e("AUTO_PAIR", "=== setPairingScreenActive called ===")
        android.util.Log.e("AUTO_PAIR", "active: $active, was: $isOnPairingScreen")
        android.util.Log.e("AUTO_PAIR", "isAutoPairing before: $isAutoPairing")
        isOnPairingScreen = active
        if (!active) {
            android.util.Log.e("AUTO_PAIR", "Leaving PairingScreen - resetting isAutoPairing")
            isAutoPairing = false // Reset auto mode when leaving
        }
        android.util.Log.e("AUTO_PAIR", "isAutoPairing after: $isAutoPairing")
    }

    fun resetNavigationCommand() {
        _navigationCommand.value = NavigationCommand.NONE
    }
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
            // --- DECRYPTION AT SOURCE ---
            // Decrypt immediately so UI, Router, and duplicate check all use the clean code
            var effectiveCode = it
            lastScanWasEncrypted = false
            if (com.xelth.eckwms_movfast.utils.EckSecurityManager.isEncryptedEckUrl(it)) {
                val decrypted = com.xelth.eckwms_movfast.utils.EckSecurityManager.tryDecryptBarcode(it)
                if (decrypted != null) {
                    effectiveCode = decrypted
                    lastScanWasEncrypted = true
                    android.util.Log.d("SCAN_OBSERVER", "🔓 Decrypted at source: $effectiveCode")
                }
            }

            val currentTime = System.currentTimeMillis()

            // Duplicate protection: only filter genuine optical double-reads (< 100ms).
            // Hardware scanner: one press = one barcode, no real duplicates possible.
            if (effectiveCode == lastProcessedBarcode && (currentTime - lastProcessedTime) < 100) {
                addLog("Ignoring optical double-read: $effectiveCode (${currentTime - lastProcessedTime}ms)")
                return@Observer
            }

            lastProcessedBarcode = effectiveCode
            lastProcessedTime = currentTime

            val barcodeType = scannerManager.getLastBarcodeType() ?: "UNKNOWN"

            // IRON FUNCTION: Hardware scanner ALWAYS works, regardless of UI state
            addLog("Hardware scan SUCCESS: $effectiveCode (type: $barcodeType, workflow: ${isWorkflowActive()}, state: ${_scanState.value})")

            // Route to appropriate handler (workflow or normal)
            val wasSpecialCommand = handleGeneralScanResult(effectiveCode, barcodeType)

            // Reset error message on successful scan
            if (_errorMessage.value != null) {
                _errorMessage.postValue(null)
            }

            // Check if barcode matches a saved repair device → navigate to repair
            val savedRepairSlots = SettingsManager.loadRepairSlots()
            if (savedRepairSlots.any { pair -> pair.second == effectiveCode }) {
                _scannedBarcode.postValue(effectiveCode)
                _navigationCommand.postValue(NavigationCommand.TO_MAIN_REPAIR)
                return@Observer
            }

            // Update UI state if NOT in workflow mode (AFTER handleGeneralScanResult to avoid race)
            // Only show in UI if it wasn't a special command (like order ID)
            android.util.Log.e("SCAN_ROUTE", "Barcode: $effectiveCode | workflow=${isWorkflowActive()} | special=$wasSpecialCommand")
            if (!isWorkflowActive() && !wasSpecialCommand) {
                android.util.Log.e("SCAN_ROUTE", "→ POSTING to _scannedBarcode")
                _scannedBarcode.postValue(effectiveCode)
                _scanState.postValue(ScanState.SUCCESS)

                // Auto-reset to IDLE after a short delay
                viewModelScope.launch {
                    delay(1000)
                    if (_scanState.value == ScanState.SUCCESS) {
                        _scanState.postValue(ScanState.IDLE)
                        addLog("Auto-reset to IDLE for continuous scanning")
                    }
                }
            } else {
                android.util.Log.e("SCAN_ROUTE", "→ BLOCKED! workflow=${isWorkflowActive()} special=$wasSpecialCommand")
            }
        }
    }

    private var isInitialized = false

    init {
        addLog("ViewModel Initialized.")
        scannerManager.scanResult.observeForever(scanResultObserver)

        // DO NOT load data here - let the UI trigger it with onViewModelReady()
        // This allows the UI to render first, preventing white screen
    }

    /**
     * Called by the UI after first composition to trigger data loading
     * This ensures UI renders before any heavy operations
     */
    fun onViewModelReady() {
        if (isInitialized) return
        isInitialized = true

        addLog("UI ready - starting background initialization...")

        // Load device registration status
        val deviceStatus = SettingsManager.getDeviceStatus()
        _deviceRegistrationStatus.postValue(deviceStatus)
        addLog("Device registration status: $deviceStatus")
        android.util.Log.w("DeviceStatus", "========================================")
        android.util.Log.w("DeviceStatus", "DEVICE REGISTRATION STATUS LOADED:")
        android.util.Log.w("DeviceStatus", "  Status from SettingsManager: '$deviceStatus'")
        android.util.Log.w("DeviceStatus", "  Expected values: 'active', 'pending', 'blocked'")
        android.util.Log.w("DeviceStatus", "  Current value matches 'active': ${deviceStatus == "active"}")
        android.util.Log.w("DeviceStatus", "========================================")

        // Load scan history from local database with auto-update via Flow
        // This ensures UI refreshes automatically when SyncWorker updates status in background
        viewModelScope.launch {
            repository.getAllScansFlow().collect { scans ->
                _scanHistory.postValue(scans)
                addLog("Scan history updated: ${scans.size} scans")
            }
        }

        // Initialize network health monitoring
        viewModelScope.launch {
            initializeNetworkHealthMonitoring()
        }

        // --- REAL-TIME LISTENER ---
        // Hook up WebSocket push notifications for instant status updates
        com.xelth.eckwms_movfast.net.HybridMessageSender.setLayoutListener { json ->
            addLog("⚡ Push Notification: Received new UI Layout")
            updateLayout(json)
        }

        com.xelth.eckwms_movfast.net.HybridMessageSender.setStatusListener { newStatus ->
            // Update LiveData on Main Thread
            _deviceRegistrationStatus.postValue(newStatus)
            SettingsManager.saveDeviceStatus(newStatus)
            addLog("⚡ Push Notification: Status changed to $newStatus")
            // Also refresh permissions in case they changed
            refreshPermissions()
        }

        com.xelth.eckwms_movfast.net.HybridMessageSender.setAiInteractionListener { aiInteraction ->
            addLog("⚡ AI Interaction: ${aiInteraction.type} - ${aiInteraction.message}")
            _aiInteraction.postValue(aiInteraction)
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

    // --- NETWORK HEALTH MONITORING METHODS ---

    /**
     * Initializes network health monitoring
     * Loads saved server URLs and starts periodic health checks
     * Uses smart recovery to restore connection from history if needed
     */
    private fun initializeNetworkHealthMonitoring() {
        addLog("Initializing network health monitoring...")

        // Load saved server URLs (already done by SettingsManager)
        val localUrl = SettingsManager.getServerUrl()
        val globalUrl = SettingsManager.getGlobalServerUrl()
        addLog("Loaded server URLs - Local: $localUrl, Global: $globalUrl")

        // Check if we have previously saved health state
        val lastHealthState = SettingsManager.getLastHealthState()
        if (lastHealthState != null) {
            addLog("Last known health state: $lastHealthState")
        }

        // Check connection history
        val history = SettingsManager.getConnectionHistory()
        if (history.isNotEmpty()) {
            addLog("Connection history available: ${history.size} URLs")
        }

        // Perform initial smart recovery immediately
        viewModelScope.launch {
            performSmartRecovery()
        }

        // Start periodic health check polling
        startHealthCheckPolling()
    }

    /**
     * Performs a network health check on both local and global servers
     */
    private suspend fun performHealthCheck() {
        addLog("Performing network health check...")
        _networkHealthState.postValue(NetworkHealthState.Checking)

        val localUrl = SettingsManager.getServerUrl()
        val globalUrl = SettingsManager.getGlobalServerUrl()

        // Perform health check using NetworkHealthMonitor
        val healthState = NetworkHealthMonitor.checkNetworkHealth(localUrl, globalUrl)

        // Update state
        _networkHealthState.postValue(healthState)
        addLog("Network health: ${healthState.displayName} - ${healthState.description}")
    }

    /**
     * Starts the periodic health check polling job
     */
    private fun startHealthCheckPolling() {
        // Cancel any existing job
        healthCheckJob?.cancel()

        addLog("Starting health check polling (every ${HEALTH_CHECK_INTERVAL_MS / 1000}s)")

        healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                performHealthCheck()
            }
        }
    }

    /**
     * Stops the periodic health check polling
     */
    fun stopHealthCheckPolling() {
        addLog("Stopping health check polling")
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    /**
     * Manually triggers a health check (can be called from UI)
     */
    fun triggerManualHealthCheck() {
        addLog("Manual health check triggered")
        viewModelScope.launch {
            performHealthCheck()
        }
    }

    // --- DEVICE STATUS MONITORING (SMART POLLING) ---

    /**
     * Starts smart status monitoring - polls device status every 30 seconds
     * Only runs when app is in foreground (called from onResume)
     */
    fun startStatusMonitoring() {
        // Cancel any existing job first
        statusMonitoringJob?.cancel()

        addLog("Starting device status monitoring (every ${STATUS_CHECK_INTERVAL_MS / 1000}s)")

        statusMonitoringJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(STATUS_CHECK_INTERVAL_MS)
                checkDeviceStatus()
            }
        }
    }

    /**
     * Stops device status monitoring
     * Called from onPause to save battery and network
     */
    fun stopStatusMonitoring() {
        addLog("Stopping device status monitoring")
        statusMonitoringJob?.cancel()
        statusMonitoringJob = null
    }

    /**
     * Checks device status with the server and updates LiveData
     */
    private suspend fun checkDeviceStatus() {
        val deviceId = android.provider.Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-android-id"

        val result = scanApiService.checkDeviceStatus(deviceId)

        when (result) {
            is ScanResult.Success -> {
                try {
                    val jsonResponse = JSONObject(result.data)
                    val status = jsonResponse.optString("status", "unknown")

                    // SELF-HEALING: If server says we are unregistered, try to register again immediately
                    if (status == "unregistered") {
                        addLog("⚠️ Server reports device unregistered. Attempting silent re-registration...")
                        performSilentReRegistration(deviceId)
                        return
                    }

                    // Cache warehouse info for smart client detection
                    val warehouse = jsonResponse.optJSONObject("warehouse")
                    if (warehouse != null) {
                        val whName = warehouse.optString("name", "")
                        val whStreet = warehouse.optString("street", "")
                        val whZip = warehouse.optString("zip", "")
                        val whCity = warehouse.optString("city", "")
                        val whAddress = listOf(whStreet, "$whZip $whCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")
                        if (whName.isNotEmpty()) {
                            com.xelth.eckwms_movfast.utils.SettingsManager.saveWarehouseInfo(whName, whAddress)
                        }
                    }

                    // Only update if status changed
                    if (_deviceRegistrationStatus.value != status) {
                        _deviceRegistrationStatus.postValue(status)
                        SettingsManager.saveDeviceStatus(status)
                        addLog("Device status updated: $status")
                        android.util.Log.w("DeviceStatus", "========================================")
                        android.util.Log.w("DeviceStatus", "DEVICE STATUS UPDATED FROM SERVER:")
                        android.util.Log.w("DeviceStatus", "  Old: '${_deviceRegistrationStatus.value}'")
                        android.util.Log.w("DeviceStatus", "  New: '$status'")
                        android.util.Log.w("DeviceStatus", "  Matches 'active': ${status == "active"}")
                        android.util.Log.w("DeviceStatus", "========================================")
                    }
                } catch (e: Exception) {
                    addLog("Warning: Could not parse status from server response")
                }
            }
            is ScanResult.Error -> {
                if (result.message.contains("blocked", ignoreCase = true)) {
                    // Server returned 403 Forbidden - device is blocked
                    _deviceRegistrationStatus.postValue("blocked")
                    SettingsManager.saveDeviceStatus("blocked")
                    addLog("⚠️ Device status: BLOCKED by server")
                }
            }
        }
    }

    /**
     * Automatically re-registers the device using stored credentials
     */
    private suspend fun performSilentReRegistration(deviceId: String) {
        try {
            // Use fully qualified names to avoid import issues if imports are missing
            val crypto = com.xelth.eckwms_movfast.utils.CryptoManager

            // Ensure keys exist
            val keyPair = crypto.getOrCreateKeyPair()
            val publicKeyBase64 = crypto.getPublicKeyBase64()
            val timestamp = System.currentTimeMillis()

            // Create signature for auth
            val signatureData = "{\"deviceId\":\"$deviceId\",\"devicePublicKey\":\"$publicKeyBase64\"}"
            val signature = android.util.Base64.encodeToString(
                crypto.sign(signatureData.toByteArray()),
                android.util.Base64.NO_WRAP
            )

            addLog("Sending silent registration request...")

            val result = scanApiService.registerDevice(
                publicKeyBase64 = publicKeyBase64,
                signature = signature,
                timestamp = timestamp
            )

            when (result) {
                is ScanResult.Success -> {
                    addLog("✅ Silent re-registration successful!")
                    // Extract and save JWT token for authenticated API calls
                    try {
                        val jsonResponse = JSONObject(result.data)
                        val token = jsonResponse.optString("token", "")
                        if (token.isNotEmpty()) {
                            SettingsManager.saveAuthToken(token)
                            addLog("🔑 Security token refreshed")
                        }
                    } catch (e: Exception) {
                        addLog("Warning: Could not parse token from re-registration response")
                    }
                    // Force immediate status re-check to update UI
                    checkDeviceStatus()
                }
                is ScanResult.Error -> {
                    addLog("❌ Silent re-registration failed: ${result.message}")
                }
            }
        } catch (e: Exception) {
            addLog("❌ Error during silent re-registration: ${e.message}")
        }
    }

    /**
     * Performs smart recovery: tries current config, then falls back to history
     * This is called on app startup to restore connection
     */
    private suspend fun performSmartRecovery() {
        addLog("Starting smart connection recovery...")

        val healthState = NetworkHealthMonitor.performSmartRecovery { state ->
            // Update UI with intermediate states (Checking, Restoring)
            _networkHealthState.postValue(state)
            addLog("Recovery state: ${state.displayName}")
        }

        // Update final state
        _networkHealthState.postValue(healthState)
        addLog("Smart recovery complete: ${healthState.displayName} - ${healthState.description}")
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
            // Use lenient parser to avoid crashes on extra fields
            val parser = Json { ignoreUnknownKeys = true }
            val workflow = parser.decodeFromString<Workflow>(workflowJson)
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

            val deviceId = android.provider.Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown-android-id"

            viewModelScope.launch {
                val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)

                // Smart Crop to 224x224 (gradient-based edge detection)
                val croppedBitmap = smartCrop(bitmapCopy, 224)
                addLog("Workflow Upload: Smart Cropped to (${croppedBitmap.width}x${croppedBitmap.height})")

                val quality = SettingsManager.getImageQuality()
                val currentStep = _workflowState.value?.currentStep
                val uploadReason = currentStep?.upload?.reason ?: "workflow_image"

                performUpload(croppedBitmap, deviceId, "workflow", uploadReason, quality, _activeOrderId.value)
            }
        }
    }

    fun endWorkflowLoop() {
        workflowEngine?.endLoop()
    }

    fun isWorkflowActive(): Boolean = _workflowState.value?.isActive ?: false

    /**
     * Fetch warehouse map and optionally highlight a target location
     */
    fun fetchAndShowMap(warehouseId: String, targetLocationBarcode: String? = null) {
        viewModelScope.launch {
            _warehouseMap.postValue(null) // Loading state
            
            val result = scanApiService.getWarehouseMap(warehouseId)
            
            if (result is ScanResult.Success) {
                try {
                    // Use kotlinx.serialization to parse
                    val mapData = Json { ignoreUnknownKeys = true }.decodeFromString<com.xelth.eckwms_movfast.ui.data.WarehouseMapResponse>(result.data)
                    _warehouseMap.postValue(mapData)
                    
                    if (targetLocationBarcode != null) {
                        // Find rack ID by location barcode
                        val target = mapData.racks.find { it.locationBarcode == targetLocationBarcode }
                        _targetRackId.postValue(target?.id)
                        if (target == null) {
                            addLog("Warning: Target location $targetLocationBarcode not found in map")
                        } else {
                            addLog("Target set to rack: ${target.name}")
                        }
                    } else {
                        _targetRackId.postValue(null)
                    }
                    
                } catch (e: Exception) {
                    addLog("Error parsing map data: ${e.message}")
                    _errorMessage.postValue("Map data error")
                }
            } else {
                addLog("Failed to fetch map: ${(result as ScanResult.Error).message}")
                _errorMessage.postValue("Failed to fetch map")
            }
        }
    }

    /**
     * Workflow scan - hardware scanner works automatically
     */
    @Deprecated("Hardware scanner works automatically")
    fun startWorkflowScan() {
        addLog("[ViewModel] Workflow scan ready - use physical button to scan")
    }

    /**
     * Sends scan to server using existing DB record (no duplicate entries).
     * @param existingScanId If provided, updates this row. If null, creates a new one (legacy path).
     */
    fun sendScanToServer(barcode: String, barcodeType: String, existingScanId: Long? = null) {
        // Check device registration status before allowing scans
        val currentStatus = SettingsManager.getDeviceStatus()
        if (currentStatus == "pending") {
            addLog("Scan blocked: Device is pending approval")
            _errorMessage.postValue("Device is pending admin approval. Cannot scan until activated.")
            return
        }

        viewModelScope.launch {
            // Use existing ID or create one (backward compat for workflow calls without ID)
            val scanId = existingScanId ?: repository.logRawScan(barcode, barcodeType, _activeOrderId.value)

            addLog("Sending scan #$scanId: $barcode")

            try {
                // Deliver via HybridMessageSender — it updates DB status in-place
                val hybridResult = com.xelth.eckwms_movfast.net.HybridMessageSender.sendScan(
                    scanApiService,
                    barcode,
                    barcodeType,
                    scanId,
                    repository
                )

                // Handle UI feedback only (DB status already updated by HybridMessageSender)
                when (hybridResult) {
                    is ScanResult.Success -> {
                        addLog("Scan #$scanId delivered")
                        hybridResult.aiInteraction?.let { aiInteraction ->
                            addLog("AI Interaction: ${aiInteraction.type} - ${aiInteraction.message}")
                            _aiInteraction.postValue(aiInteraction)
                        }
                    }
                    is ScanResult.Error -> {
                        addLog("Scan #$scanId queued for background sync")
                    }
                }
            } catch (e: Exception) {
                addLog("Error sending scan #$scanId: ${e.message}")
                // Mark as PENDING for background retry
                try {
                    repository.updateScanStatus(scanId, ScanStatus.PENDING)
                    repository.addScanToSyncQueue(scanId)
                } catch (e2: Exception) {
                    addLog("Error queuing scan for retry: ${e2.message}")
                }
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
    /**
     * Central scan routing with audit trail (Концептуальная архитектура)
     *
     * This method implements a three-tier routing system:
     * 1. System codes (ECK*) - processed locally
     * 2. Active workflow - routed to workflow engine
     * 3. Standard scans - sent to server for lookup
     *
     * All scans are logged to audit trail first (Журнал событий)
     *
     * @return true if handled as special command (ECK/Order ID), false otherwise
     */
    fun handleGeneralScanResult(barcode: String, type: String): Boolean {
        addLog("[Router] handleGeneralScanResult: '$barcode' type='$type'")

        // --- DECRYPTION LAYER ---
        // Strip encryption at the edge: decrypt ECK Smart QR → use plaintext everywhere
        var effectiveCode = barcode
        val isEncryptedEck = com.xelth.eckwms_movfast.utils.EckSecurityManager.isEncryptedEckUrl(barcode)
        if (isEncryptedEck) {
            val decrypted = com.xelth.eckwms_movfast.utils.EckSecurityManager.tryDecryptBarcode(barcode)
            if (decrypted != null) {
                android.util.Log.d("SCAN_ROUTER", "Decrypted: $decrypted")
                addLog("[Decrypt] $decrypted")
                effectiveCode = decrypted
            } else {
                addLog("[Decrypt] Failed — sending encrypted code to server")
            }
        }

        // 1. ALWAYS log to audit trail first (using decrypted code)
        viewModelScope.launch {
            val scanId = repository.logRawScan(effectiveCode, type, _activeOrderId.value)
            addLog("[Audit] Logged scan #$scanId ($effectiveCode)")

            // 2. Routing Logic (uses effectiveCode for all decisions)

            val isLinkBarcode = effectiveCode.startsWith("eck1.com", ignoreCase = true) ||
                                effectiveCode.startsWith("eck2.com", ignoreCase = true) ||
                                effectiveCode.startsWith("eck3.com", ignoreCase = true) ||
                                effectiveCode.startsWith("http://eck", ignoreCase = true) ||
                                effectiveCode.startsWith("https://eck", ignoreCase = true)

            when {
                // A. System Codes (Executed Locally) — but NOT Link Barcodes
                effectiveCode.startsWith("ECK") && !isLinkBarcode -> {
                    android.util.Log.e("AUTO_PAIR", "=== ECK PAIRING CODE DETECTED ===")
                    addLog("[Router] → ROUTE A: System code (ECK Pairing)")

                    if (!isOnPairingScreen) {
                        addLog("Auto-Pairing triggered!")
                        isAutoPairing = true
                        _navigationCommand.postValue(NavigationCommand.TO_PAIRING)
                    } else {
                        addLog("Already on Pairing Screen - manual scan")
                        isAutoPairing = false
                    }

                    handlePairingQrCode(effectiveCode)
                    repository.updateScanStatusString(scanId, "PROCESSED_LOCALLY")
                    addLog("[Audit] Scan #$scanId → PROCESSED_LOCALLY")
                }

                // Order ID session activation
                effectiveCode.startsWith("CS-DE-") && effectiveCode.length > 10 -> {
                    addLog("[Router] → ROUTE A: Order ID activation")
                    _activeOrderId.postValue(effectiveCode)
                    addLog("ACTIVE ORDER SET: $effectiveCode")
                    repository.updateScanStatusString(scanId, "SESSION_ACTIVATED")
                }

                // B. Active Workflow
                isWorkflowActive() -> {
                    addLog("[Router] → ROUTE B: Active workflow")

                    val currentStep = _workflowState.value?.currentStep
                    if (currentStep != null) {
                        addLog("[Workflow] Step: ${currentStep.stepId} ('${currentStep.action}')")
                        workflowEngine?.onBarcodeScanned(effectiveCode)

                        // Send to server with existing scanId (no duplicate entry)
                        val barcodeType = scannerManager.getLastBarcodeType() ?: type
                        sendScanToServer(effectiveCode, barcodeType, scanId)

                        repository.updateScanStatusString(scanId, "USED_IN_WORKFLOW")
                    } else {
                        addLog("[Workflow] ERROR: No active step!")
                        repository.updateScanStatusString(scanId, "REJECTED_BY_WORKFLOW")
                        _errorMessage.postValue("Workflow error: No active step")
                    }
                }

                // C. Standard Scan (single entry: scanId transitions RAW → CONFIRMED/PENDING)
                else -> {
                    addLog("[Router] → ROUTE C: Standard scan")
                    sendScanToServer(effectiveCode, type, scanId)
                }
            }
        }

        // Return value: was this a special command? (for UI)
        // Use original barcode for return check (pairing codes are never encrypted)
        val isLinkBarcodeCheck = barcode.startsWith("eck1.com", ignoreCase = true) ||
                                 barcode.startsWith("eck2.com", ignoreCase = true) ||
                                 barcode.startsWith("eck3.com", ignoreCase = true) ||
                                 barcode.startsWith("http://eck", ignoreCase = true) ||
                                 barcode.startsWith("https://eck", ignoreCase = true)
        return (barcode.startsWith("ECK", ignoreCase = true) && !isLinkBarcodeCheck) ||
               (barcode.startsWith("CS-DE-") && barcode.length > 10)
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

        viewModelScope.launch {
            // Create a mutable copy to avoid "recycled bitmap" errors
            val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true)
            addLog("Direct Upload: Created bitmap copy (${bitmapCopy.width}x${bitmapCopy.height})")

            // Smart Crop to 224x224 (gradient-based edge detection)
            val croppedBitmap = smartCrop(bitmapCopy, 224)
            addLog("Direct Upload: Smart Cropped to (${croppedBitmap.width}x${croppedBitmap.height})")

            val quality = SettingsManager.getImageQuality()
            performUpload(croppedBitmap, deviceId, "direct_upload", null, quality, _activeOrderId.value)
        }
    }

    /**
     * Smart Crop: Scales image so minimum side = targetSize, then slides a targetSize window
     * along the longer dimension to find the area with the highest gradient energy (most edges).
     * Used for AI thumbnails and avatar generation — keeps the most visually interesting region.
     * Runs on Dispatchers.Default to avoid blocking UI.
     */
    private suspend fun smartCrop(bitmap: Bitmap, targetSize: Int): Bitmap = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height

        if (width == targetSize && height == targetSize) {
            return@withContext bitmap
        }

        // 1. Scale so min dimension = targetSize
        val scale = if (width < height) {
            targetSize.toFloat() / width
        } else {
            targetSize.toFloat() / height
        }

        val scaledWidth = (width * scale).toInt().coerceAtLeast(targetSize)
        val scaledHeight = (height * scale).toInt().coerceAtLeast(targetSize)

        val scaledBitmap = if (scaledWidth != width || scaledHeight != height) {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        } else {
            bitmap
        }

        if (scaledWidth == targetSize && scaledHeight == targetSize) {
            if (scaledBitmap != bitmap) bitmap.recycle()
            return@withContext scaledBitmap
        }

        // 2. Compute per-column or per-row gradient energy
        val pixels = IntArray(scaledWidth * scaledHeight)
        scaledBitmap.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)

        var bestX = 0
        var bestY = 0

        if (scaledWidth > targetSize) {
            // Landscape: slide window along X axis
            val colEnergies = IntArray(scaledWidth)
            for (x in 1 until scaledWidth) {
                var energy = 0
                for (y in 0 until scaledHeight) {
                    val idx = y * scaledWidth + x
                    val p1 = pixels[idx]
                    val p2 = pixels[idx - 1]
                    energy += Math.abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)) +
                              Math.abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)) +
                              Math.abs((p1 and 0xFF) - (p2 and 0xFF))
                }
                colEnergies[x] = energy
            }

            // Sliding window sum
            var currentEnergy = 0L
            for (i in 0 until targetSize) currentEnergy += colEnergies[i]
            var maxEnergy = currentEnergy

            for (x in 1..scaledWidth - targetSize) {
                currentEnergy = currentEnergy - colEnergies[x - 1] + colEnergies[x + targetSize - 1]
                if (currentEnergy > maxEnergy) {
                    maxEnergy = currentEnergy
                    bestX = x
                }
            }
        } else if (scaledHeight > targetSize) {
            // Portrait: slide window along Y axis
            val rowEnergies = IntArray(scaledHeight)
            for (y in 1 until scaledHeight) {
                var energy = 0
                val rowOffset = y * scaledWidth
                val prevRowOffset = (y - 1) * scaledWidth
                for (x in 0 until scaledWidth) {
                    val p1 = pixels[rowOffset + x]
                    val p2 = pixels[prevRowOffset + x]
                    energy += Math.abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)) +
                              Math.abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)) +
                              Math.abs((p1 and 0xFF) - (p2 and 0xFF))
                }
                rowEnergies[y] = energy
            }

            var currentEnergy = 0L
            for (i in 0 until targetSize) currentEnergy += rowEnergies[i]
            var maxEnergy = currentEnergy

            for (y in 1..scaledHeight - targetSize) {
                currentEnergy = currentEnergy - rowEnergies[y - 1] + rowEnergies[y + targetSize - 1]
                if (currentEnergy > maxEnergy) {
                    maxEnergy = currentEnergy
                    bestY = y
                }
            }
        }

        // 3. Crop the best window
        val cropped = Bitmap.createBitmap(scaledBitmap, bestX, bestY, targetSize, targetSize)

        if (scaledBitmap != bitmap && scaledBitmap != cropped) scaledBitmap.recycle()
        if (bitmap != cropped && !bitmap.isRecycled) bitmap.recycle()

        return@withContext cropped
    }

    /**
     * Performs the actual upload operation
     */
    private fun performUpload(bitmap: Bitmap, deviceId: String, scanMode: String, barcodeData: String?, quality: Int, orderId: String? = null) {
        viewModelScope.launch {
            // 0. Generate imageId ONCE for deduplication
            val imageId = java.util.UUID.randomUUID().toString()
            addLog("Generated imageId: $imageId")

            // 1. Save bitmap to temp file for reference
            val tempFile = java.io.File(getApplication<Application>().cacheDir, "upload_${System.currentTimeMillis()}.webp")
            tempFile.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            }
            val imagePath = tempFile.absolutePath
            val imageSize = tempFile.length()

            // 2. Create history item BEFORE upload (with PENDING status)
            val historyId = repository.saveImageUpload(
                imagePath = imagePath,
                imageSize = imageSize,
                imageId = imageId,
                orderId = _activeOrderId.value
            )
            loadScanHistory()  // Refresh UI

            // Recycle bitmap immediately — file is already on disk
            if (!bitmap.isRecycled) {
                bitmap.recycle()
                addLog("Direct Upload: Recycled bitmap (file on disk)")
            }

            try {
                // 3. Stream file directly to server (no double-compression)
                val result = scanApiService.uploadImageFile(imagePath, deviceId, scanMode, barcodeData, orderId, imageId)
                when (result) {
                    is ScanResult.Success -> {
                        addLog("Upload successful. Server response: ${result.data}")
                        _errorMessage.postValue("Image uploaded successfully.")

                        // Capture server file ID for AI analysis
                        try {
                            val json = JSONObject(result.data)
                            if (json.has("id")) {
                                lastUploadedImageId = json.getString("id")
                                addLog("Captured Server File ID: $lastUploadedImageId")
                            }
                        } catch (e: Exception) {
                            addLog("Warning: Could not parse File ID from response")
                        }

                        // 4. Update history status to CONFIRMED
                        repository.updateScanStatus(historyId, ScanStatus.CONFIRMED)

                        // 5. Cleanup: delete local file after confirmed upload
                        if (tempFile.delete()) {
                            addLog("Cleanup: deleted ${tempFile.name}")
                        }
                        loadScanHistory()
                    }
                    is ScanResult.Error -> {
                        addLog("Upload failed: ${result.message}")
                        _errorMessage.postValue("Upload failed: ${result.message}")

                        // 5. Keep file on disk, add to sync queue for retry
                        repository.updateScanStatus(historyId, ScanStatus.FAILED)
                        repository.addImageUploadToSyncQueue(historyId)
                        loadScanHistory()
                    }
                }
            } catch (e: Exception) {
                addLog("Exception during upload: ${e.message}")
                _errorMessage.postValue("Upload failed due to an exception.")

                repository.updateScanStatus(historyId, ScanStatus.FAILED)
                repository.addImageUploadToSyncQueue(historyId)
                loadScanHistory()
            }
        }
    }

    fun endActiveOrderSession() {
        _activeOrderId.postValue(null)
        addLog("Active order session ended.")
    }

    fun analyzeLastImage() {
        if (lastUploadedImageId == null) {
            addLog("No uploaded image found. Please upload an image first.")
            _errorMessage.postValue("Please upload an image first")
            return
        }

        viewModelScope.launch {
            _isAnalyzing.postValue(true)
            addLog("Starting AI Analysis for $lastUploadedImageId...")

            val result = scanApiService.analyzeImage(lastUploadedImageId!!)

            when (result) {
                is ScanResult.Success -> {
                    try {
                        val json = JSONObject(result.data)
                        if (json.has("analysis")) {
                            _aiAnalysisResult.postValue(json.getJSONObject("analysis"))
                            addLog("AI Analysis Complete")
                        } else {
                            addLog("Unexpected analysis format")
                        }
                    } catch (e: Exception) {
                        addLog("Error parsing analysis: ${e.message}")
                    }
                }
                is ScanResult.Error -> {
                    addLog("Analysis Failed: ${result.message}")
                    _errorMessage.postValue(result.message)
                }
            }
            _isAnalyzing.postValue(false)
        }
    }

    /**
     * Add a message to the pairing log console
     */
    private suspend fun addPairingLog(message: String) {
        val currentLog = _pairingLog.value ?: emptyList()
        _pairingLog.postValue(currentLog + message)
        addLog(message) // Also add to debug log
        kotlinx.coroutines.yield() // Let UI update
    }

    /**
     * Clear the pairing log
     */
    fun clearPairingLog() {
        _pairingSuccess.postValue(false)
        _pairingLog.postValue(listOf("📱 Ready to scan pairing QR code"))
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
     * Supports:
     * - ECK-P1-ALPHA format: ECK$1$UUID$HEX_KEY (new string-based format)
     * - JSON format with instance_id (active discovery)
     * - JSON format with local_server_urls (direct testing)
     */
    fun handlePairingQrCode(qrData: String) {
        _isPairing.postValue(true)
        _pairingStatus.postValue("🔍 Analyzing QR code...")

        viewModelScope.launch {
            try {
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                addPairingLog("🔍 PAIRING STARTED")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")

                // Check if this is the ECK-P1-ALPHA format (v1 or v2)
                if (qrData.startsWith("ECK$")) {
                    addPairingLog("")
                    addPairingLog("📋 STAGE 1: Analyzing QR code")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")

                    // Basic version check
                    val parts = qrData.split("$")
                    val version = if (parts.size > 1) parts[1] else "?"

                    addPairingLog("✅ Protocol detected: ECK v$version")
                    handlePairingWithEckProtocol(qrData)
                    return@launch
                }

                // STAGE 1: Parse QR code and detect format (JSON)
                addPairingLog("")
                addPairingLog("📋 STAGE 1: Analyzing QR code")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                val qrJson = JSONObject(qrData)

                // Check if this is the new format (with instance_id) or old format (with local_server_urls)
                val hasInstanceId = qrJson.has("instance_id")
                val hasLocalServerUrls = qrJson.has("local_server_urls")

                addPairingLog("Format detection:")
                addPairingLog("  instance_id: ${if (hasInstanceId) "✓ found" else "✗ not found"}")
                addPairingLog("  local_server_urls: ${if (hasLocalServerUrls) "✓ found" else "✗ not found"}")

                if (hasInstanceId && !hasLocalServerUrls) {
                    // NEW FORMAT: Use active discovery
                    addPairingLog("")
                    addPairingLog("✅ Format: Active Discovery")
                    addPairingLog("Will contact global server...")
                    _pairingStatus.postValue("📡 Active Discovery")
                    handlePairingWithActiveDiscovery(qrJson)
                } else if (hasLocalServerUrls) {
                    // OLD FORMAT: Direct connection testing (prioritize this if both exist)
                    addPairingLog("")
                    addPairingLog("✅ Format: Direct Connection")
                    addPairingLog("Will test local servers...")
                    _pairingStatus.postValue("🔌 Direct Connection")
                    handlePairingWithDirectTesting(qrJson)
                } else {
                    throw Exception("Invalid QR code format")
                }
            } catch (e: Exception) {
                addPairingLog("")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                addPairingLog("❌ ERROR")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                addPairingLog("")
                addPairingLog("${e.message}")
                _pairingStatus.postValue("❌ Error")
                _errorMessage.postValue("Pairing error: ${e.message}")
                Log.e(TAG, "Pairing error", e)
            } finally {
                _isPairing.postValue(false)
            }
        }
    }

    /**
     * Handle pairing with ECK-P1-ALPHA protocol (v1 and v2)
     *
     * Format v2.0: ECK$2$UUID_COMPACT$HEX_KEY$URL1,URL2,URL3...
     * Format v1.0: ECK$1$UUID_COMPACT$HEX_KEY$URL
     */
    private suspend fun handlePairingWithEckProtocol(qrData: String) {
        addPairingLog("")
        addPairingLog("📋 STAGE 2: Parsing ECK Protocol")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")

        val parts = qrData.split("$")

        if (parts.size < 5) {
            throw Exception("Invalid ECK protocol format. Too few parts.")
        }

        val version = parts[1]
        if (version != "2") {
            throw Exception("Unsupported protocol version: $version. This app only supports ECK v2.")
        }

        val instanceIdCompact = parts[2]
        val serverPublicKeyHex = parts[3]
        val urlField = parts[4]
        val inviteToken = if (parts.size == 6) parts[5] else null

        if (instanceIdCompact.isBlank()) {
            throw Exception("Instance ID is empty")
        }

        if (!instanceIdCompact.matches(Regex("^[0-9A-F]{32}$"))) {
            throw Exception("Invalid UUID format. Expected 32 uppercase hex chars, got: $instanceIdCompact")
        }

        val instanceId = "${instanceIdCompact.substring(0, 8)}-${instanceIdCompact.substring(8, 12)}-${instanceIdCompact.substring(12, 16)}-${instanceIdCompact.substring(16, 20)}-${instanceIdCompact.substring(20, 32)}".lowercase()

        if (serverPublicKeyHex.isBlank()) {
            throw Exception("Server public key is empty")
        }

        if (!serverPublicKeyHex.matches(Regex("^[0-9A-F]+$"))) {
            throw Exception("Invalid server public key format. Expected uppercase HEX, got: ${serverPublicKeyHex.take(20)}...")
        }

        addPairingLog("✅ Protocol: ECK v2")
        addPairingLog("✅ UUID: $instanceId")
        addPairingLog("✅ Server PubKey: ${serverPublicKeyHex.take(16)}...${serverPublicKeyHex.takeLast(8)}")

        // Save Home Instance ID for Smart Routing
        SettingsManager.saveHomeInstanceId(instanceId)
        addPairingLog("✅ Home Instance ID saved: $instanceId")

        SettingsManager.saveServerPublicKey(serverPublicKeyHex)
        addPairingLog("✅ Server public key saved")

        val rawCandidates = urlField.split(",").filter { it.isNotBlank() }
        val candidates = rawCandidates.map { rawUrl ->
            var url = rawUrl
            if (url.startsWith("HTTPS://", ignoreCase = true)) {
                url = "https://" + url.substring(8)
            } else if (url.startsWith("HTTP://", ignoreCase = true)) {
                url = "http://" + url.substring(7)
            }
            if (!url.endsWith("/")) {
                url += "/"
            }
            url
        }

        addPairingLog("")
        addPairingLog("🔍 STAGE 3: Testing Connections")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addPairingLog("Found ${candidates.size} potential endpoints:")
        candidates.forEach { addPairingLog("  • $it") }

        _pairingStatus.postValue("Testing ${candidates.size} endpoints...")

        val sortedCandidates = candidates.sortedBy { url ->
            if (url.contains("192.168.") || url.contains("10.") || url.contains("172.")) 0 else 1
        }

        // OPTIMISTIC STRATEGY: Identify the "Dream" Local URL
        // Even if we fail to connect to it now (e.g. no WiFi), we save it to check later.
        val preferredLocal = sortedCandidates.firstOrNull { url ->
             url.contains("192.168.") || url.contains("10.") || url.contains("172.")
        }
        if (preferredLocal != null) {
            SettingsManager.savePreferredLocalUrl(preferredLocal)
            addPairingLog("💾 Saved preferred local URL for future switchback: $preferredLocal")
        }

        val reachableUrl = com.xelth.eckwms_movfast.utils.ConnectivityTester.findReachableUrl(sortedCandidates)

        if (reachableUrl == null) {
            throw Exception("No reachable server found among candidates")
        }

        addPairingLog("✅ CONNECTED to: $reachableUrl")

        SettingsManager.saveServerUrl(reachableUrl)

        // Detect if reachableUrl is IP-based (local) or domain-based (global)
        val cleanUrl = reachableUrl.replace(Regex("https?://"), "").split(":").first()
        val isIpAddress = cleanUrl.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))

        if (isIpAddress) {
            // Local IP found - look for domain-based global candidate
            val globalCandidate = candidates.firstOrNull { url ->
                val candidateClean = url.replace(Regex("https?://"), "").split(":").first()
                !candidateClean.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")) // Not an IP = domain
            }
            if (globalCandidate != null) {
                SettingsManager.saveGlobalServerUrl(globalCandidate)
                addPairingLog("✅ Saved global server: $globalCandidate")
            } else {
                addPairingLog("ℹ️ No global server URL provided (local-only setup)")
            }
        } else {
            // Domain-based URL - use as both local and global
            SettingsManager.saveGlobalServerUrl(reachableUrl)
            addPairingLog("✅ Using domain-based server as global: $reachableUrl")
        }

        performSecureRegistration(reachableUrl, instanceId, inviteToken)
    }

    private suspend fun performSecureRegistration(reachableUrl: String, instanceId: String, inviteToken: String?) {
        addPairingLog("")
        addPairingLog("🔐 STAGE 4: Secure Registration")
        _pairingStatus.postValue("Registering device securely...")

        com.xelth.eckwms_movfast.utils.CryptoManager.initialize(getApplication())
        val publicKeyBase64 = com.xelth.eckwms_movfast.utils.CryptoManager.getPublicKeyBase64()

        val timestamp = System.currentTimeMillis()
        val deviceId = android.provider.Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-android-id"

        val signatureData = "{\"deviceId\":\"$deviceId\",\"devicePublicKey\":\"$publicKeyBase64\"}"
        val signature = com.xelth.eckwms_movfast.utils.CryptoManager.sign(signatureData.toByteArray())
        val signatureBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)

        val result = scanApiService.registerDevice(
            publicKeyBase64 = publicKeyBase64,
            signature = signatureBase64,
            timestamp = timestamp,
            inviteToken = inviteToken
        )

        when (result) {
            is ScanResult.Success -> {
                try {
                    val jsonResponse = JSONObject(result.data)
                    val status = jsonResponse.optString("status", "active")
                    SettingsManager.saveDeviceStatus(status)
                    val token = jsonResponse.optString("token", "")
                    if (token.isNotEmpty()) SettingsManager.saveAuthToken(token)

                    addPairingLog("✅ PAIRING SUCCESSFUL!")
                    addPairingLog("Status: $status")

                    if (status == "pending") {
                        _pairingStatus.postValue("⚠️ Registered, waiting for approval")
                    } else {
                        _pairingStatus.postValue("✅ Success! Device paired.")
                    }
                    handlePairingSuccess()
                } catch (e: Exception) {
                    addPairingLog("Error parsing response, assuming active")
                    SettingsManager.saveDeviceStatus("active")
                    handlePairingSuccess()
                }
            }
            is ScanResult.Error -> {
                addPairingLog("❌ Registration Failed: ${result.message}")
                _pairingStatus.postValue("Error: ${result.message}")
            }
        }
    }

    /**
     * Handle pairing with new active discovery format (instance_id)
     */
    private suspend fun handlePairingWithActiveDiscovery(qrJson: JSONObject) {
        addLog("Using NEW format: Active discovery with instance_id")
        val instanceId = qrJson.getString("instance_id")
        val globalServerUrl = qrJson.getString("global_server_url")

        addLog("Instance ID: $instanceId")
        addLog("Global server URL: $globalServerUrl")
        _pairingStatus.postValue("Step 1/5: QR code parsed\nInstance ID: $instanceId")

        // STAGE 2: Collect client diagnostics
                addLog("STAGE 2: Collecting client diagnostics...")
                val clientIp = com.xelth.eckwms_movfast.utils.NetworkUtils.getDeviceIpAddress(getApplication())
                addLog("Client IP address: ${clientIp ?: "unknown"}")
                _pairingStatus.postValue("Step 2/5: Diagnostics collected\nYour device IP: ${clientIp ?: "unknown"}")

                // STAGE 3: Query global server for connection candidates
                addLog("STAGE 3: Querying global server for connection candidates...")
                _pairingStatus.postValue("Step 3/5: Contacting global server...\nServer: $globalServerUrl")

                val instanceInfoResult = scanApiService.getInstanceInfo(
                    globalServerUrl = globalServerUrl,
                    instanceId = instanceId,
                    clientIp = clientIp
                )

                when (instanceInfoResult) {
                    is ScanResult.Error -> {
                        throw Exception("Failed to get instance info: ${instanceInfoResult.message}")
                    }
                    is ScanResult.Success -> {
                        val responseJson = JSONObject(instanceInfoResult.data)
                        val candidatesArray = responseJson.getJSONArray("candidates")
                        val candidates = (0 until candidatesArray.length()).map {
                            val candidateObj = candidatesArray.getJSONObject(it)
                            candidateObj.getString("url")
                        }

                        addLog("Received ${candidates.size} connection candidates: $candidates")
                        _pairingStatus.postValue("Step 4/5: Testing connections...\nCandidates: ${candidates.size}")

                        // STAGE 4: Test connection candidates
                        addLog("STAGE 4: Testing connection candidates...")
                        val reachableUrl = com.xelth.eckwms_movfast.utils.ConnectivityTester.findReachableUrl(candidates)

                        if (reachableUrl == null) {
                            val errorMsg = """
                                Connection failed!

                                Your device IP: ${clientIp ?: "unknown"}
                                Tested ${candidates.size} candidates: ${candidates.joinToString(", ")}

                                Please ensure:
                                1. Your device is on the same network as the server
                                2. The server is running and accessible
                                3. No firewall is blocking connections
                            """.trimIndent()
                            addLog(errorMsg)
                            throw Exception(errorMsg)
                        }

                        addLog("Found reachable server: $reachableUrl")
                        _pairingStatus.postValue("Step 5/5: Server found!\nConnected to: $reachableUrl")

                        // Save server URLs to settings
                        SettingsManager.saveServerUrl(reachableUrl)
                        SettingsManager.saveGlobalServerUrl(globalServerUrl)
                        addLog("Server URLs saved to settings")

                        // STAGE 5: Perform cryptographic registration
                        addLog("STAGE 5: Performing cryptographic registration...")
                        _pairingStatus.postValue("Registering device securely...")

                        // Initialize CryptoManager
                        com.xelth.eckwms_movfast.utils.CryptoManager.initialize(getApplication())
                        addLog("CryptoManager initialized")

                        // Generate or retrieve key pair
                        val keyPair = com.xelth.eckwms_movfast.utils.CryptoManager.getOrCreateKeyPair()
                        val publicKeyBase64 = com.xelth.eckwms_movfast.utils.CryptoManager.getPublicKeyBase64()
                        addLog("Public key (Base64): $publicKeyBase64")

                        // Create signature data: deviceId + timestamp
                        val timestamp = System.currentTimeMillis()
                        val deviceId = android.provider.Settings.Secure.getString(
                            getApplication<Application>().contentResolver,
                            android.provider.Settings.Secure.ANDROID_ID
                        ) ?: "unknown-android-id"

                        val signatureData = "{\"deviceId\":\"$deviceId\",\"devicePublicKey\":\"$publicKeyBase64\"}"
                        val signature = com.xelth.eckwms_movfast.utils.CryptoManager.sign(signatureData.toByteArray())
                        val signatureBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
                        addLog("Signature created (Base64): ${signatureBase64.take(32)}...")

                        // Register device with server
                        val result = scanApiService.registerDevice(
                            publicKeyBase64 = publicKeyBase64,
                            signature = signatureBase64,
                            timestamp = timestamp
                        )

                        when (result) {
                            is ScanResult.Success -> {
                                try {
                                    val jsonResponse = JSONObject(result.data)
                                    val status = jsonResponse.optString("status", "active")
                                    SettingsManager.saveDeviceStatus(status)

                                    // Extract and save JWT token for authenticated API calls
                                    val token = jsonResponse.optString("token", "")
                                    if (token.isNotEmpty()) {
                                        SettingsManager.saveAuthToken(token)
                                        addLog("🔑 Security token saved")
                                    }

                                    addLog("✓ Device registered. Status: $status")

                                    if (status == "pending") {
                                        _pairingStatus.postValue("⚠️ Registered, but PENDING APPROVAL from admin.")
                                        _errorMessage.postValue("Waiting for admin approval.")
                                    } else {
                                        _pairingStatus.postValue("✓ Success! Device active and paired.")
                                        _errorMessage.postValue("Device successfully paired and active.")
                                    }
                                    handlePairingSuccess()
                                } catch (e: Exception) {
                                    addLog("Warning: Could not parse status from response, assuming active")
                                    SettingsManager.saveDeviceStatus("active")
                                    _pairingStatus.postValue("✓ Success! Device paired.")
                                    handlePairingSuccess()
                                }
                            }
                            is ScanResult.Error -> {
                                addLog("✗ Device registration failed: ${result.message}")
                                _pairingStatus.postValue("✗ Registration error: ${result.message}")
                                _errorMessage.postValue("Registration failed: ${result.message}")
                            }
                        }
                    }
                }
    }

    /**
     * Handle pairing with old direct testing format (local_server_urls)
     */
    private suspend fun handlePairingWithDirectTesting(qrJson: JSONObject) {
        val serverUrlsJson = qrJson.getJSONArray("local_server_urls")
        val serverUrls = (0 until serverUrlsJson.length()).map { serverUrlsJson.getString(it) }
        val globalServerUrl = qrJson.optString("global_server_url", "")

        // Get device's own IP address for diagnostics
        val clientIp = com.xelth.eckwms_movfast.utils.NetworkUtils.getDeviceIpAddress(getApplication())

        addPairingLog("")
        addPairingLog("📍 STAGE 2: Network info")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addPairingLog("Your device: ${clientIp ?: "unknown"}")
        addPairingLog("Server(s) to test: ${serverUrls.size}")
        serverUrls.forEach { addPairingLog("  → $it") }
        if (globalServerUrl.isNotEmpty()) {
            addPairingLog("Global server: $globalServerUrl")
        }

        // STAGE: Test connection candidates directly
        addPairingLog("")
        addPairingLog("🔍 STAGE 3: Testing connections")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addPairingLog("Timeout: 30 seconds per server")
        serverUrls.forEachIndexed { index, url ->
            addPairingLog("[${ index + 1}/${serverUrls.size}] Testing $url...")
        }
        addPairingLog("")
        addPairingLog("⏳ Please wait...")

        val reachableUrl = com.xelth.eckwms_movfast.utils.ConnectivityTester.findReachableUrl(serverUrls)

        if (reachableUrl == null) {
            addPairingLog("")
            addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
            addPairingLog("❌ CONNECTION FAILED")
            addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
            addPairingLog("")
            addPairingLog("📱 Your device:")
            addPairingLog("  IP: ${clientIp ?: "unknown"}")
            addPairingLog("")
            addPairingLog("🖥️ Tested servers:")
            serverUrls.forEach { addPairingLog("  ✗ $it (no response)") }
            addPairingLog("")
            addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
            addPairingLog("💡 Troubleshooting:")
            addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
            addPairingLog("  1. Same WiFi network?")
            addPairingLog("  2. Server running?")
            addPairingLog("  3. Firewall blocking?")
            addPairingLog("  4. Check server logs")
            throw Exception("No server reachable")
        }

        addPairingLog("")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addPairingLog("✅ SERVER FOUND!")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addPairingLog("")
        addPairingLog("📱 Your device: ${clientIp ?: "unknown"}")
        addPairingLog("🖥️ Server: $reachableUrl")
        addPairingLog("")
        addPairingLog("Saving configuration...")

        // Save server URLs to settings
        SettingsManager.saveServerUrl(reachableUrl)
        if (globalServerUrl.isNotEmpty()) {
            SettingsManager.saveGlobalServerUrl(globalServerUrl)
        }
        addLog("Server URLs saved to settings")

        // STAGE: Perform cryptographic registration
        addPairingLog("")
        addPairingLog("🔐 STAGE 4: Secure registration")
        addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
        addPairingLog("Initializing crypto...")

        // Initialize CryptoManager
        com.xelth.eckwms_movfast.utils.CryptoManager.initialize(getApplication())
        addPairingLog("✓ Crypto initialized")

        // Generate or retrieve key pair
        val keyPair = com.xelth.eckwms_movfast.utils.CryptoManager.getOrCreateKeyPair()
        val publicKeyBase64 = com.xelth.eckwms_movfast.utils.CryptoManager.getPublicKeyBase64()
        addPairingLog("✓ Key pair ready")
        addPairingLog("  Length: ${publicKeyBase64.length} chars")

        // Create signature data: deviceId + timestamp
        val timestamp = System.currentTimeMillis()
        val deviceId = android.provider.Settings.Secure.getString(
            getApplication<Application>().contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown-android-id"

        val signatureData = "{\"deviceId\":\"$deviceId\",\"devicePublicKey\":\"$publicKeyBase64\"}"
        val signature = com.xelth.eckwms_movfast.utils.CryptoManager.sign(signatureData.toByteArray())
        val signatureBase64 = android.util.Base64.encodeToString(signature, android.util.Base64.NO_WRAP)
        addPairingLog("✓ Signature created")

        addPairingLog("")
        addPairingLog("📤 Sending credentials...")
        addPairingLog("URL: $reachableUrl")
        addPairingLog("API: /api/device/register")
        addPairingLog("Device ID: ${deviceId.take(8)}...")
        addPairingLog("")
        addPairingLog("⏳ Waiting for server response...")

        // Register device with server
        val result = scanApiService.registerDevice(
            publicKeyBase64 = publicKeyBase64,
            signature = signatureBase64,
            timestamp = timestamp
        )

        when (result) {
            is ScanResult.Success -> {
                try {
                    val jsonResponse = JSONObject(result.data)
                    val status = jsonResponse.optString("status", "active")
                    SettingsManager.saveDeviceStatus(status)

                    // Extract and save JWT token for authenticated API calls
                    val token = jsonResponse.optString("token", "")
                    if (token.isNotEmpty()) {
                        SettingsManager.saveAuthToken(token)
                        addPairingLog("🔑 Security token saved")
                    }

                    addPairingLog("")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    if (status == "pending") {
                        addPairingLog("⚠️ PAIRING REGISTERED!")
                        addPairingLog("   (PENDING APPROVAL)")
                    } else {
                        addPairingLog("✅ PAIRING SUCCESSFUL!")
                    }
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    addPairingLog("")
                    addPairingLog("Your device is now paired!")
                    addPairingLog("")
                    addPairingLog("📱 Device ID:")
                    addPairingLog("  ${deviceId.take(16)}...")
                    addPairingLog("")
                    addPairingLog("🖥️ Server:")
                    addPairingLog("  $reachableUrl")
                    addPairingLog("")
                    addPairingLog("🔐 API Endpoint:")
                    addPairingLog("  /api/device/register")
                    addPairingLog("")
                    addPairingLog("📊 Status: $status")
                    addPairingLog("")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    if (status == "pending") {
                        addPairingLog("⚠️ Awaiting admin approval")
                    } else {
                        addPairingLog("✅ Ready to use!")
                    }
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")

                    if (status == "pending") {
                        _errorMessage.postValue("⚠️ Device registered. Waiting for admin approval.")
                    } else {
                        _errorMessage.postValue("✅ Device successfully paired")
                    }
                    handlePairingSuccess()
                } catch (e: Exception) {
                    addPairingLog("")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    addPairingLog("✅ PAIRING SUCCESSFUL!")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    addPairingLog("")
                    addPairingLog("Warning: Could not parse status from response")
                    addPairingLog("Assuming device is active")
                    addPairingLog("")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    addPairingLog("✅ Ready to use!")
                    addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                    SettingsManager.saveDeviceStatus("active")
                    _errorMessage.postValue("✅ Device successfully paired")
                    handlePairingSuccess()
                }
            }
            is ScanResult.Error -> {
                addPairingLog("")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                addPairingLog("❌ REGISTRATION FAILED")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                addPairingLog("")
                addPairingLog("Server responded with error:")
                addPairingLog("")
                addPairingLog("🖥️ Server: $reachableUrl")
                addPairingLog("📍 API: /api/device/register")
                addPairingLog("❌ Error: ${result.message}")
                addPairingLog("")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                addPairingLog("💡 The server is reachable")
                addPairingLog("   but rejected registration.")
                addPairingLog("   Check server logs for details.")
                addPairingLog("━━━━━━━━━━━━━━━━━━━━━━━━")
                _errorMessage.postValue("❌ Registration failed: ${result.message}")
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

    private fun handlePairingSuccess() {
        android.util.Log.e("AUTO_PAIR", "=== handlePairingSuccess called ===")
        android.util.Log.e("AUTO_PAIR", "isAutoPairing: $isAutoPairing")
        android.util.Log.e("AUTO_PAIR", "isOnPairingScreen: $isOnPairingScreen")
        _pairingSuccess.postValue(true)
        if (isAutoPairing) {
            android.util.Log.e("AUTO_PAIR", "AUTO mode detected - scheduling return in 2s")
            addLog("✨ Auto-Pairing successful! Returning in 2s...")
            viewModelScope.launch {
                android.util.Log.e("AUTO_PAIR", "Starting 2 second delay...")
                delay(2000)
                android.util.Log.e("AUTO_PAIR", "Delay complete - checking state before BACK")
                android.util.Log.e("AUTO_PAIR", "isAutoPairing still: $isAutoPairing")
                android.util.Log.e("AUTO_PAIR", "Sending NavigationCommand.BACK")
                _navigationCommand.postValue(NavigationCommand.BACK)
                isAutoPairing = false
                android.util.Log.e("AUTO_PAIR", "isAutoPairing reset to false")
            }
        } else {
            android.util.Log.e("AUTO_PAIR", "MANUAL mode - no auto-return")
        }
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