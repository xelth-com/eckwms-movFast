package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.ui.data.ConnectionType
import com.xelth.eckwms_movfast.ui.data.NetworkHealthState
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SlotType

@Composable
fun SelectionAreaSheet(
    renderCells: List<RenderCell>,
    cellWidth: Dp = 140.dp,
    cellHeight: Dp = 80.dp,
    buttonGap: Dp = 6.dp,
    networkState: NetworkHealthState = NetworkHealthState.Checking,
    regStatus: String = "unknown",
    onButtonClick: (String) -> Unit = {},
    onButtonLongClick: (String) -> Unit = {},
    onNetworkIndicatorClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize()) {

        // 1. Grid Layer
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Identify last row index
            val maxRow = renderCells.maxOfOrNull { it.logicalPosition.first } ?: 0

            renderCells.forEach { cell ->
                val content = cell.content
                val slotType = (cell.geometryMetadata["slotType"] as? SlotType) ?: SlotType.DEAD
                val offsetX = cell.cssPosition.x
                val offsetY = cell.cssPosition.y
                val (row, col) = cell.logicalPosition

                val isBottomRow = row == maxRow

                // Handle DEAD slots in bottom row as Indicators
                if (slotType == SlotType.DEAD) {
                    if (isBottomRow) {
                        IndicatorButton(
                            modifier = Modifier
                                .size(width = cellWidth, height = cellHeight)
                                .offset(x = offsetX, y = offsetY),
                            isActive = false,
                            defaultColor = Color(0xFF1E1E1E)
                        )
                    }
                    return@forEach
                }

                val buttonSide = when(slotType) {
                    SlotType.FULL -> HexagonSide.FULL
                    SlotType.HALF_LEFT -> HexagonSide.LEFT
                    SlotType.HALF_RIGHT -> HexagonSide.RIGHT
                    else -> HexagonSide.FULL
                }

                val buttonWidth = if (buttonSide == HexagonSide.FULL) {
                    cellWidth
                } else {
                    (cellWidth / 2) - 1.dp
                }

                // First HALF_LEFT (row=0, col=0) -> Network Indicator (clickable)
                if (slotType == SlotType.HALF_LEFT && row == 0) {
                    NetworkIndicatorButton(
                        modifier = Modifier
                            .size(width = buttonWidth, height = cellHeight)
                            .offset(x = offsetX, y = offsetY)
                            .clickable { onNetworkIndicatorClick() },
                        side = buttonSide,
                        networkState = networkState,
                        regStatus = regStatus
                    )
                    return@forEach
                }

                // Regular buttons logic
                var label = ""
                var color = "#3a3a3a"
                var enabled = false

                when {
                    content is Map<*, *> && content["type"] == "button" -> {
                        label = content["label"] as? String ?: ""
                        color = content["color"] as? String ?: "#3a3a3a"
                        enabled = true
                    }
                    else -> {
                        label = ""
                        color = "#2a2a2a"
                        enabled = false
                    }
                }

                val action = if (content is Map<*, *>) content["action"] as? String else null

                HexagonalButton(
                    modifier = Modifier
                        .size(width = buttonWidth, height = cellHeight)
                        .offset(x = offsetX, y = offsetY),
                    label = label,
                    colorHex = color,
                    enabled = enabled,
                    side = buttonSide,
                    onClick = {
                        if (enabled && action != null) onButtonClick(action)
                    },
                    onLongClick = {
                        if (enabled && action != null) onButtonLongClick(action)
                    }
                )
            }
        }

        // Gradient removed — clean boundary between console and grid
    }
}

/**
 * Network indicator inside a HALF_LEFT hexagonal button.
 * Shows connection icon, server hash, and latency.
 */
@Composable
fun NetworkIndicatorButton(
    modifier: Modifier = Modifier,
    side: HexagonSide,
    networkState: NetworkHealthState,
    regStatus: String
) {
    val isApproved = regStatus == "active" || regStatus == "running"

    val bgColor = when {
        !isApproved -> Color(0xFF8B0000)
        !networkState.isConnected() -> Color(0xFF3a3a3a)
        networkState.connectionType == ConnectionType.LOCAL_IP -> Color(0xFF1B5E20)
        else -> Color(0xFF5D4037)
    }

    val textColor = when {
        !isApproved -> Color(0xFFF44336)
        !networkState.isConnected() -> Color(0xFF9E9E9E)
        networkState.connectionType == ConnectionType.LOCAL_IP -> Color(0xFF4CAF50)
        else -> Color(0xFFFFEB3B)
    }

    Box(
        modifier = modifier
            .clip(HexagonShape(side))
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (networkState.isConnected()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 2.dp)
            ) {
                Text(
                    text = networkState.serverHash,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    lineHeight = 12.sp
                )
                if (networkState.latencyMs > 0) {
                    Text(
                        text = "${networkState.latencyMs}ms",
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        lineHeight = 11.sp
                    )
                }
            }
        } else {
            Text(
                text = "OFF",
                color = textColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun IndicatorButton(
    modifier: Modifier = Modifier,
    isActive: Boolean,
    defaultColor: Color
) {
    val targetColor = if (isActive) Color.Green else defaultColor
    val animatedColor by animateColorAsState(targetValue = targetColor, animationSpec = tween(500))

    Box(
        modifier = modifier
            .clip(HexagonShape(HexagonSide.FULL))
            .background(animatedColor.copy(alpha = 0.3f))
    )
}
