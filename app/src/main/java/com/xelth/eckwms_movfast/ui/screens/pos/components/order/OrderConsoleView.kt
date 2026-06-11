package com.xelth.eckwms_movfast.ui.screens.pos.components.order

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import com.xelth.eckwms_movfast.ui.model.OrderLineItem
import com.xelth.eckwms_movfast.ui.viewmodels.ContextMenuState
import kotlinx.collections.immutable.ImmutableList

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrderConsoleView(
    orderItems: ImmutableList<OrderLineItem>,
    totalAmount: Double,
    itemCount: Int,
    contextMenuState: ContextMenuState,
    onItemClick: (String) -> Unit,
    onItemLongClick: (String) -> Unit,
    onEditItemClick: (String) -> Unit,
    onRemoveItemClick: (String) -> Unit,
    onHideContextMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current Order",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                
                if (itemCount > 0) {
                    val chipBackground = Modifier.background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp)
                    )
                    Box(
                        modifier = chipBackground
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "$itemCount items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            
            // Order Items List
            if (orderItems.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No items in order",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tap products to add them",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            } else {
                // Items list with stable click handling and alternating styles
                val listState = rememberLazyListState()
                
                // Auto-scroll to newest item when list changes
                LaunchedEffect(orderItems.size) {
                    if (orderItems.isNotEmpty()) {
                        listState.animateScrollToItem(0)
                    }
                }
                
                Box(
                    modifier = Modifier.weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .align(Alignment.BottomCenter),
                        verticalArrangement = Arrangement.spacedBy((-24).dp, Alignment.Bottom),
                        reverseLayout = true
                    ) {
                        itemsIndexed(
                            items = orderItems.reversed(),
                            key = { _, item -> item.itemId },
                            contentType = { _, _ -> "OrderLineItem" }
                        ) { index, item ->
                            val isEven = (orderItems.size - 1 - index) % 2 == 0
                            val density = LocalDensity.current
                            
                            OrderItemRow(
                                item = item,
                                isEven = isEven,
                                index = orderItems.size - 1 - index,
                                onClick = { onItemClick(item.itemId) },
                                onLongClick = { onItemLongClick(item.itemId) }
                            )
                        }
                    }
                    
                    // Context Menu
                    if (contextMenuState is ContextMenuState.Visible) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = onHideContextMenu,
                            offset = DpOffset(x = 16.dp, y = (-40).dp),
                            properties = PopupProperties(focusable = true)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                onClick = { onEditItemClick(contextMenuState.itemId) }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { onRemoveItemClick(contextMenuState.itemId) }
                            )
                        }
                    }
                }
            }
            
            // Total Section
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "Total:",
                    modifier = Modifier.align(Alignment.CenterStart).offset(x = 15.dp, y = (-5).dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                
                Text(
                    text = "€${String.format("%.2f", totalAmount)}",
                    modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-55).dp, y = (-5).dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}