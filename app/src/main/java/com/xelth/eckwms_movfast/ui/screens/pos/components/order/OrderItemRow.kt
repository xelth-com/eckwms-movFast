package com.xelth.eckwms_movfast.ui.screens.pos.components.order

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.ui.model.OrderLineItem

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrderItemRow(
    item: OrderLineItem,
    isEven: Boolean = false,
    index: Int = 0,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null
) {
    val backgroundBrush = if (isEven) {
        // Четные: чуть светлее чем фон surface
        val surfaceColor = MaterialTheme.colorScheme.surface
        val lighterColor = MaterialTheme.colorScheme.surfaceContainer
        Brush.verticalGradient(
            colors = listOf(lighterColor, surfaceColor)
        )
    } else {
        // Нечетные: чуть темнее чем фон surface
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainer  
        val darkerColor = MaterialTheme.colorScheme.surface
        Brush.verticalGradient(
            colors = listOf(surfaceColor, darkerColor)
        )
    }
    
    Box(
        modifier = modifier
            .then(
                if (isEven) {
                    // Четные строки: обычная ширина минус 30dp справа
                    Modifier
                        .fillMaxWidth()
                        .padding(end = 30.dp)
                } else {
                    // Нечетные строки: сдвиг 30dp слева и прилипание к правому краю
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 30.dp)
                }
            )
            .height(48.dp)
            // УБИРАЕМ combinedClickable отсюда - он будет добавлен снаружи к ограниченной области
    ) {
        // Кликабельная область точно повторяет форму
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(InterlockingShape(isEven = isEven))
                .background(backgroundBrush)
                .then(
                    if (onClick != null || onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { onClick?.invoke() },
                            onLongClick = { onLongClick?.invoke() }
                        )
                    } else Modifier
                )
        ) {
        // Контент размещается так чтобы текст был выровнен между четными и нечетными рядами
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (isEven) 60.dp else 30.dp,  // Нечетные: 30dp (60dp - 30dp сдвига строки)
                    end = if (isEven) 30.dp else 60.dp,    // Четные: меньше padding для компенсации укорочения
                    top = 8.dp, 
                    bottom = 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Product Name
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = item.productName,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
            
            // Quantity
            Text(
                text = "${item.quantity}x",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            
            // Unit Price
            Text(
                text = "€${item.formattedUnitPrice}",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            
            // Total Price
            Text(
                text = "€${item.formattedTotalPrice}",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.width(70.dp)
            )
        }
        }
    }
}