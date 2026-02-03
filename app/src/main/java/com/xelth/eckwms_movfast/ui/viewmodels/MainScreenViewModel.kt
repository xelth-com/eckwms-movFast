package com.xelth.eckwms_movfast.ui.viewmodels

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

data class MainMenuButton(
    val id: String,
    val label: String,
    val color: String,
    val action: String,
    val priority: Int = PRIORITIES.DEFAULT
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
        } else {
            initializeGrid()
        }
    }

    private fun initializeGrid() {
        val buttons = listOf(
            MainMenuButton("scan", "Scan", "#4A90E2", "navigate_scan", PRIORITIES.SCAN_BUTTON),
            MainMenuButton("restock", "Restock", "#50E3C2", "navigate_restock", PRIORITIES.RESTOCK_BUTTON),
            MainMenuButton("repair", "Repair", "#E91E63", "navigate_repair", PRIORITIES.DEFAULT),
            MainMenuButton("settings", "Settings", "#9013FE", "navigate_settings", PRIORITIES.SETTINGS_BUTTON)
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

        if (action == "navigate_repair") {
            enterRepairMode()
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
            } else if (!slot.isBound) {
                // Slot not in saved data and not currently bound — ensure clean state
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

        // Action buttons (top row)
        val photoColor = if (pendingAction is RepairAction.PendingPhoto) "#FF9800" else "#9C27B0"
        uiItems.add(mapOf("type" to "button", "label" to "PHOTO", "color" to photoColor, "action" to "act_photo"))
        uiItems.add(mapOf("type" to "button", "label" to "SCAN", "color" to "#00BCD4", "action" to "act_scan"))

        // UNDO button: active only when there's something to undo
        val undoColor = if (lastSentAction != null) "#FF9800" else "#37474F"
        uiItems.add(mapOf("type" to "button", "label" to "UNDO", "color" to undoColor, "action" to "act_undo"))

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
