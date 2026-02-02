package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    onButtonClick: (String) -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        renderCells.forEach { cell ->
            val content = cell.content
            val slotType = (cell.geometryMetadata["slotType"] as? SlotType) ?: SlotType.DEAD

            if (slotType == SlotType.DEAD) {
                return@forEach
            }

            val offsetX = cell.cssPosition.x
            val offsetY = cell.cssPosition.y
            val (row, col) = cell.logicalPosition

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

            // First HALF_LEFT (row=0, col=0) → Network Indicator
            if (slotType == SlotType.HALF_LEFT && row == 0) {
                NetworkIndicatorButton(
                    modifier = Modifier
                        .size(width = buttonWidth, height = cellHeight)
                        .offset(x = offsetX, y = offsetY),
                    side = buttonSide,
                    networkState = networkState,
                    regStatus = regStatus
                )
                return@forEach
            }

            // Regular buttons
            var label = ""
            var color = "#3a3a3a"
            var enabled = false

            when {
                content is Map<*, *> && content["type"] == "button" -> {
                    label = content["label"] as? String ?: ""
                    color = content["color"] as? String ?: "#3a3a3a"
                    enabled = true
                }
                content is Map<*, *> && content["type"] == "system" -> {
                    label = ""
                    color = "#2a2a2a"
                    enabled = false
                }
                content is Map<*, *> && content["type"] == "empty" -> {
                    label = ""
                    color = "#2a2a2a"
                    enabled = false
                }
                content == null || content is String && content.isEmpty() -> {
                    label = ""
                    color = "#2a2a2a"
                    enabled = false
                }
            }

            HexagonalButton(
                modifier = Modifier
                    .size(width = buttonWidth, height = cellHeight)
                    .offset(x = offsetX, y = offsetY),
                label = label,
                colorHex = color,
                enabled = enabled,
                side = buttonSide,
                onClick = {
                    if (enabled && content is Map<*, *>) {
                        val action = content["action"] as? String
                        if (action != null) {
                            onButtonClick(action)
                        }
                    }
                }
            )
        }
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
        !isApproved -> Color(0xFF8B0000) // Dark red
        !networkState.isConnected() -> Color(0xFF3a3a3a)
        networkState.connectionType == ConnectionType.LOCAL_IP -> Color(0xFF1B5E20) // Dark green
        else -> Color(0xFF5D4037) // Dark brown/yellow
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
                // Server hash — max size to fill the button
                Text(
                    text = networkState.serverHash,
                    color = textColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    lineHeight = 12.sp
                )
                // Latency — tight spacing
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
