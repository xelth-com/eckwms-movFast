package com.xelth.eckwms_movfast.ui.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridConfig
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridManager
import com.xelth.eckwms_movfast.ui.screens.pos.grid.PRIORITIES
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
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

class MainScreenViewModel : ViewModel() {

    // Dynamic grid config - recalculated based on screen dimensions
    var gridConfig = GridConfig(
        cellWidth = 140.dp,
        cellHeight = 80.dp,
        buttonGap = 2.dp,
        verticalOverlap = 0.75f
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

    private var lastWidth = 0f
    private var lastHeight = 0f

    init {
        initializeGrid()
    }

    /**
     * Recalculate grid dimensions to fill screen edge-to-edge
     * Ported from ecKasseAnd PosViewModel.recalculateLayout()
     *
     * Formula: cellWidth = containerWidth / 3.5 - gap
     * (3 full buttons + 1 half button = 3.5 button widths per row)
     */
    fun updateLayoutDimensions(width: Dp, height: Dp, density: Density) {
        val containerWidth = width.value
        val containerHeight = height.value

        // Avoid redundant recalculations
        if (containerWidth == lastWidth && containerHeight == lastHeight) return
        if (containerWidth <= 0 || containerHeight <= 0) return
        lastWidth = containerWidth
        lastHeight = containerHeight

        val buttonGap = 2f

        // Each row has 3 full buttons + 1 half = 3.5 button widths
        val buttonWidth = containerWidth / 3.5f - buttonGap
        val buttonHeight = buttonWidth * (80f / 120f) // 2:3 aspect ratio

        val effectiveRowHeight = buttonHeight * 0.75f + buttonGap
        if (effectiveRowHeight <= 0) return

        // Calculate how many rows fit in available height
        val numRows = floor((containerHeight - buttonHeight * 0.25f) / effectiveRowHeight).toInt() + 1
        if (numRows <= 0) return

        gridConfig = GridConfig(
            cellWidth = buttonWidth.dp,
            cellHeight = buttonHeight.dp,
            buttonGap = buttonGap.dp,
            verticalOverlap = 0.75f
        )

        gridManager = GridManager(
            config = gridConfig,
            dimensions = Pair(numRows.coerceAtLeast(1), 7),
            layoutType = "asymmetrical"
        )

        initializeGrid()
    }

    private fun initializeGrid() {
        val buttons = listOf(
            MainMenuButton(
                id = "scan",
                label = "Scan",
                color = "#4A90E2",
                action = "navigate_scan",
                priority = PRIORITIES.SCAN_BUTTON
            ),
            MainMenuButton(
                id = "restock",
                label = "Restock",
                color = "#50E3C2",
                action = "navigate_restock",
                priority = PRIORITIES.RESTOCK_BUTTON
            ),
            MainMenuButton(
                id = "ai_agent",
                label = "AI Agent",
                color = "#F5A623",
                action = "navigate_ai",
                priority = PRIORITIES.AI_BUTTON
            ),
            MainMenuButton(
                id = "settings",
                label = "Settings",
                color = "#9013FE",
                action = "navigate_settings",
                priority = PRIORITIES.SETTINGS_BUTTON
            )
        )

        val contentItems = buttons.map { button ->
            mapOf(
                "type" to "button",
                "label" to button.label,
                "color" to button.color,
                "action" to button.action
            )
        }

        gridManager.placeItems(contentItems, priority = PRIORITIES.SCAN_BUTTON)
        updateRenderCells()
    }

    private fun updateRenderCells() {
        viewModelScope.launch {
            val cells = gridManager.getRenderStructure()
            _renderCells.postValue(cells)
        }
    }

    fun onButtonClick(action: String): String {
        val logEntry = "Button clicked: $action"
        addLog(logEntry)
        return action
    }

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
