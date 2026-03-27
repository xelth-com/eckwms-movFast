package com.xelth.eckwms_movfast.core.domain.model

import java.util.Date

data class Transaction(
    val transactionId: String,
    val createdAt: Date,
    val updatedAt: Date,
    val items: List<TransactionItem>,
    val totalAmount: Double,
    val status: TransactionStatus = TransactionStatus.ACTIVE
) {
    fun calculateTotal(): Double {
        return items.sumOf { it.totalPrice }
    }

    fun getItemCount(): Int {
        return items.sumOf { it.quantity }
    }
}

data class TransactionItem(
    val itemId: String,
    val productId: Int,
    val productName: String,
    val unitPrice: Double,
    val quantity: Int,
    val totalPrice: Double
) {
    companion object {
        fun create(productId: Int, productName: String, unitPrice: Double, quantity: Int): TransactionItem {
            return TransactionItem(
                itemId = "${productId}_${System.currentTimeMillis()}",
                productId = productId,
                productName = productName,
                unitPrice = unitPrice,
                quantity = quantity,
                totalPrice = unitPrice * quantity
            )
        }
    }
}

enum class TransactionStatus {
    ACTIVE, COMPLETED, CANCELLED
}
