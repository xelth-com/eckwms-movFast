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
    var photo: Bitmap? = null
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
    // Persistence callbacks (set by UI layer, backed by SettingsManager)
    var onSaveRepairSlots: ((List<Pair<Int, String>>) -> Unit)? = null
    var onLoadRepairSlots: (() -> List<Pair<Int, String>>)? = null
    var onSaveRepairPhoto: ((Int, Bitmap) -> Unit)? = null
    var onLoadRepairPhoto: ((Int) -> Bitmap?)? = null
    var onDeleteRepairPhoto: ((Int) -> Unit)? = null
    // Callback for fetching shipments (set by UI layer, backed by ScanApiService)
    var onFetchShipments: (suspend (limit: Int) -> ScanResult)? = null

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

    // --- RECEIVING MODE STATE ---

    private val _isReceivingMode = MutableLiveData<Boolean>(false)
    val isReceivingMode: LiveData<Boolean> = _isReceivingMode

    private val _receivingStatus = MutableLiveData<String>("")
    val receivingStatus: LiveData<String> = _receivingStatus

    private var receivingSteps: List<Map<String, Any>> = emptyList()
    private var currentStepIndex: Int = 0
    val receivingData = mutableMapOf<String, Any>()

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

    // Signals that shipment data has been loaded (triggers UI recomposition)
    private val _shipmentsLoaded = MutableLiveData<Boolean>(false)
    val shipmentsLoaded: LiveData<Boolean> = _shipmentsLoaded

    // Track shipment loading error state
    private val _shipmentsError = MutableLiveData<String?>(null)
    val shipmentsError: LiveData<String?> = _shipmentsError

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
            MainMenuButton("settings", "Settings", "#9013FE", "navigate_settings", PRIORITIES.DEFAULT),
            MainMenuButton("photo", "Photo", "#9C27B0", "capture_photo", PRIORITIES.DEFAULT),
            MainMenuButton("scan", "Scan", "#00BCD4", "capture_barcode", PRIORITIES.DEFAULT),
            MainMenuButton("repair", "Repair", "#E91E63", "navigate_repair", PRIORITIES.DEFAULT),
            MainMenuButton("receiving", "Receiving", "#FF9800", "navigate_receiving", PRIORITIES.DEFAULT),
            MainMenuButton("device_check", "Check Dev", "#4CAF50", "navigate_device_check", PRIORITIES.DEFAULT),
            MainMenuButton("restock", "Restock", "#50E3C2", "navigate_restock", PRIORITIES.DEFAULT)
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

    fun onButtonClick(action: String): String {
        addLog("Button clicked: $action")

        if (_isRepairMode.value == true) {
            return handleRepairButtonClick(action)
        }

        if (_isReceivingMode.value == true) {
            return handleReceivingButtonClick(action)
        }

        if (_isDeviceCheckMode.value == true) {
            return handleDeviceCheckButtonClick(action)
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

        return action
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
                    if (slot.photo != null) Log.d("RepairMode", "Restored photo for slot #${slot.index} from disk")
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
                Log.d("RepairMode", "No match in saved slots, ignoring (not in repair mode)")
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
            active.photo = copy
            // Persist to disk so photo survives process death
            onSaveRepairPhoto?.invoke(active.index, copy)
            _activeSlotPhoto.value = copy
            Log.d("RepairMode", "Photo stored in slot ${active.index}, copy=${copy.hashCode()}")
            uploadPhoto(active.barcode!!, bitmap)
            _repairStatus.value = "Photo sent -> ${active.barcode}"
        } else {
            pendingAction = RepairAction.PendingPhoto(bitmap)
            _repairStatus.value = "Photo taken. Select slot to attach."
            renderRepairGrid()
        }
    }

    private fun activateSlot(index: Int) {
        slots.forEach { it.isActive = false }
        slots[index].isActive = true
        _repairStatus.value = "Active: ${slots[index].barcode}"
        val photo = slots[index].photo
        Log.d("RepairMode", "activateSlot($index): photo=${if (photo != null) "exists (recycled=${photo.isRecycled})" else "null"}")
        _activeSlotPhoto.value = photo
        renderRepairGrid()
        resetActiveTimer(index)
    }

    private fun resetActiveTimer(index: Int) {
        activeSlotJob?.cancel()
        activeSlotJob = viewModelScope.launch {
            delay(60_000L)
            if (index in slots.indices && slots[index].isActive) {
                slots[index].isActive = false
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
            Log.d("RepairMode", "clearSlot(#$index): barcode=${slots[index].barcode?.takeLast(6)}, photo=${slots[index].photo != null}")
            slots[index].isBound = false
            slots[index].isActive = false
            slots[index].barcode = null
            slots[index].photo?.recycle()
            slots[index].photo = null
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
        uiItems.add(mapOf("type" to "button", "label" to "UNDO", "color" to undoColor, "action" to "act_undo"))
        val photoColor = if (pendingAction is RepairAction.PendingPhoto) "#FF9800" else "#9C27B0"
        uiItems.add(mapOf("type" to "button", "label" to "PHOTO", "color" to photoColor, "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "SCAN", "color" to "#00BCD4", "action" to "act_scan"))

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
            addLog("Auto-matched shipment ID: ${match["id"]}")
            populateClientData(match)
        } else {
            addLog("No direct match for $barcode in ${cachedShipments.size} shipments")
        }
    }

    private fun populateClientData(shipment: Map<String, Any>) {
        val rawResponseStr = shipment["rawResponse"] as? String
        var clientName = ""
        var clientAddress = ""

        if (rawResponseStr != null) {
            try {
                val raw = JSONObject(rawResponseStr)
                // Client = sender (pickup)
                clientName = raw.optString("pickup_name", "")
                val name2 = raw.optString("pickup_name2", "")
                if (name2.isNotEmpty() && name2 != clientName) clientName = "$clientName $name2".trim()
                if (clientName.isEmpty()) {
                    // Fallback to delivery/recipient for DHL
                    clientName = raw.optString("delivery_name", raw.optString("recipient_name", ""))
                }
                val street = raw.optString("pickup_street", "")
                val zip = raw.optString("pickup_zip", "")
                val city = raw.optString("pickup_city", "")
                clientAddress = listOf(street, "$zip $city".trim()).filter { it.isNotEmpty() }.joinToString(", ")
            } catch (_: Exception) {}
        }

        val shipmentId = (shipment["id"] as? Number)?.toString() ?: shipment["id"]?.toString() ?: ""
        receivingData["selected_shipment_id"] = shipmentId
        if (clientName.isNotEmpty()) receivingData["client_name"] = clientName
        if (clientAddress.isNotEmpty()) receivingData["client_address"] = clientAddress
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
                    client = raw.optString("delivery_name", "")
                    if (client.isEmpty()) client = raw.optString("recipient_name", "")
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
            populateClientData(shipment)
            val track = shipment["trackingNumber"] as? String ?: "?"
            addLog("Selected shipment: $track")
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
        val senderName: String,       // pickup = client (отправитель)
        val senderAddress: String,
        val receiverName: String,     // delivery = получатель
        val receiverAddress: String,
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
            var productType = ""
            var receivedBy = ""

            val rawStr = s["rawResponse"] as? String
            if (rawStr != null) {
                try {
                    val raw = JSONObject(rawStr)
                    // Sender (pickup = client)
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

                    // Product type: OPAL=product_type, DHL=product
                    productType = raw.optString("product_type", "")
                    if (productType.isEmpty()) productType = raw.optString("product", "")

                    // Who received it
                    receivedBy = raw.optString("receiver", "")
                    if (receivedBy.isEmpty()) receivedBy = raw.optString("delivered_to_name", "")
                } catch (_: Exception) {}
            }
            if (senderName.isEmpty()) senderName = "Unknown"

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

            ShipmentDisplayItem(id, track, senderName, senderAddr, receiverName, receiverAddr, productType, receivedBy, status, dateStr, isMatched)
        }
    }

    fun exitReceivingMode() {
        _isReceivingMode.value = false
        currentStepIndex = 0
        receivingData.clear()
        _showReceivingModal.value = null
        addLog("Exited Receiving Mode")
        initializeGrid()
    }

    private fun handleReceivingButtonClick(action: String): String {
        when {
            action == "act_exit" -> {
                exitReceivingMode()
                return "handled"
            }
            action == "act_photo" -> return "capture_photo"
            action == "act_scan" -> return "capture_barcode"
            action == "act_save_receiving" -> {
                saveReceivingWorkflow()
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
        }
    }

    fun onReceivingScan(barcode: String) {
        if (_isReceivingMode.value != true) return
        if (currentStepIndex !in receivingSteps.indices) return

        val step = receivingSteps[currentStepIndex]
        val dataKey = step["dataKey"] as? String ?: return

        receivingData[dataKey] = barcode
        _receivingStatus.value = "✓ $dataKey: $barcode"
        addLog("✓ $dataKey = $barcode")

        // If postal barcode just scanned, try to auto-match a shipment
        if (dataKey == "postal_barcode") {
            tryAutoMatchShipment(barcode)
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
        addLog("✓ $dataKey = photo ${copy.width}x${copy.height}")
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

        // Top row: UNDO-placeholder | PHOTO | SCAN
        uiItems.add(mapOf("type" to "button", "label" to "UNDO", "color" to "#37474F", "action" to "act_noop"))
        uiItems.add(mapOf("type" to "button", "label" to "PHOTO", "color" to "#9C27B0", "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "SCAN", "color" to "#00BCD4", "action" to "act_scan"))

        // Workflow step buttons
        receivingSteps.forEachIndexed { index, step ->
            val stepId = step["id"] as? String ?: "step_$index"
            val label = step["label"] as? String ?: "Step ${index + 1}"
            @Suppress("UNCHECKED_CAST")
            val colors = step["colors"] as? Map<String, String>

            val color = when {
                index < currentStepIndex -> colors?.get("done") ?: "#4CAF50"
                index == currentStepIndex -> colors?.get("active") ?: "#FF9800"
                else -> colors?.get("pending") ?: "#424242"
            }

            uiItems.add(mapOf(
                "type" to "button",
                "label" to label,
                "color" to color,
                "action" to "step_$stepId"
            ))
        }

        // SAVE button when all steps done — bright blue to stand out from green done steps
        if (currentStepIndex >= receivingSteps.size && receivingSteps.isNotEmpty()) {
            uiItems.add(mapOf("type" to "button", "label" to "\uD83D\uDCBE SAVE", "color" to "#2196F3", "action" to "act_save_receiving"))
        }

        gridManager.clearAndReset()
        gridManager.placeItems(uiItems, priority = 100)

        // EXIT at HALF_RIGHT row=1 last col (same as repair mode)
        val cols = gridManager.contentGrid.cols
        gridManager.contentGrid.placeContentAt(1, cols - 1,
            mapOf("type" to "button", "label" to "✕", "color" to "#F44336", "action" to "act_exit"),
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
            uiItems.add(mapOf("type" to "button", "label" to "UPLOAD", "color" to "#2196F3", "action" to "act_upload_check"))
        } else {
            uiItems.add(mapOf("type" to "button", "label" to "UNDO", "color" to "#37474F", "action" to "act_undo"))
        }
        uiItems.add(mapOf("type" to "button", "label" to "PHOTO", "color" to "#9C27B0", "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "SCAN", "color" to "#00BCD4", "action" to "act_scan"))

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
