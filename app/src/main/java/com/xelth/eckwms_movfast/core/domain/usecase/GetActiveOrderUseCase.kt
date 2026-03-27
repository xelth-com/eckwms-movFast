package com.xelth.eckwms_movfast.core.domain.usecase

import com.xelth.eckwms_movfast.core.domain.model.Transaction
import com.xelth.eckwms_movfast.core.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow

class GetActiveOrderUseCase(
    private val transactionRepository: TransactionRepository
) {
    operator fun invoke(): Flow<Transaction?> {
        return transactionRepository.getActiveTransaction()
    }
}
