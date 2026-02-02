package com.xelth.eckwms_movfast.ui.screens.pos.components.order

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

class InterlockingShape(
    private val isEven: Boolean = false
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val path = Path()
        val width = size.width
        val height = size.height

        val cutoutWidthDp = 60.dp
        val bodyHeightFraction = 0.5f

        val cutoutWidthPx = with(density) { cutoutWidthDp.toPx() }

        val bodyHeight = height * bodyHeightFraction
        val bodyMargin = (height - bodyHeight) / 2f
        val topY = bodyMargin
        val bottomY = height - bodyMargin

        val midHeight = height / 2f
        val transitionStartPx = with(density) { 45.dp.toPx() }

        val tipOffsetPx = with(density) { 15.dp.toPx() }
        val tipTransitionPx = with(density) { 15.dp.toPx() }

        if (isEven) {
            val bodyEndPx = width - (tipOffsetPx + tipTransitionPx)
            path.moveTo(0f, 0f)
            path.lineTo(transitionStartPx, 0f)
            path.lineTo(cutoutWidthPx, topY)
            path.lineTo(bodyEndPx, topY)
            path.lineTo(width - tipOffsetPx, midHeight)
            path.lineTo(bodyEndPx, bottomY)
            path.lineTo(cutoutWidthPx, bottomY)
            path.lineTo(transitionStartPx, height)
            path.lineTo(0f, height)
            path.close()
        } else {
            val bodyStartPx = tipOffsetPx + tipTransitionPx
            path.moveTo(bodyStartPx, topY)
            path.lineTo(width - cutoutWidthPx, topY)
            path.lineTo(width - transitionStartPx, 0f)
            path.lineTo(width, 0f)
            path.lineTo(width, height)
            path.lineTo(width - transitionStartPx, height)
            path.lineTo(width - cutoutWidthPx, bottomY)
            path.lineTo(bodyStartPx, bottomY)
            path.lineTo(tipOffsetPx, midHeight)
            path.close()
        }
        return Outline.Generic(path)
    }
}
