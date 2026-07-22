package com.xelth.eckwms_movfast.ui.viewmodels

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xelth.eckwms_movfast.api.ScanResult
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridConfig
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridManager
import com.xelth.eckwms_movfast.ui.screens.pos.grid.PRIORITIES
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SystemElement
import com.xelth.eckwms_movfast.voice.VoiceCommandManager
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SlotType
import com.xelth.eckwms_movfast.ui.screens.pos.grid.ContentGrid
import com.xelth.eckwms_movfast.ui.screens.pos.grid.ContentSlot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import org.json.JSONArray
import org.json.JSONObject

data class MainMenuButton(
    val id: String,
    val label: String,
    val color: String,
    val action: String,
    val priority: Int = PRIORITIES.DEFAULT
)

// --- Device Check Mode Data Models ---

enum class DcStepId {
    CONTENT_PHOTO,    // required, photo
    SCAN_DEVICE,      // required, scan (validates barcode match)
    IMPEDANCE_PHOTO,  // OPTIONAL, photo, saved separately for analysis
    CONDITION_PHOTO,  // required, photo
    LEFTOVER_PHOTO    // required, photo
}

data class DcStepDef(
    val id: DcStepId,
    val label: String,
    val type: String,        // "photo" or "scan"
    val required: Boolean,
    val color: String        // color when targeted/active
)

val DC_STEPS = listOf(
    DcStepDef(DcStepId.CONTENT_PHOTO,   "Content",   "photo", required = true,  color = "#9C27B0"),
    DcStepDef(DcStepId.SCAN_DEVICE,     "Scan Dev",  "scan",  required = true,  color = "#00BCD4"),
    DcStepDef(DcStepId.IMPEDANCE_PHOTO, "Impedance", "photo", required = false, color = "#FF9800"),
    DcStepDef(DcStepId.CONDITION_PHOTO, "Condition",  "photo", required = true,  color = "#2196F3"),
    DcStepDef(DcStepId.LEFTOVER_PHOTO,  "Leftover",   "photo", required = true,  color = "#4CAF50")
)

data class DeviceCheckSlot(
    val index: Int,
    var boxBarcode: String? = null,
    var deviceBarcode: String? = null,
    var isActive: Boolean = false,
    val stepData: MutableMap<DcStepId, Any> = mutableMapOf()
)

// --- Repair Mode Data Models ---

data class RepairSlot(
    val index: Int,
    var barcode: String? = null,
    var isBound: Boolean = false,
    var isActive: Boolean = false,
    var photo: Bitmap? = null,                          // background photo (first photo sent)
    val allPhotos: MutableList<Bitmap> = mutableListOf(), // all photos for avatar picker (max 20)
    val photoUuids: MutableList<String> = mutableListOf(), // UUIDs of all photos (for DB + disk)
    val history: MutableList<String> = mutableListOf()    // Action history for this slot
)

sealed class RepairAction {
    object None : RepairAction()
    data class PendingPhoto(val bitmap: Bitmap) : RepairAction()
    data class PendingBarcode(val code: String) : RepairAction()
}

// Tracks the last sent action so it can be undone
/** State for a native half-slot button (EXIT, etc.) rendered by SelectionAreaSheet. */
data class HalfButtonState(
    val label: String,
    val colorHex: String,
    val action: String,
    val textColorHex: String? = null,
    val enabled: Boolean = true
)

data class LastRepairAction(
    val targetDeviceId: String,
    val eventType: String, // "photo", "part_scan"
    val data: String,
    val bitmap: Bitmap? = null
)

class MainScreenViewModel : ViewModel() {

    private val _isLeftHanded = MutableLiveData<Boolean>(false)
    val isLeftHanded: LiveData<Boolean> = _isLeftHanded

    // Configurable number of grid rows (default 7)
    private val _gridRowCount = MutableLiveData<Int>(7)
    val gridRowCount: LiveData<Int> = _gridRowCount

    // Status colors for bottom indicators (Map of Slot ID -> Hex Color)
    private val _indicatorStates = MutableLiveData<Map<String, String>>(emptyMap())
    val indicatorStates: LiveData<Map<String, String>> = _indicatorStates

    // Dynamic grid config - recalculated based on screen dimensions
    var gridConfig = GridConfig(
        cellWidth = 140.dp,
        cellHeight = 80.dp,
        buttonGap = 2.dp,
        verticalOverlap = 0.75f,
        containerWidth = 0.dp,
        isMirrored = false
    )
        private set

    var gridManager = GridManager(
        config = gridConfig,
        dimensions = Pair(4, 7),
        layoutType = "asymmetrical"
    )
        private set

    private val _renderCells = MutableLiveData<List<RenderCell>>(emptyList())
    val renderCells: LiveData<List<RenderCell>> = _renderCells

    // Native half-slot: EXIT button (HALF_RIGHT row=1)
    private val _exitButton = MutableLiveData<HalfButtonState?>(null)
    val exitButton: LiveData<HalfButtonState?> = _exitButton

    private val _consoleLogs = MutableLiveData<List<String>>(emptyList())
    val consoleLogs: LiveData<List<String>> = _consoleLogs

    private val _pageCount = MutableLiveData<Int>(1)
    val pageCount: LiveData<Int> = _pageCount

    private val _scannerEnabled = MutableLiveData<Boolean>(true)
    val scannerEnabled: LiveData<Boolean> = _scannerEnabled

    // Actual computed grid height in Dp (for bottom-anchored layout)
    var gridTotalHeight: Dp = 0.dp
        private set

    private var lastWidth = 0f
    private var lastHeight = 0f

    // --- REPAIR MODE STATE ---

    // Callbacks for sending repair events to the server (set by UI layer)
    var onRepairEventSend: ((targetDeviceId: String, eventType: String, data: String) -> Unit)? = null
    var onRepairPhotoUpload: ((targetDeviceId: String, bitmap: Bitmap) -> Unit)? = null
    // Inventory submit callback — returns ScanResult with discrepancy data
    var onInventorySubmit: (suspend (targetDeviceId: String, eventType: String, data: String) -> com.xelth.eckwms_movfast.api.ScanResult)? = null
    // Persistence callbacks (set by UI layer, backed by SettingsManager)
    var onSaveRepairSlots: ((List<Pair<Int, String>>) -> Unit)? = null
    var onLoadRepairSlots: (() -> List<Pair<Int, String>>)? = null
    var onSaveRepairPhoto: ((Int, Bitmap) -> Unit)? = null
    var onLoadRepairPhoto: ((Int) -> Bitmap?)? = null
    var onDeleteRepairPhoto: ((Int) -> Unit)? = null
    // CAS-id photo persistence (backed by LocalPhotoDao + SettingsManager).
    // Returns the ContentHash UUID of the stored bytes — the SyncWorker later
    // claims exactly this id, and the server verifies it against the content.
    var onSavePhoto: ((slotIndex: Int, bitmap: Bitmap) -> String?)? = null
    var onLoadSlotPhotoUuids: (suspend (slotIndex: Int) -> List<String>)? = null
    var onDeleteSlotPhotos: (suspend (slotIndex: Int) -> Unit)? = null
    var onBindSlotPhotos: (suspend (slotIndex: Int, receiverId: String) -> Unit)? = null
    // Callback for fetching shipments (set by UI layer, backed by ScanApiService)
    var onFetchShipments: (suspend (limit: Int) -> ScanResult)? = null
    // Fat Client: offline lookup callbacks (set by UI layer, backed by WarehouseRepository)
    var onUpdateProductQty: (suspend (barcode: String, qty: Double) -> Unit)? = null
    var onLookupProduct: (suspend (barcode: String) -> com.xelth.eckwms_movfast.data.local.entity.ProductEntity?)? = null
    var onLookupLocation: (suspend (barcode: String) -> com.xelth.eckwms_movfast.data.local.entity.LocationEntity?)? = null
    var onFetchLocationContents: (suspend (locationId: String) -> com.xelth.eckwms_movfast.api.ScanResult)? = null
    // Warehouse soll (Exact reconcile) — returns raw JSON `{lines:[{default_code,name,soll,…}]}`.
    var onFetchExpectedSoll: (suspend (warehouse: String) -> String?)? = null
    // Put-away one counted line to a shelf (optimistic; offline-queued on failure).
    var onPutAwayItem: (suspend (itemBarcode: String, shelfBarcode: String, warehouse: String, qty: Double) -> Unit)? = null
    // Inventory records persistence (PDA = source of truth)
    var onSaveInventoryRecords: (suspend (locationBarcode: String, records: List<com.xelth.eckwms_movfast.data.local.entity.InventoryRecordEntity>) -> Unit)? = null
    var onLoadInventoryRecords: (suspend (locationBarcode: String) -> List<com.xelth.eckwms_movfast.data.local.entity.InventoryRecordEntity>)? = null

    // --- SMART CONTEXT STATE (Main Menu / Idle) ---
    sealed class SmartContext {
        object Idle : SmartContext()
        data class ItemSelected(val barcode: String, val product: com.xelth.eckwms_movfast.data.local.entity.ProductEntity?, val bitmap: Bitmap?) : SmartContext()
        data class BoxSelected(val barcode: String, val bitmap: Bitmap?) : SmartContext()
        data class LocationSelected(val barcode: String, val location: com.xelth.eckwms_movfast.data.local.entity.LocationEntity?, val bitmap: Bitmap?) : SmartContext()
    }

    private val _smartContext = MutableLiveData<SmartContext>(SmartContext.Idle)
    val smartContext: LiveData<SmartContext> = _smartContext

    private val _smartStatus = MutableLiveData<String>("")
    val smartStatus: LiveData<String> = _smartStatus

    private val _isRepairMode = MutableLiveData<Boolean>(false)
    val isRepairMode: LiveData<Boolean> = _isRepairMode

    private val _repairStatus = MutableLiveData<String>("Repair Mode Ready")
    val repairStatus: LiveData<String> = _repairStatus

    private val _navigateToCamera = MutableLiveData<Boolean>(false)
    val navigateToCamera: LiveData<Boolean> = _navigateToCamera

    // CRM entity navigation from inventory scan
    private val _navigateToCrm = MutableLiveData<Pair<String, String>?>()
    val navigateToCrm: LiveData<Pair<String, String>?> = _navigateToCrm
    fun consumeNavigateToCrm() { _navigateToCrm.value = null }

    private val _activeSlotPhoto = MutableLiveData<Bitmap?>(null)
    val activeSlotPhoto: LiveData<Bitmap?> = _activeSlotPhoto

    // LiveData for the active slot's history and photos to trigger UI recomposition
    private val _activeSlotHistory = MutableLiveData<List<String>>(emptyList())
    val activeSlotHistory: LiveData<List<String>> = _activeSlotHistory

    private val _activeSlotPhotosList = MutableLiveData<List<Bitmap>>(emptyList())
    val activeSlotPhotosList: LiveData<List<Bitmap>> = _activeSlotPhotosList

    fun consumeNavigateToCamera() { _navigateToCamera.value = false }

    // 18 slots to fill the 7-row grid (3 actions + 18 slots = 21 = 7 rows × 3)
    private val slots = MutableList(18) { i -> RepairSlot(i) }
    private var activeSlotJob: Job? = null
    private var pendingAction: RepairAction = RepairAction.None
    private var slotWaitingForBind: Int? = null
    private var lastSentAction: LastRepairAction? = null

    // Deactivation timer: doubles on each action, resets on slot switch
    private var activeSlotTimeoutMs = INITIAL_SLOT_TIMEOUT_MS

    companion object {
        private const val INITIAL_SLOT_TIMEOUT_MS = 60_000L
        private const val MAX_SLOT_TIMEOUT_MS = 15 * 60_000L // 15 min cap
    }

    // --- TRIP MODE STATE (Fahrtenbuch) ---

    private val _isTripMode = MutableLiveData<Boolean>(false)
    val isTripMode: LiveData<Boolean> = _isTripMode

    private val _tripStatus = MutableLiveData<String>("")
    val tripStatus: LiveData<String> = _tripStatus

    private val _tripAutoDetect = MutableLiveData<Boolean>(false)
    val tripAutoDetect: LiveData<Boolean> = _tripAutoDetect

    private val _tripRecording = MutableLiveData<Boolean>(false)
    val tripRecording: LiveData<Boolean> = _tripRecording

    // Trip-mode console data (cities + ticket list)
    private val _tripCities = MutableLiveData<List<com.xelth.eckwms_movfast.api.CityCount>>(emptyList())
    val tripCities: LiveData<List<com.xelth.eckwms_movfast.api.CityCount>> = _tripCities

    private val _tripDestinations = MutableLiveData<List<com.xelth.eckwms_movfast.api.Destination>>(emptyList())
    val tripDestinations: LiveData<List<com.xelth.eckwms_movfast.api.Destination>> = _tripDestinations

    private var tripSelectedCity: String? = null
    /** Wired from MainScreen → ScanApiService.fetchDestinations(query, city, ai). */
    var onFetchDestinations: (suspend (String?, String?, Boolean) -> com.xelth.eckwms_movfast.api.DestinationsResult?)? = null

    // --- RECEIVING MODE STATE ---

    private val _isReceivingMode = MutableLiveData<Boolean>(false)
    val isReceivingMode: LiveData<Boolean> = _isReceivingMode

    private val _receivingStatus = MutableLiveData<String>("")
    val receivingStatus: LiveData<String> = _receivingStatus

    // Network mode: single-tap on the server half-button. Console shows connection status,
    // hex grid offers Scan QR / Code / Refresh / Exit (pairing itself runs in ScanRecoveryVM).
    private val _isNetworkMode = MutableLiveData<Boolean>(false)
    val isNetworkMode: LiveData<Boolean> = _isNetworkMode

    private var receivingSteps: List<Map<String, Any>> = emptyList()
    private var currentStepIndex: Int = 0
    val receivingData = mutableMapOf<String, Any>()

    // Contents grid state (for step 4 "Contents")
    private val contentsToggles = mutableMapOf<String, Boolean>()   // device→true, psu→false...
    private val contentsBarcodes = mutableMapOf<String, String>()   // device→"SN123"...
    private var activeContentItem: String? = null                    // last toggled-ON item for scan
    private var packagingSubLevel = false                           // true = showing packaging options
    private var selectedPackaging: String? = null

    // Modal trigger — set to step's uiSchema JSON when a modal step is clicked
    private val _showReceivingModal = MutableLiveData<String?>(null)
    val showReceivingModal: LiveData<String?> = _showReceivingModal

    fun dismissReceivingModal() { _showReceivingModal.value = null }

    // Shipment picker trigger — true when the shipment list should be shown
    private val _showShipmentPicker = MutableLiveData<Boolean>(false)
    val showShipmentPicker: LiveData<Boolean> = _showShipmentPicker

    fun dismissShipmentPicker() { _showShipmentPicker.value = false }

    // --- MULTI-USER STATE ---
    private val _showUserDialog = MutableLiveData<Boolean>(false)
    val showUserDialog: LiveData<Boolean> = _showUserDialog

    // "login" = long press (Anmeldung with PIN), "view" = short press (switch view)
    private val _userDialogMode = MutableLiveData<String>("view")
    val userDialogMode: LiveData<String> = _userDialogMode

    private val _showPinDialog = MutableLiveData<Boolean>(false)
    val showPinDialog: LiveData<Boolean> = _showPinDialog

    // Temporarily holds the user selected for PIN verification
    private var pendingLoginUser: AppUser? = null

    // Callback for verifying PIN (set by UI layer, backed by ScanApiService)
    var onVerifyPin: (suspend (userId: String, pin: String) -> PinAuthResult)? = null
    // Callback for fetching user list
    var onFetchUsers: (suspend () -> List<AppUser>?)? = null
    // Callback for changing the current user's password (backed by ScanApiService)
    var onChangePassword: (suspend (oldPassword: String, newPassword: String) -> ChangePasswordResult)? = null

    fun dismissUserDialog() { _showUserDialog.value = false }
    fun dismissPinDialog() { _showPinDialog.value = false; pendingLoginUser = null }

    // --- CHANGE PASSWORD STATE ---
    private val _showChangePasswordDialog = MutableLiveData<Boolean>(false)
    val showChangePasswordDialog: LiveData<Boolean> = _showChangePasswordDialog

    // Forced = seeded account must set a password before proceeding (non-dismissable).
    private val _changePasswordForced = MutableLiveData<Boolean>(false)
    val changePasswordForced: LiveData<Boolean> = _changePasswordForced

    private val _changePasswordError = MutableLiveData<String?>(null)
    val changePasswordError: LiveData<String?> = _changePasswordError

    private val _changePasswordBusy = MutableLiveData<Boolean>(false)
    val changePasswordBusy: LiveData<Boolean> = _changePasswordBusy

    /** Self-service entry: open a dismissable change-password dialog. */
    fun openChangePasswordDialog() {
        _changePasswordForced.value = false
        _changePasswordError.value = null
        _showChangePasswordDialog.value = true
    }

    /** Forced entry: seeded account must change before using the app (no dismiss). */
    private fun openForcedChangePasswordDialog() {
        _changePasswordForced.postValue(true)
        _changePasswordError.postValue(null)
        _showChangePasswordDialog.postValue(true)
    }

    /** Dismiss — a no-op while a forced change is pending. */
    fun dismissChangePasswordDialog() {
        if (_changePasswordForced.value == true) return
        _showChangePasswordDialog.value = false
        _changePasswordError.value = null
    }

    /** Validate client-side, then POST. On success clear the flag and close. */
    fun submitChangePassword(oldPassword: String, newPassword: String, confirmPassword: String) {
        val localError = com.xelth.eckwms_movfast.utils.PasswordPolicy.validate(oldPassword, newPassword, confirmPassword)
        if (localError != null) {
            _changePasswordError.value = localError
            onHapticError?.invoke()
            return
        }
        val cb = onChangePassword
        if (cb == null) {
            _changePasswordError.value = "Password change unavailable"
            return
        }
        _changePasswordBusy.value = true
        _changePasswordError.value = null
        viewModelScope.launch {
            val result = cb.invoke(oldPassword, newPassword)
            _changePasswordBusy.postValue(false)
            if (result.success) {
                UserManager.clearMustChangePassword()
                _changePasswordForced.postValue(false)
                _showChangePasswordDialog.postValue(false)
                addLog("✅ Password changed")
            } else {
                _changePasswordError.postValue(result.error ?: "Password change failed")
                onHapticError?.invoke()
            }
        }
    }

    // Camera navigation for receiving — holds scan_mode string, null when not navigating
    private val _receivingCameraNav = MutableLiveData<String?>(null)
    val receivingCameraNav: LiveData<String?> = _receivingCameraNav

    fun consumeReceivingCameraNav() { _receivingCameraNav.value = null }

    // Cached shipments for receiving dropdown
    private var cachedShipments: List<Map<String, Any>> = emptyList()
    // Cached warehouse info for smart client detection (from server config)
    private var warehouseName: String = ""
    private var warehouseAddress: String = ""

    // Signals that shipment data has been loaded (triggers UI recomposition)
    private val _shipmentsLoaded = MutableLiveData<Boolean>(false)
    val shipmentsLoaded: LiveData<Boolean> = _shipmentsLoaded

    // Track shipment loading error state
    private val _shipmentsError = MutableLiveData<String?>(null)
    val shipmentsError: LiveData<String?> = _shipmentsError

    // --- RESTOCK MODE STATE ---

    private val _isRestockMode = MutableLiveData<Boolean>(false)
    val isRestockMode: LiveData<Boolean> = _isRestockMode

    private val restockItems = mutableMapOf<String, Int>()

    private val _restockStatus = MutableLiveData<String>("Ready to scan parts")
    val restockStatus: LiveData<String> = _restockStatus

    // --- INVENTORY MODE STATE ---

    private val _isInventoryMode = MutableLiveData<Boolean>(false)
    val isInventoryMode: LiveData<Boolean> = _isInventoryMode

    // Item barcode -> InventoryEntry (qty, type, photo, displayName)
    data class InventoryEntry(
        var quantity: Int = 1,
        var type: String = "item",  // "item" or "box"
        var photo: Bitmap? = null,
        var displayName: String? = null,  // From offline DB (Fat Client)
        var internalId: String? = null,  // Decrypted Smart Code (i000.../b000...) for server upload
        var needsPhoto: Boolean = false  // Flagged for photo, doesn't block scanning
    )

    // Vibration callback (set by UI layer which has Context)
    var onLongVibrate: (() -> Unit)? = null
    // Haptic feedback callbacks (wired to SunlightModeManager by UI layer)
    var onHapticSuccess: (() -> Unit)? = null
    var onHapticError: (() -> Unit)? = null
    var onHapticAttention: (() -> Unit)? = null
    private val inventoryItems = mutableMapOf<String, InventoryEntry>()

    // Expected inventory from server (populated when location is scanned)
    data class ExpectedItem(
        val productId: Long,
        val productName: String,
        val barcode: String,
        val defaultCode: String,
        val quantity: Double,
        val reservedQty: Double
    )
    private val expectedInventory = mutableMapOf<String, ExpectedItem>()  // barcode -> ExpectedItem
    // Exact scraped stock (soll), warehouse-level: default_code -> expected qty. Loaded
    // once via reconcile; a scanned item's soll is copied into expectedInventory on demand.
    private val sollMap = mutableMapOf<String, Double>()
    private var inventoryWarehouse: String = "WH008"  // target WH for stocktake put-away + reconcile

    // Numpad state
    private var isNumpadActive = false
    private var numpadInput = ""

    private val _inventoryStatus = MutableLiveData<String>("Ready to count")
    val inventoryStatus: LiveData<String> = _inventoryStatus

    // Current item photo for console background
    private val _inventoryItemPhoto = MutableLiveData<Bitmap?>(null)
    val inventoryItemPhoto: LiveData<Bitmap?> = _inventoryItemPhoto

    private var currentInventoryLocation: String = ""
    private var lastScannedInventoryItem: String = ""  // for photo attachment
    private var waitingForManualLocation: Boolean = false  // SET LOC was pressed, next scan = location
    private var inventoryBoxMode: Boolean = false  // toggle: false=items, true=boxes (for external barcodes)
    private var inventoryAutoPhoto: Boolean = true  // auto-open camera on new item (torch-lit); Compose focus-crash now caught by the MainActivity pre-IME guard

    // Enhanced: track last scanned type for photo attachment
    private var lastScannedType: String = ""  // "place" or "item"
    private var decryptedLocationId: String? = null  // internal ID for place photo (p000...)
    private var decryptedItemId: String? = null  // internal ID for item photo (i000... or b000...)

    // Place photo
    private val _inventoryLocationPhoto = MutableLiveData<Bitmap?>(null)
    val inventoryLocationPhoto: LiveData<Bitmap?> = _inventoryLocationPhoto

    // Persistence callbacks for item photos (global, by internal ID)
    // Internal ID prefix defines type: i=item, b=box, p=place, l=label
    var onSaveItemPhoto: ((internalId: String, bitmap: Bitmap) -> Unit)? = null
    var onLoadItemPhoto: (suspend (internalId: String) -> Bitmap?)? = null

    // --- DEVICE CHECK MODE STATE ---

    private val _isDeviceCheckMode = MutableLiveData<Boolean>(false)
    val isDeviceCheckMode: LiveData<Boolean> = _isDeviceCheckMode

    private val _deviceCheckStatus = MutableLiveData<String>("Select a slot to start check")
    val deviceCheckStatus: LiveData<String> = _deviceCheckStatus

    // 3 Slots for parallel processing
    private val deviceCheckSlots = MutableList(3) { i -> DeviceCheckSlot(i) }
    private var targetedStep: DcStepId? = null
    private var slotWaitingForInit: Int? = null  // slot waiting for box barcode scan

    // Persistence callbacks (set by UI layer)
    var onSaveDeviceCheckSlots: ((List<Pair<Int, String>>) -> Unit)? = null
    var onLoadDeviceCheckSlots: (() -> List<Pair<Int, String>>)? = null

    init {
        Log.d("MainViewModel", "ViewModel init (slots=${slots.size}, hashCode=${hashCode()})")
        initializeGrid()
    }

    /**
     * Recalculate grid with fixed row count, anchored to bottom edge.
     */
    fun updateLayoutDimensions(width: Dp, height: Dp, density: Density) {
        val containerWidth = width.value
        val containerHeight = height.value
        val isMirrored = _isLeftHanded.value ?: false
        val numRows = _gridRowCount.value ?: 7

        if (containerWidth == lastWidth && containerHeight == lastHeight && gridConfig.isMirrored == isMirrored) return
        if (containerWidth <= 0 || containerHeight <= 0) return
        lastWidth = containerWidth
        lastHeight = containerHeight

        val buttonGap = 2f
        val buttonWidth = containerWidth / 3.5f - buttonGap
        val buttonHeight = buttonWidth * (80f / 120f)
        val effectiveRowHeight = buttonHeight * 0.75f + buttonGap
        if (effectiveRowHeight <= 0) return

        val actualGridHeight = (numRows - 1) * effectiveRowHeight + buttonHeight
        gridTotalHeight = actualGridHeight.dp
        Log.d("GridLayout", "container=${containerWidth}x${containerHeight} rows=$numRows btnW=$buttonWidth btnH=$buttonHeight rowH=$effectiveRowHeight gridH=$actualGridHeight consoleH=${containerHeight - actualGridHeight}")

        gridConfig = GridConfig(
            cellWidth = buttonWidth.dp,
            cellHeight = buttonHeight.dp,
            buttonGap = buttonGap.dp,
            verticalOverlap = 0.75f,
            containerWidth = width,
            isMirrored = isMirrored
        )

        gridManager = GridManager(
            config = gridConfig,
            dimensions = Pair(numRows.coerceAtLeast(1), 7),
            layoutType = "asymmetrical"
        )

        if (_isRepairMode.value == true) {
            renderRepairGrid()
        } else if (_isRestockMode.value == true) {
            renderRestockGrid()
        } else if (_isInventoryMode.value == true) {
            renderInventoryGrid()
        } else if (_isReceivingMode.value == true) {
            renderReceivingGrid()
        } else if (_isTripMode.value == true) {
            renderTripGrid()
        } else if (_isDeviceCheckMode.value == true) {
            renderDeviceCheckGrid()
        } else if (_isNetworkMode.value == true) {
            renderNetworkGrid()
        } else {
            initializeGrid()
        }
    }

    /** Checklist line for an armed start intent: what it still lacks (🔖 plate /
     *  🔢 odometer / 🎯 purpose, "?"-marked), "✓" once everything is aboard.
     *  Shown on the MAIN-MENU Trips hex — the armed state is visible (and
     *  cancellable via trip mode) without entering trip mode. */
    private fun intentChecklist(i: com.xelth.eckwms_movfast.trips.TripManager.TripIntent): String {
        val missing = buildString {
            if (i.plate.isNullOrBlank()) append("🔖?")
            if (i.odoKm == null) append("🔢?")
            if (i.label.isBlank()) append("🎯?")
        }
        return missing.ifEmpty { "✓" }
    }

    private fun initializeGrid() {
        // Trips is the trip-state indicator: blue-grey car while idle, YELLOW
        // with the armed checklist while a start intent is pending (the
        // "decorated" state lives HERE, not on the inner Start hex), green car
        // (🚙 renders green on Android) while a Fahrt is recording — all
        // visible without entering trip mode.
        val tripActive = _tripRecording.value == true
        val armedIntent = if (tripActive) null
            else com.xelth.eckwms_movfast.trips.TripManager.peekTripIntent()
        val buttons = listOf(
            MainMenuButton("repair", "🔧\nRepair", "#E91E63", "navigate_repair", PRIORITIES.DEFAULT),
            MainMenuButton("receiving", "📦\nReceiving", "#FF9800", "navigate_receiving", PRIORITIES.DEFAULT),
            MainMenuButton("device_check", "✅\nCheck", "#4CAF50", "navigate_device_check", PRIORITIES.DEFAULT),
            MainMenuButton("restock", "📋\nRestock", "#50E3C2", "navigate_restock", PRIORITIES.DEFAULT),
            MainMenuButton("inventory", "📊\nInventory", "#795548", "navigate_inventory", PRIORITIES.DEFAULT),
            MainMenuButton("picking", "📦\nPicking", "#3F51B5", "navigate_picking", PRIORITIES.DEFAULT),
            MainMenuButton("qc", "QC", "#F44336", "navigate_qc", PRIORITIES.DEFAULT),
            MainMenuButton("explorer", "🔎\nExplorer", "#2196F3", "navigate_explorer", PRIORITIES.DEFAULT),
            MainMenuButton("pos", "💶\nPOS", "#4CAF50", "navigate_pos", PRIORITIES.DEFAULT),
            MainMenuButton("trips",
                when {
                    tripActive -> "🚙\nTrip●"
                    armedIntent != null -> "🕐 Armed\n" + intentChecklist(armedIntent)
                    else -> "🚗\nTrips"
                },
                when {
                    tripActive -> "#2E7D32"
                    armedIntent != null -> "#F9A825"
                    else -> "#607D8B"
                },
                "navigate_trips", PRIORITIES.DEFAULT)
        )

        val contentItems = buttons.map { button ->
            mapOf(
                "type" to "button",
                "label" to button.label,
                "color" to button.color,
                "action" to button.action
            )
        }

        gridManager.clearAndReset()
        placeSystemButtons(scanAction = "capture_barcode")
        gridManager.placeItems(contentItems, priority = PRIORITIES.SCAN_BUTTON)
        // Root grid keeps the red ✕ too: short press is a no-op (nowhere to go
        // back to), long press minimizes the app — same gesture at every depth.
        _exitButton.postValue(HalfButtonState("✕", "#F44336", "act_exit"))
        updateRenderCells()
    }

    private fun updateRenderCells() {
        viewModelScope.launch {
            val cells = gridManager.getRenderStructure()
            _renderCells.postValue(cells)
        }
    }

    /**
     * Voice Commands P0 — pin the always-present "system" buttons at FIXED hex
     * positions in EVERY mode. Hex geometry (asymmetrical): even rows have FULL
     * slots at cols 1/3/5, odd rows at cols 0/2/4.
     *   • Scan  → (row 0, col 5)  top-right corner. Since the Scan/Photo merge the
     *             camera screen it opens carries photo + OCR flags, so no separate
     *             Photo hex is pinned by default (photoAction = null). Trip field
     *             menus still pass a photoAction — there the hex is a context
     *             trigger (fuel receipt / odometer OCR), not a plain Photo button.
     *   • 🎤    → rendered natively by SelectionAreaSheet as the HALF_RIGHT slot
     *             at row 3 (right edge, under the ✕) — GLOBAL push-to-talk.
     * Scan carries the CURRENT mode's action; only its POSITION is fixed.
     * Call AFTER clearAndReset() and BEFORE placeItems(): SYSTEM_FIXED priority
     * reserves the slots so the mode's content flows around them.
     */
    private fun placeSystemButtons(
        scanAction: String = "act_scan",
        photoAction: String? = null,
        scanLabel: String = "🔲\nScan",
        scanColor: String = "#00BCD4",
        photoLabel: String = "📷\nPhoto",
        photoColor: String = "#9C27B0",
    ) {
        val sys = mutableListOf<SystemElement>()
        sys.add(SystemElement(0, 5,
            mapOf("type" to "button", "label" to scanLabel, "color" to scanColor, "action" to scanAction),
            PRIORITIES.SYSTEM_FIXED, "scan"))
        if (photoAction != null) {
            sys.add(SystemElement(0, 3,
                mapOf("type" to "button", "label" to photoLabel, "color" to photoColor, "action" to photoAction),
                PRIORITIES.SYSTEM_FIXED, "photo"))
        }
        gridManager.placeSystemElements(sys, _isLeftHanded.value ?: false)
    }

    /** The active workspace mode, as a registry key for [VoiceCommandManager]. */
    fun currentVoiceMode(): String = when {
        _isRepairMode.value == true -> "repair"
        _isReceivingMode.value == true -> "receiving"
        _isInventoryMode.value == true -> "inventory"
        _isDeviceCheckMode.value == true -> "device_check"
        _isRestockMode.value == true -> "restock"
        _isTripMode.value == true -> "trip"
        else -> "main"
    }

    /**
     * Voice Commands P1/P2 — match an STT transcript against the current mode's
     * LOCAL command registry. On a hit: log "verstanden → …" and return the grid
     * action (nothing is executed here — the caller dispatches it via the normal
     * button path). On a miss return null; the caller decides what null means:
     * trip mode runs its destination search, other modes escalate to the Gemini
     * fallback in MainScreen and finally call [logVoiceMiss].
     */
    fun matchVoiceLocal(text: String): String? {
        if (text.isBlank()) return null
        val cmd = VoiceCommandManager.match(currentVoiceMode(), text) ?: return null
        addLog("🎤 verstanden: „$text\" → ${cmd.description}")
        return cmd.action
    }

    /** Console line for a Gemini-resolved command (P2). */
    fun logVoiceGemini(text: String, description: String) =
        addLog("🤖 verstanden: „$text\" → $description")

    /** Free-form voice status line (e.g. "🤖 frage KI…"). */
    fun logVoiceInfo(message: String) = addLog(message)

    /** Final miss — surface the text + what the user CAN say in this mode. */
    fun logVoiceMiss(text: String) {
        val mode = currentVoiceMode()
        addLog("🎤 „$text\" — kein Sprachbefehl erkannt")
        val labels = VoiceCommandManager.availableLabels(mode)
        if (labels.isEmpty()) addLog("· keine Sprachbefehle in diesem Modus")
        else addLog("· möglich: ${labels.joinToString(", ")}")
    }

    /** Fetch users from server and populate UserManager. */
    fun loadAvailableUsers() {
        viewModelScope.launch {
            Log.d("UserManager", "loadAvailableUsers: callback=${onFetchUsers != null}")
            val users = onFetchUsers?.invoke()
            Log.d("UserManager", "loadAvailableUsers: result=${users?.size ?: "null"}")
            if (users != null) {
                UserManager.setAvailableUsers(users)
                addLog("Loaded ${users.size} users")
            } else {
                addLog("Failed to load users")
            }
        }
    }

    /** Handle user selected from the dialog. */
    fun onUserSelected(user: AppUser) {
        if (_userDialogMode.value == "login") {
            // Login mode: need PIN verification
            pendingLoginUser = user
            _showUserDialog.value = false
            _showPinDialog.value = true
        } else {
            // View mode: switch viewing user immediately
            UserManager.switchView(user)
            addLog("👁 Viewing as ${user.name}")
            _showUserDialog.value = false
        }
    }

    /** Handle PIN entry result. */
    fun onPinSubmitted(pin: String) {
        val user = pendingLoginUser ?: return
        viewModelScope.launch {
            val result = onVerifyPin?.invoke(user.id, pin) ?: PinAuthResult(ok = false)
            if (result.ok) {
                UserManager.login(user)
                addLog("✅ Logged in as ${user.name}")
                _showPinDialog.postValue(false)
                pendingLoginUser = null
                // Seeded staff account → force a password change before proceeding.
                if (result.mustChangePassword || user.mustChangePassword) {
                    openForcedChangePasswordDialog()
                }
            } else {
                addLog("❌ Wrong PIN for ${user.name}")
                onHapticError?.invoke()
                // Keep PIN dialog open for retry
            }
        }
    }

    fun toggleHandedness() {
        _isLeftHanded.value = !(_isLeftHanded.value ?: false)
        lastWidth = 0f
    }

    fun setGridRowCount(rows: Int) {
        val clamped = rows.coerceIn(4, 12)
        _gridRowCount.value = clamped
        lastWidth = 0f
    }

    // --- SMART CONTEXT LOGIC (Main Menu) ---

    /**
     * Handle scan in idle/main menu state.
     * State machine: Idle -> ItemSelected/BoxSelected/LocationSelected -> Action
     */
    fun handleSmartScan(barcode: String, type: String) {
        val currentState = _smartContext.value ?: SmartContext.Idle
        val prefix = barcode.firstOrNull()?.lowercaseChar()

        viewModelScope.launch {
            when (currentState) {
                is SmartContext.Idle -> {
                    when {
                        // Place (p... or LOC...)
                        prefix == 'p' || barcode.startsWith("LOC", true) -> {
                            val loc = onLookupLocation?.invoke(barcode)
                            val name = loc?.name ?: barcode.takeLast(10)
                            val photo = onLoadItemPhoto?.invoke(barcode)
                            _smartContext.postValue(SmartContext.LocationSelected(barcode, loc, photo))
                            _activeSlotPhoto.postValue(photo)
                            _smartStatus.postValue("AT: $name")
                            addLog("📍 Selected: $name")
                            renderSmartGrid(SmartContext.LocationSelected(barcode, loc, photo))
                            // Fetch and display location contents
                            if (loc != null) {
                                fetchAndDisplayLocationContents(barcode, loc.id)
                            } else if (barcode.startsWith("p") && barcode.length == 19) {
                                // Fallback: extract Odoo ID from legacy p-code
                                val id = barcode.substring(1).trimStart('0')
                                if (id.isNotEmpty()) fetchAndDisplayLocationContents(barcode, id)
                            } else if (barcode.startsWith("p-") && barcode.length == 38) {
                                // SmartTag UUID location — no numeric Odoo ID available
                                addLog("📍 UUID location: ${barcode.substring(2)}")
                            }
                        }
                        // Box (b...)
                        prefix == 'b' -> {
                            val photo = onLoadItemPhoto?.invoke(barcode)
                            _smartContext.postValue(SmartContext.BoxSelected(barcode, photo))
                            _activeSlotPhoto.postValue(photo)
                            _smartStatus.postValue("BOX: ${barcode.takeLast(6)}")
                            addLog("Selected Box: $barcode")
                            renderSmartGrid(SmartContext.BoxSelected(barcode, photo))
                        }
                        // Default: Item (i... or numeric EAN)
                        else -> {
                            val prod = onLookupProduct?.invoke(barcode)
                            val name = prod?.name ?: barcode
                            val photo = onLoadItemPhoto?.invoke(barcode)
                            _smartContext.postValue(SmartContext.ItemSelected(barcode, prod, photo))
                            _activeSlotPhoto.postValue(photo)
                            if (prod != null) {
                                val qtyStr = if (prod.qtyAvailable % 1.0 == 0.0) prod.qtyAvailable.toInt().toString() else "%.1f".format(prod.qtyAvailable)
                                _smartStatus.postValue("${prod.defaultCode}: $qtyStr")
                                addLog("🔧 ${prod.name}")
                                addLog("📊 Stock: $qtyStr")
                            } else {
                                _smartStatus.postValue("ITEM: ${barcode.takeLast(10)}")
                                addLog("Selected Item: $name")
                            }
                            renderSmartGrid(SmartContext.ItemSelected(barcode, prod, photo))
                        }
                    }
                }

                is SmartContext.ItemSelected -> {
                    when {
                        // Item + Place = PUT
                        prefix == 'p' || barcode.startsWith("LOC", true) -> {
                            performMoveAction(currentState.barcode, barcode, "PUT_ITEM")
                        }
                        // Item + Box = PACK
                        prefix == 'b' -> {
                            performMoveAction(currentState.barcode, barcode, "PACK_ITEM")
                        }
                        // Item + Item = switch selection
                        else -> {
                            _smartContext.value = SmartContext.Idle
                            handleSmartScan(barcode, type)
                        }
                    }
                }

                is SmartContext.LocationSelected -> {
                    when {
                        // Place + Item = PICK
                        prefix == 'i' || (barcode.all { it.isDigit() } && barcode.length > 5) -> {
                            performMoveAction(barcode, currentState.barcode, "PICK_ITEM")
                        }
                        // Place + Box = PICK BOX
                        prefix == 'b' -> {
                            performMoveAction(barcode, currentState.barcode, "PICK_BOX")
                        }
                        // Place + Place = switch
                        else -> {
                            _smartContext.value = SmartContext.Idle
                            handleSmartScan(barcode, type)
                        }
                    }
                }

                is SmartContext.BoxSelected -> {
                    when {
                        // Box + Place = PUT BOX
                        prefix == 'p' || barcode.startsWith("LOC", true) -> {
                            performMoveAction(currentState.barcode, barcode, "PUT_BOX")
                        }
                        // Box + Item = PACK (put item into box)
                        prefix == 'i' || (barcode.all { it.isDigit() } && barcode.length > 5) -> {
                            performMoveAction(barcode, currentState.barcode, "PACK_ITEM")
                        }
                        // Box + Box = switch
                        else -> {
                            _smartContext.value = SmartContext.Idle
                            handleSmartScan(barcode, type)
                        }
                    }
                }
            }
        }
    }

    private fun fetchAndDisplayLocationContents(barcode: String, locationId: String) {
        viewModelScope.launch {
            // 1. Local inventory records (green = PDA counted)
            try {
                val localRecords = onLoadInventoryRecords?.invoke(barcode) ?: emptyList()
                if (localRecords.isNotEmpty()) {
                    addLog("🟢 PDA records (${localRecords.size}):")
                    localRecords.forEach { rec ->
                        val qtyStr = if (rec.quantity % 1.0 == 0.0) rec.quantity.toInt().toString() else "%.1f".format(rec.quantity)
                        addLog("  🟢 ${rec.productName.take(20)}: $qtyStr")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SMART_SCAN", "Local records error: ${e.message}")
            }

            // 2. Server contents (blue = server confirmed)
            try {
                val result = onFetchLocationContents?.invoke(locationId)
                if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                    val raw = result.data?.trim() ?: ""
                    if (raw.isNotEmpty() && raw != "null" && raw != "[]") {
                        val arr = org.json.JSONArray(raw)
                        addLog("🔵 Server (${arr.length()}):")
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val name = obj.optString("product_name", "Unknown").take(20)
                            val qty = obj.optDouble("quantity", 0.0)
                            val qtyStr = if (qty % 1.0 == 0.0) qty.toInt().toString() else "%.1f".format(qty)
                            addLog("  🔵 $name: $qtyStr")
                        }
                    } else {
                        addLog("🔵 Server: empty")
                    }
                } else if (result is com.xelth.eckwms_movfast.api.ScanResult.Error) {
                    addLog("🟡 Server: ${result.message}")
                }
            } catch (e: Exception) {
                addLog("🟡 Server error: ${e.message}")
            }
        }
    }

    private fun performMoveAction(item: String, target: String, actionType: String) {
        viewModelScope.launch {
            addLog("ACTION: $actionType")
            addLog("  $item -> $target")

            val payload = JSONObject().apply {
                put("item", item)
                put("target", target)
                put("action", actionType)
                put("timestamp", System.currentTimeMillis())
            }

            onRepairEventSend?.invoke("SMART_OPS", actionType, payload.toString())

            _smartStatus.postValue("OK: $actionType")
            resetSmartContext()
            delay(2000)
            _smartStatus.postValue("")
        }
    }

    fun resetSmartContext() {
        _smartContext.postValue(SmartContext.Idle)
        _activeSlotPhoto.postValue(null)
        _smartStatus.postValue("")
        initializeGrid()
    }

    private fun renderSmartGrid(ctx: SmartContext) {
        val uiItems = mutableListOf<Map<String, Any>>()

        // 1. Info button (shows what's selected)
        val infoLabel = when (ctx) {
            is SmartContext.ItemSelected -> "ITEM\n${ctx.product?.defaultCode ?: "..."}"
            is SmartContext.LocationSelected -> "LOC\n${ctx.location?.name ?: "..."}"
            is SmartContext.BoxSelected -> "BOX\n${ctx.barcode.takeLast(6)}"
            else -> ""
        }
        uiItems.add(mapOf("type" to "button", "label" to infoLabel, "color" to "#4CAF50", "action" to "act_noop"))

        // 2. Context-specific actions
        when (ctx) {
            is SmartContext.ItemSelected -> {
                uiItems.add(mapOf("type" to "button", "label" to "INFO", "color" to "#2196F3", "action" to "act_smart_info"))
            }
            is SmartContext.LocationSelected -> {
                uiItems.add(mapOf("type" to "button", "label" to "COUNT", "color" to "#795548", "action" to "act_jump_inventory"))
            }
            else -> {}
        }

        // 3. Photo & Scan are now pinned via placeSystemButtons (fixed positions).

        gridManager.clearAndReset()
        placeSystemButtons(scanAction = "capture_barcode")
        gridManager.placeItems(uiItems, priority = PRIORITIES.SCAN_BUTTON)

        // 4. X button — native half-slot
        _exitButton.postValue(HalfButtonState("X", "#F44336", "act_smart_cancel"))

        updateRenderCells()
    }

    fun onButtonClick(action: String): String {
        // 🎤 Voice command is GLOBAL push-to-talk, driven by onMicPress/onMicRelease
        // in MainScreen. A plain tap falls through to here as a harmless no-op.
        if (action == "voice_command") return "handled"
        addLog("Button clicked: $action")

        // User button — works in ALL modes (short press = switch view)
        if (action == "act_user_profile") {
            if (!UserManager.isLoggedIn()) {
                _userDialogMode.value = "login"
            } else {
                _userDialogMode.value = "view"
            }
            _showUserDialog.value = true
            return "handled"
        }

        if (_isRestockMode.value == true) {
            return handleRestockButtonClick(action)
        }

        if (_isInventoryMode.value == true) {
            return handleInventoryButtonClick(action)
        }

        if (_isRepairMode.value == true) {
            return handleRepairButtonClick(action)
        }

        if (_isReceivingMode.value == true) {
            return handleReceivingButtonClick(action)
        }

        if (_isTripMode.value == true) {
            return handleTripButtonClick(action)
        }

        if (_isDeviceCheckMode.value == true) {
            return handleDeviceCheckButtonClick(action)
        }

        if (_isNetworkMode.value == true) {
            return handleNetworkButtonClick(action)
        }

        // Smart Context actions (main menu)
        if (action == "act_smart_cancel") {
            resetSmartContext()
            return "handled"
        }
        if (action == "act_jump_inventory") {
            val ctx = _smartContext.value
            if (ctx is SmartContext.LocationSelected) {
                resetSmartContext()
                enterInventoryMode()
                onInventoryScan(ctx.barcode, false)
            }
            return "handled"
        }
        if (action == "act_noop" || action == "act_smart_info") {
            return "handled"
        }
        // Root grid ✕ short press: already at the root, nowhere to step back to.
        if (action == "act_exit") {
            return "handled"
        }

        if (action == "navigate_repair") {
            enterRepairMode()
            return "handled"
        }

        if (action == "navigate_receiving") {
            enterReceivingMode()
            return "handled"
        }

        if (action == "navigate_device_check") {
            enterDeviceCheckMode()
            return "handled"
        }

        if (action == "navigate_restock") {
            enterRestockMode()
            return "handled"
        }

        if (action == "navigate_inventory") {
            enterInventoryMode()
            return "handled"
        }

        if (action == "navigate_trips") {
            enterTripMode()
            return "handled"
        }

        return action
    }

    /**
     * Handle long-press on grid buttons.
     * Returns navigation action string or "handled".
     */
    fun onButtonLongClick(action: String): String {
        addLog("Long press: $action")
        // User button long press = Login (Anmeldung) in ALL modes
        if (action == "act_user_profile") {
            _userDialogMode.value = "login"
            _showUserDialog.value = true
            return "handled"
        }
        return when (action) {
            // Long-press the red ✕ half-slot (SelectionAreaSheet dispatches
            // act_minimize_app for it regardless of the short-press action) =
            // minimize the app from ANY menu depth, keeping mode/state intact.
            "act_minimize_app", "act_exit" -> "minimize_app"
            "act_photo" -> "capture_photo_continuous"
            // Long-press the merged Scan button = straight into PHOTO: the combined
            // camera opens with the barcode analyzer OFF (torch on), blue flag ready.
            "act_scan", "capture_barcode" -> "capture_photo_direct"
            // 🚗 long-press = trip mode with the LAST TRIPS listed in the
            // console (offline view straight from Room — no server needed).
            "navigate_trips" -> {
                enterTripMode()
                "trip_history"
            }
            // 🧾 long-press = the last logged expenses in the trip console.
            "trip_fuel" -> "trip_expense_history"
            else -> onButtonClick(action) // fallback to normal click
        }
    }

    // --- REPAIR MODE LOGIC ---

    fun enterRepairMode() {
        _isRepairMode.value = true
        // Restore saved bindings from persistence
        val saved = onLoadRepairSlots?.invoke() ?: emptyList()
        val savedMap = saved.toMap() // index -> barcode

        // NON-DESTRUCTIVE: update existing slot objects instead of recreating them.
        // This preserves in-memory photos and other transient state.
        slots.forEach { slot ->
            val savedBarcode = savedMap[slot.index]
            if (savedBarcode != null) {
                slot.barcode = savedBarcode
                slot.isBound = true
                if (slot.history.isEmpty()) slot.history.add("Restored from DB")
                // Restore photo from disk if not already in memory
                if (slot.photo == null) {
                    slot.photo = onLoadRepairPhoto?.invoke(slot.index)
                    if (slot.photo != null) {
                        // Also add to allPhotos so it appears in the avatar picker
                        if (slot.allPhotos.isEmpty()) slot.allPhotos.add(slot.photo!!)
                        Log.d("RepairMode", "Restored photo for slot #${slot.index} from disk")
                    }
                }
            } else if (!slot.isBound) {
                slot.barcode = null
                slot.photo = null
            }
            slot.isActive = false
        }

        val boundCount = slots.count { it.isBound }
        _repairStatus.value = if (boundCount > 0) "Restored $boundCount devices" else "Select a slot to bind device"
        addLog("Entered Repair Mode ($boundCount saved devices)")
        renderRepairGrid()
    }

    fun exitRepairMode() {
        _isRepairMode.value = false
        activeSlotJob?.cancel()
        pendingAction = RepairAction.None
        slotWaitingForBind = null
        lastSentAction = null
        // Don't clear slots — they're persisted. Just reset runtime state.
        slots.forEach { it.isActive = false }
        addLog("Exited Repair Mode")
        initializeGrid()
    }

    private fun handleRepairButtonClick(action: String): String {
        when {
            action == "act_exit" -> {
                exitRepairMode()
                return "handled"
            }
            action == "act_photo" -> return "capture_photo"
            action == "act_scan" -> return "capture_barcode"
            action == "act_undo" -> {
                undoLastAction()
                return "handled"
            }
            action.startsWith("slot_") -> {
                val index = action.removePrefix("slot_").toIntOrNull()
                if (index != null && index in slots.indices) {
                    handleSlotClick(index)
                }
                return "handled"
            }
        }
        return "handled"
    }

    private fun handleSlotClick(index: Int) {
        val slot = slots[index]
        Log.d("RepairMode", "slotClick(#$index): bound=${slot.isBound}, barcode=${slot.barcode?.takeLast(6)}, waitBind=$slotWaitingForBind, pending=${pendingAction::class.simpleName}")

        // If we have a pending action, apply it to a bound slot
        if (pendingAction !is RepairAction.None) {
            if (slot.isBound) {
                applyPendingActionToSlot(slot)
            } else {
                _repairStatus.value = "Slot empty! Bind device first."
            }
            return
        }

        if (!slot.isBound) {
            // Toggle: if already waiting for bind on this slot, cancel it
            if (slotWaitingForBind == index) {
                slotWaitingForBind = null
                _repairStatus.value = "Cancelled"
                renderRepairGrid()
                return
            }
            slotWaitingForBind = index
            _repairStatus.value = "Scan device barcode for Slot #${index + 1}"
            renderRepairGrid()
        } else {
            activateSlot(index)
        }
    }

    fun onRepairScan(barcode: String) {
        val slotsSummary = slots.filter { it.isBound }.joinToString { "#${it.index}=${it.barcode?.takeLast(6)}" }
        Log.d("RepairMode", "onRepairScan: barcode=${barcode.takeLast(10)}, isRepairMode=${_isRepairMode.value}, waitBind=$slotWaitingForBind, bound=[$slotsSummary]")
        // Auto-enter repair mode if barcode matches a saved device
        if (_isRepairMode.value != true) {
            val savedSlots = onLoadRepairSlots?.invoke() ?: emptyList()
            Log.d("RepairMode", "Saved slots: ${savedSlots.map { "${it.first}:${it.second.takeLast(6)}" }}")
            val match = savedSlots.find { it.second == barcode }
            if (match != null) {
                Log.d("RepairMode", "MATCH found: slot #${match.first} -> entering repair mode")
                addLog("Auto-repair: $barcode -> Slot #${match.first}")
                enterRepairMode()
                val slotIndex = match.first
                if (slotIndex in slots.indices && slots[slotIndex].isBound) {
                    activateSlot(slotIndex)
                    _repairStatus.value = "AUTO: ${barcode}"
                }
            } else {
                Log.d("RepairMode", "No match in saved slots -> Smart Context")
                handleSmartScan(barcode, "barcode")
            }
            return
        }

        // 1. Binding mode
        if (slotWaitingForBind != null) {
            val index = slotWaitingForBind!!
            Log.d("RepairMode", "BIND: slot #$index <- ${barcode.takeLast(10)}")
            val dupes = slots.filter { it.barcode == barcode }
            if (dupes.isNotEmpty()) {
                Log.d("RepairMode", "BIND: clearing dupes: ${dupes.map { "#${it.index} photo=${it.photo != null}" }}")
            }
            slots.forEach { if (it.barcode == barcode) clearSlot(it.index) }
            slots[index].barcode = barcode
            slots[index].isBound = true
            slots[index].history.add("Device bound: ${barcode.takeLast(8)}")
            slotWaitingForBind = null
            persistSlots()
            activateSlot(index)

            // Bind orphaned photos in DB to this device barcode
            viewModelScope.launch { onBindSlotPhotos?.invoke(index, barcode) }

            // Trigger device_bound event to auto-create repair order on server
            onRepairEventSend?.invoke(barcode, "device_bound", "{\"slot\": $index}")

            _repairStatus.value = "Bound: $barcode — Take ID photo"
            addLog("Slot #$index bound to $barcode")
            // Auto-trigger camera for identification photo
            _navigateToCamera.value = true
            return
        }

        // 2. Scan matches existing slot -> activate
        val existingSlot = slots.find { it.barcode == barcode }
        if (existingSlot != null) {
            Log.d("RepairMode", "EXISTING: barcode matches slot #${existingSlot.index}, activating")
            activateSlot(existingSlot.index)
            return
        }

        // 3. Active slot -> data scan
        val active = slots.find { it.isActive }
        if (active != null) {
            Log.d("RepairMode", "DATA_SCAN: ${barcode.takeLast(10)} -> active slot #${active.index} (${active.barcode?.takeLast(6)})")
            sendPartScan(active.barcode!!, barcode)
            resetActiveTimer(active.index)
        } else {
            // 4. No active -> pending
            pendingAction = RepairAction.PendingBarcode(barcode)
            _repairStatus.value = "Barcode scanned. Select slot to attach."
            renderRepairGrid()
        }
    }

    fun onRepairPhotoCaptured(bitmap: Bitmap) {
        val active = slots.find { it.isActive }
        Log.d("RepairMode", "onRepairPhotoCaptured: active=${active?.index}, bitmap.recycled=${bitmap.isRecycled}")
        if (active != null) {
            // Copy bitmap — original may be recycled by BitmapCache
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            // Track all photos for avatar picker (limit 20)
            if (active.allPhotos.size < 20) active.allPhotos.add(copy)

            // Save to CAS-id immutable storage (id = ContentHash of the bytes)
            val uuid = onSavePhoto?.invoke(active.index, copy)
            uuid?.let { active.photoUuids.add(it) }

            // First photo becomes the background, subsequent don't change it
            if (active.photo == null) {
                active.photo = copy
                onSaveRepairPhoto?.invoke(active.index, copy) // legacy slot_N.webp for quick restore
                _activeSlotPhoto.value = copy
                Log.d("RepairMode", "First photo → background for slot ${active.index}")
            } else {
                Log.d("RepairMode", "Photo #${active.allPhotos.size} for slot ${active.index} (background unchanged)")
            }
            active.history.add("Photo attached")
            _activeSlotPhotosList.value = active.allPhotos.toList()
            _activeSlotHistory.value = active.history.toList()

            uploadPhoto(active.barcode!!, bitmap)
            _repairStatus.value = "Photo sent -> ${active.barcode} (#${active.allPhotos.size})"
            resetActiveTimer(active.index)
        } else {
            pendingAction = RepairAction.PendingPhoto(bitmap)
            _repairStatus.value = "Photo taken. Select slot to attach."
            renderRepairGrid()
        }
    }

    private fun activateSlot(index: Int) {
        slots.forEach { it.isActive = false }
        slots[index].isActive = true
        activeSlotTimeoutMs = INITIAL_SLOT_TIMEOUT_MS // reset timeout for new slot
        _repairStatus.value = "Active: ${slots[index].barcode}"
        val photo = slots[index].photo
        Log.d("RepairMode", "activateSlot($index): photo=${if (photo != null) "exists (recycled=${photo.isRecycled})" else "null"}")
        _activeSlotPhoto.value = photo
        _activeSlotHistory.value = slots[index].history.toList()
        _activeSlotPhotosList.value = slots[index].allPhotos.toList()
        renderRepairGrid()
        startActiveTimer(index)
    }

    /** Restart timer AND double its duration (called on each action with active slot) */
    private fun resetActiveTimer(index: Int) {
        activeSlotTimeoutMs = (activeSlotTimeoutMs * 2).coerceAtMost(MAX_SLOT_TIMEOUT_MS)
        Log.d("RepairMode", "Timer reset: ${activeSlotTimeoutMs / 1000}s for slot #$index")
        startActiveTimer(index)
    }

    /** Start deactivation timer at current duration (without doubling) */
    private fun startActiveTimer(index: Int) {
        activeSlotJob?.cancel()
        activeSlotJob = viewModelScope.launch {
            delay(activeSlotTimeoutMs)
            if (index in slots.indices && slots[index].isActive) {
                slots[index].isActive = false
                _activeSlotPhoto.postValue(null)
                _repairStatus.postValue("Timeout. Slot deactivated.")
                renderRepairGrid()
            }
        }
    }

    private fun applyPendingActionToSlot(slot: RepairSlot) {
        when (val action = pendingAction) {
            is RepairAction.PendingPhoto -> {
                uploadPhoto(slot.barcode!!, action.bitmap)
                _repairStatus.value = "Photo attached to ${slot.barcode}"
            }
            is RepairAction.PendingBarcode -> {
                sendPartScan(slot.barcode!!, action.code)
                _repairStatus.value = "Barcode attached to ${slot.barcode}"
            }
            else -> {}
        }
        pendingAction = RepairAction.None
        activateSlot(slot.index)
    }

    private fun sendPartScan(deviceBarcode: String, partBarcode: String) {
        addLog("Data scan '$partBarcode' -> device '$deviceBarcode'")
        _repairStatus.value = "Sent: $partBarcode -> $deviceBarcode"
        lastSentAction = LastRepairAction(deviceBarcode, "part_scan", partBarcode)

        val active = slots.find { it.isActive }
        if (active != null) {
            active.history.add("Added part: ${partBarcode.takeLast(8)}")
            _activeSlotHistory.value = active.history.toList()
        }

        onRepairEventSend?.invoke(deviceBarcode, "part_scan", partBarcode)
        renderRepairGrid()
    }

    private fun uploadPhoto(deviceBarcode: String, bitmap: Bitmap) {
        addLog("Uploading photo for device: $deviceBarcode")
        lastSentAction = LastRepairAction(deviceBarcode, "photo", "", bitmap)
        onRepairPhotoUpload?.invoke(deviceBarcode, bitmap)
        renderRepairGrid()
    }

    private fun undoLastAction() {
        val last = lastSentAction
        if (last == null) {
            _repairStatus.value = "Nothing to undo"
            return
        }
        // Send undo event to server
        addLog("UNDO: ${last.eventType} for ${last.targetDeviceId}")
        onRepairEventSend?.invoke(last.targetDeviceId, "undo_${last.eventType}", last.data)
        _repairStatus.value = "Undone: ${last.eventType} -> ${last.targetDeviceId}"
        lastSentAction = null
        renderRepairGrid()
    }

    private fun clearSlot(index: Int) {
        if (index in slots.indices) {
            Log.d("RepairMode", "clearSlot(#$index): barcode=${slots[index].barcode?.takeLast(6)}, photo=${slots[index].photo != null}, allPhotos=${slots[index].allPhotos.size}, uuids=${slots[index].photoUuids.size}")
            slots[index].isBound = false
            slots[index].isActive = false
            slots[index].barcode = null
            slots[index].photo?.recycle()
            slots[index].photo = null
            slots[index].allPhotos.forEach { it.recycle() }
            slots[index].allPhotos.clear()
            slots[index].photoUuids.clear()
            slots[index].history.clear()
            onDeleteRepairPhoto?.invoke(index)
            // Delete UUID-based photos from DB + disk
            viewModelScope.launch { onDeleteSlotPhotos?.invoke(index) }
        }
        persistSlots()
    }

    /** Check if a slot action (e.g. "slot_3") refers to a bound slot */
    fun isSlotBound(action: String): Boolean {
        if (!action.startsWith("slot_")) return false
        val index = action.removePrefix("slot_").toIntOrNull() ?: return false
        return index in slots.indices && slots[index].isBound
    }

    /** Get the barcode of a slot for display in confirmation dialog */
    fun getSlotBarcode(action: String): String {
        val index = action.removePrefix("slot_").toIntOrNull() ?: return ""
        return if (index in slots.indices) slots[index].barcode ?: "" else ""
    }

    /** Get action string of the currently active slot, or null */
    fun getActiveSlotAction(): String? {
        val active = slots.find { it.isActive && it.isBound } ?: return null
        return "slot_${active.index}"
    }

    /** Get all photos for the active slot (for background picker) */
    fun getActiveSlotPhotos(): List<Bitmap> {
        val active = slots.find { it.isActive && it.isBound } ?: return emptyList()
        return active.allPhotos.toList()
    }

    /** Change background photo of active slot to one from its allPhotos list */
    fun changeSlotBackground(photoIndex: Int) {
        val active = slots.find { it.isActive && it.isBound } ?: return
        if (photoIndex !in active.allPhotos.indices) return
        val newBg = active.allPhotos[photoIndex]
        active.photo = newBg
        onSaveRepairPhoto?.invoke(active.index, newBg)
        _activeSlotPhoto.value = newBg
        addLog("Background changed to photo #${photoIndex + 1} for slot #${active.index}")
    }

    /** Public delete: clears slot, deactivates, updates UI */
    fun deleteSlot(action: String) {
        val index = action.removePrefix("slot_").toIntOrNull() ?: return
        if (index !in slots.indices) return
        val barcode = slots[index].barcode
        activeSlotJob?.cancel()
        clearSlot(index)
        _activeSlotPhoto.value = null
        _repairStatus.value = "Slot #${index + 1} cleared ($barcode)"
        addLog("Deleted slot #$index ($barcode)")
        renderRepairGrid()
    }

    private fun persistSlots() {
        val bound = slots.filter { it.isBound && it.barcode != null }
            .map { Pair(it.index, it.barcode!!) }
        onSaveRepairSlots?.invoke(bound)
    }

    private fun renderRepairGrid() {
        val uiItems = mutableListOf<Map<String, Any>>()

        // Action button (top-left): UNDO. Photo/Scan are pinned via placeSystemButtons.
        val undoColor = if (lastSentAction != null) "#FF9800" else "#37474F"
        uiItems.add(mapOf("type" to "button", "label" to "↩️\nUNDO", "color" to undoColor, "action" to "act_undo"))
        val photoColor = if (pendingAction is RepairAction.PendingPhoto) "#FF9800" else "#9C27B0"

        // Device slots
        slots.forEach { slot ->
            val color = when {
                slot.isActive -> "#4CAF50"
                slot.index == slotWaitingForBind -> "#FFEB3B"
                slot.isBound -> "#2196F3"
                else -> "#333333"
            }
            val label = when {
                slot.isActive -> "${slot.barcode ?: ""}"
                slot.isBound -> slot.barcode ?: ""
                slot.index == slotWaitingForBind -> "SCAN\nDEVICE..."
                else -> "+"
            }
            uiItems.add(mapOf(
                "type" to "button",
                "label" to label,
                "color" to color,
                "action" to "slot_${slot.index}"
            ))
        }

        gridManager.clearAndReset()
        placeSystemButtons(scanLabel = "🔲\nSCAN")
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT button — native half-slot
        _exitButton.postValue(HalfButtonState("✕", "#F44336", "act_exit"))

        updateRenderCells()
    }

    // --- TRIP MODE LOGIC (Fahrtenbuch) ---

    fun enterTripMode() {
        _isTripMode.value = true
        _tripAutoDetect.value = com.xelth.eckwms_movfast.utils.SettingsManager.getTripAutoDetect()
        _tripStatus.value = "Fahrten — Stadt wählen oder tippen/🎤"
        tripSelectedCity = null
        // Re-hydrate the field hexes from an armed intent: the intent survives
        // process death (prefs), the ViewModel fields don't — after a restart
        // the driver saw EMPTY plate/km/purpose although the armed intent still
        // carried them ("всё куда-то делось", 2026-07-13). Armed values show
        // yellow ("auto") — user-set values in this session stay green.
        com.xelth.eckwms_movfast.trips.TripManager.peekTripIntent()?.let { ti ->
            if (tripPlate.isNullOrBlank()) ti.plate?.let { tripPlate = it; tripPlateSource = "auto" }
            if (tripKm.isNullOrBlank()) ti.odoKm?.let {
                tripKm = if (it == Math.floor(it)) it.toLong().toString() else it.toString()
                tripKmSource = "auto"
            }
            if (tripPurpose.isNullOrBlank()) ti.label.takeIf { l -> l.isNotBlank() }?.let {
                tripPurpose = it; tripPurposeSource = "auto"
            }
        }
        addLog("Entered Trip Mode")
        renderTripGrid()
        refreshTripDestinations() // load the city chips
    }

    /** Live text/voice search over ALL tickets — updates ONLY the console list
     *  (no grid re-render on each keystroke). Blank query → back to city view. */
    fun searchTripDestinations(query: String) {
        viewModelScope.launch {
            val res = onFetchDestinations?.invoke(query.ifBlank { null }, null, false) ?: return@launch
            if (query.isBlank()) {
                tripSelectedCity = null
                _tripCities.value = res.cities
                _tripDestinations.value = emptyList()
                _tripStatus.value = "Fahrten — Stadt wählen oder tippen/🎤"
                if (_isTripMode.value == true) renderTripGrid()
            } else {
                _tripDestinations.value = res.results
                _tripStatus.value = "Suche „$query\" — ${res.results.size} Treffer"
            }
        }
    }

    /** Smart Gemini fallback — ONLY on explicit user tap (cost-controlled).
     *  Corrects mis-heard/typo queries ("treutlingen" → "Reutlingen"). */
    fun aiSearchTripDestinations(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _tripStatus.value = "🤖 KI sucht „$query\"…"
            val res = onFetchDestinations?.invoke(query, null, true)
            when {
                res == null -> _tripStatus.value = "🤖 KI nicht verfügbar"
                res.results.isEmpty() -> _tripStatus.value = "🤖 keine Treffer für „$query\""
                else -> {
                    _tripDestinations.value = res.results
                    _tripStatus.value = "🤖 ${res.results.size} Treffer für „$query\""
                }
            }
        }
    }

    /** Load the trip-mode console data: cities (chips) + the ticket list for a
     *  typed query (fuzzy over ALL tickets) or a selected city. */
    fun refreshTripDestinations(query: String? = null, city: String? = null) {
        tripSelectedCity = city
        viewModelScope.launch {
            val res = onFetchDestinations?.invoke(query, city, false) ?: return@launch
            _tripCities.value = res.cities
            _tripDestinations.value = res.results
            _tripStatus.value = when {
                !query.isNullOrBlank() -> "Suche: \"$query\" — ${res.results.size} Treffer"
                city != null -> "$city — ${res.results.size} Tickets"
                else -> "Fahrten — Stadt wählen oder tippen/🎤"
            }
            if (_isTripMode.value == true) renderTripGrid()
        }
    }

    fun exitTripMode() {
        _isTripMode.value = false
        tripSettingsMenu = false
        tripFieldMenu = null
        addLog("Exited Trip Mode")
        initializeGrid()
    }

    /** Live recording state — set from MainScreen observing TripManager.activeTrip. */
    fun setTripRecording(recording: Boolean) {
        if (_tripRecording.value != recording) {
            _tripRecording.value = recording
            _tripStatus.value = if (recording) "🚗 Trip running" else "Trips"
            if (_isTripMode.value == true) {
                renderTripGrid()
            } else if (atRootMenu()) {
                // Refresh the main menu so the Trips hex flips its car/colour
                // indicator (red/idle ↔ green/recording).
                initializeGrid()
            }
        }
    }

    /** True when no workspace mode is active (the root main-menu grid is showing) —
     *  the only state where a background re-render of the main grid is safe. */
    private fun atRootMenu(): Boolean =
        _isRepairMode.value != true && _isRestockMode.value != true &&
        _isInventoryMode.value != true && _isReceivingMode.value != true &&
        _isTripMode.value != true && _isDeviceCheckMode.value != true &&
        _isNetworkMode.value != true

    /** Auto-detect toggle reflection — set from MainScreen after the permission. */
    fun setTripAutoDetect(enabled: Boolean) {
        _tripAutoDetect.value = enabled
        if (_isTripMode.value == true) renderTripGrid()
    }

    /** External re-render hook (MainScreen) — e.g. after arming a start intent
     *  so the Start hex flips to the yellow "Armed" state. Also refreshes the
     *  ROOT menu when it is showing: the main-menu Trips hex carries the armed
     *  checklist now (voice-armed intents happen from the main menu). */
    fun refreshTripGrid() {
        if (_isTripMode.value == true) renderTripGrid()
        else if (atRootMenu()) initializeGrid()
    }

    // Trip SETTINGS sub-menu — holds the auto-detect + live-server toggles so
    // they aren't fat-fingered on the main grid. `⚙️ Settings` opens it; ◀ back.
    private var tripSettingsMenu = false

    // ── Trip FIELD entry (Kennzeichen / Kilometerstand / Purpose) ──────────────
    // Three field buttons on the trip grid, each opening its own hex sub-menu
    // with known values + a Photo→OCR key + a paged hex-KEYBOARD (all free hexes
    // become keys). No bottom text field. Button colour = state:
    //   red   = empty (nothing chosen)
    //   yellow= auto (system pulled a previous value, not user-confirmed)
    //   green = user (explicitly set/typed/OCR'd this trip)
    private var tripFieldMenu: String? = null   // null | "plate" | "km" | "purpose"
    // 🧾 Expense entry lives ON the hexes (no modal): the numeric keypad edits the
    // odometer (shared tripEntryBuffer), the global 📷 becomes the receipt capture,
    // a hex OCRs the exact odometer, and Save writes the expense event. ONE button
    // covers the whole family — a receipt photo alone is enough; picking a type
    // (⛽/🅿/🛣) just annotates it and rides along to the server.
    private var tripFuelMenu = false
    private var tripFuelSource = "estimated"     // estimated | manual | scanned
    private var tripFuelReceiptOk = false
    private var tripExpenseType: String? = null  // null (plain receipt) | fuel | parking | toll
    private var tripKeyPage = 0
    private var tripEntryBuffer = ""
    private var tripPlate: String? = null;   private var tripPlateSource = "empty"
    private var tripKm: String? = null;      private var tripKmSource = "empty"
    private var tripPurpose: String? = null; private var tripPurposeSource = "empty"
    // CAS ids of the OCR photos captured for plate/km (evidence). Uploaded when
    // the photo is taken; attached to the trip at start so the shot isn't lost.
    private var tripPlatePhotoId: String? = null
    private var tripKmPhotoId: String? = null
    // Known/previous values shown as quick-pick hexes (populated from MainScreen).
    var tripKnownPlates: List<String> = emptyList()
    var tripKnownPurposes: List<String> = emptyList()
    // "Allow all the time" location state (synced from MainScreen — the
    // ViewModel has no context). Drives the ✓/✕ BG-Standort settings hex.
    var tripBgLocationGranted: Boolean = true

    private fun fieldColor(source: String) = when (source) {
        "user" -> "#2E7D32"   // green
        "auto" -> "#F9A825"   // yellow
        else -> "#C62828"     // red
    }
    private fun fieldValue(field: String) = when (field) {
        "plate" -> tripPlate; "km" -> tripKm; else -> tripPurpose
    }
    private fun fieldName(field: String) = when (field) {
        "plate" -> "Plate"; "km" -> "Km"; else -> "Purpose"
    }
    // Estimated CURRENT odometer while a trip is recording (start reading +
    // track distance so far). Shown on the Km hex with a ≈ prefix instead of
    // the stale pre-set value; refreshed by MainScreen once a minute, cleared
    // when the trip ends. Odometer photos stay the authoritative number.
    var tripKmEstimate: String? = null
        private set
    fun setTripKmEstimate(v: String?) {
        if (tripKmEstimate == v) return
        tripKmEstimate = v
        if (_isTripMode.value == true) renderTripGrid()
    }

    /** Label for a field button on the trip grid. Once a value is set the hex
     *  shows the VALUE (icon + value) instead of the "Plate/Km/Purpose" caption —
     *  yellow/green buttons carry data, so the caption is redundant. Long values are
     *  truncated to what fits on a hex. */
    private fun fieldButtonLabel(field: String, icon: String, name: String): String {
        // Live estimate wins on the Km hex while a trip is recording.
        if (field == "km") tripKmEstimate?.let { return "$icon\n≈$it" }
        val v = fieldValue(field)?.trim()
        if (v.isNullOrBlank()) return "$icon\n$name"
        val shown = if (v.length > 10) v.take(9) + "…" else v
        return "$icon\n$shown"
    }
    private fun fieldKnown(field: String) = when (field) {
        "plate" -> tripKnownPlates; "purpose" -> tripKnownPurposes; else -> emptyList()
    }
    // Keyboard char set per field (paged). Plate = A–Z/0–9/space/hyphen; km = digits+dot;
    // purpose = free text.
    private fun keySetFor(field: String): List<String> = when (field) {
        "km" -> "0123456789.".map { it.toString() }
        // Digits FIRST so they always land on page 1 (plates carry numbers too).
        "plate" -> (('0'..'9').map { it.toString() } + ('A'..'Z').map { it.toString() } + listOf(" ", "-"))
        else -> (('0'..'9').map { it.toString() } + ('A'..'Z').map { it.toString() } + listOf(" ", ".", ",", "-"))
    }
    // Wired by MainScreen (needs the app context): a user-set Km value while a
    // trip records = "odometer photographed at a stop" → TripManager validates
    // the delta against the track and arms the tentative end. The photo CAS id
    // lands later (async upload) via the second callback. A user-set Purpose
    // while a trip records merges onto the OPEN trip (editable until trip end)
    // instead of waiting for the next start.
    var onTripKmCaptured: ((Double) -> Unit)? = null
    var onTripKmPhotoCaptured: ((String) -> Unit)? = null
    var onTripPurposeCaptured: ((String) -> Unit)? = null

    private fun setTripField(field: String, value: String?, source: String) {
        when (field) {
            "plate" -> {
                tripPlate = value; tripPlateSource = source
                // A plate set while a start intent is armed upgrades the intent's
                // last-trip preset (no-op when nothing is armed).
                if (source == "user") value?.takeIf { it.isNotBlank() }?.let {
                    com.xelth.eckwms_movfast.trips.TripManager.bindTripIntentVehicle(null, it)
                }
            }
            "km" -> {
                tripKm = value; tripKmSource = source
                if (source == "user") value?.toDoubleOrNull()?.let { onTripKmCaptured?.invoke(it) }
            }
            else -> {
                tripPurpose = value; tripPurposeSource = source
                if (source == "user") value?.takeIf { it.isNotBlank() }
                    ?.let { onTripPurposeCaptured?.invoke(it) }
            }
        }
    }
    /** Current pending trip fields (read by MainScreen when starting a trip). */
    fun pendingTripPlate() = tripPlate
    fun pendingTripKm() = tripKm?.toDoubleOrNull()
    fun pendingTripPurposeText() = tripPurpose
    fun pendingTripPlatePhotoId() = tripPlatePhotoId
    fun pendingTripKmPhotoId() = tripKmPhotoId
    /** CAS id of an OCR photo just uploaded for a field (evidence), stored so it
     *  can be attached to the trip at start. */
    fun setTripFieldPhotoId(field: String, photoId: String) {
        when (field) {
            "plate" -> tripPlatePhotoId = photoId
            "km" -> {
                tripKmPhotoId = photoId
                onTripKmPhotoCaptured?.invoke(photoId)
            }
        }
    }
    /** Set a field from OCR/known-value (called by MainScreen), marks it user-set. */
    fun applyTripFieldValue(field: String, value: String) {
        setTripField(field, value.trim(), "user")
        tripFieldMenu = null
        if (_isTripMode.value == true) renderTripGrid()
    }
    /** After a trip starts, keep the values but demote user→auto so next trip
     *  shows them YELLOW ("system pulled a previous value") until re-confirmed. */
    fun markTripFieldsAuto() {
        if (tripPlateSource == "user") tripPlateSource = "auto"
        if (tripKmSource == "user") tripKmSource = "auto"
        if (tripPurposeSource == "user") tripPurposeSource = "auto"
        if (_isTripMode.value == true) renderTripGrid()
    }
    private fun updateFieldStatus() {
        val f = tripFieldMenu ?: return
        _tripStatus.value = "${fieldName(f)}: ${tripEntryBuffer}▮"
    }

    // ── 🧾 Expense hex sub-menu (no modal) ────────────────────────────────────
    /** Open the expense hex sub-menu, prefilling the odometer with the track estimate. */
    fun openFuelMenu(estimate: String?) {
        tripFuelMenu = true
        tripEntryBuffer = estimate ?: ""
        tripFuelSource = "estimated"
        tripFuelReceiptOk = false
        tripExpenseType = null
        if (_isTripMode.value == true) renderTripGrid()
    }
    /** Odometer scanned (km OCR) → overwrite the buffer, mark it exact. */
    fun setFuelOdometer(km: String) {
        tripEntryBuffer = km
        tripFuelSource = "scanned"
        if (_isTripMode.value == true) renderTripGrid()
    }
    /** Receipt photo captured (turns the 📷 button into "✓ Receipt"). */
    fun setFuelReceiptOk(ok: Boolean) {
        tripFuelReceiptOk = ok
        if (_isTripMode.value == true) renderTripGrid()
    }
    fun currentFuelOdometer(): Double? = tripEntryBuffer.trim().toDoubleOrNull()
    fun currentFuelSource(): String = tripFuelSource
    /** The picked expense type, defaulting to a plain "receipt" when none was. */
    fun currentExpenseType(): String = tripExpenseType ?: "receipt"

    /** Field sub-menu on hexes. Two shapes:
     *  • KM (number only): a numeric hex keypad (0-9 + dot) — big, fixed-position
     *    keys are fine for digits. OK carries the live buffer so typing is visible.
     *  • PLATE / PURPOSE (text): NO hex keyboard (unfamiliar key positions are
     *    confusing for words). Known values as quick-pick + a ⌨ Keyboard hex that
     *    opens the normal Android soft keyboard (with a 🎤 dictate option).
     *  Plate/km also get OCR via the global 📷 button relabelled in renderTripGrid. */
    private fun renderTripFieldMenu(uiItems: MutableList<Map<String, Any>>, field: String) {
        // No full Back hex — the ✕ half-button (set at the end of renderTripGrid)
        // already steps back out of the field menu.
        if (field == "km") {
            // OK shows the current buffer so the number being typed is visible.
            uiItems.add(mapOf("type" to "button",
                "label" to "✓\n" + tripEntryBuffer.ifBlank { "OK" },
                "color" to "#2E7D32", "action" to "trip_field_ok"))
            uiItems.add(mapOf("type" to "button", "label" to "⌫\nDel", "color" to "#546E7A", "action" to "trip_key_bs"))
            keySetFor("km").forEach { k ->
                uiItems.add(mapOf("type" to "button", "label" to k, "color" to "#263238", "action" to "trip_key:$k"))
            }
        } else {
            // Text field: known picks + open the Android keyboard (no hex keys).
            fieldKnown(field).take(6).forEach { v ->
                uiItems.add(mapOf("type" to "button", "label" to v, "color" to "#1565C0", "action" to "trip_pick:$v"))
            }
            uiItems.add(mapOf("type" to "button", "label" to "⌨\nKeyboard", "color" to "#00695C", "action" to "trip_kbd"))
        }
    }

    private val expenseTypes = listOf(
        Triple("fuel", "⛽", "Fuel"),
        Triple("parking", "🅿", "Parking"),
        Triple("toll", "🛣", "Toll")
    )
    private fun expenseIcon(type: String?): String =
        expenseTypes.firstOrNull { it.first == type }?.second ?: "🧾"

    /** 🧾 Expense sub-menu on hexes (mirrors the km field keypad + a Save +
     *  odometer OCR + the optional TYPE row). The receipt photo — enough by
     *  itself — rides the global 📷 button (relabelled below); a picked type
     *  is saved with the receipt info, tapping it again unpicks it. */
    private fun renderTripFuelMenu(uiItems: MutableList<Map<String, Any>>) {
        // Save — green, shows the picked type icon + current odometer buffer.
        uiItems.add(mapOf("type" to "button",
            "label" to expenseIcon(tripExpenseType) + "\nSave\n" + tripEntryBuffer.ifBlank { "?" } + " km",
            "color" to "#2E7D32", "action" to "trip_fuel_save"))
        uiItems.add(mapOf("type" to "button", "label" to "⌫\nDel", "color" to "#546E7A", "action" to "trip_key_bs"))
        // OCR the exact odometer into the buffer.
        uiItems.add(mapOf("type" to "button", "label" to "📷\nScan odo", "color" to "#37474F", "action" to "trip_fuel_ocr"))
        // Type row — optional annotation, selected renders blue.
        expenseTypes.forEach { (key, icon, name) ->
            uiItems.add(mapOf("type" to "button",
                "label" to "$icon\n$name",
                "color" to if (tripExpenseType == key) "#1565C0" else "#37474F",
                "action" to "trip_expense_type:$key"))
        }
        // Numeric keypad (same digits as the km field).
        keySetFor("km").forEach { k ->
            uiItems.add(mapOf("type" to "button", "label" to k, "color" to "#263238", "action" to "trip_key:$k"))
        }
    }

    private fun renderTripGrid() {
        val auto = _tripAutoDetect.value == true
        val recording = _tripRecording.value == true
        val uiItems = mutableListOf<Map<String, Any>>()

        if (tripFuelMenu) {
            renderTripFuelMenu(uiItems)
        } else if (tripFieldMenu != null) {
            renderTripFieldMenu(uiItems, tripFieldMenu!!)
        } else if (tripSettingsMenu) {
            // ── Settings sub-menu (on hexes): the two toggles live here now so
            // they aren't fat-fingered on the main trip grid. ✓/✕ shows state. ──
            // No full Back hex — the ✕ half-button already steps out of this menu.
            uiItems.add(mapOf(
                "type" to "button",
                "label" to if (auto) "✓ Auto\nStart" else "✕ Auto\nStart",
                "color" to "#455A64",
                "action" to "trip_toggle_autodetect"
            ))
            // Live server tracking (consent to be watched live on the dashboard).
            // Default OFF; flipping only sets the flag — no location leaves the
            // device unless a business trip is actually recording.
            val liveShare = com.xelth.eckwms_movfast.utils.SettingsManager.getTripLiveShare()
            uiItems.add(mapOf(
                "type" to "button",
                "label" to if (liveShare) "✓ Live\nServer" else "✕ Live\nServer",
                "color" to "#455A64",
                "action" to "trip_toggle_liveshare"
            ))
            // "Allow all the time" location — without it auto-detected trips
            // record ZERO points while the screen is off (Android strips
            // location from a background-started FGS). Red = broken auto
            // recording; tap opens the system grant flow (MainScreen).
            uiItems.add(mapOf(
                "type" to "button",
                "label" to if (tripBgLocationGranted) "✓ BG\nStandort" else "✕ BG\nStandort",
                "color" to if (tripBgLocationGranted) "#455A64" else "#C62828",
                "action" to "trip_bg_location"
            ))
        } else {
            // Row-major flow onto the fixed hex geometry (row 0: col 1 free —
            // Photo/Scan pinned at 3/5; row 1: cols 0/2 free — 🎤 Audio pinned
            // at 4). Item order IS the layout:
            //   row 1: Settings              row 2: Private · Me · (Audio)
            //   row 3: Start · Checkpoint · Stop
            //   row 4: Plate · Km · Purpose  then cities; Expense pinned at the bottom.
            uiItems.add(mapOf(
                "type" to "button",
                "label" to "⚙️\nSettings",
                "color" to "#455A64",
                "action" to "trip_open_settings"
            ))
            uiItems.add(mapOf(
                "type" to "button", "label" to "🔒\nPrivate",
                "color" to if (recording) "#424242" else "#546E7A",
                "action" to "trip_start_private", "enabled" to !recording
            ))
            // 📍 Recenter the map on the current position (keeps zoom). A hex here —
            // no overlay button on the map (plenty of free hexes).
            uiItems.add(mapOf(
                "type" to "button",
                "label" to "📍\nMe",
                "color" to "#37474F",
                "action" to "trip_recenter"
            ))
            // Start = INTENT (like the spoken declaration): arming turns the hex
            // yellow; the trip starts on movement, or the intent silently
            // expires after 2 h (peekTripIntent clears it). The hex is a
            // plain traffic light — grey idle, YELLOW armed, GREEN once Android
            // actually detects driving. Tapping the YELLOW hex forces the start
            // NOW (2026-07-13 field case: Activity Recognition never fired for
            // a whole drive — the driver needs a manual escape hatch, and a
            // second tap on "Start" is what they try anyway). Cancelling an
            // armed intent moved to the Stop hex. The checklist decoration
            // lives on the MAIN-MENU Trips hex (initializeGrid), not here.
            val armedIntent = if (recording) null
                else com.xelth.eckwms_movfast.trips.TripManager.peekTripIntent()
            uiItems.add(when {
                recording -> mapOf(
                    "type" to "button", "label" to "🚗\nStart",
                    "color" to "#2E7D32", "action" to "trip_start_business", "enabled" to false
                )
                armedIntent != null -> mapOf(
                    "type" to "button", "label" to "🕐▶\nStart",
                    "color" to "#F9A825", "action" to "trip_force_start", "enabled" to true
                )
                else -> mapOf(
                    "type" to "button", "label" to "🚗\nStart",
                    "color" to "#607D8B", "action" to "trip_start_business", "enabled" to true
                )
            })
            // One-tap "drop a stop" — records the current position as a manual
            // checkpoint on THIS trip (multi-stop trip, no stop/restart) and
            // uploads it immediately.
            uiItems.add(mapOf(
                "type" to "button", "label" to "📍\nCheckpoint",
                "color" to if (recording) "#00695C" else "#424242",
                "action" to "trip_checkpoint", "enabled" to recording
            ))
            // Stop doubles as the armed-intent cancel (the yellow Start hex
            // now force-starts instead of cancelling).
            uiItems.add(when {
                recording -> mapOf(
                    "type" to "button", "label" to "⏹\nStop",
                    "color" to "#C62828", "action" to "trip_stop", "enabled" to true
                )
                armedIntent != null -> mapOf(
                    "type" to "button", "label" to "✕\nCancel",
                    "color" to "#C62828", "action" to "trip_cancel_intent", "enabled" to true
                )
                else -> mapOf(
                    "type" to "button", "label" to "⏹\nStop",
                    "color" to "#424242", "action" to "trip_stop", "enabled" to false
                )
            })
            // Three field buttons (pulled out of the old start sub-menu) — colour
            // shows state (red empty / yellow auto / green user-set); each opens
            // its own hex sub-menu (known values + Photo→OCR + keypad/keyboard).
            uiItems.add(mapOf("type" to "button",
                "label" to fieldButtonLabel("plate", "🔖", "Plate"),
                "color" to fieldColor(tripPlateSource), "action" to "trip_field:plate"))
            uiItems.add(mapOf("type" to "button",
                "label" to fieldButtonLabel("km", "🔢", "Km"),
                "color" to fieldColor(tripKmSource), "action" to "trip_field:km"))
            uiItems.add(mapOf("type" to "button",
                "label" to fieldButtonLabel("purpose", "🎯", "Purpose"),
                "color" to fieldColor(tripPurposeSource), "action" to "trip_field:purpose"))
            // City buttons (cities with waiting tickets). Tap → console shows that
            // city's tickets. "All" clears the filter.
            if (tripSelectedCity != null) {
                uiItems.add(mapOf("type" to "button", "label" to "⬅\nAll", "color" to "#455A64", "action" to "trip_city_all"))
            }
            _tripCities.value?.forEach { c ->
                val selected = c.city == tripSelectedCity
                uiItems.add(mapOf(
                    "type" to "button",
                    "label" to "${c.city}\n(${c.count})",
                    "color" to if (selected) "#1565C0" else "#37474F",
                    "action" to "trip_city:${c.city}"
                ))
            }
        }

        gridManager.clearAndReset()
        // Inside a plate/km field menu the global 📷 button doubles as the OCR
        // trigger (saves one hex — the photo is uploaded either way). Elsewhere it
        // stays the normal Photo capture.
        val ocrField = tripFieldMenu == "plate" || tripFieldMenu == "km"
        placeSystemButtons(
            scanAction = "act_scan",
            photoAction = when {
                tripFuelMenu -> "trip_fuel_receipt"
                ocrField -> "trip_ocr"
                else -> "act_photo"
            },
            photoLabel = when {
                tripFuelMenu -> if (tripFuelReceiptOk) "🧾\n✓Receipt" else "🧾\nReceipt"
                ocrField -> "📷\nOCR"
                else -> "📷\nPhoto"
            }
        )
        if (!tripFuelMenu && tripFieldMenu == null && !tripSettingsMenu) {
            // 🧾 ONE expense button for the whole trip-cost family (fuel /
            // parking / toll), pinned at the BOTTOM row, away from the driving
            // controls. Inside: receipt photo (enough by itself), odometer OCR
            // and optional type hexes. Cities flow around the reserved slot.
            val bottomRow = (gridManager.dimensions.first - 1).coerceAtLeast(4)
            val bottomCol = if (bottomRow % 2 == 0) 3 else 2   // centre full hex
            gridManager.placeSystemElements(listOf(SystemElement(
                bottomRow, bottomCol,
                mapOf(
                    "type" to "button", "label" to "🧾\nExpense",
                    "color" to if (recording) "#00695C" else "#424242",
                    "action" to "trip_fuel", "enabled" to recording
                ),
                PRIORITIES.SYSTEM_FIXED, "expense"
            )))
        }
        gridManager.placeItems(uiItems, priority = 100)
        _exitButton.postValue(HalfButtonState("✕", "#F44336", "act_exit"))
        updateRenderCells()
    }

    private fun handleTripButtonClick(action: String): String {
        return when {
            // Red ✕ short press = step BACK one level (long press exits to root,
            // handled in onButtonLongClick): sub-menu → main grid, city filter →
            // all cities, otherwise leave trip mode.
            action == "act_exit" -> {
                when {
                    tripFuelMenu -> { tripFuelMenu = false; renderTripGrid() }
                    tripFieldMenu != null -> { tripFieldMenu = null; renderTripGrid() }
                    tripSettingsMenu -> { tripSettingsMenu = false; renderTripGrid() }
                    tripSelectedCity != null -> refreshTripDestinations()
                    else -> exitTripMode()
                }
                "handled"
            }
            action == "act_photo" -> "capture_photo"
            action == "act_scan" -> "capture_barcode"
            action == "trip_city_all" -> { refreshTripDestinations(); "handled" }
            action.startsWith("trip_city:") -> {
                refreshTripDestinations(city = action.removePrefix("trip_city:"))
                "handled"
            }
            // Open / close the settings sub-menu (auto-detect + live-server toggles).
            action == "trip_open_settings" -> { tripSettingsMenu = true; renderTripGrid(); "handled" }
            action == "trip_settings_back" -> { tripSettingsMenu = false; renderTripGrid(); "handled" }
            // ── Field entry (Kennzeichen / Kilometerstand / Purpose) ──
            action.startsWith("trip_field:") -> {
                tripFieldMenu = action.removePrefix("trip_field:")
                tripKeyPage = 0
                tripEntryBuffer = fieldValue(tripFieldMenu!!) ?: ""
                updateFieldStatus(); renderTripGrid(); "handled"
            }
            action == "trip_field_back" -> {
                tripFieldMenu = null
                _tripStatus.postValue(if (_tripRecording.value == true) "🚗 Trip running" else "Trips")
                renderTripGrid(); "handled"
            }
            action.startsWith("trip_key:") -> {
                tripEntryBuffer += action.removePrefix("trip_key:")   // km keypad → digits
                if (tripFuelMenu) tripFuelSource = "manual"           // typed over the estimate
                // Re-render so the buffer shows on the OK/Save hex (numeric keypad).
                updateFieldStatus(); renderTripGrid(); "handled"
            }
            action == "trip_key_bs" -> {
                if (tripEntryBuffer.isNotEmpty()) tripEntryBuffer = tripEntryBuffer.dropLast(1)
                if (tripFuelMenu) tripFuelSource = "manual"
                updateFieldStatus(); renderTripGrid(); "handled"
            }
            // ── 🧾 Expense sub-menu actions (MainScreen owns camera + logExpense) ──
            action == "trip_fuel_ocr" -> "trip_fuel_ocr"          // OCR exact odometer
            action == "trip_fuel_receipt" -> "trip_fuel_receipt"  // capture receipt
            action.startsWith("trip_expense_type:") -> {
                val t = action.removePrefix("trip_expense_type:")
                tripExpenseType = if (tripExpenseType == t) null else t   // tap again = unpick
                renderTripGrid(); "handled"
            }
            action == "trip_fuel_save" -> { tripFuelMenu = false; renderTripGrid(); "trip_fuel_save" }
            action == "trip_key_page" -> { tripKeyPage++; renderTripGrid(); "handled" }
            // ⌨ Keyboard on a text field → MainScreen opens the Android soft keyboard
            // (with 🎤 dictate). It calls applyTripFieldValue(field, text) on confirm.
            action == "trip_kbd" -> "trip_kbd:${tripFieldMenu ?: "purpose"}"
            action == "trip_field_ok" -> {
                tripFieldMenu?.let { f ->
                    val v = tripEntryBuffer.trim()
                    setTripField(f, v.ifEmpty { null }, if (v.isEmpty()) "empty" else "user")
                }
                tripFieldMenu = null
                _tripStatus.postValue(if (_tripRecording.value == true) "🚗 Trip running" else "Trips")
                renderTripGrid(); "handled"
            }
            action.startsWith("trip_pick:") -> {
                val v = action.removePrefix("trip_pick:")
                tripFieldMenu?.let { setTripField(it, v, "user") }
                tripFieldMenu = null
                _tripStatus.postValue(if (_tripRecording.value == true) "🚗 Trip running" else "Trips")
                renderTripGrid(); "handled"
            }
            // OCR the plate/odometer — MainScreen owns the camera; it calls
            // applyTripFieldValue(field, text) with the recognised value.
            action == "trip_ocr" -> "trip_ocr:${tripFieldMenu ?: "plate"}"
            // Start/stop/odometer need MainScreen (TripManager + dialogs +
            // permissions) — return the action; setTripRecording re-renders the
            // grid when the state actually flips.
            action == "trip_start_business" || action == "trip_start_private" ||
                action == "trip_stop" || action == "trip_odometer" ||
                action == "trip_force_start" || action == "trip_bg_location" -> action
            // Cancel an armed (not yet consumed) start intent — tap on the
            // yellow "Armed" hex. Pure prefs write, no context needed.
            action == "trip_cancel_intent" -> {
                com.xelth.eckwms_movfast.trips.TripManager.clearTripIntent()
                addLog("🕐 Start intent cancelled")
                renderTripGrid()
                "handled"
            }
            // context-bound — handled in MainScreen (needs the permission flow)
            action == "trip_toggle_autodetect" -> "trip_toggle_autodetect"
            // Map recenter — handled in MainScreen (it owns the map view).
            action == "trip_recenter" -> "trip_recenter"
            // Drop a checkpoint on the open trip — MainScreen calls TripManager
            // (needs the app context + the current purpose as the stop label).
            action == "trip_checkpoint" -> "trip_checkpoint"
            // Refuel event — MainScreen owns the camera (receipt/odometer) + dialog.
            action == "trip_fuel" -> "trip_fuel"
            // Live server tracking: a pure consent flag — flip it here, no
            // permission needed (the recording service re-reads it per fix).
            action == "trip_toggle_liveshare" -> {
                val cur = com.xelth.eckwms_movfast.utils.SettingsManager.getTripLiveShare()
                com.xelth.eckwms_movfast.utils.SettingsManager.saveTripLiveShare(!cur)
                addLog(if (!cur) "🛰 Live-Tracking AN" else "Live-Tracking AUS")
                renderTripGrid()
                "handled"
            }
            else -> "handled"
        }
    }

    // --- RECEIVING MODE LOGIC ---

    fun loadReceivingWorkflow(context: android.content.Context) {
        try {
            val json = context.assets.open("workflows/receiving.json")
                .bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(json)
            val stepsArray = root.getJSONArray("steps")

            val parsed = mutableListOf<Map<String, Any>>()
            for (i in 0 until stepsArray.length()) {
                val s = stepsArray.getJSONObject(i)
                val m = mutableMapOf<String, Any>(
                    "id" to s.getString("id"),
                    "label" to s.getString("label"),
                    "type" to s.getString("type"),
                    "dataKey" to s.getString("dataKey")
                )
                if (s.has("action")) m["action"] = s.getString("action")
                if (s.has("uiSchema")) m["uiSchema"] = s.getJSONObject("uiSchema").toString()
                if (s.optBoolean("autoOpen", false)) m["autoOpen"] = true
                if (s.has("colors")) {
                    val c = s.getJSONObject("colors")
                    m["colors"] = mapOf(
                        "pending" to c.optString("pending", "#424242"),
                        "active" to c.optString("active", "#FF9800"),
                        "done" to c.optString("done", "#4CAF50")
                    )
                }
                // Parse contents_grid items and packaging
                if (s.has("items")) {
                    val itemsArr = s.getJSONArray("items")
                    val items = mutableListOf<Map<String, Any>>()
                    for (j in 0 until itemsArr.length()) {
                        val item = itemsArr.getJSONObject(j)
                        items.add(mapOf(
                            "key" to item.getString("key"),
                            "label" to item.getString("label"),
                            "scannable" to item.optBoolean("scannable", false)
                        ))
                    }
                    m["items"] = items
                }
                if (s.has("packaging")) {
                    val pkg = s.getJSONObject("packaging")
                    val opts = pkg.getJSONArray("options")
                    val options = mutableListOf<Map<String, String>>()
                    for (j in 0 until opts.length()) {
                        val opt = opts.getJSONObject(j)
                        options.add(mapOf(
                            "value" to opt.getString("value"),
                            "label" to opt.getString("label")
                        ))
                    }
                    m["packaging"] = mapOf(
                        "key" to pkg.getString("key"),
                        "label" to pkg.getString("label"),
                        "options" to options
                    )
                }
                parsed.add(m)
            }
            receivingSteps = parsed
            addLog("Loaded ${parsed.size} receiving steps")
        } catch (e: Exception) {
            addLog("Error loading receiving.json: ${e.message}")
        }
    }

    fun enterReceivingMode() {
        _isReceivingMode.value = true
        currentStepIndex = 0
        receivingData.clear()
        _receivingStatus.value = if (receivingSteps.isNotEmpty())
            "Step 1: ${receivingSteps[0]["label"]}" else "No steps loaded"
        addLog("Entered Receiving Mode")
        fetchAndCacheShipments()
        renderReceivingGrid()
    }

    private fun fetchAndCacheShipments() {
        viewModelScope.launch {
            // Load warehouse info for smart client detection
            warehouseName = com.xelth.eckwms_movfast.utils.SettingsManager.getWarehouseName()
            warehouseAddress = com.xelth.eckwms_movfast.utils.SettingsManager.getWarehouseAddress()
            if (warehouseName.isNotEmpty()) {
                addLog("Warehouse: $warehouseName ($warehouseAddress)")
            } else {
                addLog("Warning: warehouse info not available yet (client detection will use fallback)")
            }
            addLog("Fetching recent shipments...")
            _shipmentsError.postValue(null) // Clear previous errors
            val fetchFn = onFetchShipments
            if (fetchFn == null) {
                val errMsg = "No shipment fetcher configured"
                addLog(errMsg)
                _shipmentsError.postValue(errMsg)
                _shipmentsLoaded.postValue(true) // Still trigger recomposition
                return@launch
            }
            val result = fetchFn(100)
            if (result is ScanResult.Success) {
                try {
                    val jsonArray = JSONArray(result.data)
                    val list = mutableListOf<Map<String, Any>>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val map = mutableMapOf<String, Any>()
                        val iter = obj.keys()
                        while (iter.hasNext()) {
                            val key = iter.next()
                            map[key] = obj.get(key)
                        }
                        // The Rust server answers in snake_case; every consumer
                        // in this app still reads the Go-era camelCase names.
                        // Alias centrally here instead of touching all readers.
                        obj.optString("tracking_number").takeIf { it.isNotEmpty() }
                            ?.let { map["trackingNumber"] = it }
                        obj.optString("raw_response").takeIf { it.isNotEmpty() }
                            ?.let { map["rawResponse"] = it }
                        obj.optString("updated_at").takeIf { it.isNotEmpty() }
                            ?.let { map["lastActivityAt"] = it }
                        list.add(map)
                    }

                    // Server already returns smart-sorted (active first, then delivered)
                    // Just use the order as-is
                    cachedShipments = list

                    addLog("Loaded ${cachedShipments.size} shipments")
                    _shipmentsError.postValue(null) // Clear error on success
                    _shipmentsLoaded.postValue(true)
                } catch (e: Exception) {
                    val errMsg = "Error parsing shipments: ${e.message}"
                    addLog(errMsg)
                    _shipmentsError.postValue(errMsg)
                    _shipmentsLoaded.postValue(true) // Still trigger recomposition
                }
            } else if (result is ScanResult.Error) {
                val errMsg = "Failed to fetch shipments: ${result.message}"
                addLog(errMsg)
                _shipmentsError.postValue(errMsg)
                _shipmentsLoaded.postValue(true) // Still trigger recomposition
            }
        }
    }

    // id → score from the last postal scan's fuzzy match; drives the picker
    // sort order and the "best candidate" highlight.
    private var fuzzyScores: Map<String, Int> = emptyMap()

    /** OCR-tolerant normalization: uppercase + the classic digit confusions
     *  (O→0, I/L→1, S→5, B→8). Applied to BOTH sides of every comparison, so
     *  words stay comparable ("S+T FITNESS" and its OCR twin normalize alike). */
    private fun ocrNorm(s: String): String = buildString(s.length) {
        for (c in s.uppercase()) append(
            when (c) { 'O' -> '0'; 'I' -> '1'; 'L' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> c }
        )
    }

    private val tokenSplit = Regex("[^A-Za-z0-9]+")

    /** Score one shipment against the scanned-label tokens: long token inside the
     *  tracking/order id = 5, a 5-digit zip token = 3, any other field token = 1. */
    private fun scoreShipment(tokens: List<String>, s: Map<String, Any?>): Int {
        val fields = StringBuilder()
        val raw = s["rawResponse"] as? String
        if (raw != null) try {
            val j = JSONObject(raw)
            for (k in listOf("recipient_name", "recipient_street", "recipient_zip",
                             "recipient_city", "pickup_name", "pickup_street",
                             "pickup_zip", "pickup_city", "reference", "description")) {
                fields.append(' ').append(j.optString(k, ""))
            }
        } catch (_: Exception) {}
        val hayTokens = tokenSplit.split(ocrNorm(fields.toString()))
            .filter { it.length >= 3 }.toSet()
        val trackingHay = ocrNorm(
            (s["trackingNumber"] as? String ?: "") + " " + (s["id"]?.toString() ?: "")
        )
        var score = 0
        for (t in tokens) {
            when {
                t.length >= 6 && trackingHay.contains(t) -> score += 5
                hayTokens.contains(t) ->
                    score += if (t.length == 5 && t.all { it.isDigit() }) 3 else 1
            }
        }
        return score
    }

    private fun tryAutoMatchShipment(barcode: String) {
        fuzzyScores = emptyMap()
        if (cachedShipments.isEmpty()) {
            addLog("Cannot auto-match: no shipments loaded yet")
            return
        }

        // 1. Exact tracking hit (a real barcode scan).
        val match = cachedShipments.find {
            (it["trackingNumber"] as? String) == barcode
        }
        if (match != null) {
            val track = match["trackingNumber"] as? String ?: "?"
            addLog("Auto-match: barcode $barcode -> shipment #${match["id"]} ($track)")
            populateClientData(match)
            return
        }

        // 2. Fuzzy: OCR'd label text (or an unknown barcode) scored against every
        //    shipment's fields. Confident single winner → auto-select; otherwise
        //    the scores just rank the picker.
        val tokens = tokenSplit.split(ocrNorm(barcode)).filter { it.length >= 3 }.distinct()
        if (tokens.isEmpty()) {
            addLog("No auto-match for $barcode in ${cachedShipments.size} shipments")
            return
        }
        val scores = mutableMapOf<String, Int>()
        for (s in cachedShipments) {
            val id = (s["id"] as? Number)?.toString() ?: s["id"]?.toString() ?: continue
            val sc = scoreShipment(tokens, s)
            if (sc > 0) scores[id] = sc
        }
        fuzzyScores = scores
        val best = scores.maxByOrNull { it.value }
        if (best == null) {
            addLog("No auto-match for ${barcode.take(40)} in ${cachedShipments.size} shipments")
            return
        }
        val second = scores.values.sortedDescending().getOrNull(1) ?: 0
        val bestShipment = cachedShipments.find {
            ((it["id"] as? Number)?.toString() ?: it["id"]?.toString()) == best.key
        }
        if (best.value >= 6 && best.value >= second + 3 && bestShipment != null) {
            addLog("Fuzzy match (${best.value} pts): -> #${best.key} (${bestShipment["trackingNumber"]})")
            populateClientData(bestShipment)
        } else {
            addLog("Fuzzy candidates: best #${best.key} (${best.value} pts, next $second) — list sorted by match")
        }
    }

    /**
     * Levenshtein edit distance between two strings (case-insensitive).
     */
    private fun levenshtein(a: String, b: String): Int {
        val s = a.lowercase().trim()
        val t = b.lowercase().trim()
        val m = s.length
        val n = t.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (s[i - 1] == t[j - 1]) dp[i - 1][j - 1]
            else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
        }
        return dp[m][n]
    }

    /**
     * Check if a name looks like our company using Levenshtein distance.
     * Returns true if similarity ratio > 0.5 (i.e. distance < half the max length).
     */
    private fun looksLikeOurCompany(name: String): Boolean {
        if (warehouseName.isEmpty() || name.isEmpty()) return false
        val dist = levenshtein(name, warehouseName)
        val maxLen = maxOf(name.length, warehouseName.length)
        return dist.toDouble() / maxLen < 0.5
    }

    /**
     * Extract both pickup and delivery data from rawResponse,
     * then use Levenshtein to determine which is the client (not us).
     * Returns Pair(clientName, clientAddress).
     */
    private fun detectClient(raw: JSONObject): Pair<String, String> {
        // Extract pickup (sender) info
        var pickupName = raw.optString("pickup_name", "")
        val pName2 = raw.optString("pickup_name2", "")
        if (pName2.isNotEmpty() && pName2 != pickupName) pickupName = "$pickupName $pName2".trim()
        val pStreet = raw.optString("pickup_street", "")
        val pZip = raw.optString("pickup_zip", "")
        val pCity = raw.optString("pickup_city", "")
        val pickupAddr = listOf(pStreet, "$pZip $pCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")

        // Extract delivery (receiver) info
        var deliveryName = raw.optString("delivery_name", "")
        if (deliveryName.isEmpty()) deliveryName = raw.optString("recipient_name", "")
        val dStreet = raw.optString("delivery_street", raw.optString("recipient_street", ""))
        val dZip = raw.optString("delivery_zip", raw.optString("recipient_zip", ""))
        val dCity = raw.optString("delivery_city", raw.optString("recipient_city", ""))
        val deliveryAddr = listOf(dStreet, "$dZip $dCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")

        // Smart detection: which one is our company?
        val pickupIsUs = looksLikeOurCompany(pickupName)
        val deliveryIsUs = looksLikeOurCompany(deliveryName)

        val pickupDist = if (warehouseName.isNotEmpty() && pickupName.isNotEmpty()) levenshtein(pickupName, warehouseName) else -1
        val deliveryDist = if (warehouseName.isNotEmpty() && deliveryName.isNotEmpty()) levenshtein(deliveryName, warehouseName) else -1

        addLog("Pickup '$pickupName' dist=$pickupDist ${if (pickupIsUs) "(=US)" else ""} | Delivery '$deliveryName' dist=$deliveryDist ${if (deliveryIsUs) "(=US)" else ""}")

        return when {
            // Pickup is us → client is delivery
            pickupIsUs && !deliveryIsUs -> {
                addLog("Client = Delivery (pickup matches our company)")
                Pair(deliveryName, deliveryAddr)
            }
            // Delivery is us → client is pickup (normal inbound)
            deliveryIsUs && !pickupIsUs -> {
                addLog("Client = Pickup (delivery matches our company)")
                Pair(pickupName, pickupAddr)
            }
            // Both match or neither matches → fallback to pickup as client
            else -> {
                addLog("Client = Pickup (fallback)")
                Pair(pickupName.ifEmpty { deliveryName }, if (pickupName.isNotEmpty()) pickupAddr else deliveryAddr)
            }
        }
    }

    /**
     * Silent version of detectClient (no addLog) for use in list builders.
     */
    private fun detectClientSilent(raw: JSONObject): Pair<String, String> {
        var pickupName = raw.optString("pickup_name", "")
        val pName2 = raw.optString("pickup_name2", "")
        if (pName2.isNotEmpty() && pName2 != pickupName) pickupName = "$pickupName $pName2".trim()
        val pStreet = raw.optString("pickup_street", "")
        val pZip = raw.optString("pickup_zip", "")
        val pCity = raw.optString("pickup_city", "")
        val pickupAddr = listOf(pStreet, "$pZip $pCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")

        var deliveryName = raw.optString("delivery_name", "")
        if (deliveryName.isEmpty()) deliveryName = raw.optString("recipient_name", "")
        val dStreet = raw.optString("delivery_street", raw.optString("recipient_street", ""))
        val dZip = raw.optString("delivery_zip", raw.optString("recipient_zip", ""))
        val dCity = raw.optString("delivery_city", raw.optString("recipient_city", ""))
        val deliveryAddr = listOf(dStreet, "$dZip $dCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")

        val pickupIsUs = looksLikeOurCompany(pickupName)
        val deliveryIsUs = looksLikeOurCompany(deliveryName)
        return when {
            pickupIsUs && !deliveryIsUs -> Pair(deliveryName, deliveryAddr)
            deliveryIsUs && !pickupIsUs -> Pair(pickupName, pickupAddr)
            else -> Pair(pickupName.ifEmpty { deliveryName }, if (pickupName.isNotEmpty()) pickupAddr else deliveryAddr)
        }
    }

    private fun populateClientData(shipment: Map<String, Any>) {
        val rawResponseStr = shipment["rawResponse"] as? String
        var clientName = ""
        var clientAddress = ""

        if (rawResponseStr != null) {
            try {
                val raw = JSONObject(rawResponseStr)
                val (name, addr) = detectClient(raw)
                clientName = name
                clientAddress = addr
            } catch (_: Exception) {}
        }

        val shipmentId = (shipment["id"] as? Number)?.toString() ?: shipment["id"]?.toString() ?: ""
        receivingData["selected_shipment_id"] = shipmentId
        if (clientName.isNotEmpty()) receivingData["client_name"] = clientName
        if (clientAddress.isNotEmpty()) receivingData["client_address"] = clientAddress
        addLog("Client: $clientName | $clientAddress")
    }

    fun getShipmentOptions(): String {
        val options = JSONArray()
        // Manual entry option
        val manualOpt = JSONObject()
        manualOpt.put("label", "-- Manual Entry --")
        manualOpt.put("value", "")
        options.put(manualOpt)

        cachedShipments.forEach { s ->
            val id = (s["id"] as? Number)?.toString() ?: s["id"]?.toString() ?: ""
            val track = s["trackingNumber"] as? String ?: "?"
            val status = s["status"] as? String ?: "?"

            var client = ""
            val rawStr = s["rawResponse"] as? String
            if (rawStr != null) {
                try {
                    val raw = JSONObject(rawStr)
                    val (detectedClient, _) = detectClientSilent(raw)
                    client = detectedClient
                } catch (_: Exception) {}
            }
            if (client.isEmpty()) client = "Unknown"

            val statusIcon = when (status) {
                "delivered" -> "V"
                "cancelled" -> "X"
                "error" -> "!"
                else -> "#"
            }
            val label = "[$statusIcon] $track - $client"

            val opt = JSONObject()
            opt.put("label", label)
            opt.put("value", id)
            options.put(opt)
        }
        return options.toString()
    }

    /**
     * Called when user taps a shipment in the picker list.
     * Auto-fills client data and advances to next step.
     */
    fun selectShipment(shipmentId: String) {
        if (cachedShipments.isEmpty()) {
            addLog("Cannot select shipment: list is empty")
            _showShipmentPicker.value = false
            return
        }

        val shipment = cachedShipments.find {
            (it["id"] as? Number)?.toString() == shipmentId || it["id"]?.toString() == shipmentId
        }
        if (shipment != null) {
            val track = shipment["trackingNumber"] as? String ?: "?"
            addLog("Shipment selected from list: #$shipmentId ($track)")
            populateClientData(shipment)
            _receivingStatus.value = "✓ Shipment: $track"
        } else {
            addLog("Shipment $shipmentId not found in cache")
        }
        _showShipmentPicker.value = false
        advanceToNextStep()
    }

    /**
     * Called on long-press in the shipment picker → open manual entry form.
     */
    fun openManualClientEntry() {
        _showShipmentPicker.value = false
        // Find the current step's uiSchema for manual entry
        if (currentStepIndex in receivingSteps.indices) {
            val step = receivingSteps[currentStepIndex]
            val uiSchema = step["uiSchema"] as? String
            if (uiSchema != null) {
                _showReceivingModal.value = uiSchema
            }
        }
    }

    data class ShipmentDisplayItem(
        val id: String,
        val trackingNumber: String,
        val senderName: String,       // pickup (raw)
        val senderAddress: String,
        val receiverName: String,     // delivery (raw)
        val receiverAddress: String,
        val clientName: String,       // smart-detected client (not us)
        val clientAddress: String,    // smart-detected client address
        val productType: String,      // Overnight, X-Change, DHL Paket...
        val receivedBy: String,       // кто принял (receiver field)
        val status: String,
        val date: String,
        val isMatched: Boolean = false
    )

    fun getShipmentDisplayList(): List<ShipmentDisplayItem> {
        val scannedBarcode = receivingData["postal_barcode"] as? String
        return cachedShipments.map { s ->
            val id = (s["id"] as? Number)?.toString() ?: s["id"]?.toString() ?: ""
            val track = s["trackingNumber"] as? String ?: "?"
            val status = s["status"] as? String ?: "?"

            var senderName = ""
            var senderAddr = ""
            var receiverName = ""
            var receiverAddr = ""
            var clientName = ""
            var clientAddr = ""
            var productType = ""
            var receivedBy = ""
            var reference = ""

            val rawStr = s["rawResponse"] as? String
            if (rawStr != null) {
                try {
                    val raw = JSONObject(rawStr)
                    // Sender (pickup)
                    senderName = raw.optString("pickup_name", "")
                    val sName2 = raw.optString("pickup_name2", "")
                    if (sName2.isNotEmpty() && sName2 != senderName) senderName = "$senderName $sName2".trim()
                    val pStreet = raw.optString("pickup_street", "")
                    val pZip = raw.optString("pickup_zip", "")
                    val pCity = raw.optString("pickup_city", "")
                    senderAddr = listOf(pStreet, "$pZip $pCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")

                    // Receiver (delivery)
                    receiverName = raw.optString("delivery_name", "")
                    if (receiverName.isEmpty()) receiverName = raw.optString("recipient_name", "")
                    val dStreet = raw.optString("delivery_street", raw.optString("recipient_street", ""))
                    val dZip = raw.optString("delivery_zip", raw.optString("recipient_zip", ""))
                    val dCity = raw.optString("delivery_city", raw.optString("recipient_city", ""))
                    receiverAddr = listOf(dStreet, "$dZip $dCity".trim()).filter { it.isNotEmpty() }.joinToString(", ")

                    // Smart client detection via Levenshtein. Whichever end IS
                    // our company (tiny edit distance to the warehouse name) is
                    // pure noise for the worker — hide it and show only the
                    // counterparty. If NEITHER end is us (stray parcel, or a
                    // deployment at a company with different flows) show BOTH.
                    val pickupIsUs = looksLikeOurCompany(senderName)
                    val deliveryIsUs = looksLikeOurCompany(receiverName)
                    when {
                        pickupIsUs && deliveryIsUs -> { clientName = "— intern —"; clientAddr = "" }
                        pickupIsUs -> { clientName = receiverName; clientAddr = receiverAddr }
                        deliveryIsUs -> { clientName = senderName; clientAddr = senderAddr }
                        senderName.isEmpty() -> { clientName = receiverName; clientAddr = receiverAddr }
                        receiverName.isEmpty() -> { clientName = senderName; clientAddr = senderAddr }
                        else -> {
                            // Both ends known, neither is us — show the pair.
                            clientName = "$senderName → $receiverName"
                            clientAddr = listOf(senderAddr, receiverAddr)
                                .filter { it.isNotEmpty() }.joinToString(" → ")
                        }
                    }

                    // Product type: OPAL=product_type, DHL=product
                    productType = raw.optString("product_type", "")
                    if (productType.isEmpty()) productType = raw.optString("product", "")

                    // Who received it
                    receivedBy = raw.optString("receiver", "")
                    if (receivedBy.isEmpty()) receivedBy = raw.optString("delivered_to_name", "")

                    reference = raw.optString("reference", "")
                } catch (_: Exception) {}
            }
            // No counterparty in the data (DHL inbound — the carrier reveals no
            // sender). Show the reference as the next-best hint. NEVER our own
            // company name: that tells the worker nothing.
            if (senderName.isEmpty()) senderName = "Unknown"
            if (clientName.isEmpty()) {
                clientName = if (reference.isNotEmpty()) "Ref: $reference" else "— Kunde? —"
                clientAddr = ""
            }

            // Date from lastActivityAt or createdAt
            var dateStr = ""
            val dateRaw = s["lastActivityAt"] as? String
                ?: s["createdAt"] as? String ?: ""
            if (dateRaw.length >= 10) {
                try {
                    dateStr = dateRaw.substring(8, 10) + "." + dateRaw.substring(5, 7) + "." + dateRaw.substring(2, 4)
                } catch (_: Exception) {
                    dateStr = dateRaw.take(10)
                }
            }

            val bestFuzzyId = fuzzyScores.maxByOrNull { it.value }?.key
            val isMatched = (scannedBarcode != null && track == scannedBarcode) ||
                (id == bestFuzzyId && (fuzzyScores[id] ?: 0) >= 3)

            ShipmentDisplayItem(id, track, senderName, senderAddr, receiverName, receiverAddr, clientName, clientAddr, productType, receivedBy, status, dateStr, isMatched)
        }.sortedByDescending { fuzzyScores[it.id] ?: 0 } // fuzzy candidates first, stable otherwise
    }

    fun exitReceivingMode() {
        _isReceivingMode.value = false
        currentStepIndex = 0
        fuzzyScores = emptyMap()
        receivingData.clear()
        contentsToggles.clear()
        contentsBarcodes.clear()
        activeContentItem = null
        packagingSubLevel = false
        selectedPackaging = null
        _showReceivingModal.value = null
        addLog("Exited Receiving Mode")
        initializeGrid()
    }

    private fun handleReceivingButtonClick(action: String): String {
        when {
            action == "act_noop" -> return "handled"
            action == "act_back_contents" -> {
                packagingSubLevel = false
                addLog("Back to contents")
                renderReceivingGrid()
                return "handled"
            }
            action == "act_exit" -> {
                exitReceivingMode()
                return "handled"
            }
            action == "act_photo" -> return "capture_photo"
            action == "act_scan" -> {
                // In contents step, scan associates barcode with active item
                val isContentsStep = currentStepIndex in receivingSteps.indices &&
                    receivingSteps[currentStepIndex]["type"] == "contents_grid"
                if (isContentsStep && activeContentItem != null) {
                    addLog("Scanning for: $activeContentItem")
                }
                return "capture_barcode"
            }
            action == "act_save_receiving" -> {
                saveReceivingWorkflow()
                return "handled"
            }
            action == "act_contents_ok" -> {
                confirmContentsStep()
                return "handled"
            }
            action == "act_packaging" -> {
                packagingSubLevel = true
                addLog("Packaging options opened")
                renderReceivingGrid()
                return "handled"
            }
            action.startsWith("act_pkg_") -> {
                val value = action.removePrefix("act_pkg_")
                selectedPackaging = value
                packagingSubLevel = false
                addLog("Packaging: $value")
                renderReceivingGrid()
                return "handled"
            }
            action.startsWith("act_toggle_") -> {
                val key = action.removePrefix("act_toggle_")
                val wasOn = contentsToggles[key] == true
                contentsToggles[key] = !wasOn
                if (!wasOn) {
                    activeContentItem = key
                    addLog("$key: ON (active for scan)")
                } else {
                    contentsBarcodes.remove(key)
                    if (activeContentItem == key) activeContentItem = null
                    addLog("$key: OFF")
                }
                renderReceivingGrid()
                return "handled"
            }
            action.startsWith("step_") -> {
                val stepId = action.removePrefix("step_")
                handleStepClick(stepId)
                return "handled"
            }
        }
        return "handled"
    }

    private fun confirmContentsStep() {
        // Save all contents data to receivingData
        contentsToggles.forEach { (key, isOn) ->
            receivingData["${key}_included"] = isOn
            val barcode = contentsBarcodes[key]
            if (barcode != null) receivingData["${key}_barcode"] = barcode
        }
        if (selectedPackaging != null) {
            receivingData["packaging"] = selectedPackaging!!
        }

        // Log summary
        val summary = contentsToggles.entries.joinToString(", ") { (k, v) ->
            val bc = contentsBarcodes[k]
            if (v) "$k=ON${if (bc != null) "($bc)" else ""}" else "$k=OFF"
        }
        addLog("Contents confirmed: $summary, packaging=${selectedPackaging ?: "not set"}")

        // Reset contents state and advance
        packagingSubLevel = false
        advanceToNextStep()
    }

    private fun handleStepClick(stepId: String) {
        val stepIndex = receivingSteps.indexOfFirst { (it["id"] as? String) == stepId }
        if (stepIndex == -1) return

        // Only allow clicking current step
        if (stepIndex > currentStepIndex) {
            _receivingStatus.value = "Complete step ${currentStepIndex + 1} first"
            return
        }
        if (stepIndex < currentStepIndex) {
            _receivingStatus.value = "Step ${stepIndex + 1} already done"
            return
        }

        val step = receivingSteps[stepIndex]
        when (step["type"] as? String) {
            "action" -> {
                val stepAction = step["action"] as? String
                _receivingStatus.value = "Executing: ${step["label"]}"
                val scanMode = if (stepAction == "capture_photo") "workflow_capture" else "barcode"
                _receivingCameraNav.value = scanMode
            }
            "modal" -> {
                val uiSchema = step["uiSchema"] as? String ?: return
                _receivingStatus.value = "${step["label"]}"
                _showReceivingModal.value = uiSchema
            }
            "shipment_select" -> {
                _receivingStatus.value = "Select shipment or long-press for manual"
                _showShipmentPicker.value = true
            }
            "contents_grid" -> {
                // Contents grid is rendered inline — just update status
                _receivingStatus.value = "${step["label"]} — toggle items, then OK"
                addLog("Entering Contents step")
                // Initialize toggles from items definition
                @Suppress("UNCHECKED_CAST")
                val items = step["items"] as? List<Map<String, Any>> ?: emptyList()
                items.forEach { item ->
                    val key = item["key"] as? String ?: return@forEach
                    if (!contentsToggles.containsKey(key)) contentsToggles[key] = false
                }
                renderReceivingGrid()
            }
        }
    }

    fun onReceivingScan(barcode: String) {
        if (_isReceivingMode.value != true) return
        if (currentStepIndex !in receivingSteps.indices) return

        val step = receivingSteps[currentStepIndex]

        // Contents grid: associate barcode with active item
        if (step["type"] == "contents_grid") {
            val item = activeContentItem
            if (item != null) {
                contentsBarcodes[item] = barcode
                contentsToggles[item] = true
                addLog("$item barcode scanned: $barcode")
                _receivingStatus.value = "\u2713 $item: $barcode"
                renderReceivingGrid()
            } else {
                addLog("Barcode scanned but no active item selected: $barcode")
                _receivingStatus.value = "Select an item first, then scan"
            }
            return
        }

        val dataKey = step["dataKey"] as? String ?: return

        receivingData[dataKey] = barcode
        _receivingStatus.value = "\u2713 $dataKey: $barcode"
        addLog("Barcode scanned: $barcode")
        if (dataKey == "postal_barcode") {
            addLog("Box created (inactive) — identifiable by barcode: $barcode")
            tryAutoMatchShipment(barcode)
        } else {
            addLog("$dataKey = $barcode")
        }

        advanceToNextStep()
    }

    fun onReceivingPhotoCaptured(bitmap: Bitmap) {
        if (_isReceivingMode.value != true) return
        if (currentStepIndex !in receivingSteps.indices) return

        val step = receivingSteps[currentStepIndex]
        val dataKey = step["dataKey"] as? String ?: return

        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        receivingData[dataKey] = copy
        _receivingStatus.value = "✓ $dataKey: photo ${copy.width}x${copy.height}"
        addLog("Photo captured: $dataKey (${copy.width}x${copy.height})")
        advanceToNextStep()
    }

    fun advanceToNextStep() {
        currentStepIndex++
        if (currentStepIndex >= receivingSteps.size) {
            _receivingStatus.value = "All steps done — tap \uD83D\uDCBE SAVE"
            addLog("--- Receiving Summary ---")
            receivingData.forEach { (k, v) ->
                val display = when (v) {
                    is Bitmap -> "photo ${v.width}x${v.height}"
                    else -> v.toString()
                }
                addLog("  $k: $display")
            }
        } else {
            val next = receivingSteps[currentStepIndex]
            _receivingStatus.value = "Step ${currentStepIndex + 1}: ${next["label"]}"

            // Contents grid: initialize toggles when entering this step
            if (next["type"] == "contents_grid") {
                @Suppress("UNCHECKED_CAST")
                val items = next["items"] as? List<Map<String, Any>> ?: emptyList()
                items.forEach { item ->
                    val key = item["key"] as? String ?: return@forEach
                    if (!contentsToggles.containsKey(key)) contentsToggles[key] = false
                }
                _receivingStatus.value = "${next["label"]} — toggle items, then OK"
                addLog("Entering Contents step")
            }

            // Auto-open steps after a short delay (e.g. after scan → show list/form)
            if (next["autoOpen"] == true) {
                viewModelScope.launch {
                    delay(500) // Let camera screen finish closing
                    when (next["type"]) {
                        "modal" -> {
                            val uiSchema = next["uiSchema"] as? String
                            if (uiSchema != null) {
                                _showReceivingModal.postValue(uiSchema)
                            }
                        }
                        "shipment_select" -> {
                            _showShipmentPicker.postValue(true)
                        }
                    }
                }
            }
        }
        renderReceivingGrid()
    }

    fun setReceivingDataValue(key: String, value: Any) {
        receivingData[key] = value

        // If user selected a shipment from dropdown, populate client fields
        if (key == "selected_shipment_id") {
            val id = value.toString()
            if (id.isNotEmpty()) {
                val shipment = cachedShipments.find {
                    (it["id"] as? Number)?.toString() == id || it["id"]?.toString() == id
                }
                if (shipment != null) {
                    populateClientData(shipment)
                    addLog("Shipment selected: $id")
                }
            }
        }
    }

    private fun saveReceivingWorkflow() {
        addLog("=== SAVING RECEIVING ===")
        val jsonData = org.json.JSONObject()
        receivingData.forEach { (key, value) ->
            when (value) {
                is String -> jsonData.put(key, value)
                is Boolean -> jsonData.put(key, value)
                is Bitmap -> jsonData.put(key, "photo:${value.width}x${value.height}")
                else -> jsonData.put(key, value.toString())
            }
        }
        addLog(jsonData.toString(2))

        try {
            onRepairEventSend?.invoke("RECEIVING", "workflow_complete", jsonData.toString())
            addLog("Sent workflow_complete event")
        } catch (e: Exception) {
            addLog("Send error: ${e.message}")
            onHapticError?.invoke()
        }

        var photoCount = 0
        receivingData.forEach { (key, value) ->
            if (value is Bitmap) {
                try {
                    onRepairPhotoUpload?.invoke("RECEIVING:$key", value)
                    photoCount++
                    addLog("Uploaded photo: $key")
                } catch (e: Exception) {
                    addLog("Photo upload error: ${e.message}")
                    onHapticError?.invoke()
                }
            }
        }
        addLog("=== DONE ($photoCount photos) ===")
        _receivingStatus.value = "✓ Saved! Exiting..."

        viewModelScope.launch {
            delay(2000)
            exitReceivingMode()
        }
    }

    private fun renderReceivingGrid() {
        val uiItems = mutableListOf<Map<String, Any>>()

        // Check if current step is contents_grid
        val isContentsStep = currentStepIndex in receivingSteps.indices &&
            receivingSteps[currentStepIndex]["type"] == "contents_grid"

        // Top-left: BACK/UNDO. Photo/Scan are pinned via placeSystemButtons.
        val backAction = if (isContentsStep && packagingSubLevel) "act_back_contents" else "act_noop"
        val backColor = if (isContentsStep && packagingSubLevel) "#546E7A" else "#37474F"
        val backLabel = if (isContentsStep && packagingSubLevel) "◀️\nBACK" else "↩️\nUNDO"
        uiItems.add(mapOf("type" to "button", "label" to backLabel, "color" to backColor, "action" to backAction))

        // Workflow step buttons
        receivingSteps.forEachIndexed { index, step ->
            val stepId = step["id"] as? String ?: "step_$index"
            @Suppress("UNCHECKED_CAST")
            val colors = step["colors"] as? Map<String, String>

            if (isContentsStep && index == currentStepIndex) {
                // Current contents step → OK or packaging pick indicator
                val okLabel = if (packagingSubLevel) "📦\nPick" else "OK \u2713"
                val okColor: Any = colors?.get("active") ?: "#FFCA28"
                uiItems.add(mapOf(
                    "type" to "button",
                    "label" to okLabel,
                    "color" to okColor,
                    "action" to if (packagingSubLevel) "act_noop" else "act_contents_ok"
                ))
            } else {
                val label = step["label"] as? String ?: "Step ${index + 1}"
                val color = when {
                    index < currentStepIndex -> colors?.get("done") ?: "#4CAF50"
                    index == currentStepIndex -> colors?.get("active") ?: "#FF9800"
                    // Subtle blue-grey tint for non-current steps during contents
                    isContentsStep -> "#263238"
                    else -> colors?.get("pending") ?: "#37474F"
                }
                uiItems.add(mapOf(
                    "type" to "button",
                    "label" to label,
                    "color" to color,
                    "action" to if (isContentsStep) "act_noop" else "step_$stepId"
                ))
            }
        }

        // Contents grid items (only when step 4 is active)
        if (isContentsStep) {
            val step = receivingSteps[currentStepIndex]
            @Suppress("UNCHECKED_CAST")
            val items = step["items"] as? List<Map<String, Any>> ?: emptyList()
            @Suppress("UNCHECKED_CAST")
            val packaging = step["packaging"] as? Map<String, Any>

            if (packagingSubLevel && packaging != null) {
                // Sub-level: packaging options
                @Suppress("UNCHECKED_CAST")
                val options = packaging["options"] as? List<Map<String, String>> ?: emptyList()
                options.forEach { opt ->
                    val value = opt["value"] ?: ""
                    val label = opt["label"] ?: value
                    val isSelected = selectedPackaging == value
                    uiItems.add(mapOf(
                        "type" to "button",
                        "label" to label,
                        "color" to if (isSelected) "#4CAF50" else "#5C6BC0",
                        "action" to "act_pkg_$value"
                    ))
                }
            } else {
                // Main level: item toggles + packaging button
                items.forEach { item ->
                    val key = item["key"] as? String ?: ""
                    val label = item["label"] as? String ?: key
                    val isOn = contentsToggles[key] == true
                    val isActive = activeContentItem == key
                    val barcode = contentsBarcodes[key]
                    val displayLabel = when {
                        barcode != null -> "$label\n\u2713"       // has barcode → green with checkmark
                        isActive -> "$label\n\uD83D\uDCE1"        // active for scan → blue with 📡
                        isOn -> "$label\n+"                        // present, not scanning → blue-grey with +
                        else -> label                              // not present → grey
                    }
                    val color = when {
                        barcode != null -> "#2E7D32"  // green = has barcode
                        isActive -> "#1565C0"          // blue = active for scanning
                        isOn -> "#37474F"              // blue-grey = present
                        else -> "#616161"              // grey = not present
                    }
                    uiItems.add(mapOf(
                        "type" to "button",
                        "label" to displayLabel,
                        "color" to color,
                        "action" to "act_toggle_$key"
                    ))
                }
                // Packaging button — always visible with current selection
                if (packaging != null) {
                    val pkgLabel = if (selectedPackaging != null) {
                        @Suppress("UNCHECKED_CAST")
                        val options = packaging["options"] as? List<Map<String, String>> ?: emptyList()
                        val selected = options.find { it["value"] == selectedPackaging }
                        "\uD83D\uDCE6\n${selected?.get("label") ?: selectedPackaging}"
                    } else "\uD83D\uDCE6\nPackaging"
                    uiItems.add(mapOf(
                        "type" to "button",
                        "label" to pkgLabel,
                        "color" to if (selectedPackaging != null) "#5C6BC0" else "#455A64",
                        "action" to "act_packaging"
                    ))
                }
            }
        }

        // SAVE button when all steps done
        if (currentStepIndex >= receivingSteps.size && receivingSteps.isNotEmpty()) {
            uiItems.add(mapOf("type" to "button", "label" to "\uD83D\uDCBE SAVE", "color" to "#2196F3", "action" to "act_save_receiving"))
        }

        gridManager.clearAndReset()
        placeSystemButtons(scanLabel = "\uD83D\uDD0D\nSCAN")
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT — native half-slot (dimmed during contents step)
        _exitButton.postValue(HalfButtonState(
            label = "\u2715",
            colorHex = if (isContentsStep) "#263238" else "#F44336",
            action = if (isContentsStep) "act_noop" else "act_exit"
        ))

        updateRenderCells()
    }

    // --- DEVICE CHECK MODE LOGIC ---

    fun enterDeviceCheckMode() {
        _isDeviceCheckMode.value = true
        targetedStep = null
        slotWaitingForInit = null
        // Restore saved slot bindings
        val saved = onLoadDeviceCheckSlots?.invoke() ?: emptyList()
        val savedMap = saved.toMap()
        deviceCheckSlots.forEach { slot ->
            val savedBarcode = savedMap[slot.index]
            if (savedBarcode != null && slot.boxBarcode == null) {
                slot.boxBarcode = savedBarcode
            }
            slot.isActive = false
        }
        val boundCount = deviceCheckSlots.count { it.boxBarcode != null }
        _deviceCheckStatus.value = if (boundCount > 0) "Restored $boundCount slots" else "Select a slot to start"
        addLog("Entered Device Check Mode ($boundCount saved)")
        renderDeviceCheckGrid()
    }

    fun exitDeviceCheckMode() {
        _isDeviceCheckMode.value = false
        targetedStep = null
        slotWaitingForInit = null
        deviceCheckSlots.forEach { it.isActive = false }
        initializeGrid()
    }

    /**
     * Cross-mode auto-enter: check if barcode matches a device check slot.
     * If match, exit current mode, enter device check, activate slot.
     * Returns true if handled.
     */
    fun checkDeviceCheckAutoEnter(barcode: String): Boolean {
        val matchSlot = deviceCheckSlots.find { it.boxBarcode == barcode }
        if (matchSlot == null) return false

        // Exit whatever mode we're in
        if (_isRepairMode.value == true) exitRepairMode()
        if (_isReceivingMode.value == true) exitReceivingMode()
        if (_isDeviceCheckMode.value != true) {
            _isDeviceCheckMode.value = true
            targetedStep = null
            slotWaitingForInit = null
        }

        activateDeviceCheckSlot(matchSlot.index)
        _deviceCheckStatus.value = "AUTO: ${barcode.takeLast(10)}"
        addLog("Auto-enter device check: slot #${matchSlot.index+1}")
        return true
    }

    private fun handleDeviceCheckButtonClick(action: String): String {
        when {
            action == "act_exit" -> {
                exitDeviceCheckMode()
                return "handled"
            }
            action == "act_photo" -> return "capture_photo"
            action == "act_scan" -> return "capture_barcode"
            action == "act_upload_check" -> {
                val active = deviceCheckSlots.find { it.isActive }
                if (active != null) uploadDeviceCheck(active)
                return "handled"
            }
            action.startsWith("check_slot_") -> {
                val index = action.removePrefix("check_slot_").toIntOrNull()
                if (index != null && index in deviceCheckSlots.indices) {
                    val slot = deviceCheckSlots[index]
                    if (slot.boxBarcode == null) {
                        // Empty slot — activate and wait for barcode init
                        deviceCheckSlots.forEach { it.isActive = false }
                        slot.isActive = true
                        slotWaitingForInit = index
                        targetedStep = null
                        _deviceCheckStatus.value = "Slot #${index+1}: Scan BOX barcode"
                        _activeSlotPhoto.value = null
                    } else {
                        // Initialized slot — activate and show steps
                        activateDeviceCheckSlot(index)
                    }
                    renderDeviceCheckGrid()
                }
                return "handled"
            }
            action.startsWith("dc_step_") -> {
                val stepName = action.removePrefix("dc_step_")
                try {
                    val stepId = DcStepId.valueOf(stepName)
                    targetedStep = stepId
                    val stepDef = DC_STEPS.first { it.id == stepId }
                    val instruction = if (stepDef.type == "photo") "take PHOTO" else "SCAN barcode"
                    _deviceCheckStatus.value = "Target: ${stepDef.label} — $instruction"
                    renderDeviceCheckGrid()
                } catch (_: Exception) {}
                return "handled"
            }
        }
        return "handled"
    }

    private fun activateDeviceCheckSlot(index: Int) {
        deviceCheckSlots.forEach { it.isActive = false }
        deviceCheckSlots[index].isActive = true
        slotWaitingForInit = null
        targetedStep = null

        val slot = deviceCheckSlots[index]
        // Background photo: first available from stepData
        val photo = slot.stepData[DcStepId.CONTENT_PHOTO] as? Bitmap
            ?: slot.stepData[DcStepId.CONDITION_PHOTO] as? Bitmap
        _activeSlotPhoto.value = photo

        val doneCount = slot.stepData.size
        val requiredDone = DC_STEPS.filter { it.required }.count { slot.stepData.containsKey(it.id) }
        val totalRequired = DC_STEPS.count { it.required }
        _deviceCheckStatus.value = "Slot #${index+1}: ${slot.boxBarcode?.takeLast(8)} ($requiredDone/$totalRequired)"

        renderDeviceCheckGrid()
    }

    fun onDeviceCheckScan(barcode: String) {
        // 1. Auto-switch: if barcode matches another slot, activate it
        val matchSlot = deviceCheckSlots.find { it.boxBarcode == barcode }
        if (matchSlot != null) {
            activateDeviceCheckSlot(matchSlot.index)
            _deviceCheckStatus.value = "Switched: ${barcode.takeLast(10)}"
            return
        }

        // 2. Slot init: active slot waiting for box barcode
        if (slotWaitingForInit != null) {
            val slot = deviceCheckSlots[slotWaitingForInit!!]
            slot.boxBarcode = barcode
            slotWaitingForInit = null
            persistDeviceCheckSlots()
            activateDeviceCheckSlot(slot.index)
            _deviceCheckStatus.value = "Slot #${slot.index+1} initialized: ${barcode.takeLast(8)}"
            addLog("DC Slot #${slot.index+1} init: $barcode")
            return
        }

        // 3. Active slot — route to targeted step or smart default
        val active = deviceCheckSlots.find { it.isActive }
        if (active == null || active.boxBarcode == null) {
            _deviceCheckStatus.value = "Select a slot first!"
            return
        }

        val step = targetedStep ?: run {
            // Smart default: if SCAN_DEVICE not done, target it
            if (!active.stepData.containsKey(DcStepId.SCAN_DEVICE)) DcStepId.SCAN_DEVICE
            else { _deviceCheckStatus.value = "Tap a step first"; return }
        }

        if (step != DcStepId.SCAN_DEVICE) {
            _deviceCheckStatus.value = "Scan only for Scan Dev step"
            return
        }

        // Validate barcode match
        if (barcode == active.boxBarcode) {
            active.deviceBarcode = barcode
            active.stepData[DcStepId.SCAN_DEVICE] = barcode
            targetedStep = null
            addLog("DC Slot #${active.index+1}: Device MATCHED!")
            _deviceCheckStatus.value = "Slot #${active.index+1}: Device MATCHED!"
        } else {
            addLog("DC Slot #${active.index+1}: MISMATCH! Box=${active.boxBarcode}, Dev=$barcode")
            _deviceCheckStatus.value = "MISMATCH! Expecting: ${active.boxBarcode}"
        }
        renderDeviceCheckGrid()
    }

    fun onDeviceCheckPhotoCaptured(bitmap: Bitmap) {
        val active = deviceCheckSlots.find { it.isActive }
        if (active == null || active.boxBarcode == null) return

        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        // Determine target step
        val step = targetedStep ?: run {
            // Smart default: first incomplete required photo step
            DC_STEPS.firstOrNull { it.type == "photo" && it.required && !active.stepData.containsKey(it.id) }?.id
                ?: DC_STEPS.firstOrNull { it.type == "photo" && !active.stepData.containsKey(it.id) }?.id
                ?: run { _deviceCheckStatus.value = "All photos taken!"; return }
        }

        val stepDef = DC_STEPS.firstOrNull { it.id == step }
        if (stepDef == null || stepDef.type != "photo") {
            _deviceCheckStatus.value = "Selected step needs SCAN, not photo"
            return
        }

        active.stepData[step] = copy
        _activeSlotPhoto.value = copy
        targetedStep = null
        addLog("DC Slot #${active.index+1}: ${stepDef.label} photo taken")

        val requiredDone = DC_STEPS.filter { it.required }.count { active.stepData.containsKey(it.id) }
        val totalRequired = DC_STEPS.count { it.required }
        _deviceCheckStatus.value = "Slot #${active.index+1}: ${stepDef.label} OK ($requiredDone/$totalRequired)"

        if (isSlotReadyForUpload(active)) {
            _deviceCheckStatus.value = "Slot #${active.index+1}: Ready to UPLOAD!"
        }
        renderDeviceCheckGrid()
    }

    private fun isSlotReadyForUpload(slot: DeviceCheckSlot): Boolean {
        if (slot.boxBarcode == null) return false
        return DC_STEPS.filter { it.required }.all { slot.stepData.containsKey(it.id) }
    }

    private fun uploadDeviceCheck(slot: DeviceCheckSlot) {
        addLog("Uploading device check slot #${slot.index+1}")

        val payload = JSONObject().apply {
            put("box_barcode", slot.boxBarcode)
            put("device_barcode", slot.deviceBarcode)
            put("has_impedance", slot.stepData.containsKey(DcStepId.IMPEDANCE_PHOTO))
            put("step_count", slot.stepData.size)
        }

        // 1. Send metadata event
        onRepairEventSend?.invoke("DEVICE_CHECK", "check_complete", payload.toString())

        // 2. Upload regular photos
        val photoTags = listOf(
            DcStepId.CONTENT_PHOTO to "CONTENT",
            DcStepId.CONDITION_PHOTO to "CONDITION",
            DcStepId.LEFTOVER_PHOTO to "LEFTOVER"
        )
        for ((stepId, tag) in photoTags) {
            val bmp = slot.stepData[stepId] as? Bitmap
            if (bmp != null) onRepairPhotoUpload?.invoke("CHECK:${slot.boxBarcode}:$tag", bmp)
        }

        // 3. Impedance photo — saved separately with distinct event for analysis pipeline
        val impedanceBmp = slot.stepData[DcStepId.IMPEDANCE_PHOTO] as? Bitmap
        if (impedanceBmp != null) {
            onRepairPhotoUpload?.invoke("CHECK:${slot.boxBarcode}:IMPEDANCE", impedanceBmp)
            onRepairEventSend?.invoke("DEVICE_CHECK", "impedance_photo",
                JSONObject().apply {
                    put("box_barcode", slot.boxBarcode)
                    put("device_barcode", slot.deviceBarcode)
                }.toString()
            )
        }

        // 4. Reset slot
        slot.stepData.clear()
        slot.boxBarcode = null
        slot.deviceBarcode = null
        slot.isActive = false
        targetedStep = null
        _activeSlotPhoto.value = null
        persistDeviceCheckSlots()
        _deviceCheckStatus.value = "Slot #${slot.index+1} uploaded & cleared"
        addLog("DC Slot #${slot.index+1} uploaded & cleared")
        renderDeviceCheckGrid()
    }

    private fun persistDeviceCheckSlots() {
        val bound = deviceCheckSlots.filter { it.boxBarcode != null }
            .map { Pair(it.index, it.boxBarcode!!) }
        onSaveDeviceCheckSlots?.invoke(bound)
    }

    private fun renderDeviceCheckGrid() {
        val uiItems = mutableListOf<Map<String, Any>>()
        val active = deviceCheckSlots.find { it.isActive }

        // Row 1: Action buttons
        val canUpload = active != null && isSlotReadyForUpload(active)
        if (canUpload) {
            uiItems.add(mapOf("type" to "button", "label" to "📤\nUPLOAD", "color" to "#2196F3", "action" to "act_upload_check"))
        } else {
            uiItems.add(mapOf("type" to "button", "label" to "↩️\nUNDO", "color" to "#37474F", "action" to "act_undo"))
        }
        // Photo/Scan are pinned via placeSystemButtons (fixed positions).

        // Row 2+: 3 Slots
        // Colors: grey=inactive, yellow=initializing, blue=initialized-not-done, green=ready
        deviceCheckSlots.forEach { slot ->
            val color = when {
                isSlotReadyForUpload(slot) -> "#4CAF50"  // Green: all required steps done
                slot.isActive && slot.boxBarcode == null -> "#FFEB3B"  // Yellow: waiting for init barcode
                slot.boxBarcode != null -> "#2196F3"  // Blue: initialized, steps in progress
                else -> "#333333"  // Grey: empty/inactive
            }
            val label = when {
                slot.boxBarcode != null -> "Slot ${slot.index+1}\n${slot.boxBarcode!!.takeLast(6)}"
                slot.isActive -> "Slot ${slot.index+1}\nSCAN..."
                else -> "Slot ${slot.index+1}\n+"
            }
            uiItems.add(mapOf(
                "type" to "button",
                "label" to label,
                "color" to color,
                "action" to "check_slot_${slot.index}"
            ))
        }

        // Row 3+: Steps for active slot (only when initialized)
        // Colors: grey=not done, yellow=targeted, green=done
        if (active != null && active.boxBarcode != null) {
            DC_STEPS.forEach { stepDef ->
                val isDone = active.stepData.containsKey(stepDef.id)
                val isTargeted = targetedStep == stepDef.id
                val color = when {
                    isDone -> "#4CAF50"       // Green: step completed
                    isTargeted -> "#FFEB3B"   // Yellow: targeted for next action
                    else -> "#424242"         // Grey: not started
                }
                val prefix = if (!stepDef.required && !isDone) "* " else ""
                val suffix = if (isDone) " OK" else ""
                uiItems.add(mapOf(
                    "type" to "button",
                    "label" to "$prefix${stepDef.label}$suffix",
                    "color" to color,
                    "action" to "dc_step_${stepDef.id.name}"
                ))
            }
        }

        gridManager.clearAndReset()
        placeSystemButtons(scanLabel = "🔲\nSCAN")
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT button — native half-slot
        _exitButton.postValue(HalfButtonState("✕", "#F44336", "act_exit"))

        updateRenderCells()
    }

    // --- INVENTORY MODE LOGIC ---

    fun enterInventoryMode() {
        _isInventoryMode.value = true
        _isRestockMode.value = false
        _isRepairMode.value = false
        _isReceivingMode.value = false
        _isDeviceCheckMode.value = false

        currentInventoryLocation = ""
        waitingForManualLocation = false
        lastScannedType = ""
        lastScannedInventoryItem = ""
        decryptedLocationId = null
        decryptedItemId = null
        _inventoryItemPhoto.value = null
        _inventoryLocationPhoto.value = null
        inventoryItems.clear()
        expectedInventory.clear()
        isNumpadActive = false
        numpadInput = ""
        updateInventoryConsole()
        renderInventoryGrid()
        addLog("Entered Inventory Mode")
    }

    fun exitInventoryMode() {
        _isInventoryMode.value = false
        waitingForManualLocation = false
        expectedInventory.clear()
        isNumpadActive = false
        numpadInput = ""
        initializeGrid()
    }

    private fun handleInventoryButtonClick(action: String): String {
        // While numpad is active, only process numpad actions
        if (isNumpadActive && !action.startsWith("act_num")) {
            return "handled"
        }

        when (action) {
            // --- Numpad actions ---
            "act_numpad" -> {
                if (lastScannedInventoryItem.isEmpty()) {
                    _inventoryStatus.value = "⚠️ Scan an item first!"
                    return "handled"
                }
                isNumpadActive = true
                numpadInput = ""
                val entry = inventoryItems[lastScannedInventoryItem]
                val label = entry?.displayName ?: lastScannedInventoryItem.takeLast(12)
                _inventoryStatus.value = "$label  Qty: _"
                renderNumpadGrid()
                return "handled"
            }
            "act_num_0", "act_num_1", "act_num_2", "act_num_3", "act_num_4",
            "act_num_5", "act_num_6", "act_num_7", "act_num_8", "act_num_9" -> {
                if (!isNumpadActive) return "handled"
                val digit = action.removePrefix("act_num_")
                if (numpadInput.length < 5) {
                    numpadInput += digit
                    val entry = inventoryItems[lastScannedInventoryItem]
                    val label = entry?.displayName ?: lastScannedInventoryItem.takeLast(12)
                    _inventoryStatus.value = "$label  Qty: ${numpadInput}_"
                }
                return "handled"
            }
            "act_numpad_cancel" -> {
                isNumpadActive = false
                numpadInput = ""
                _inventoryStatus.value = "Numpad cancelled"
                renderInventoryGrid()
                return "handled"
            }
            "act_numpad_ok" -> {
                if (!isNumpadActive) return "handled"
                val qty = numpadInput.toIntOrNull()
                if (qty == null || qty < 0) {
                    _inventoryStatus.value = "⚠️ Invalid quantity"
                    return "handled"
                }
                val entry = inventoryItems[lastScannedInventoryItem]
                if (entry != null) {
                    entry.quantity = qty
                    val label = entry.displayName ?: lastScannedInventoryItem.takeLast(12)
                    addLog("🔢 $label qty set to $qty")
                    _inventoryStatus.value = "$label = $qty"
                    updateInventoryConsole()
                }
                isNumpadActive = false
                numpadInput = ""
                renderInventoryGrid()
                return "handled"
            }
            // --- Regular inventory actions ---
            "act_exit" -> {
                exitInventoryMode()
                return "handled"
            }
            "act_submit_inventory" -> {
                submitInventory()
                return "handled"
            }
            "act_clear_inventory" -> {
                inventoryItems.clear()
                expectedInventory.clear()
                isNumpadActive = false
                numpadInput = ""
                currentInventoryLocation = ""
                lastScannedInventoryItem = ""
                lastScannedType = ""
                decryptedLocationId = null
                decryptedItemId = null
                _inventoryItemPhoto.value = null
                _inventoryLocationPhoto.value = null
                _inventoryStatus.value = "Cleared"
                updateInventoryConsole()
                renderInventoryGrid()
                return "handled"
            }
            "act_set_location" -> {
                waitingForManualLocation = true
                _inventoryStatus.value = "Scan ANY barcode as location..."
                addLog("Manual location mode — next scan = location")
                return "capture_barcode"
            }
            "act_toggle_box" -> {
                inventoryBoxMode = !inventoryBoxMode
                val mode = if (inventoryBoxMode) "BOX" else "ITEM"
                _inventoryStatus.value = "Mode: $mode"
                addLog("Switched to $mode mode")
                renderInventoryGrid()
                return "handled"
            }
            "act_scan" -> return "capture_barcode"
            "act_photo" -> {
                if (!canTakeInventoryPhoto()) {
                    _inventoryStatus.value = "Scan first, then photo"
                    return "handled"
                }
                return "capture_photo"
            }
        }
        return "handled"
    }

    fun onInventoryScan(barcode: String, wasDecryptedUpstream: Boolean = false) {
        val cleanCode = barcode.trim()
        android.util.Log.e("INVENTORY", ">>> onInventoryScan called: '$cleanCode'")

        // --- DECRYPTION LAYER ---
        // Check if this is an encrypted ECK Smart QR code and try to decrypt locally
        var effectiveCode = cleanCode
        var decryptedPath: String? = null

        if (wasDecryptedUpstream) {
            // Already decrypted by observer — treat cleanCode as the decrypted smart code
            decryptedPath = cleanCode
            effectiveCode = cleanCode
            android.util.Log.e("INVENTORY", "🔓 Pre-decrypted: $cleanCode")
            addLog("🔓 $cleanCode")
        } else {
            val isEncryptedEck = com.xelth.eckwms_movfast.utils.EckSecurityManager.isEncryptedEckUrl(cleanCode)
            if (isEncryptedEck) {
                decryptedPath = com.xelth.eckwms_movfast.utils.EckSecurityManager.tryDecryptBarcode(cleanCode)
                if (decryptedPath != null) {
                    android.util.Log.e("INVENTORY", "🔓 Decrypted: $decryptedPath")
                    addLog("🔓 $decryptedPath")
                    effectiveCode = decryptedPath
                } else {
                    android.util.Log.e("INVENTORY", "🔒 Decryption failed")
                    addLog("⚠️ KEY MISMATCH - check server sync")
                    _inventoryStatus.value = "⚠️ Ключ не совпадает!"
                }
            } else {
                addLog(">>> $cleanCode")
            }
        }

        // --- CRM ENTITY INTERCEPT ---
        // If decrypted code is a CRM entity (company/person/opp), navigate to CRM screen
        val crmPattern = Regex("^(company|person|opp)-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$", RegexOption.IGNORE_CASE)
        val crmMatch = crmPattern.matchEntire(effectiveCode)
        if (crmMatch != null) {
            val entityType = crmMatch.groupValues[1].lowercase()
            val entityId = crmMatch.groupValues[2]
            android.util.Log.d("INVENTORY", "CRM entity detected: $entityType $entityId")
            addLog("CRM: $entityType $entityId")
            _navigateToCrm.postValue(Pair(entityType, entityId))
            return
        }

        // --- SECURITY FILTER ---
        // 1. Check for Trusted Link Barcodes (dynamic prefixes from server + hardcoded fallbacks)
        val isLinkBarcode = com.xelth.eckwms_movfast.utils.EckSecurityManager.isTrustedLinkBarcode(cleanCode)

        // 2. Check for Spoofing Attempts on Internal ID format
        // Raw barcodes (NOT Link Barcodes) that look like internal IDs are rejected
        // Skip if code was already decrypted from encrypted QR (proves authenticity)
        val isLegacySmartCode = cleanCode.length == 19 &&
                               cleanCode.matches(Regex("^[ibpl][0-9]{18}$", RegexOption.IGNORE_CASE))
        val isSmartTagCode = cleanCode.matches(Regex("^[a-z]+-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE))
        val isPotentialSpoof = !wasDecryptedUpstream &&
                               !isLinkBarcode &&
                               (isLegacySmartCode || isSmartTagCode)

        if (isPotentialSpoof) {
            addLog("⛔ SECURITY: Spoofed internal ID rejected")
            _inventoryStatus.value = "⛔ REJECTED"
            return
        }

        // --- END SECURITY FILTER ---

        // MANUAL MODE: SET LOC was pressed — any barcode becomes location
        if (waitingForManualLocation) {
            waitingForManualLocation = false
            if (currentInventoryLocation.isNotEmpty() && inventoryItems.isNotEmpty()) {
                submitInventoryAndStartNew(cleanCode)
            } else {
                currentInventoryLocation = cleanCode
                inventoryItems.clear()
                lastScannedInventoryItem = ""
                _inventoryItemPhoto.value = null
                _inventoryStatus.value = "LOC: ${effectiveCode.takeLast(20)} (manual)"
                addLog("Manual location: $effectiveCode")
                updateInventoryConsole()
                renderInventoryGrid()
            }
            return
        }

        // Smart Logic: Check if it's our Place Code
        // Use effectiveCode (decrypted path) for type detection
        // Decrypted format: p000... = place, i000... = item, b000... = box (19 chars: prefix + 18 digits)
        val isDecryptedPlace = decryptedPath?.startsWith("p", ignoreCase = true) == true
        val isDecryptedItem = decryptedPath?.startsWith("i", ignoreCase = true) == true ||
                              decryptedPath?.startsWith("b", ignoreCase = true) == true

        // Fallback for non-encrypted codes
        val isLinkPlace = isLinkBarcode && !wasDecryptedUpstream &&
                          (cleanCode.contains("/p/") || cleanCode.contains("/place/"))
        val isRawPlace = !isLinkBarcode && (
            cleanCode.startsWith("LOC-", ignoreCase = true) ||
            cleanCode.startsWith("p-", ignoreCase = true) ||
            cleanCode.matches(Regex("^p[0-9]{5,}$", RegexOption.IGNORE_CASE))
        )

        val isOurPlaceCode = isDecryptedPlace || isLinkPlace || isRawPlace

        android.util.Log.e("INVENTORY", "DecPlace=$isDecryptedPlace, LinkPlace=$isLinkPlace, RawPlace=$isRawPlace → Place=$isOurPlaceCode")
        addLog("Place=$isOurPlaceCode")

        // Wrap in coroutine for async offline DB lookups
        viewModelScope.launch {
            if (isOurPlaceCode) {
                // --- OUR LOCATION SCAN (smart flow) ---
                // Track for photo attachment
                lastScannedType = "place"
                decryptedLocationId = decryptedPath  // e.g., "p000000000000000001"
                decryptedItemId = null
                lastScannedInventoryItem = ""

                // Fat Client: try to find readable name from offline DB
                val localLoc = onLookupLocation?.invoke(cleanCode)
                val displayLoc = localLoc?.completeName
                    ?: localLoc?.name
                    ?: effectiveCode.takeLast(25)

                android.util.Log.e("INVENTORY", "Place branch: currentLoc='$currentInventoryLocation' cleanCode='$cleanCode'")
                if (currentInventoryLocation.isEmpty()) {
                    // Start new session
                    currentInventoryLocation = cleanCode
                    _inventoryItemPhoto.value = null

                    // Try to load existing place photo
                    if (decryptedLocationId != null) {
                        val existingPhoto = onLoadItemPhoto?.invoke(decryptedLocationId!!)
                        _inventoryLocationPhoto.value = existingPhoto
                        if (existingPhoto != null) {
                            addLog("📷 Place photo loaded")
                        }
                    } else {
                        _inventoryLocationPhoto.value = null
                    }

                    _inventoryStatus.value = "📍 $displayLoc"
                    addLog("📍 Location SET: $displayLoc")
                    if (localLoc != null) {
                        addLog("✅ Offline: ${localLoc.name}")
                    }
                    // Fetch expected inventory from server + load local records
                    expectedInventory.clear()
                    fetchExpectedInventory()
                    loadLocalInventoryRecords(cleanCode)
                    renderInventoryGrid()
                } else if (currentInventoryLocation.equals(cleanCode, ignoreCase = true)) {
                    // Scanned SAME location again -> Auto-Submit (close session)
                    addLog("📍 Same location → SUBMIT")
                    submitInventory()
                } else {
                    // Scanned DIFFERENT location -> Submit current, Start new
                    addLog("📍 New location: $displayLoc")
                    submitInventoryAndStartNew(cleanCode)
                }
            } else {
                // --- ITEM/BOX SCAN ---
                if (currentInventoryLocation.isEmpty()) {
                    addLog("⚠️ Item without location: $effectiveCode")
                    _inventoryStatus.value = "⚠️ Scan Location first!"
                } else {
                    // Track for photo attachment
                    lastScannedType = "item"
                    decryptedItemId = decryptedPath
                    // For plain EAN (no encrypted QR), generate Smart Code: i + base36(len) + 0-padded serial + EAN
                    if (decryptedItemId == null && cleanCode.all { it.isDigit() } && cleanCode.length in 8..14) {
                        val base36 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                        val serialLen = 17 - cleanCode.length
                        decryptedItemId = "i${base36[cleanCode.length]}${"0".repeat(serialLen)}$cleanCode"
                        android.util.Log.d("INVENTORY", "📷 Generated Smart Code from EAN: $decryptedItemId")
                    }
                    _inventoryLocationPhoto.value = null  // clear place photo when scanning item

                    // Add item to current session
                    val itemType = if (inventoryBoxMode) "box" else "item"
                    val entry = inventoryItems.getOrPut(cleanCode) {
                        InventoryEntry(quantity = 0, type = itemType, internalId = decryptedItemId)
                    }
                    if (entry.internalId == null && decryptedItemId != null) {
                        entry.internalId = decryptedItemId
                    }
                    entry.quantity++

                    // Fat Client: try to find product name from offline DB
                    val localProd = onLookupProduct?.invoke(cleanCode)
                    if (localProd != null && entry.displayName == null) {
                        entry.displayName = localProd.name
                    }
                    // Fallback: use expected inventory name
                    if (entry.displayName == null) {
                        entry.displayName = expectedInventory[cleanCode]?.productName
                    }
                    // Warehouse-level Exact soll: surface the expected qty for this scanned part.
                    if (!expectedInventory.containsKey(cleanCode)) {
                        sollMap[cleanCode]?.let { s ->
                            expectedInventory[cleanCode] = ExpectedItem(0L, entry.displayName ?: "", cleanCode, cleanCode, s, 0.0)
                        }
                    }

                    // Try to load existing photo using decrypted internal ID
                    if (entry.photo == null && decryptedItemId != null) {
                        val existingPhoto = onLoadItemPhoto?.invoke(decryptedItemId!!)
                        if (existingPhoto != null) {
                            entry.photo = existingPhoto
                            addLog("📷 Item photo loaded")
                        }
                    }

                    lastScannedInventoryItem = cleanCode
                    _inventoryItemPhoto.value = entry.photo

                    // --- AUTO-PHOTO: open camera + long vibrate on a new item ---
                    // Gated OFF by default for stocktake counting: the camera round-trip
                    // leaves the Compose focus system in an invalidated state, and the next
                    // hardware-scanner key event then crashes in
                    // FocusOwnerImpl.dispatchInterceptedSoftKeyboardEvent (a Compose bug).
                    // Bulk counting doesn't need a photo per part; the photo button still works.
                    android.util.Log.e("AUTO_PHOTO", "Check: qty=${entry.quantity}, photo=${entry.photo != null}, barcode=$cleanCode")
                    if (inventoryAutoPhoto && entry.quantity == 1 && entry.photo == null) {
                        entry.needsPhoto = true
                        addLog("📸 New item — take photo!")
                        android.util.Log.e("AUTO_PHOTO", ">>> TRIGGERING camera + vibrate for: $cleanCode")
                        onLongVibrate?.invoke()
                        _navigateToCamera.postValue(true)
                    }

                    val typeLabel = if (entry.type == "box") "📦" else "🔧"
                    val photoIcon = if (entry.photo != null) " 📷" else ""

                    // Show previous count from inventory_records (PDA truth) or product DB
                    val expectedQty = expectedInventory[cleanCode]?.quantity
                    val displayName = entry.displayName ?: localProd?.name ?: effectiveCode.takeLast(20)
                    if (expectedQty != null) {
                        val expStr = if (expectedQty % 1.0 == 0.0) expectedQty.toInt().toString() else "%.1f".format(expectedQty)
                        addLog("$typeLabel $displayName ${entry.quantity}/$expStr$photoIcon")
                        _inventoryStatus.value = "$displayName ${entry.quantity}/$expStr"
                    } else if (localProd != null) {
                        addLog("$typeLabel ${localProd.name} x${entry.quantity}$photoIcon")
                        _inventoryStatus.value = "${localProd.defaultCode} x${entry.quantity}"
                    } else {
                        addLog("$typeLabel $displayName x${entry.quantity}$photoIcon")
                        _inventoryStatus.value = "$typeLabel $displayName"
                    }
                    updateInventoryConsole()
                }
            }
        }
    }

    /** Attach photo to the last scanned inventory item or place.
     *  UI update is instant; disk save + upload runs in background. */
    fun onInventoryPhotoCaptured(bitmap: Bitmap) {
        val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        when (lastScannedType) {
            "place" -> {
                if (currentInventoryLocation.isEmpty()) {
                    addLog("⚠️ No location to attach photo to")
                    return
                }
                _inventoryLocationPhoto.value = copy
                val saveId = decryptedLocationId ?: "p_$currentInventoryLocation"
                addLog("📷 Place photo saved")
                // Save to disk in background
                viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    onSaveItemPhoto?.invoke(saveId, copy)
                }
            }
            "item" -> {
                if (lastScannedInventoryItem.isEmpty()) {
                    addLog("⚠️ No item to attach photo to")
                    return
                }
                val entry = inventoryItems[lastScannedInventoryItem]
                if (entry != null) {
                    entry.photo = copy
                    entry.needsPhoto = false
                    _inventoryItemPhoto.value = copy
                    val saveId = decryptedItemId ?: run {
                        val prefix = if (entry.type == "box") "b" else "i"
                        "$prefix$lastScannedInventoryItem"
                    }
                    addLog("📷 Item photo: ${saveId.takeLast(12)}")
                    updateInventoryConsole()
                    // Save to disk in background
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        onSaveItemPhoto?.invoke(saveId, copy)
                    }
                }
            }
            else -> {
                addLog("⚠️ Scan something first!")
            }
        }
    }

    /** Check if photo can be taken (something was scanned) */
    fun canTakeInventoryPhoto(): Boolean {
        return lastScannedType.isNotEmpty() &&
               (lastScannedType == "place" && currentInventoryLocation.isNotEmpty() ||
                lastScannedType == "item" && lastScannedInventoryItem.isNotEmpty())
    }

    /** Submit current inventory and immediately start new session with given location */
    private fun submitInventoryAndStartNew(newLocation: String) {
        if (currentInventoryLocation.isNotEmpty() && inventoryItems.isNotEmpty()) {
            // Build and send payload for current session
            val itemsArray = JSONArray()
            inventoryItems.forEach { (barcode, entry) ->
                val itemObj = JSONObject()
                itemObj.put("barcode", barcode)
                itemObj.put("quantity", entry.quantity)
                itemObj.put("type", entry.type)
                itemObj.put("has_photo", entry.photo != null)
                itemsArray.put(itemObj)
            }

            val payload = JSONObject()
            payload.put("location", currentInventoryLocation)
            payload.put("items", itemsArray)
            payload.put("timestamp", System.currentTimeMillis())

            val itemCount = inventoryItems.size
            val loc = currentInventoryLocation

            // Book the counted quantities into stock (put-away, offline-queued on failure).
            pushPutAways(loc, inventoryItems.toMap())

            // Upload photos for items that have them
            inventoryItems.forEach { (barcode, entry) ->
                if (entry.photo != null) {
                    onRepairPhotoUpload?.invoke(barcode, entry.photo!!)
                }
            }

            // Submit with discrepancy detection (async)
            viewModelScope.launch {
                val result = onInventorySubmit?.invoke("INVENTORY", "count_submit", payload.toString())
                if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                    try {
                        val response = JSONObject(result.data)
                        val discArr = response.optJSONArray("discrepancies")
                        if (discArr != null && discArr.length() > 0) {
                            addLog("⚠️ ${discArr.length()} discrepancies @ $loc")
                        } else {
                            addLog("✅ SUBMITTED: $itemCount items @ $loc")
                        }
                    } catch (_: Exception) {
                        addLog("✅ SUBMITTED: $itemCount items @ $loc")
                    }
                } else {
                    onRepairEventSend?.invoke("INVENTORY", "count_submit", payload.toString())
                    addLog("✅ SUBMITTED: $itemCount items @ $loc")
                }
            }
        }

        // Save to local DB (PDA = source of truth) before clearing
        saveCurrentInventoryToLocal(currentInventoryLocation, inventoryItems.toMap())

        // Start new session
        inventoryItems.clear()
        expectedInventory.clear()
        lastScannedInventoryItem = ""
        lastScannedType = "place"  // new location is scanned
        decryptedItemId = null
        _inventoryItemPhoto.value = null
        _inventoryLocationPhoto.value = null
        currentInventoryLocation = newLocation
        _inventoryStatus.value = "LOC: $newLocation"
        addLog("📍 Location SET: $newLocation")
        fetchExpectedInventory()
        loadLocalInventoryRecords(newLocation)
        updateInventoryConsole()
        renderInventoryGrid()
    }

    private fun fetchExpectedInventory() {
        // Soll = warehouse-level Exact scraped stock (reconcile), not per-location. Loaded
        // into sollMap; a scanned item's soll is copied into expectedInventory on demand
        // (see the item-scan branch), so the per-session "NOT COUNTED" list stays clean.
        viewModelScope.launch {
            try {
                val raw = onFetchExpectedSoll?.invoke(inventoryWarehouse)?.trim()
                if (raw.isNullOrEmpty() || raw == "null") {
                    android.util.Log.e("INVENTORY", "fetchExpected(reconcile): empty for $inventoryWarehouse")
                    return@launch
                }
                val json = org.json.JSONObject(raw)
                val arr = json.optJSONArray("lines") ?: org.json.JSONArray()
                sollMap.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val code = obj.optString("default_code", "")
                    if (code.isNotEmpty()) sollMap[code] = obj.optDouble("soll", 0.0)
                }
                // Backfill expected for anything already counted this session.
                inventoryItems.keys.forEach { code ->
                    sollMap[code]?.let { s ->
                        if (!expectedInventory.containsKey(code)) {
                            expectedInventory[code] = ExpectedItem(0L, "", code, code, s, 0.0)
                        }
                    }
                }
                addLog("📋 Soll (Exact $inventoryWarehouse): ${sollMap.size}")
                updateInventoryConsole()
            } catch (e: Exception) {
                android.util.Log.e("INVENTORY", "fetchExpected(reconcile) exception", e)
            }
        }
    }

    /** Push each counted line to its shelf as a put-away (op=set = the counted
     *  on-hand). Optimistic; the callback offline-queues on failure. Iterates a
     *  snapshot so the session clear that follows submit can't race it. */
    private fun pushPutAways(shelf: String, items: Map<String, InventoryEntry>) {
        if (shelf.isEmpty() || items.isEmpty()) return
        val wh = inventoryWarehouse
        viewModelScope.launch {
            items.forEach { (code, entry) ->
                try {
                    onPutAwayItem?.invoke(code, shelf, wh, entry.quantity.toDouble())
                } catch (e: Exception) {
                    android.util.Log.e("INVENTORY", "put-away failed for $code", e)
                }
            }
            addLog("📦 ${items.size} → put-away @ ${shelf.takeLast(12)}")
        }
    }

    private fun updateInventoryConsole() {
        val header = if (currentInventoryLocation.isNotEmpty()) {
            "--- INVENTORY @ $currentInventoryLocation ---"
        } else {
            "--- SCAN LOCATION FIRST ---"
        }
        val lines = mutableListOf<String>()
        val countedBarcodes = inventoryItems.keys.toSet()

        // Show counted items with expected comparison
        inventoryItems.entries.sortedBy { it.value.displayName ?: it.key }.forEach { (barcode, entry) ->
            val typeIcon = if (entry.type == "box") "📦" else "🔧"
            val photoIcon = if (entry.photo != null) "📷" else ""
            val label = entry.displayName
                ?: expectedInventory[barcode]?.productName
                ?: barcode.takeLast(12)
            val expected = expectedInventory[barcode]
            if (expected != null) {
                val expQty = if (expected.quantity % 1.0 == 0.0) expected.quantity.toInt().toString() else "%.1f".format(expected.quantity)
                val mark = if (entry.quantity == expected.quantity.toInt()) " ✓" else ""
                lines.add("$typeIcon %-14s: ${entry.quantity}/$expQty$mark $photoIcon".format(label.take(14)))
            } else {
                lines.add("$typeIcon %-14s: ${entry.quantity} (new) $photoIcon".format(label.take(14)))
            }
        }

        // Show expected-but-not-counted items
        if (expectedInventory.isNotEmpty()) {
            val notCounted = expectedInventory.keys - countedBarcodes
            if (notCounted.isNotEmpty()) {
                lines.add("--- NOT COUNTED ---")
                notCounted.forEach { barcode ->
                    val exp = expectedInventory[barcode]!!
                    val label = exp.productName.ifEmpty { exp.defaultCode.ifEmpty { barcode.takeLast(12) } }
                    val expQty = if (exp.quantity % 1.0 == 0.0) exp.quantity.toInt().toString() else "%.1f".format(exp.quantity)
                    lines.add("🔧 %-14s: 0/$expQty ⚠️".format(label.take(14)))
                }
            }
        }

        // Summary
        val totalCounted = inventoryItems.values.sumOf { it.quantity }
        val boxCount = inventoryItems.values.count { it.type == "box" }
        val itemCount = inventoryItems.values.count { it.type == "item" }
        val summary = if (expectedInventory.isNotEmpty()) {
            "Counted: ${countedBarcodes.size}/${expectedInventory.size} | Qty: $totalCounted"
        } else {
            "Items: $itemCount | Boxes: $boxCount | Total: $totalCounted"
        }
        _consoleLogs.postValue(listOf(header) + lines + listOf("", summary))
    }

    private fun submitInventory() {
        if (currentInventoryLocation.isEmpty()) {
            _inventoryStatus.value = "Set location first!"
            return
        }
        if (inventoryItems.isEmpty()) {
            _inventoryStatus.value = "Nothing to submit!"
            return
        }

        // Pre-submit discrepancy warning
        if (expectedInventory.isNotEmpty()) {
            val notCounted = expectedInventory.keys - inventoryItems.keys
            val diffs = inventoryItems.entries.filter { (bc, e) ->
                val exp = expectedInventory[bc]
                exp != null && e.quantity != exp.quantity.toInt()
            }
            if (notCounted.isNotEmpty() || diffs.isNotEmpty()) {
                addLog("⚠️ SUBMITTING WITH DISCREPANCIES:")
                notCounted.forEach { bc ->
                    val exp = expectedInventory[bc]!!
                    addLog("  MISSING: ${exp.productName.take(20)} (exp ${exp.quantity.toInt()})")
                }
                diffs.forEach { (bc, entry) ->
                    val exp = expectedInventory[bc]!!
                    addLog("  DIFF: ${(entry.displayName ?: bc).take(20)} ${entry.quantity} vs ${exp.quantity.toInt()}")
                }
            }
        }

        val itemsArray = JSONArray()
        inventoryItems.forEach { (barcode, entry) ->
            val itemObj = JSONObject()
            itemObj.put("barcode", barcode)
            itemObj.put("quantity", entry.quantity)
            itemObj.put("type", entry.type)
            itemObj.put("has_photo", entry.photo != null)
            itemsArray.put(itemObj)
        }

        val payload = JSONObject()
        payload.put("location", currentInventoryLocation)
        payload.put("items", itemsArray)
        payload.put("timestamp", System.currentTimeMillis())

        val itemCount = inventoryItems.size
        val loc = currentInventoryLocation

        // Book the counted quantities into stock (put-away, offline-queued on failure).
        pushPutAways(loc, inventoryItems.toMap())

        // Upload photos for items that have them
        inventoryItems.forEach { (barcode, entry) ->
            if (entry.photo != null) {
                // Prefer decrypted Smart Code (i000.../b000...) for proper server auto-linking
                val uploadKey = entry.internalId ?: "ITEM:$barcode"
                addLog("📤 Upload photo: $uploadKey")
                onRepairPhotoUpload?.invoke(uploadKey, entry.photo!!)
            }
        }

        // Submit with discrepancy detection
        viewModelScope.launch {
            val result = onInventorySubmit?.invoke("INVENTORY", "count_submit", payload.toString())
            if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                try {
                    val response = JSONObject(result.data)
                    val discArr = response.optJSONArray("discrepancies")
                    if (discArr != null && discArr.length() > 0) {
                        addLog("⚠️ ${discArr.length()} DISCREPANCIES @ $loc:")
                        for (i in 0 until discArr.length()) {
                            val d = discArr.getJSONObject(i)
                            val name = d.optString("product_name", d.optString("barcode", "?"))
                            val expected = d.optDouble("expected_qty", 0.0)
                            val counted = d.optDouble("counted_qty", 0.0)
                            val delta = d.optDouble("delta", 0.0)
                            val deltaSign = if (delta > 0) "+" else ""
                            val expStr = if (expected % 1.0 == 0.0) expected.toInt().toString() else "%.1f".format(expected)
                            val cntStr = if (counted % 1.0 == 0.0) counted.toInt().toString() else "%.1f".format(counted)
                            addLog("  ⚠️ $name: counted $cntStr vs svr $expStr ($deltaSign${delta.toInt()})")
                        }
                        _inventoryStatus.value = "⚠️ $itemCount items, ${discArr.length()} discrepancies"
                    } else {
                        addLog("✅ SUBMITTED: $itemCount items @ $loc — no discrepancies")
                        _inventoryStatus.value = "✅ Submitted $itemCount items"
                    }
                } catch (e: Exception) {
                    addLog("✅ SUBMITTED: $itemCount items @ $loc")
                    _inventoryStatus.value = "✅ Submitted $itemCount items"
                }
            } else {
                // Fallback: fire-and-forget via old callback
                onRepairEventSend?.invoke("INVENTORY", "count_submit", payload.toString())
                addLog("✅ SUBMITTED: $itemCount items @ $loc")
                _inventoryStatus.value = "✅ Submitted $itemCount items"
            }

            // Update local DB with counted quantities (offline-first)
            inventoryItems.forEach { (barcode, entry) ->
                try {
                    onUpdateProductQty?.invoke(barcode, entry.quantity.toDouble())
                } catch (e: Exception) {
                    android.util.Log.e("INVENTORY", "Failed to update local qty for $barcode: ${e.message}")
                }
            }

            delay(2000)
            if (_isInventoryMode.value == true) {
                _inventoryStatus.value = "Scan next location..."
                renderInventoryGrid()
            }
        }

        // Save to local DB (PDA = source of truth) before clearing
        saveCurrentInventoryToLocal(currentInventoryLocation, inventoryItems.toMap())

        inventoryItems.clear()
        expectedInventory.clear()
        currentInventoryLocation = ""
        lastScannedInventoryItem = ""
        lastScannedType = ""
        decryptedLocationId = null
        decryptedItemId = null
        _inventoryItemPhoto.value = null
        _inventoryLocationPhoto.value = null
        updateInventoryConsole()
    }

    /** Persist current inventory session to local DB (PDA = source of truth) */
    private fun saveCurrentInventoryToLocal(location: String, items: Map<String, InventoryEntry>) {
        android.util.Log.e("INVENTORY", "saveCurrentInventoryToLocal: loc='$location', items=${items.size}")
        if (location.isEmpty() || items.isEmpty()) {
            android.util.Log.e("INVENTORY", "saveCurrentInventoryToLocal: SKIP (empty loc or items)")
            return
        }
        val records = items.map { (barcode, entry) ->
            com.xelth.eckwms_movfast.data.local.entity.InventoryRecordEntity(
                locationBarcode = location,
                productBarcode = barcode,
                productName = entry.displayName ?: barcode.takeLast(12),
                quantity = entry.quantity.toDouble(),
                type = entry.type
            )
        }
        android.util.Log.e("INVENTORY", "saveCurrentInventoryToLocal: ${records.size} records, callback=${onSaveInventoryRecords != null}")
        viewModelScope.launch {
            try {
                onSaveInventoryRecords?.invoke(location, records)
                android.util.Log.e("INVENTORY", "✅ Saved ${records.size} inventory records for $location")
            } catch (e: Exception) {
                android.util.Log.e("INVENTORY", "❌ Failed to save inventory records", e)
            }
        }
    }

    /** Load previously counted inventory from local DB and populate expectedInventory */
    private fun loadLocalInventoryRecords(location: String) {
        android.util.Log.e("INVENTORY", "loadLocalInventoryRecords: loc='$location', callback=${onLoadInventoryRecords != null}")
        viewModelScope.launch {
            try {
                val records = onLoadInventoryRecords?.invoke(location) ?: emptyList()
                android.util.Log.e("INVENTORY", "loadLocalInventoryRecords: got ${records.size} records")
                if (records.isNotEmpty()) {
                    records.forEach { rec ->
                        if (!expectedInventory.containsKey(rec.productBarcode)) {
                            expectedInventory[rec.productBarcode] = ExpectedItem(
                                productId = 0,
                                productName = rec.productName,
                                barcode = rec.productBarcode,
                                defaultCode = "",
                                quantity = rec.quantity,
                                reservedQty = 0.0
                            )
                        }
                    }
                    addLog("📦 Local: ${records.size} prev. items")
                    updateInventoryConsole()
                }
            } catch (e: Exception) {
                android.util.Log.e("INVENTORY", "Failed to load inventory records", e)
            }
        }
    }

    private fun renderInventoryGrid() {
        val uiItems = mutableListOf<Map<String, Any>>()

        // Row 1: LOC | PHOTO | SCAN
        val locationColor = if (currentInventoryLocation.isEmpty()) "#FF5722" else "#4CAF50"
        val locationLabel = if (currentInventoryLocation.isEmpty()) "📍\nSET" else "📍\n${currentInventoryLocation.takeLast(6)}"
        uiItems.add(mapOf("type" to "button", "label" to locationLabel, "color" to locationColor, "action" to "act_set_location"))

        // PHOTO button - enabled when place or item is scanned
        val canPhoto = canTakeInventoryPhoto()
        val photoColor = if (canPhoto) "#9C27B0" else "#424242"
        val photoLabel = when {
            lastScannedType == "place" && canPhoto -> "📍📷\nPLACE"
            lastScannedType == "item" && canPhoto -> "📷\nITEM"
            else -> "📷\nPHOTO"
        }
        // Photo (dynamic state) + Scan are pinned via placeSystemButtons.

        // Row 2: TYPE toggle | SUBMIT | CLEAR
        val typeColor = when {
            inventoryBoxMode -> "#FF9800"  // orange for box
            else -> "#795548"              // brown for item
        }
        val typeLabel = if (inventoryBoxMode) "📦\nBOX" else "🔧\nITEM"
        uiItems.add(mapOf("type" to "button", "label" to typeLabel, "color" to typeColor, "action" to "act_toggle_box"))
        uiItems.add(mapOf("type" to "button", "label" to "📤\nSEND", "color" to "#2196F3", "action" to "act_submit_inventory"))
        uiItems.add(mapOf("type" to "button", "label" to "🗑️\nCLEAR", "color" to "#D32F2F", "action" to "act_clear_inventory"))

        // Row 3: NUM button for manual quantity entry (bottom-left)
        val numEnabled = lastScannedInventoryItem.isNotEmpty()
        val numColor = if (numEnabled) "#607D8B" else "#424242"
        uiItems.add(mapOf("type" to "button", "label" to "🔢\nNUM", "color" to numColor, "action" to "act_numpad", "enabled" to numEnabled))

        gridManager.clearAndReset()
        placeSystemButtons(scanLabel = "🔲\nSCAN")
        gridManager.placeItems(uiItems, priority = 100)

        _exitButton.postValue(HalfButtonState("X", "#F44336", "act_exit"))

        updateRenderCells()
    }

    private fun renderNumpadGrid() {
        val uiItems = mutableListOf<Map<String, Any>>()
        // Phone layout: 1-2-3 / 4-5-6 / 7-8-9 / CANCEL-0-OK
        for (d in 1..9) {
            uiItems.add(mapOf("type" to "button", "label" to "$d", "color" to "#37474F", "action" to "act_num_$d"))
        }
        uiItems.add(mapOf("type" to "button", "label" to "❌\nCANCEL", "color" to "#D32F2F", "action" to "act_numpad_cancel"))
        uiItems.add(mapOf("type" to "button", "label" to "0", "color" to "#37474F", "action" to "act_num_0"))
        uiItems.add(mapOf("type" to "button", "label" to "✅\nOK", "color" to "#4CAF50", "action" to "act_numpad_ok"))

        gridManager.clearAndReset()
        gridManager.placeItems(uiItems, priority = 100)
        updateRenderCells()
    }

    // --- RESTOCK MODE LOGIC ---

    fun enterRestockMode() {
        _isRestockMode.value = true
        _isRepairMode.value = false
        _isReceivingMode.value = false
        _isDeviceCheckMode.value = false

        updateRestockConsole()
        renderRestockGrid()
        addLog("Entered Restock Mode")
    }

    fun exitRestockMode() {
        _isRestockMode.value = false
        initializeGrid()
    }

    private fun handleRestockButtonClick(action: String): String {
        when (action) {
            "act_exit" -> {
                exitRestockMode()
                return "handled"
            }
            "act_submit_restock" -> {
                submitRestockOrder()
                return "handled"
            }
            "act_clear_restock" -> {
                restockItems.clear()
                _restockStatus.value = "Order cleared"
                updateRestockConsole()
                renderRestockGrid()
                return "handled"
            }
            "act_scan" -> return "capture_barcode"
            "act_photo" -> return "capture_photo"
        }
        return "handled"
    }

    fun onRestockScan(barcode: String) {
        val cleanCode = barcode.trim().uppercase()

        val isValidPart = cleanCode.length in 8..15 &&
                cleanCode[0].isLetter() &&
                cleanCode.any { it.isDigit() }

        if (isValidPart) {
            val currentQty = restockItems[cleanCode] ?: 0
            restockItems[cleanCode] = currentQty + 1
            addLog("Added: $cleanCode (Total: ${restockItems[cleanCode]})")
            _restockStatus.value = "Added: $cleanCode"
            updateRestockConsole()
        } else {
            addLog("Invalid Part # format: $cleanCode")
            _restockStatus.value = "Invalid Part # format"
        }
    }

    private fun updateRestockConsole() {
        val header = "--- RESTOCK ORDER ---"
        val items = restockItems.entries.map { (part, qty) ->
            "%-12s : %d".format(part, qty)
        }.sorted()
        val summary = "Total Items: ${restockItems.values.sum()} | Unique: ${restockItems.size}"
        _consoleLogs.postValue(listOf(header) + items + listOf("", summary))
    }

    private fun submitRestockOrder() {
        if (restockItems.isEmpty()) {
            _restockStatus.value = "Order is empty!"
            return
        }

        val itemsArray = JSONArray()
        restockItems.forEach { (part, qty) ->
            val itemObj = JSONObject()
            itemObj.put("part_number", part)
            itemObj.put("quantity", qty)
            itemsArray.put(itemObj)
        }

        val payload = JSONObject()
        payload.put("items", itemsArray)
        payload.put("timestamp", System.currentTimeMillis())

        onRepairEventSend?.invoke("RESTOCK", "order_submit", payload.toString())

        addLog("Order submitted: ${restockItems.size} unique parts")
        _restockStatus.value = "Order Sent!"

        restockItems.clear()
        updateRestockConsole()

        viewModelScope.launch {
            delay(1500)
            if (_isRestockMode.value == true) _restockStatus.value = "Ready for next order"
        }
    }

    private fun renderRestockGrid() {
        val uiItems = mutableListOf<Map<String, Any>>()

        uiItems.add(mapOf("type" to "button", "label" to "📤\nSEND", "color" to "#2196F3", "action" to "act_submit_restock"))
        uiItems.add(mapOf("type" to "button", "label" to "🗑️\nCLEAR", "color" to "#D32F2F", "action" to "act_clear_restock"))
        // Scan + 🎤 are pinned via placeSystemButtons. Restock has no Photo action.

        gridManager.clearAndReset()
        placeSystemButtons(scanLabel = "🔲\nSCAN")
        gridManager.placeItems(uiItems, priority = 100)

        _exitButton.postValue(HalfButtonState("X", "#F44336", "act_exit"))

        updateRenderCells()
    }

    // --- NETWORK MODE ---
    // Console shows the live connection status; the hex grid offers Scan QR / Code /
    // Refresh / Exit. The net_* grid actions are routed by MainScreen.onGridAction (they
    // need navController + ScanRecoveryViewModel for the actual pairing).
    fun enterNetworkMode() {
        _isNetworkMode.value = true
        addLog("🌐 Network mode")
        renderNetworkGrid()
    }

    fun exitNetworkMode() {
        _isNetworkMode.value = false
        addLog("Exited Network mode")
        initializeGrid()
    }

    private fun renderNetworkGrid() {
        val uiItems = listOf(
            mapOf("type" to "button", "label" to "🔑\nCode", "color" to "#FF9800", "action" to "net_enter_code"),
            mapOf("type" to "button", "label" to "🔄\nStatus", "color" to "#607D8B", "action" to "net_refresh")
        )
        gridManager.clearAndReset()
        // Pin the scan button (kept as the usual 🔲 square) to the pairing-QR scan; no
        // photo button in this mode.
        placeSystemButtons(scanAction = "net_scan_qr")
        gridManager.placeItems(uiItems, priority = PRIORITIES.SCAN_BUTTON)
        _exitButton.postValue(HalfButtonState("✕", "#F44336", "act_exit"))
        updateRenderCells()
    }

    private fun handleNetworkButtonClick(action: String): String {
        return when (action) {
            "act_exit" -> { exitNetworkMode(); "handled" }
            // Routed by MainScreen.onGridAction (camera nav / code dialog / health check):
            "net_scan_qr", "net_enter_code", "net_refresh" -> action
            else -> "handled"
        }
    }

    // --- COMMON ---

    fun addLog(message: String) {
        val timestamp = System.currentTimeMillis()
        val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
        val logEntry = "${dateFormat.format(Date(timestamp))} $message"

        val currentLogs = _consoleLogs.value ?: emptyList()
        val updatedLogs = (listOf(logEntry) + currentLogs).take(50)
        _consoleLogs.postValue(updatedLogs)
    }

    fun clearLogs() {
        _consoleLogs.postValue(emptyList())
    }

    // --- TRANSPORT SWITCH LOG ---
    // Surface direct ↔ relay ↔ offline transitions in the console (they were silent;
    // only the half-button color changed). The last value lives in the VM so it survives
    // MainScreen leaving/re-entering composition.
    private var lastTransport: String? = null
    fun noteTransport(transport: String, label: String) {
        if (lastTransport != null && lastTransport != transport) addLog(label)
        lastTransport = transport
    }

    // --- PAIRING LOG BRIDGE ---
    // The dedicated Pairing Console screen was removed; pairing now runs in place and
    // its feedback streams into this console. The forward counter lives in the VM (not
    // a Compose `remember`) so it survives MainScreen leaving/re-entering composition
    // during the camera round-trip — otherwise the new pairing lines get skipped.
    private var pairingForwarded = 0

    /** Forward only the NEW lines of the cumulative pairing log into the console.
     *  A shrink (clearPairingLog) re-baselines so the next session starts clean. */
    fun forwardPairingLog(pairingLog: List<String>) {
        if (pairingLog.size < pairingForwarded) {
            pairingForwarded = pairingLog.size
            return
        }
        if (pairingLog.size > pairingForwarded) {
            for (i in pairingForwarded until pairingLog.size) {
                // Skip blank lines and pure "━━━" separators — noise in a shared console.
                pairingLog[i].takeIf { line -> line.isNotBlank() && line.any { it != '━' } }
                    ?.let { addLog(it) }
            }
            pairingForwarded = pairingLog.size
        }
    }

    fun toggleScanner() {
        val current = _scannerEnabled.value ?: true
        _scannerEnabled.postValue(!current)
        addLog("Scanner ${if (!current) "enabled" else "disabled"}")
    }

    fun onSystemAction(action: String) {
        addLog("System action: $action")
    }
}
