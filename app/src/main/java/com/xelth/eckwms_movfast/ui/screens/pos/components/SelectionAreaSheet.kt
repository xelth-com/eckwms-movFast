package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.screens.pos.grid.Position
import com.xelth.eckwms_movfast.ui.screens.pos.grid.RenderCell
import com.xelth.eckwms_movfast.ui.screens.pos.grid.SlotType

@Composable
fun SelectionAreaSheet(
    renderCells: List<RenderCell>,
    cellWidth: Dp = 140.dp,
    cellHeight: Dp = 80.dp,
    buttonGap: Dp = 6.dp,
    onButtonClick: (String) -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        renderCells.forEach { cell ->
            val content = cell.content
            val slotType = (cell.geometryMetadata["slotType"] as? SlotType) ?: SlotType.DEAD

            if (slotType == SlotType.DEAD) {
                return@forEach
            }

            var label = ""
            var color = "#3a3a3a"
            var enabled = false

            when {
                content is Map<*, *> && content["type"] == "button" -> {
                    label = content["label"] as? String ?: ""
                    color = content["color"] as? String ?: "#3a3a3a"
                    enabled = true
                }
            }

            val offsetX = cell.cssPosition.x
            val offsetY = cell.cssPosition.y

            val buttonSide = when(slotType) {
                SlotType.FULL -> HexagonSide.FULL
                else -> HexagonSide.FULL
            }

            val buttonWidth = if (buttonSide == HexagonSide.FULL) {
                cellWidth
            } else {
                (cellWidth / 2) - 1.dp
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
