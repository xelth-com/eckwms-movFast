package com.xelth.eckwms_movfast.ui.screens.pos.components.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

@Composable
fun HandIcon(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    Canvas(
        modifier = modifier.size(24.dp)
    ) {
        drawHandIcon(color)
    }
}

private fun DrawScope.drawHandIcon(color: Color) {
    val path = Path().apply {
        // Draw a simplified hand icon
        // Palm
        moveTo(size.width * 0.3f, size.height * 0.8f)
        lineTo(size.width * 0.3f, size.height * 0.5f)
        lineTo(size.width * 0.7f, size.height * 0.5f)
        lineTo(size.width * 0.7f, size.height * 0.8f)
        close()
        
        // Thumb
        moveTo(size.width * 0.2f, size.height * 0.7f)
        lineTo(size.width * 0.2f, size.height * 0.55f)
        lineTo(size.width * 0.3f, size.height * 0.55f)
        lineTo(size.width * 0.3f, size.height * 0.7f)
        close()
        
        // Index finger
        moveTo(size.width * 0.35f, size.height * 0.5f)
        lineTo(size.width * 0.35f, size.height * 0.2f)
        lineTo(size.width * 0.45f, size.height * 0.2f)
        lineTo(size.width * 0.45f, size.height * 0.5f)
        close()
        
        // Middle finger
        moveTo(size.width * 0.47f, size.height * 0.5f)
        lineTo(size.width * 0.47f, size.height * 0.15f)
        lineTo(size.width * 0.57f, size.height * 0.15f)
        lineTo(size.width * 0.57f, size.height * 0.5f)
        close()
        
        // Ring finger
        moveTo(size.width * 0.59f, size.height * 0.5f)
        lineTo(size.width * 0.59f, size.height * 0.25f)
        lineTo(size.width * 0.69f, size.height * 0.25f)
        lineTo(size.width * 0.69f, size.height * 0.5f)
        close()
    }
    
    drawPath(path, color)
}