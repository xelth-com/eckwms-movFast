package com.xelth.eckwms_movfast.ui.screens.pos.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xelth.eckwms_movfast.ui.screens.pos.components.order.OrderConsoleView
import com.xelth.eckwms_movfast.ui.screens.pos.components.receipts.ReceiptsConsoleView
import com.xelth.eckwms_movfast.ui.viewmodels.PosViewModel
import com.xelth.eckwms_movfast.utils.DeviceConfig

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConsoleViewPager(
    viewModel: PosViewModel,
    deviceConfig: DeviceConfig,
    bottomPadding: Dp = 0.dp
) {
    val orderUiState by viewModel.orderUiState.collectAsState()
    val receiptsUiState by viewModel.receiptsUiState.collectAsState()
    val contextMenuState by viewModel.contextMenuState.collectAsState()
    val pageCount = 3 // Order, Receipts, Agent
    val pagerState = rememberPagerState(
        initialPage = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2 % pageCount),
        pageCount = { Int.MAX_VALUE }
    )

    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { pageIndex ->
        val actualPage = pageIndex % pageCount
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding),
            contentAlignment = Alignment.Center
        ) {
            when (actualPage) {
                0 -> OrderConsoleView(
                    orderItems = orderUiState.orderItems,
                    totalAmount = orderUiState.totalAmount,
                    itemCount = orderUiState.itemCount,
                    contextMenuState = contextMenuState,
                    onItemClick = viewModel::onOrderItemClick,
                    onItemLongClick = viewModel::onOrderItemLongClick,
                    onEditItemClick = viewModel::onEditItemClick,
                    onRemoveItemClick = viewModel::onRemoveItemClick,
                    onHideContextMenu = viewModel::hideContextMenu,
                    modifier = Modifier.fillMaxSize()
                )
                1 -> ReceiptsConsoleView(
                    receipts = receiptsUiState.receipts,
                    onReprintClick = viewModel::onReprintClick,
                    modifier = Modifier.fillMaxSize()
                )
                2 -> Text(text = "Agent Console - Page ${actualPage + 1}")
            }
        }
    }
}