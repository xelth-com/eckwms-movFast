package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "active_transaction_items",
    foreignKeys = [
        ForeignKey(
            entity = ActiveTransactionEntity::class,
            parentColumns = ["transactionId"],
            childColumns = ["transactionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["transactionId"])]
)
data class ActiveTransactionItemEntity(
    @PrimaryKey
    val itemId: String,
    val transactionId: String,
    val productId: Int,
    val productName: String,
    val unitPrice: Double,
    val quantity: Int,
    val totalPrice: Double
)
