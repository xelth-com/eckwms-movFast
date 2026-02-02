package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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

    val density = LocalDensity.current

    // Initialize network health monitoring (same as ScanScreen does)
    LaunchedEffect(Unit) {
        viewModel.onViewModelReady()
    }

    // No Scaffold, no TopBar — console goes edge-to-edge from top
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight

        // Recalculate grid dimensions when container size changes
        LaunchedEffect(containerWidth, containerHeight) {
            if (containerWidth > 0.dp && containerHeight > 0.dp) {
                // Pass only the button area height for row calculation
                val consoleH = when {
                    containerHeight < 600.dp -> 150.dp
                    containerHeight < 800.dp -> 200.dp
                    else -> 250.dp
                }
                mainViewModel.updateLayoutDimensions(containerWidth, containerHeight - consoleH, density)
            }
        }

        val gridConfig = mainViewModel.gridConfig

        val consoleHeight = when {
            containerHeight < 600.dp -> 150.dp
            containerHeight < 800.dp -> 200.dp
            else -> 250.dp
        }

        val selectionAreaHeight = containerHeight - consoleHeight

        Column(modifier = Modifier.fillMaxSize()) {
            // Console — full width, from very top of screen
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(consoleHeight)
                    .background(Color(0xFF121212))
            ) {
                ConsoleView(
                    logs = consoleLogs,
                    modifier = Modifier.fillMaxSize(),
                    scannerEnabled = scannerEnabled,
                    onScannerToggle = { mainViewModel.toggleScanner() }
                )
            }

            // Selection area — hexagonal buttons fill remaining space
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(selectionAreaHeight)
            ) {
                SelectionAreaSheet(
                    renderCells = renderCells,
                    cellWidth = gridConfig.cellWidth,
                    cellHeight = gridConfig.cellHeight,
                    buttonGap = gridConfig.buttonGap,
                    networkState = networkHealthState,
                    regStatus = deviceRegistrationStatus,
                    onButtonClick = { action ->
                        mainViewModel.onButtonClick(action)
                        when (action) {
                            "navigate_scan" -> navController.navigate("scanScreen")
                            "navigate_restock" -> navController.navigate("restockScreen")
                            "navigate_ai" -> navController.navigate("scanScreen")
                            "navigate_settings" -> navController.navigate("settings")
                        }
                    }
                )
            }
        }
    }
}
