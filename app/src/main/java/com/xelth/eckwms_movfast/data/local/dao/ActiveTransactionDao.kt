package com.xelth.eckwms_movfast.data.local.dao

import androidx.room.*
import com.xelth.eckwms_movfast.data.local.entity.ActiveTransactionEntity
import com.xelth.eckwms_movfast.data.local.entity.ActiveTransactionItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ActiveTransactionDao {

    @Query("SELECT * FROM active_transactions WHERE status = 'active' LIMIT 1")
    fun getActiveTransaction(): Flow<ActiveTransactionEntity?>

    @Query("SELECT * FROM active_transaction_items WHERE transactionId = :transactionId")
    fun getTransactionItems(transactionId: String): Flow<List<ActiveTransactionItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: ActiveTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransactionItem(item: ActiveTransactionItemEntity)

    @Update
    suspend fun updateTransaction(transaction: ActiveTransactionEntity)

    @Update
    suspend fun updateTransactionItem(item: ActiveTransactionItemEntity)

    @Query("DELETE FROM active_transactions WHERE transactionId = :transactionId")
    suspend fun deleteTransaction(transactionId: String)

    @Query("DELETE FROM active_transaction_items WHERE transactionId = :transactionId")
    suspend fun deleteTransactionItems(transactionId: String)

    @Query("DELETE FROM active_transaction_items WHERE itemId = :itemId")
    suspend fun deleteTransactionItem(itemId: String)

    @Transaction
    suspend fun clearActiveTransaction() {
        val activeTransaction = getActiveTransactionSync()
        if (activeTransaction != null) {
            deleteTransactionItems(activeTransaction.transactionId)
            deleteTransaction(activeTransaction.transactionId)
        }
    }

    @Query("SELECT * FROM active_transactions WHERE status = 'active' LIMIT 1")
    suspend fun getActiveTransactionSync(): ActiveTransactionEntity?

    @Query("SELECT * FROM active_transaction_items WHERE transactionId = :transactionId AND productId = :productId LIMIT 1")
    suspend fun getTransactionItemByProduct(transactionId: String, productId: Int): ActiveTransactionItemEntity?

    @Query("SELECT * FROM active_transaction_items WHERE transactionId = :transactionId")
    suspend fun getTransactionItemsSync(transactionId: String): List<ActiveTransactionItemEntity>

    @Transaction
    suspend fun deleteItemAndRecalculateTotals(itemId: String) {
        val item = getItemById(itemId) ?: return
        deleteTransactionItem(itemId)
        val remainingItems = getTransactionItemsSync(item.transactionId)
        val totalAmount = remainingItems.sumOf { it.totalPrice }
        val transaction = getActiveTransactionSync()
        if (transaction != null) {
            updateTransaction(
                transaction.copy(
                    totalAmount = totalAmount,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    @Query("SELECT * FROM active_transaction_items WHERE itemId = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): ActiveTransactionItemEntity?

    @Query("SELECT * FROM active_transactions WHERE status = 'completed' ORDER BY updatedAt DESC")
    fun getFinishedTransactions(): Flow<List<ActiveTransactionEntity>>
}
