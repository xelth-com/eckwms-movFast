package com.xelth.eckwms_movfast.ui.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import java.util.Date

@Immutable
data class Receipt(
    val transactionId: String,
    val createdAt: Date,
    val updatedAt: Date,
    val items: ImmutableList<ReceiptItem>,
    val totalAmount: Double,
    val formattedTotalAmount: String,
    val itemCount: Int
)

@Immutable
data class ReceiptItem(
    val itemId: String,
    val productId: Int,
    val productName: String,
    val unitPrice: Double,
    val quantity: Int,
    val totalPrice: Double,
    val formattedUnitPrice: String,
    val formattedTotalPrice: String
)
