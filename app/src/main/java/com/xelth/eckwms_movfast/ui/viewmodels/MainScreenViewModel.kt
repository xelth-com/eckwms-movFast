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
    val allPhotos: MutableList<Bitmap> = mutableListOf() // all photos for avatar picker (max 20)
)

sealed class RepairAction {
    object None : RepairAction()
    data class PendingPhoto(val bitmap: Bitmap) : RepairAction()
    data class PendingBarcode(val code: String) : RepairAction()
}

// Tracks the last sent action so it can be undone
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
    // Callback for fetching shipments (set by UI layer, backed by ScanApiService)
    var onFetchShipments: (suspend (limit: Int) -> ScanResult)? = null
    // Fat Client: offline lookup callbacks (set by UI layer, backed by WarehouseRepository)
    var onUpdateProductQty: (suspend (barcode: String, qty: Double) -> Unit)? = null
    var onLookupProduct: (suspend (barcode: String) -> com.xelth.eckwms_movfast.data.local.entity.ProductEntity?)? = null
    var onLookupLocation: (suspend (barcode: String) -> com.xelth.eckwms_movfast.data.local.entity.LocationEntity?)? = null
    var onFetchLocationContents: (suspend (locationId: Long) -> com.xelth.eckwms_movfast.api.ScanResult)? = null
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

    private val _activeSlotPhoto = MutableLiveData<Bitmap?>(null)
    val activeSlotPhoto: LiveData<Bitmap?> = _activeSlotPhoto

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

    // --- RECEIVING MODE STATE ---

    private val _isReceivingMode = MutableLiveData<Boolean>(false)
    val isReceivingMode: LiveData<Boolean> = _isReceivingMode

    private val _receivingStatus = MutableLiveData<String>("")
    val receivingStatus: LiveData<String> = _receivingStatus

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
        } else if (_isDeviceCheckMode.value == true) {
            renderDeviceCheckGrid()
        } else {
            initializeGrid()
        }
    }

    private fun initializeGrid() {
        val buttons = listOf(
            MainMenuButton("settings", "⚙️\nSettings", "#9013FE", "navigate_settings", PRIORITIES.DEFAULT),
            MainMenuButton("photo", "📷\nPhoto", "#9C27B0", "capture_photo", PRIORITIES.DEFAULT),
            MainMenuButton("scan", "🔍\nScan", "#00BCD4", "capture_barcode", PRIORITIES.DEFAULT),
            MainMenuButton("repair", "🔧\nRepair", "#E91E63", "navigate_repair", PRIORITIES.DEFAULT),
            MainMenuButton("receiving", "📦\nReceiving", "#FF9800", "navigate_receiving", PRIORITIES.DEFAULT),
            MainMenuButton("device_check", "✅\nCheck", "#4CAF50", "navigate_device_check", PRIORITIES.DEFAULT),
            MainMenuButton("restock", "📋\nRestock", "#50E3C2", "navigate_restock", PRIORITIES.DEFAULT),
            MainMenuButton("inventory", "📊\nInventory", "#795548", "navigate_inventory", PRIORITIES.DEFAULT),
            MainMenuButton("qc", "QC", "#F44336", "navigate_qc", PRIORITIES.DEFAULT),
            MainMenuButton("explorer", "🔎\nExplorer", "#2196F3", "navigate_explorer", PRIORITIES.DEFAULT)
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
        gridManager.placeItems(contentItems, priority = PRIORITIES.SCAN_BUTTON)
        updateRenderCells()
    }

    private fun updateRenderCells() {
        viewModelScope.launch {
            val cells = gridManager.getRenderStructure()
            _renderCells.postValue(cells)
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
                                // Fallback: extract Odoo ID from p-code even without local DB match
                                val id = barcode.substring(1).trimStart('0').toLongOrNull()
                                if (id != null) fetchAndDisplayLocationContents(barcode, id)
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

    private fun fetchAndDisplayLocationContents(barcode: String, locationId: Long) {
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

        // 3. Photo & Scan buttons (always available)
        uiItems.add(mapOf("type" to "button", "label" to "PHOTO", "color" to "#9C27B0", "action" to "capture_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "SCAN", "color" to "#00BCD4", "action" to "capture_barcode"))

        gridManager.clearAndReset()
        gridManager.placeItems(uiItems, priority = PRIORITIES.SCAN_BUTTON)

        // 4. X button (half-height, top-right corner like repair mode)
        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "X", "color" to "#F44336", "action" to "act_smart_cancel"),
            200
        )

        updateRenderCells()
    }

    fun onButtonClick(action: String): String {
        addLog("Button clicked: $action")

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

        if (_isDeviceCheckMode.value == true) {
            return handleDeviceCheckButtonClick(action)
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

        return action
    }

    /**
     * Handle long-press on grid buttons.
     * Returns navigation action string or "handled".
     */
    fun onButtonLongClick(action: String): String {
        addLog("Long press: $action")
        return when (action) {
            "act_photo" -> "capture_photo_continuous"
            "act_scan" -> "capture_barcode_continuous"
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
            slotWaitingForBind = null
            persistSlots()
            activateSlot(index)
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
            // First photo becomes the background, subsequent don't change it
            if (active.photo == null) {
                active.photo = copy
                onSaveRepairPhoto?.invoke(active.index, copy)
                _activeSlotPhoto.value = copy
                Log.d("RepairMode", "First photo → background for slot ${active.index}")
            } else {
                Log.d("RepairMode", "Photo #${active.allPhotos.size} for slot ${active.index} (background unchanged)")
            }
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
            Log.d("RepairMode", "clearSlot(#$index): barcode=${slots[index].barcode?.takeLast(6)}, photo=${slots[index].photo != null}, allPhotos=${slots[index].allPhotos.size}")
            slots[index].isBound = false
            slots[index].isActive = false
            slots[index].barcode = null
            slots[index].photo?.recycle()
            slots[index].photo = null
            slots[index].allPhotos.forEach { it.recycle() }
            slots[index].allPhotos.clear()
            onDeleteRepairPhoto?.invoke(index)
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

        // Action buttons (top row): UNDO | PHOTO | SCAN (scan = top-right)
        val undoColor = if (lastSentAction != null) "#FF9800" else "#37474F"
        uiItems.add(mapOf("type" to "button", "label" to "↩️\nUNDO", "color" to undoColor, "action" to "act_undo"))
        val photoColor = if (pendingAction is RepairAction.PendingPhoto) "#FF9800" else "#9C27B0"
        uiItems.add(mapOf("type" to "button", "label" to "📷\nPHOTO", "color" to photoColor, "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "🔍\nSCAN", "color" to "#00BCD4", "action" to "act_scan"))

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
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT button — HALF_RIGHT at row=1 (first odd row, top-right area)
        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "✕", "color" to "#F44336", "action" to "act_exit"),
            200
        )

        updateRenderCells()
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

    private fun tryAutoMatchShipment(barcode: String) {
        if (cachedShipments.isEmpty()) {
            addLog("Cannot auto-match: no shipments loaded yet")
            return
        }

        val match = cachedShipments.find {
            (it["trackingNumber"] as? String) == barcode
        }

        if (match != null) {
            val track = match["trackingNumber"] as? String ?: "?"
            addLog("Auto-match: barcode $barcode -> shipment #${match["id"]} ($track)")
            populateClientData(match)
        } else {
            addLog("No auto-match for $barcode in ${cachedShipments.size} shipments")
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

                    // Smart client detection via Levenshtein
                    val pickupIsUs = looksLikeOurCompany(senderName)
                    val deliveryIsUs = looksLikeOurCompany(receiverName)
                    when {
                        pickupIsUs && !deliveryIsUs -> { clientName = receiverName; clientAddr = receiverAddr }
                        deliveryIsUs && !pickupIsUs -> { clientName = senderName; clientAddr = senderAddr }
                        else -> { clientName = senderName.ifEmpty { receiverName }; clientAddr = if (senderName.isNotEmpty()) senderAddr else receiverAddr }
                    }

                    // Product type: OPAL=product_type, DHL=product
                    productType = raw.optString("product_type", "")
                    if (productType.isEmpty()) productType = raw.optString("product", "")

                    // Who received it
                    receivedBy = raw.optString("receiver", "")
                    if (receivedBy.isEmpty()) receivedBy = raw.optString("delivered_to_name", "")
                } catch (_: Exception) {}
            }
            if (senderName.isEmpty()) senderName = "Unknown"
            if (clientName.isEmpty()) clientName = senderName

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

            val isMatched = scannedBarcode != null && track == scannedBarcode

            ShipmentDisplayItem(id, track, senderName, senderAddr, receiverName, receiverAddr, clientName, clientAddr, productType, receivedBy, status, dateStr, isMatched)
        }
    }

    fun exitReceivingMode() {
        _isReceivingMode.value = false
        currentStepIndex = 0
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

        // Top row: BACK | PHOTO | SCAN
        val backAction = if (isContentsStep && packagingSubLevel) "act_back_contents" else "act_noop"
        val backColor = if (isContentsStep && packagingSubLevel) "#546E7A" else "#37474F"
        val backLabel = if (isContentsStep && packagingSubLevel) "◀️\nBACK" else "↩️\nUNDO"
        uiItems.add(mapOf("type" to "button", "label" to backLabel, "color" to backColor, "action" to backAction))
        uiItems.add(mapOf("type" to "button", "label" to "📷\nPHOTO",
            "color" to if (isContentsStep) "#1A1A1A" else "#9C27B0",
            "action" to if (isContentsStep) "act_noop" else "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "🔍\nSCAN", "color" to "#00BCD4", "action" to "act_scan"))

        // Workflow step buttons
        receivingSteps.forEachIndexed { index, step ->
            val stepId = step["id"] as? String ?: "step_$index"
            @Suppress("UNCHECKED_CAST")
            val colors = step["colors"] as? Map<String, String>

            if (isContentsStep && index == currentStepIndex) {
                // Current contents step → becomes "OK" button
                val okColor: Any = colors?.get("active") ?: "#FFCA28"
                uiItems.add(mapOf(
                    "type" to "button",
                    "label" to "OK \u2713",
                    "color" to okColor,
                    "action" to "act_contents_ok"
                ))
            } else {
                val label = step["label"] as? String ?: "Step ${index + 1}"
                val color = when {
                    index < currentStepIndex -> colors?.get("done") ?: "#4CAF50"
                    index == currentStepIndex -> colors?.get("active") ?: "#FF9800"
                    // Dim all non-current steps when in contents mode
                    isContentsStep -> "#1A1A1A"
                    else -> colors?.get("pending") ?: "#424242"
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
                        barcode != null -> "$label \u2713"  // has barcode
                        isOn -> "$label \u25CF"             // toggled on (filled circle)
                        else -> label
                    }
                    val color = when {
                        isActive -> "#66BB6A"   // bright green = active for scanning
                        isOn -> "#388E3C"       // dark green = on but not active
                        else -> "#424242"        // grey = off
                    }
                    uiItems.add(mapOf(
                        "type" to "button",
                        "label" to displayLabel,
                        "color" to color,
                        "action" to "act_toggle_$key"
                    ))
                }
                // Packaging button
                if (packaging != null) {
                    val pkgLabel = if (selectedPackaging != null) {
                        @Suppress("UNCHECKED_CAST")
                        val options = packaging["options"] as? List<Map<String, String>> ?: emptyList()
                        val selected = options.find { it["value"] == selectedPackaging }
                        selected?.get("label") ?: "Packaging \u25BC"
                    } else "Packaging \u25BC"
                    uiItems.add(mapOf(
                        "type" to "button",
                        "label" to pkgLabel,
                        "color" to if (selectedPackaging != null) "#4CAF50" else "#5C6BC0",
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
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT at HALF_RIGHT row=1 last col
        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "\u2715",
                "color" to if (isContentsStep) "#1A1A1A" else "#F44336",
                "action" to if (isContentsStep) "act_noop" else "act_exit"),
            200
        )

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
        uiItems.add(mapOf("type" to "button", "label" to "📷\nPHOTO", "color" to "#9C27B0", "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "🔍\nSCAN", "color" to "#00BCD4", "action" to "act_scan"))

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
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT button at HALF_RIGHT
        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "✕", "color" to "#F44336", "action" to "act_exit"),
            200
        )

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

        // --- SECURITY FILTER ---
        // 1. Check for Trusted Link Barcodes (eck1.com, eck2.com, eck3.com)
        val isLinkBarcode = cleanCode.startsWith("eck1.com", ignoreCase = true) ||
                            cleanCode.startsWith("eck2.com", ignoreCase = true) ||
                            cleanCode.startsWith("eck3.com", ignoreCase = true) ||
                            cleanCode.startsWith("http://eck", ignoreCase = true) ||
                            cleanCode.startsWith("https://eck", ignoreCase = true)

        // 2. Check for Spoofing Attempts on Internal ID format
        // Raw barcodes (NOT Link Barcodes) that look like internal IDs are rejected
        // Skip if code was already decrypted from encrypted QR (proves authenticity)
        val isPotentialSpoof = !wasDecryptedUpstream &&
                               !isLinkBarcode &&
                               cleanCode.length == 19 &&
                               cleanCode.matches(Regex("^[ibpl][0-9]{18}$", RegexOption.IGNORE_CASE))

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

                    // --- AUTO-PHOTO: open camera + long vibrate to alert ---
                    android.util.Log.e("AUTO_PHOTO", "Check: qty=${entry.quantity}, photo=${entry.photo != null}, barcode=$cleanCode")
                    if (entry.quantity == 1 && entry.photo == null) {
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
        val locationId = decryptedLocationId?.substring(1)?.toLongOrNull()
        if (locationId == null || locationId == 0L) {
            android.util.Log.e("INVENTORY", "fetchExpected: no Odoo ID from decryptedLocationId=$decryptedLocationId")
            return
        }
        android.util.Log.e("INVENTORY", "fetchExpected: locationId=$locationId, decryptedLocationId=$decryptedLocationId")
        viewModelScope.launch {
            try {
                val result = onFetchLocationContents?.invoke(locationId)
                android.util.Log.e("INVENTORY", "fetchExpected result: ${result?.javaClass?.simpleName} ${if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) result.data.take(200) else if (result is com.xelth.eckwms_movfast.api.ScanResult.Error) result.message else "null"}")
                if (result is com.xelth.eckwms_movfast.api.ScanResult.Success) {
                    val raw = result.data?.trim() ?: ""
                    if (raw.isEmpty() || raw == "null" || raw == "[]") {
                        addLog("📋 Location empty (no items on server)")
                        updateInventoryConsole()
                        return@launch
                    }
                    val arr = org.json.JSONArray(raw)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val barcode = obj.optString("barcode", "")
                        val defaultCode = obj.optString("default_code", "")
                        val key = barcode.ifEmpty { defaultCode }
                        if (key.isNotEmpty()) {
                            expectedInventory[key] = ExpectedItem(
                                productId = obj.optLong("product_id", 0),
                                productName = obj.optString("product_name", "Unknown"),
                                barcode = barcode,
                                defaultCode = defaultCode,
                                quantity = obj.optDouble("quantity", 0.0),
                                reservedQty = obj.optDouble("reserved_qty", 0.0)
                            )
                        }
                    }
                    addLog("📋 Expected: ${expectedInventory.size} products")
                    updateInventoryConsole()
                } else if (result is com.xelth.eckwms_movfast.api.ScanResult.Error) {
                    android.util.Log.e("INVENTORY", "fetchExpected failed: ${result.message}")
                    // Don't show error to user — just no expected data
                }
            } catch (e: Exception) {
                android.util.Log.e("INVENTORY", "fetchExpected exception", e)
            }
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
        uiItems.add(mapOf("type" to "button", "label" to photoLabel, "color" to photoColor, "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "🔍\nSCAN", "color" to "#00BCD4", "action" to "act_scan"))

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
        gridManager.placeItems(uiItems, priority = 100)

        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "X", "color" to "#F44336", "action" to "act_exit"),
            200
        )

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
        uiItems.add(mapOf("type" to "button", "label" to "🔍\nSCAN", "color" to "#00BCD4", "action" to "act_scan"))

        gridManager.clearAndReset()
        gridManager.placeItems(uiItems, priority = 100)

        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "X", "color" to "#F44336", "action" to "act_exit"),
            200
        )

        updateRenderCells()
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

    fun toggleScanner() {
        val current = _scannerEnabled.value ?: true
        _scannerEnabled.postValue(!current)
        addLog("Scanner ${if (!current) "enabled" else "disabled"}")
    }

    fun onSystemAction(action: String) {
        addLog("System action: $action")
    }
}
