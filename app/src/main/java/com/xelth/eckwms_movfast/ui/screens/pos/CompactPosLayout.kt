package com.xelth.eckwms_movfast.ui.screens.pos

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import com.xelth.eckwms_movfast.ui.screens.pos.components.ConsoleViewPager
import com.xelth.eckwms_movfast.ui.screens.pos.components.SelectionAreaSheet
import com.xelth.eckwms_movfast.ui.viewmodels.PosViewModel
import com.xelth.eckwms_movfast.utils.DeviceConfig

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactPosLayout(
    viewModel: PosViewModel,
    deviceConfig: DeviceConfig,
    onLogout: () -> Unit
) {
    val mainRenderCells by viewModel.mainRenderCells.collectAsState()
    val overflowRightRenderCells by viewModel.overflowRightRenderCells.collectAsState()
    val overflowLeftRenderCells by viewModel.overflowLeftRenderCells.collectAsState()
    val currentRenderCells by viewModel.currentRenderCells.collectAsState()
    val pageCount by viewModel.pageCount.collectAsState()
    val selectionAreaHeight by viewModel.selectionAreaHeight.collectAsState()

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    
    // Gesture state variables
    var totalDragX by remember { mutableFloatStateOf(0f) }
    var totalDragY by remember { mutableFloatStateOf(0f) }
    var lockedAxis by remember { mutableStateOf<String?>(null) }

    // Wrap the entire layout in BoxWithConstraints to capture dimensions
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val containerWidth = maxWidth
        val containerHeight = maxHeight
        
        // Update layout dimensions when container size changes
        LaunchedEffect(containerWidth, containerHeight) {
            if (containerWidth > 0.dp && containerHeight > 0.dp) {
                viewModel.updateLayoutDimensions(containerWidth, containerHeight, density)
            }
        }

        // Calculate the visual overlap of the top row of hexagonal buttons
        val topRowOverlap = with(density) {
            val buttonHeight = viewModel.gridManager.config.cellHeight.value
            val verticalOverlap = viewModel.gridManager.config.verticalOverlap
            (buttonHeight * (1 - verticalOverlap)).dp
        }
        
        // Calculate console bottom padding: selectionAreaHeight minus the visual overlap
        val consoleBottomPadding = (selectionAreaHeight - topRowOverlap).coerceAtLeast(0.dp)
        
        ConsoleViewPager(
            viewModel = viewModel,
            deviceConfig = deviceConfig,
            bottomPadding = consoleBottomPadding
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(selectionAreaHeight.coerceAtLeast(80.dp))
        ) {
            val pagerState = rememberPagerState(
                initialPage = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % pageCount),
                pageCount = { Int.MAX_VALUE }
            )

            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { 
                                totalDragX = 0f
                                totalDragY = 0f
                                lockedAxis = null
                            },
                            onDragEnd = {
                                val dragThreshold = with(density) { 50.dp.toPx() }
                                val axisLockThreshold = with(density) { 20.dp.toPx() }
                                
                                when {
                                    // If axis is locked to vertical, handle panel resizing
                                    lockedAxis == "vertical" && abs(totalDragY) > dragThreshold -> {
                                        when {
                                            totalDragY < 0 -> viewModel.increaseRows()
                                            totalDragY > 0 -> viewModel.decreaseRows()
                                        }
                                    }
                                    // If axis is locked to horizontal, handle page changing
                                    lockedAxis == "horizontal" && abs(totalDragX) > dragThreshold -> {
                                        coroutineScope.launch {
                                            val currentPage = pagerState.currentPage
                                            when {
                                                totalDragX > 0 -> pagerState.scrollToPage(currentPage - 1)
                                                totalDragX < 0 -> pagerState.scrollToPage(currentPage + 1)
                                            }
                                        }
                                    }
                                    // If no axis is locked but movement exceeds threshold, 
                                    // determine dominant direction and act accordingly
                                    lockedAxis == null -> {
                                        val absX = abs(totalDragX)
                                        val absY = abs(totalDragY)
                                        
                                        if (absY > absX && absY > dragThreshold) {
                                            // Vertical movement dominant
                                            when {
                                                totalDragY < 0 -> viewModel.increaseRows()
                                                totalDragY > 0 -> viewModel.decreaseRows()
                                            }
                                        } else if (absX > absY && absX > dragThreshold) {
                                            // Horizontal movement dominant
                                            coroutineScope.launch {
                                                val currentPage = pagerState.currentPage
                                                when {
                                                    totalDragX > 0 -> pagerState.scrollToPage(currentPage - 1)
                                                    totalDragX < 0 -> pagerState.scrollToPage(currentPage + 1)
                                                }
                                            }
                                        }
                                    }
                                }
                                
                                // Reset state
                                totalDragX = 0f
                                totalDragY = 0f
                                lockedAxis = null
                            }
                        ) { change, dragAmount ->
                            change.consume()
                            totalDragX += dragAmount.x
                            totalDragY += dragAmount.y
                            
                            // Determine axis lock based on initial movement
                            val axisLockThreshold = with(density) { 20.dp.toPx() }
                            if (lockedAxis == null) {
                                val absX = abs(totalDragX)
                                val absY = abs(totalDragY)
                                
                                if (absY > axisLockThreshold && absY > absX * 1.5f) {
                                    lockedAxis = "vertical"
                                } else if (absX > axisLockThreshold && absX > absY * 1.5f) {
                                    lockedAxis = "horizontal"
                                }
                            }
                        }
                    }
            ) { pageIndex ->
                val actualPage = when (pageCount) {
                    0 -> 0
                    else -> pageIndex % pageCount
                }

                when (actualPage) {
                    0 -> SelectionAreaSheet(viewModel = viewModel, renderCells = if (currentRenderCells.isNotEmpty()) currentRenderCells else mainRenderCells)
                    1 -> SelectionAreaSheet(viewModel = viewModel, renderCells = overflowRightRenderCells)
                    2 -> SelectionAreaSheet(viewModel = viewModel, renderCells = overflowLeftRenderCells)
                    else -> SelectionAreaSheet(viewModel = viewModel, renderCells = if (currentRenderCells.isNotEmpty()) currentRenderCells else mainRenderCells)
                }
            }
        }
    }
}