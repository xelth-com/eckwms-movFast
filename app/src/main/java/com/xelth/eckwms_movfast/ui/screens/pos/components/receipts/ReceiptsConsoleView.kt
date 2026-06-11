package com.xelth.eckwms_movfast.ui.screens.pos.components.receipts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xelth.eckwms_movfast.ui.model.Receipt
import kotlinx.collections.immutable.ImmutableList

@Composable
fun ReceiptsConsoleView(
    receipts: ImmutableList<Receipt>,
    modifier: Modifier = Modifier,
    onReprintClick: (String) -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        Text(
            text = "Recent Receipts",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(16.dp),
            textAlign = TextAlign.Center
        )
        
        if (receipts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No completed transactions yet",
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Complete a transaction to see receipts here",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(
                    items = receipts,
                    key = { receipt -> receipt.transactionId }
                ) { receipt ->
                    ReceiptItemRow(
                        receipt = receipt,
                        onReprintClick = onReprintClick
                    )
                }
            }
        }
    }
}