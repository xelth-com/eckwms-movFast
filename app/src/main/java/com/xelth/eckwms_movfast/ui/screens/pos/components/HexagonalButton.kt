package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HexagonalButton(
    modifier: Modifier = Modifier,
    label: String,
    colorHex: String,
    textColorHex: String? = null,
    enabled: Boolean = true,
    side: HexagonSide = HexagonSide.FULL,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    // PUSH-TO-TALK: when provided, the button fires onPress on touch-down and
    // onRelease on lift (used by the 🎤 mic button) instead of click.
    onPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null
) {
    val backgroundColor = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (e: IllegalArgumentException) {
        Color.Gray
    }

    // Dynamic font sizing: larger for short labels, smaller for long ones
    // Check if label has newline (icon + text format)
    val hasEmoji = label.any { it.code > 0x1F00 }
    val lines = label.split("\n")
    val maxLineLength = lines.maxOfOrNull { it.length } ?: label.length

    val fontSize = when {
        hasEmoji && lines.size >= 2 -> 16.sp  // emoji + label format
        maxLineLength <= 4 -> 20.sp           // very short: X, OK, etc
        maxLineLength <= 6 -> 18.sp           // short labels
        maxLineLength <= 8 -> 16.sp           // medium labels
        maxLineLength <= 12 -> 14.sp          // longer labels
        else -> 12.sp                         // very long labels
    }

    val lineHeight = when {
        hasEmoji && lines.size >= 2 -> 18.sp
        fontSize >= 18.sp -> 22.sp
        fontSize >= 16.sp -> 18.sp
        else -> 16.sp
    }

    Box(
        modifier = modifier
            .clip(HexagonShape(side))
            .alpha(if (enabled) 1f else 0.8f)
            .background(backgroundColor)
            .then(
                if (onPress != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            onPress()
                            tryAwaitRelease()
                            onRelease?.invoke()
                        })
                    }
                } else {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Disabled buttons stay VISIBLE: the label + emoji render greyed-out
        // (a saturation-0 layer turns even colour emoji grey) on the dimmed
        // hex — clearly present, clearly inert. Empty filler slots pass an
        // empty label, so nothing shows there either way.
        val textColor = when {
            !enabled -> Color(0xFF9E9E9E)
            textColorHex != null ->
                try { Color(android.graphics.Color.parseColor(textColorHex)) } catch (e: Exception) { Color.White }
            else -> Color.White
        }
        Text(
            text = label,
            color = textColor,
            textAlign = TextAlign.Center,
            fontSize = fontSize,
            lineHeight = lineHeight,
            fontFamily = FontFamily.Monospace,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(4.dp)
                .then(if (enabled) Modifier else Modifier.grayscale())
        )
    }
}

/** Render the content through a saturation-0 layer — the only way to grey out
 *  colour emoji glyphs (a text colour can't tint them). */
private fun Modifier.grayscale(): Modifier = drawWithCache {
    val paint = Paint().apply {
        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    }
    onDrawWithContent {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
}
