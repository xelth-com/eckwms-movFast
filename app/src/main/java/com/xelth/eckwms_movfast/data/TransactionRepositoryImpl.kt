package com.xelth.eckwms_movfast.data

import android.util.Log
import com.xelth.eckwms_movfast.core.domain.model.Transaction
import com.xelth.eckwms_movfast.core.domain.model.TransactionItem
import com.xelth.eckwms_movfast.core.domain.model.TransactionStatus
import com.xelth.eckwms_movfast.core.domain.repository.TransactionRepository
import com.xelth.eckwms_movfast.data.local.dao.ActiveTransactionDao
import com.xelth.eckwms_movfast.data.local.dao.SyncQueueDao
import com.xelth.eckwms_movfast.data.local.entity.ActiveTransactionEntity
import com.xelth.eckwms_movfast.data.local.entity.ActiveTransactionItemEntity
import com.xelth.eckwms_movfast.data.local.entity.SyncQueueEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID

class TransactionRepositoryImpl(
    private val activeTransactionDao: ActiveTransactionDao,
    private val syncQueueDao: SyncQueueDao
) : TransactionRepository {

    companion object {
        private const val TAG = "TransactionRepo"
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getActiveTransaction(): Flow<Transaction?> {
        return activeTransactionDao.getActiveTransaction().flatMapLatest { entity ->
            if (entity == null) {
                flowOf(null)
            } else {
                activeTransactionDao.getTransactionItems(entity.transactionId).map { items ->
                    mapToTransaction(entity, items)
                }
            }
        }
    }

    override suspend fun createTransaction(): Transaction {
        val transactionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = ActiveTransactionEntity(
            transactionId = transactionId,
            createdAt = now,
            updatedAt = now,
            totalAmount = 0.0,
            status = "active"
        )
        activeTransactionDao.insertTransaction(entity)

        return Transaction(
            transactionId = transactionId,
            createdAt = Date(now),
            updatedAt = Date(now),
            items = emptyList(),
            totalAmount = 0.0,
            status = TransactionStatus.ACTIVE
        )
    }

    override suspend fun addItemToTransaction(
        transactionId: String,
        productId: Int,
        productName: String,
        unitPrice: Double
    ): Result<TransactionItem> {
        return try {
            val existingItem = activeTransactionDao.getTransactionItemByProduct(transactionId, productId)

            val transactionItem = if (existingItem != null) {
                val newQuantity = existingItem.quantity + 1
                val newTotalPrice = unitPrice * newQuantity
                val updatedItem = existingItem.copy(
                    quantity = newQuantity,
                    totalPrice = newTotalPrice
                )
                activeTransactionDao.updateTransactionItem(updatedItem)
                mapToTransactionItem(updatedItem)
            } else {
                val itemEntity = ActiveTransactionItemEntity(
                    itemId = "${productId}_${System.currentTimeMillis()}",
                    transactionId = transactionId,
                    productId = productId,
                    productName = productName,
                    unitPrice = unitPrice,
                    quantity = 1,
                    totalPrice = unitPrice
                )
                activeTransactionDao.insertTransactionItem(itemEntity)
                mapToTransactionItem(itemEntity)
            }

            updateTransactionTotal(transactionId)
            Result.success(transactionItem)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateItemQuantity(
        transactionId: String,
        itemId: String,
        newQuantity: Int
    ): Result<TransactionItem> {
        return try {
            val items = activeTransactionDao.getTransactionItemsSync(transactionId)
            val targetItem = items.find { it.itemId == itemId }
                ?: return Result.failure(Exception("Item not found"))

            if (newQuantity <= 0) {
                activeTransactionDao.deleteTransactionItem(itemId)
                updateTransactionTotal(transactionId)
                return Result.failure(Exception("Item removed"))
            }

            val updatedItem = targetItem.copy(
                quantity = newQuantity,
                totalPrice = targetItem.unitPrice * newQuantity
            )
            activeTransactionDao.updateTransactionItem(updatedItem)
            updateTransactionTotal(transactionId)
            Result.success(mapToTransactionItem(updatedItem))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeItemFromTransaction(transactionId: String, itemId: String): Result<Unit> {
        return try {
            activeTransactionDao.deleteTransactionItem(itemId)
            updateTransactionTotal(transactionId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearActiveTransaction(): Result<Unit> {
        return try {
            activeTransactionDao.clearActiveTransaction()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun completeTransaction(transactionId: String): Result<Transaction> {
        return try {
            val transactionEntity = activeTransactionDao.getActiveTransactionSync()
            if (transactionEntity == null || transactionEntity.transactionId != transactionId) {
                return Result.failure(Exception("Transaction not found"))
            }

            val now = System.currentTimeMillis()
            val updatedEntity = transactionEntity.copy(
                status = "completed",
                updatedAt = now
            )
            activeTransactionDao.updateTransaction(updatedEntity)

            val items = activeTransactionDao.getTransactionItemsSync(transactionId)
            val transaction = mapToTransaction(updatedEntity, items)

            // Enqueue for sync via Relay
            enqueueTransactionSync(transaction, items)

            Result.success(transaction)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeItemFromOrder(itemId: String): Result<Unit> {
        return try {
            activeTransactionDao.deleteItemAndRecalculateTotals(itemId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getCompletedTransactions(): Flow<List<Transaction>> {
        return activeTransactionDao.getFinishedTransactions().map { entities ->
            entities.map { entity ->
                runBlocking {
                    val items = activeTransactionDao.getTransactionItemsSync(entity.transactionId)
                    mapToTransaction(entity, items)
                }
            }
        }
    }

    private suspend fun updateTransactionTotal(transactionId: String) {
        val entity = activeTransactionDao.getActiveTransactionSync()
        if (entity != null && entity.transactionId == transactionId) {
            val items = activeTransactionDao.getTransactionItemsSync(transactionId)
            val totalAmount = items.sumOf { it.totalPrice }
            activeTransactionDao.updateTransaction(
                entity.copy(totalAmount = totalAmount, updatedAt = System.currentTimeMillis())
            )
        }
    }

    /**
     * Serializes a completed transaction to JSON and enqueues it in the SyncQueue
     * for background delivery via SyncWorker → RelayClient.
     */
    private suspend fun enqueueTransactionSync(
        transaction: Transaction,
        itemEntities: List<ActiveTransactionItemEntity>
    ) {
        try {
            val itemsArray = JSONArray()
            itemEntities.forEach { item ->
                itemsArray.put(JSONObject().apply {
                    put("itemId", item.itemId)
                    put("productId", item.productId)
                    put("productName", item.productName)
                    put("unitPrice", item.unitPrice)
                    put("quantity", item.quantity)
                    put("totalPrice", item.totalPrice)
                })
            }

            val payload = JSONObject().apply {
                put("transactionId", transaction.transactionId)
                put("createdAt", transaction.createdAt.time)
                put("completedAt", transaction.updatedAt.time)
                put("totalAmount", transaction.totalAmount)
                put("itemCount", transaction.getItemCount())
                put("items", itemsArray)
            }.toString()

            val queueEntity = SyncQueueEntity(
                type = "transaction",
                payload = payload
            )
            val queueId = syncQueueDao.addToQueue(queueEntity)
            Log.d(TAG, "Transaction ${transaction.transactionId} enqueued for sync (queueId=$queueId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enqueue transaction sync: ${e.message}", e)
        }
    }

    private fun mapToTransaction(entity: ActiveTransactionEntity, items: List<ActiveTransactionItemEntity>): Transaction {
        return Transaction(
            transactionId = entity.transactionId,
            createdAt = Date(entity.createdAt),
            updatedAt = Date(entity.updatedAt),
            items = items.map { mapToTransactionItem(it) },
            totalAmount = entity.totalAmount,
            status = when (entity.status) {
                "active" -> TransactionStatus.ACTIVE
                "completed" -> TransactionStatus.COMPLETED
                "cancelled" -> TransactionStatus.CANCELLED
                else -> TransactionStatus.ACTIVE
            }
        )
    }

    private fun mapToTransactionItem(entity: ActiveTransactionItemEntity): TransactionItem {
        return TransactionItem(
            itemId = entity.itemId,
            productId = entity.productId,
            productName = entity.productName,
            unitPrice = entity.unitPrice,
            quantity = entity.quantity,
            totalPrice = entity.totalPrice
        )
    }
}
