package com.xelth.eckwms_movfast.ui.screens.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.ui.screens.pos.components.order.InterlockingShape

/**
 * One selectable console line. Generic version of the POS `OrderLineItem` so the
 * console (the ecKasse cart console) can be reused for any list — trip tickets,
 * scan log, etc.
 */
data class ConsoleRow(
    val id: String,
    val primary: String,
    val secondary: String? = null,
    val trailing: String? = null,
    val onClick: (() -> Unit)? = null,
    val onLongClick: (() -> Unit)? = null
)

/**
 * The interactive, multi-line console — generalized from the ecKasse cashier
 * cart (`OrderConsoleView` / `OrderItemRow` / `InterlockingShape`). Rows are
 * interlocking "complex buttons" (alternating left/right offset), newest at the
 * bottom, auto-scrolls down, scrolls bottom → up.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleList(
    rows: List<ConsoleRow>,
    modifier: Modifier = Modifier,
    emptyHint: String = ""
) {
    if (rows.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (emptyHint.isNotEmpty()) {
                Text(emptyHint, color = Color(0xFF666666), fontSize = 13.sp)
            }
        }
        return
    }
    val listState = rememberLazyListState()
    // Keep the newest (bottom, index 0 under reverseLayout) in view.
    LaunchedEffect(rows.size) { listState.scrollToItem(0) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy((-24).dp, Alignment.Bottom)
    ) {
        itemsIndexed(rows, key = { _, r -> r.id }) { index, row ->
            ConsoleRowItem(row = row, isEven = index % 2 == 0)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleRowItem(row: ConsoleRow, isEven: Boolean) {
    val brush = if (isEven) {
        Brush.verticalGradient(listOf(Color(0xFF2A3640), Color(0xFF161B20)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF24306B), Color(0xFF141A33)))
    }
    Box(
        modifier = (if (isEven) Modifier.fillMaxWidth().padding(end = 30.dp)
        else Modifier.fillMaxWidth().padding(start = 30.dp)).height(50.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(InterlockingShape(isEven = isEven))
                .background(brush)
                .then(
                    if (row.onClick != null || row.onLongClick != null) {
                        Modifier.combinedClickable(
                            onClick = { row.onClick?.invoke() },
                            onLongClick = { row.onLongClick?.invoke() }
                        )
                    } else Modifier
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (isEven) 64.dp else 34.dp,
                        end = if (isEven) 34.dp else 64.dp,
                        top = 6.dp, bottom = 6.dp
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        row.primary,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!row.secondary.isNullOrEmpty()) {
                        Text(
                            row.secondary,
                            color = Color(0xFF90A4AE),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!row.trailing.isNullOrEmpty()) {
                    Text(row.trailing, color = Color(0xFFB0BEC5), fontSize = 14.sp)
                }
            }
        }
    }
}
