package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xelth.eckwms_movfast.ui.screens.pos.components.ConsoleView
import com.xelth.eckwms_movfast.ui.screens.pos.components.SelectionAreaSheet
import com.xelth.eckwms_movfast.ui.screens.pos.grid.GridConfig
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SlotType
import com.xelth.eckwms_movfast.ui.viewmodels.MainScreenViewModel
import com.xelth.eckwms_movfast.ui.viewmodels.ScanRecoveryViewModel
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import com.xelth.eckwms_movfast.ui.data.ConnectionType
import kotlinx.coroutines.launch

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
    val pageCount by mainViewModel.pageCount.observeAsState(1)
    val scannerEnabled by mainViewModel.scannerEnabled.observeAsState(true)

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            MainTopBar(networkHealthState, deviceRegistrationStatus)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight

            // Recalculate grid dimensions when container size changes
            LaunchedEffect(containerWidth, containerHeight) {
                if (containerWidth > 0.dp && containerHeight > 0.dp) {
                    mainViewModel.updateLayoutDimensions(containerWidth, containerHeight, density)
                }
            }

            // Use dynamic grid config from ViewModel
            val gridConfig = mainViewModel.gridConfig

            // Console takes top portion, buttons fill the rest
            val consoleHeight = when {
                containerHeight < 600.dp -> 150.dp
                containerHeight < 800.dp -> 200.dp
                else -> 250.dp
            }

            val selectionAreaHeight = containerHeight - consoleHeight

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(selectionAreaHeight)
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    SelectionAreaSheet(
                        renderCells = renderCells,
                        cellWidth = gridConfig.cellWidth,
                        cellHeight = gridConfig.cellHeight,
                        buttonGap = gridConfig.buttonGap,
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(networkState: NetworkHealthState, deviceRegistrationStatus: String) {
    val isDeviceApproved = deviceRegistrationStatus == "active" || deviceRegistrationStatus == "running"

    val indicatorColor = when {
        !isDeviceApproved -> {
            Color(0xFFF44336)
        }
        !networkState.isConnected() -> {
            Color(0xFF9E9E9E)
        }
        networkState.connectionType == ConnectionType.LOCAL_IP -> {
            Color(0xFF4CAF50)
        }
        networkState.connectionType == ConnectionType.GLOBAL_URL -> {
            Color(0xFFFFEB3B)
        }
        else -> {
            Color(0xFF9E9E9E)
        }
    }

    val connectionEmoji = when {
        networkState.isConnected() -> {
            when (networkState.connectionType) {
                ConnectionType.LOCAL_IP -> "🖥️"
                ConnectionType.GLOBAL_URL -> "🌍"
                else -> "❌"
            }
        }
        else -> "❌"
    }

    TopAppBar(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "eckWMS",
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )

                Row(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .background(
                            color = indicatorColor.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(16.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = connectionEmoji,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.size(18.dp)
                    )

                    if (networkState.isConnected() && networkState.serverHash.isNotEmpty()) {
                        Column(
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = networkState.serverHash,
                                color = indicatorColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = when (networkState.connectionType) {
                                    ConnectionType.LOCAL_IP -> "LOCAL"
                                    ConnectionType.GLOBAL_URL -> "GLOBAL"
                                    else -> "OFFLINE"
                                },
                                color = indicatorColor,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF000000),
            titleContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}
