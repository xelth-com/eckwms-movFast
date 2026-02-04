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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider

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

    // Receiving mode state
    val isReceivingMode by mainViewModel.isReceivingMode.observeAsState(false)
    val receivingStatus by mainViewModel.receivingStatus.observeAsState("")
    val receivingModalJson by mainViewModel.showReceivingModal.observeAsState(null)

    // Device Check mode state
    val isDeviceCheckMode by mainViewModel.isDeviceCheckMode.observeAsState(false)
    val deviceCheckStatus by mainViewModel.deviceCheckStatus.observeAsState("")

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

    // State for receiving workflow modal
    val receivingModalFormState = remember { mutableStateMapOf<String, Any>() }

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
        mainViewModel.onSaveRepairPhoto = { index, bitmap ->
            com.xelth.eckwms_movfast.utils.SettingsManager.saveRepairPhoto(index, bitmap)
        }
        mainViewModel.onLoadRepairPhoto = { index ->
            com.xelth.eckwms_movfast.utils.SettingsManager.loadRepairPhoto(index)
        }
        mainViewModel.onDeleteRepairPhoto = { index ->
            com.xelth.eckwms_movfast.utils.SettingsManager.deleteRepairPhoto(index)
        }
    }

    // Wire shipment fetching callback
    LaunchedEffect(Unit) {
        val scanApiService = com.xelth.eckwms_movfast.api.ScanApiService(context)
        mainViewModel.onFetchShipments = { limit -> scanApiService.getShipments(limit) }
    }

    // Load receiving workflow JSON
    LaunchedEffect(Unit) {
        mainViewModel.loadReceivingWorkflow(context)
    }

    // Receiving mode: camera navigation
    val receivingCameraNav by mainViewModel.receivingCameraNav.observeAsState(null)
    LaunchedEffect(receivingCameraNav) {
        val scanMode = receivingCameraNav
        if (scanMode != null) {
            mainViewModel.consumeReceivingCameraNav()
            navController.navigate("cameraScanScreen?scan_mode=$scanMode")
        }
    }

    // Bridge: forward scanner results to appropriate mode
    val scannedBarcode by viewModel.scannedBarcode.observeAsState(null)
    LaunchedEffect(scannedBarcode) {
        if (scannedBarcode != null) {
            if (isReceivingMode) {
                mainViewModel.onReceivingScan(scannedBarcode!!)
            } else if (isDeviceCheckMode) {
                mainViewModel.onDeviceCheckScan(scannedBarcode!!)
            } else {
                mainViewModel.onRepairScan(scannedBarcode!!)
            }
            viewModel.consumeScannedBarcode()
        }
    }

    // Bridge: forward repair photo from ScanRecoveryViewModel to MainScreenViewModel
    val repairPhoto by viewModel.repairPhotoBitmap.observeAsState(null)
    LaunchedEffect(repairPhoto, isRepairMode, isReceivingMode, isDeviceCheckMode) {
        if (repairPhoto != null) {
            if (isReceivingMode) {
                mainViewModel.onReceivingPhotoCaptured(repairPhoto!!)
            } else if (isDeviceCheckMode) {
                mainViewModel.onDeviceCheckPhotoCaptured(repairPhoto!!)
            } else if (isRepairMode) {
                mainViewModel.onRepairPhotoCaptured(repairPhoto!!)
            }
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

    // No Scaffold, no TopBar — edge-to-edge layout, ignore nav bar insets
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
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

        // Console extends 1/4 button height under the grid to fill hex gaps
        val overlap = gridConfig.cellHeight / 4
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Console / Repair Status — extends slightly under grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(consoleHeight + overlap)
                    .align(Alignment.TopCenter)
                    .background(Color.Black)
            ) {
                if (isRepairMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    val activeSlotAction = mainViewModel.getActiveSlotAction()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
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
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.2f),
                                contentScale = ContentScale.Crop
                            )
                        }
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
                } else if (isDeviceCheckMode) {
                    val activeSlotPhoto by mainViewModel.activeSlotPhoto.observeAsState(null)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                    ) {
                        if (activeSlotPhoto != null) {
                            Image(
                                bitmap = activeSlotPhoto!!.asImageBitmap(),
                                contentDescription = "Device check photo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .alpha(0.2f),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = deviceCheckStatus,
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (isReceivingMode) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight + overlap)
                            .background(Color.Black)
                    ) {
                        // Status bar
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = receivingStatus,
                                color = Color(0xFFFF9800),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        // Console logs below status
                        ConsoleView(
                            logs = consoleLogs,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            scannerEnabled = false,
                            onScannerToggle = {}
                        )
                    }
                } else {
                    ConsoleView(
                        logs = consoleLogs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(consoleHeight),
                        scannerEnabled = scannerEnabled,
                        onScannerToggle = { mainViewModel.toggleScanner() }
                    )
                }
            }

            // Grid area — transparent background, hex gaps show console beneath
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight)
                    .align(Alignment.BottomCenter)
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

    // Shipment Picker BottomSheet (swipeable list)
    val showShipmentPicker by mainViewModel.showShipmentPicker.observeAsState(false)
    if (showShipmentPicker) {
        ModalBottomSheet(
            onDismissRequest = { mainViewModel.dismissShipmentPicker() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            // Observe shipment loading state to trigger recomposition
            val shipmentsLoaded by mainViewModel.shipmentsLoaded.observeAsState(false)
            val shipmentsError by mainViewModel.shipmentsError.observeAsState(null)
            val shipments = remember(shipmentsLoaded) { mainViewModel.getShipmentDisplayList() }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                // Header
                Text(
                    text = "Select Shipment  (long-press = manual)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                HorizontalDivider(color = Color.Gray.copy(alpha = 0.3f))

                if (shipmentsError != null) {
                    // Show error message
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("⚠️ Error loading shipments", color = Color(0xFFFF6B6B), fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(shipmentsError!!, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (shipments.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No shipments available", color = Color.Gray)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(shipments) { item ->
                            val bgColor = when {
                                item.isMatched -> Color(0xFF1B5E20) // dark green for auto-match
                                item.status == "delivered" || item.status == "cancelled" -> Color(0xFF212121)
                                else -> Color(0xFF2E2E2E)
                            }
                            val statusIcon = when (item.status) {
                                "delivered" -> "✅"
                                "cancelled" -> "❌"
                                "error" -> "⚠️"
                                "shipped" -> "\uD83D\uDE9A"
                                else -> "\uD83D\uDCE6"
                            }

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            mainViewModel.selectShipment(item.id)
                                        },
                                        onLongClick = {
                                            mainViewModel.openManualClientEntry()
                                        }
                                    ),
                                colors = CardDefaults.cardColors(containerColor = bgColor)
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    // Line 1: icon + tracking | product type | date
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "$statusIcon ${item.trackingNumber}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (item.productType.isNotEmpty()) {
                                            Text(
                                                text = item.productType,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFFF9800),
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    // Line 2: Sender (client) bold
                                    Text(
                                        text = item.senderName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE0E0E0)
                                    )
                                    if (item.senderAddress.isNotEmpty()) {
                                        Text(
                                            text = item.senderAddress,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF999999)
                                        )
                                    }
                                    // Line 3: Receiver
                                    if (item.receiverName.isNotEmpty()) {
                                        Text(
                                            text = "\u2192 ${item.receiverName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color(0xFF80CBC4)
                                        )
                                        if (item.receiverAddress.isNotEmpty()) {
                                            Text(
                                                text = "  ${item.receiverAddress}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF777777)
                                            )
                                        }
                                    }
                                    // Line 4: status | received by | date | MATCH
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        val infoLeft = buildString {
                                            append(item.status)
                                            if (item.receivedBy.isNotEmpty()) append(" | ${item.receivedBy}")
                                        }
                                        Text(
                                            text = infoLeft,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.Gray
                                        )
                                        Row {
                                            if (item.isMatched) {
                                                Text(
                                                    text = "MATCH ",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF4CAF50),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Text(
                                                text = item.date,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFFAAAAAA)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Receiving Workflow Step Modal (manual entry or device info)
    if (receivingModalJson != null) {
        LaunchedEffect(Unit) {
            mainViewModel.receivingData.forEach { (k, v) ->
                if (v is String) receivingModalFormState.putIfAbsent(k, v)
            }
        }

        ModalBottomSheet(
            onDismissRequest = { mainViewModel.dismissReceivingModal() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 20.dp)
            ) {
                DynamicUiRenderer(
                    layoutJson = receivingModalJson!!,
                    stateValues = receivingModalFormState.toMap(),
                    onValueChange = { key, value ->
                        receivingModalFormState[key] = value
                    },
                    onAction = { action, _ ->
                        mainViewModel.addLog("Receiving modal: $action")
                        when (action) {
                            "close_modal" -> {
                                receivingModalFormState.forEach { (k, v) ->
                                    mainViewModel.setReceivingDataValue(k, v)
                                }
                                mainViewModel.dismissReceivingModal()
                                mainViewModel.advanceToNextStep()
                                receivingModalFormState.clear()
                            }
                            "scan_serial" -> {
                                mainViewModel.dismissReceivingModal()
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
