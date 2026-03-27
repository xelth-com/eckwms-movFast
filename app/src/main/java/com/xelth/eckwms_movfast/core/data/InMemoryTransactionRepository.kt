package com.xelth.eckwms_movfast.core.data

import com.xelth.eckwms_movfast.core.domain.model.Transaction
import com.xelth.eckwms_movfast.core.domain.model.TransactionItem
import com.xelth.eckwms_movfast.core.domain.model.TransactionStatus
import com.xelth.eckwms_movfast.core.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.Date
import java.util.UUID

/**
 * In-memory stub implementation of TransactionRepository.
 * Replaces the Room-backed implementation from eckasseand for now.
 */
class InMemoryTransactionRepository : TransactionRepository {

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())

    override fun getActiveTransaction(): Flow<Transaction?> {
        return _transactions.map { list ->
            list.firstOrNull { it.status == TransactionStatus.ACTIVE }
        }
    }

    override suspend fun createTransaction(): Transaction {
        val transaction = Transaction(
            transactionId = UUID.randomUUID().toString(),
            createdAt = Date(),
            updatedAt = Date(),
            items = emptyList(),
            totalAmount = 0.0,
            status = TransactionStatus.ACTIVE
        )
        _transactions.value = _transactions.value + transaction
        return transaction
    }

    override suspend fun addItemToTransaction(
        transactionId: String,
        productId: Int,
        productName: String,
        unitPrice: Double
    ): Result<TransactionItem> {
        val newItem = TransactionItem.create(productId, productName, unitPrice, 1)
        _transactions.value = _transactions.value.map { tx ->
            if (tx.transactionId == transactionId) {
                // Check if product already exists — increment quantity
                val existingItem = tx.items.firstOrNull { it.productId == productId }
                val updatedItems = if (existingItem != null) {
                    tx.items.map { item ->
                        if (item.productId == productId) {
                            item.copy(
                                quantity = item.quantity + 1,
                                totalPrice = item.unitPrice * (item.quantity + 1)
                            )
                        } else item
                    }
                } else {
                    tx.items + newItem
                }
                tx.copy(
                    items = updatedItems,
                    totalAmount = updatedItems.sumOf { it.totalPrice },
                    updatedAt = Date()
                )
            } else tx
        }
        return Result.success(newItem)
    }

    override suspend fun updateItemQuantity(
        transactionId: String,
        itemId: String,
        newQuantity: Int
    ): Result<TransactionItem> {
        var updatedItem: TransactionItem? = null
        _transactions.value = _transactions.value.map { tx ->
            if (tx.transactionId == transactionId) {
                val updatedItems = tx.items.map { item ->
                    if (item.itemId == itemId) {
                        item.copy(quantity = newQuantity, totalPrice = item.unitPrice * newQuantity).also { updatedItem = it }
                    } else item
                }
                tx.copy(items = updatedItems, totalAmount = updatedItems.sumOf { it.totalPrice }, updatedAt = Date())
            } else tx
        }
        return updatedItem?.let { Result.success(it) } ?: Result.failure(Exception("Item not found"))
    }

    override suspend fun removeItemFromTransaction(transactionId: String, itemId: String): Result<Unit> {
        _transactions.value = _transactions.value.map { tx ->
            if (tx.transactionId == transactionId) {
                val updatedItems = tx.items.filter { it.itemId != itemId }
                tx.copy(items = updatedItems, totalAmount = updatedItems.sumOf { it.totalPrice }, updatedAt = Date())
            } else tx
        }
        return Result.success(Unit)
    }

    override suspend fun clearActiveTransaction(): Result<Unit> {
        _transactions.value = _transactions.value.filter { it.status != TransactionStatus.ACTIVE }
        return Result.success(Unit)
    }

    override suspend fun completeTransaction(transactionId: String): Result<Transaction> {
        var completed: Transaction? = null
        _transactions.value = _transactions.value.map { tx ->
            if (tx.transactionId == transactionId) {
                tx.copy(status = TransactionStatus.COMPLETED, updatedAt = Date()).also { completed = it }
            } else tx
        }
        return completed?.let { Result.success(it) } ?: Result.failure(Exception("Transaction not found"))
    }

    override suspend fun removeItemFromOrder(itemId: String): Result<Unit> {
        _transactions.value = _transactions.value.map { tx ->
            if (tx.status == TransactionStatus.ACTIVE) {
                val updatedItems = tx.items.filter { it.itemId != itemId }
                tx.copy(items = updatedItems, totalAmount = updatedItems.sumOf { it.totalPrice }, updatedAt = Date())
            } else tx
        }
        return Result.success(Unit)
    }

    override fun getCompletedTransactions(): Flow<List<Transaction>> {
        return _transactions.map { list ->
            list.filter { it.status == TransactionStatus.COMPLETED }
        }
    }
}
