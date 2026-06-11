package com.xelth.eckwms_movfast.core.domain.repository

import com.xelth.eckwms_movfast.core.domain.model.Transaction
import com.xelth.eckwms_movfast.core.domain.model.TransactionItem
import kotlinx.coroutines.flow.Flow

interface TransactionRepository {

    fun getActiveTransaction(): Flow<Transaction?>

    suspend fun createTransaction(): Transaction

    suspend fun addItemToTransaction(transactionId: String, productId: Int, productName: String, unitPrice: Double): Result<TransactionItem>

    suspend fun updateItemQuantity(transactionId: String, itemId: String, newQuantity: Int): Result<TransactionItem>

    suspend fun removeItemFromTransaction(transactionId: String, itemId: String): Result<Unit>

    suspend fun clearActiveTransaction(): Result<Unit>

    suspend fun completeTransaction(transactionId: String): Result<Transaction>

    suspend fun removeItemFromOrder(itemId: String): Result<Unit>

    fun getCompletedTransactions(): Flow<List<Transaction>>
}
