package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    enabled: Boolean = true,
    side: HexagonSide = HexagonSide.FULL,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val backgroundColor = try {
        Color(android.graphics.Color.parseColor(colorHex))
    } catch (e: IllegalArgumentException) {
        Color.Gray
    }

    Box(
        modifier = modifier
            .clip(HexagonShape(side))
            .alpha(if (enabled) 1f else 0.6f)
            .background(backgroundColor)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (enabled) {
            Text(
                text = label,
                color = Color.White,
                textAlign = TextAlign.Center,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp)
            )
        }
    }
}
