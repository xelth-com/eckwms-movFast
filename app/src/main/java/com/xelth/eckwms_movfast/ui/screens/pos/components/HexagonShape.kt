package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

enum class HexagonSide { FULL, LEFT, RIGHT }

class HexagonShape(private val side: HexagonSide = HexagonSide.FULL) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        return Outline.Generic(
            path = hexagonPath(size, side)
        )
    }
}

private fun hexagonPath(size: Size, side: HexagonSide): Path {
    val width = size.width
    val height = size.height
    return Path().apply {
        when (side) {
            HexagonSide.FULL -> {
                moveTo(width * 0.5f, 0f)
                lineTo(width, height * 0.25f)
                lineTo(width, height * 0.75f)
                lineTo(width * 0.5f, height)
                lineTo(0f, height * 0.75f)
                lineTo(0f, height * 0.25f)
            }
            HexagonSide.LEFT -> {
                moveTo(0f, 0f)
                lineTo(width, height * 0.25f)
                lineTo(width, height * 0.75f)
                lineTo(0f, height)
            }
            HexagonSide.RIGHT -> {
                moveTo(width, 0f)
                lineTo(0f, height * 0.25f)
                lineTo(0f, height * 0.75f)
                lineTo(width, height)
            }
        }
        close()
    }
}
