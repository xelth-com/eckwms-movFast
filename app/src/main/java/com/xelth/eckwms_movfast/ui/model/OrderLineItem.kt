package com.xelth.eckwms_movfast.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class OrderLineItem(
    val itemId: String,
    val productId: Int,
    val productName: String,
    val unitPrice: Double,
    val quantity: Int,
    val totalPrice: Double,
    val formattedUnitPrice: String,
    val formattedTotalPrice: String
)
