package com.xelth.eckwms_movfast.core.domain.usecase

import com.xelth.eckwms_movfast.core.domain.model.TransactionItem
import com.xelth.eckwms_movfast.core.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.firstOrNull

class AddItemToOrderUseCase(
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(
        productId: Int,
        productName: String,
        unitPrice: Double
    ): Result<TransactionItem> {
        val activeTransaction = transactionRepository.getActiveTransaction()
        val currentTransaction = activeTransaction.firstOrNull()
        val transactionId = currentTransaction?.transactionId

        val finalTransactionId = transactionId ?: run {
            val newTransaction = transactionRepository.createTransaction()
            newTransaction.transactionId
        }

        return transactionRepository.addItemToTransaction(
            transactionId = finalTransactionId,
            productId = productId,
            productName = productName,
            unitPrice = unitPrice
        )
    }
}
