package com.xelth.eckwms_movfast.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    navController: NavController,
    viewModel: ScanRecoveryViewModel
) {
    val mainViewModel: MainScreenViewModel = viewModel()
    val networkHealthState by viewModel.networkHealthState.observeAsState(NetworkHealthState.Checking)

    val renderCells by mainViewModel.renderCells.observeAsState(emptyList())
    val consoleLogs by mainViewModel.consoleLogs.observeAsState(emptyList())
    val pageCount by mainViewModel.pageCount.observeAsState(1)

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            MainTopBar(networkHealthState)
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val containerWidth = maxWidth
            val containerHeight = maxHeight

            val gridConfig = GridConfig(
                cellWidth = 140.dp,
                cellHeight = 80.dp,
                buttonGap = 6.dp,
                verticalOverlap = 0.75f
            )

            val consoleHeight = when {
                containerHeight < 600.dp -> 150.dp
                containerHeight < 800.dp -> 200.dp
                else -> 250.dp
            }

            val verticalOverlap = with(density) {
                val buttonHeight = gridConfig.cellHeight.value
                val verticalOverlap = gridConfig.verticalOverlap
                (buttonHeight * (1 - verticalOverlap)).dp
            }

            val selectionAreaHeight = containerHeight - consoleHeight - padding.calculateTopPadding()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
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

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(consoleHeight)
                        .background(Color(0xFF121212))
                ) {
                    ConsoleView(
                        logs = consoleLogs,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainTopBar(networkState: NetworkHealthState) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "eckWMS",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text("GO", modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        },
        actions = {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (networkState.isConnected()) Color(0xFFE8F5E9) else Color(0xFFFFEBEE),
                modifier = Modifier.padding(end = 16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val color = if (networkState.isConnected()) Color(0xFF2E7D32) else Color(0xFFC62828)
                    val icon = if (networkState.isConnected()) "☁️" else "❌"

                    Text(text = icon, fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (networkState.isConnected()) "ONLINE" else "OFFLINE",
                        color = color,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    )
}
