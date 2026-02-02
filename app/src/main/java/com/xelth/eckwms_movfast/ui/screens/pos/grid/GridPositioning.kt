package com.xelth.eckwms_movfast.ui.screens.pos.grid

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.math.sqrt

data class Position(val x: Dp, val y: Dp)

data class GridConfig(
    val cellWidth: Dp = 140.dp,
    val cellHeight: Dp = 80.dp,
    val buttonGap: Dp = 6.dp,
    val verticalOverlap: Float = 0.75f,
    val containerWidth: Dp = 0.dp,
    val isMirrored: Boolean = false
)

fun Pair<Int, Int>.firstComponent(): Int = this.first
fun Pair<Int, Int>.secondComponent(): Int = this.second

/**
 * Converts virtual grid coordinates to physical Dp positions.
 * Supports asymmetric layout and mirroring (Left-handed mode).
 */
fun virtualToPhysical(row: Int, col: Int, config: GridConfig): Position {
    val fullWidth = config.cellWidth.value
    val halfWidth = fullWidth / 2f
    val gap = config.buttonGap.value

    // Calculate Standard (Right-Handed) X Position
    val standardX = if (row % 2 == 0) {
        // Even rows: HALF_LEFT at start
        when (col) {
            0 -> 0f  // HALF_LEFT starts at x=0
            1 -> halfWidth + gap - 1f
            3 -> halfWidth + gap - 1f + fullWidth + gap
            5 -> halfWidth + gap - 1f + 2 * fullWidth + 2 * gap
            else -> 0f
        }
    } else {
        // Odd rows: Full buttons shifted
        val isOddCol = col % 2 == 1
        val visualCol = col / 2
        visualCol.toFloat() * (fullWidth + gap) + (if (isOddCol) (fullWidth + gap) / 2f else 0f)
    }

    // Apply Mirroring if enabled
    val finalX = if (config.isMirrored && config.containerWidth.value > 0) {
        val elementWidth = if (row % 2 == 0 && col == 0) halfWidth else fullWidth
        config.containerWidth.value - standardX - elementWidth
    } else {
        standardX
    }

    val verticalSpacing = config.cellHeight * config.verticalOverlap
    val y = (row.toFloat() * (verticalSpacing + config.buttonGap).value).dp

    return Position(finalX.dp, y)
}

/**
 * Calculate center point of a cell's physical position.
 */
fun getCellCenter(row: Int, col: Int, config: GridConfig): Position {
    val pos = virtualToPhysical(row, col, config)
    return Position(
        x = pos.x + config.cellWidth / 2,
        y = pos.y + config.cellHeight / 2
    )
}

/**
 * Calculate Euclidean distance between two slots.
 */
fun calculateDistance(fromRow: Int, fromCol: Int, toRow: Int, toCol: Int, config: GridConfig): Float {
    val fromCenter = getCellCenter(fromRow, fromCol, config)
    val toCenter = getCellCenter(toRow, toCol, config)
    return sqrt(
        (toCenter.x.value - fromCenter.x.value).pow(2) + (toCenter.y.value - fromCenter.y.value).pow(2)
    )
}
