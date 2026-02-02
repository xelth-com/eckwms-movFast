package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xelth.eckwms_movfast.ui.screens.pos.components.ConsoleView
import com.xelth.eckwms_movfast.ui.screens.pos.components.SelectionAreaSheet
import com.xelth.eckwms_movfast.ui.viewmodels.MainScreenViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState

@OptIn(ExperimentalFoundationApi::class)
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

    // Bridge: forward hardware scanner results to Repair Mode (or auto-enter if device matches)
    val scannedBarcode by viewModel.scannedBarcode.observeAsState(null)
    LaunchedEffect(scannedBarcode) {
        if (scannedBarcode != null) {
            mainViewModel.onRepairScan(scannedBarcode!!)
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

    // No Scaffold, no TopBar — console goes edge-to-edge from top
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

        Column(modifier = Modifier.fillMaxSize()) {
            // Console / Repair Status Header — fills top, flexible height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(consoleHeight)
                    .background(if (isRepairMode) Color(0xFF263238) else Color(0xFF121212))
            ) {
                if (isRepairMode) {
                    // Repair Mode: show status text centered
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
                    onNetworkIndicatorClick = {
                        navController.navigate("pairingScreen")
                    }
                )
            }
        }
    }
}
