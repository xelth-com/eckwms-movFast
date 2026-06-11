package com.xelth.eckwms_movfast.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "active_transactions")
data class ActiveTransactionEntity(
    @PrimaryKey
    val transactionId: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val totalAmount: Double = 0.0,
    val status: String = "active"
)
