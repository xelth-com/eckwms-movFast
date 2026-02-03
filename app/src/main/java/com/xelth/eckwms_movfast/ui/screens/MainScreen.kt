package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xelth.eckwms_movfast.ui.dynamic.DynamicUiRenderer
import com.xelth.eckwms_movfast.ui.screens.pos.components.ConsoleView
import com.xelth.eckwms_movfast.ui.screens.pos.components.SelectionAreaSheet
import com.xelth.eckwms_movfast.ui.viewmodels.MainScreenViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    navController: NavController,
    viewModel: ScanRecoveryViewModel
) {
    val mainViewModel: MainScreenViewModel = viewModel()
    val networkHealthState by viewModel.networkHealthState.observeAsState(NetworkHealthState.Checking)
    val deviceRegistrationStatus by viewModel.deviceRegistrationStatus.observeAsState("unknown")

    val renderCells by mainViewModel.renderCells.observeAsState(emptyList())
    val consoleLogs by mainViewModel.consoleLogs.observeAsState(emptyList())
    val scannerEnabled by mainViewModel.scannerEnabled.observeAsState(true)

    // Repair mode state
    val isRepairMode by mainViewModel.isRepairMode.observeAsState(false)
    val repairStatus by mainViewModel.repairStatus.observeAsState("")

    // Read shared settings from ScanRecoveryViewModel
    val sharedGridRowCount by viewModel.gridRowCount.observeAsState(7)
    val sharedIsLeftHanded by viewModel.isLeftHanded.observeAsState(false)

    val density = LocalDensity.current
    val context = LocalContext.current

    // State for the Intake Bottom Sheet
    var showIntakeSheet by remember { mutableStateOf(false) }
    var intakeConfigJson by remember { mutableStateOf("{}") }
    var intakeLongPressAction by remember { mutableStateOf("") }
    val intakeFormState = remember { mutableStateMapOf<String, Any>() }

    // State for slot delete confirmation
    var showDeleteSlotDialog by remember { mutableStateOf(false) }
    var deleteSlotAction by remember { mutableStateOf("") }

    // Sync shared settings into MainScreenViewModel
    LaunchedEffect(sharedGridRowCount) {
        mainViewModel.setGridRowCount(sharedGridRowCount)
    }
    LaunchedEffect(sharedIsLeftHanded) {
        if (sharedIsLeftHanded != (mainViewModel.isLeftHanded.value ?: false)) {
            mainViewModel.toggleHandedness()
        }
    }

    // Initialize network health monitoring (same as ScanScreen does)
    LaunchedEffect(Unit) {
        viewModel.onViewModelReady()
    }

    // Wire repair event callbacks from MainScreenViewModel to ScanRecoveryViewModel
    LaunchedEffect(Unit) {
        mainViewModel.onRepairEventSend = { targetDeviceId, eventType, data ->
            viewModel.sendRepairEvent(targetDeviceId, eventType, data)
        }
        mainViewModel.onRepairPhotoUpload = { targetDeviceId, bitmap ->
            viewModel.uploadRepairPhoto(targetDeviceId, bitmap)
        }
        mainViewModel.onSaveRepairSlots = { slots ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairSlots(slots)
        }
        mainViewModel.onLoadRepairSlots = {
            com.xelth.eckwms_movfast.utils.SettingsManager.loadRepairSlots()
        }
    }

    // Bridge: forward scanner results to Repair Mode (or auto-enter if device matches)
    val scannedBarcode by viewModel.scannedBarcode.observeAsState(null)
    LaunchedEffect(scannedBarcode) {
        if (scannedBarcode != null) {
            mainViewModel.onRepairScan(scannedBarcode!!)
            viewModel.consumeScannedBarcode()
        }
    }

    // Bridge: forward repair photo from ScanRecoveryViewModel to MainScreenViewModel
    val repairPhoto by viewModel.repairPhotoBitmap.observeAsState(null)
    LaunchedEffect(repairPhoto, isRepairMode) {
        if (isRepairMode && repairPhoto != null) {
            mainViewModel.onRepairPhotoCaptured(repairPhoto!!)
            viewModel.consumeRepairPhotoBitmap()
        }
    }

    // Auto-navigate to camera after slot binding
    val shouldNavigateToCamera by mainViewModel.navigateToCamera.observeAsState(false)
    LaunchedEffect(shouldNavigateToCamera) {
        if (shouldNavigateToCamera) {
            mainViewModel.consumeNavigateToCamera()
            navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
        }
    }

    // No Scaffold, no TopBar — console goes edge-to-edge from top
    val navBarHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(bottom = navBarHeight)) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        val gridRowCount by mainViewModel.gridRowCount.observeAsState(7)

        // Recalculate grid when dimensions or settings change
        LaunchedEffect(containerWidth, containerHeight, mainViewModel.isLeftHanded.value, gridRowCount) {
            if (containerWidth > 0.dp && containerHeight > 0.dp) {
                mainViewModel.updateLayoutDimensions(containerWidth, containerHeight, density)
            }
        }

        val gridConfig = mainViewModel.gridConfig
        val gridHeight = mainViewModel.gridTotalHeight
        // Console gets whatever is left above the grid
        val consoleHeight = (containerHeight - gridHeight).coerceAtLeast(60.dp)

        Column(modifier = Modifier.fillMaxSize()) {
            // Console / Repair Status Header — fills top, flexible height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(consoleHeight)
                    .background(if (isRepairMode) Color(0xFF263238) else Color(0xFF121212))
            ) {
                if (isRepairMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    // Find active slot action for delete
                    val activeSlotAction = mainViewModel.getActiveSlotAction()
                    // Repair Mode: photo as dim background, status text on top
                    // Long press on this panel → delete active slot
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = {
                                    if (activeSlotAction != null) {
                                        deleteSlotAction = activeSlotAction
                                        showDeleteSlotDialog = true
                                    }
                                }
                            )
                    ) {
                        // Background photo — full size, very dim
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.12f),
                                contentScale = ContentScale.Crop
                            )
                        }
                        // Status text on top
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = repairStatus,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else {
                    // Standard Console
                    ConsoleView(
                        logs = consoleLogs,
                        modifier = Modifier.fillMaxSize(),
                        scannerEnabled = scannerEnabled,
                        onScannerToggle = { mainViewModel.toggleScanner() }
                    )
                }
            }

            // Grid area — exact computed height, buttons touch bottom edge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .background(Color.Black)
            ) {
                SelectionAreaSheet(
                    renderCells = renderCells,
                    cellWidth = gridConfig.cellWidth,
                    cellHeight = gridConfig.cellHeight,
                    buttonGap = gridConfig.buttonGap,
                    networkState = networkHealthState,
                    regStatus = deviceRegistrationStatus,
                    onButtonClick = { action ->
                        val result = mainViewModel.onButtonClick(action)
                        when (result) {
                            "capture_photo" -> {
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                            }
                            "capture_barcode" -> {
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                            "navigate_scan" -> navController.navigate("scanScreen")
                            "navigate_restock" -> navController.navigate("restockScreen")
                            "navigate_ai" -> navController.navigate("scanScreen")
                            "navigate_settings" -> navController.navigate("settings")
                        }
                    },
                    onButtonLongClick = { action ->
                        mainViewModel.addLog("Long press: $action")
                        intakeLongPressAction = action
                        intakeFormState.clear()
                        try {
                            val inputStream = context.assets.open("workflows/device_intake.json")
                            val json = inputStream.bufferedReader().use { it.readText() }
                            intakeConfigJson = json
                            showIntakeSheet = true
                        } catch (e: Exception) {
                            mainViewModel.addLog("Error loading intake config: ${e.message}")
                        }
                    },
                    onNetworkIndicatorClick = {
                        navController.navigate("pairingScreen")
                    }
                )
            }
        }
    }

    // Intake ModalBottomSheet
    if (showIntakeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showIntakeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                DynamicUiRenderer(
                    layoutJson = intakeConfigJson,
                    stateValues = intakeFormState.toMap(),
                    onValueChange = { key, value ->
                        intakeFormState[key] = value
                    },
                    onAction = { action, _ ->
                        mainViewModel.addLog("Intake action: $action ($intakeLongPressAction)")
                        when (action) {
                            "save_intake_details" -> {
                                val jsonPayload = org.json.JSONObject(intakeFormState as Map<*, *>).toString(2)
                                mainViewModel.addLog("--- Intake Payload ---")
                                mainViewModel.addLog(jsonPayload)
                                mainViewModel.addLog("--- End Payload ---")
                                viewModel.sendRepairEvent(
                                    intakeLongPressAction,
                                    "intake_save",
                                    org.json.JSONObject(intakeFormState as Map<*, *>).toString()
                                )
                                showIntakeSheet = false
                            }
                            "capture_evidence_box", "capture_evidence_device" -> {
                                showIntakeSheet = false
                                navController.navigate("cameraScanScreen?scan_mode=workflow_capture")
                            }
                            "scan_link_hwb", "scan_link_sn" -> {
                                showIntakeSheet = false
                                navController.navigate("cameraScanScreen?scan_mode=barcode")
                            }
                        }
                    }
                )
            }
        }
    }

    // Delete slot confirmation dialog
    if (showDeleteSlotDialog) {
        val slotBarcode = mainViewModel.getSlotBarcode(deleteSlotAction)
        AlertDialog(
            onDismissRequest = { showDeleteSlotDialog = false },
            title = { Text("Delete Slot?") },
            text = { Text("Unbind device $slotBarcode and clear all data for this slot?") },
            confirmButton = {
                TextButton(onClick = {
                    mainViewModel.deleteSlot(deleteSlotAction)
                    showDeleteSlotDialog = false
                }) {
                    Text("Delete", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSlotDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
