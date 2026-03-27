package com.xelth.eckwms_movfast.core.domain.usecase

import com.xelth.eckwms_movfast.core.domain.repository.TransactionRepository

class RemoveItemFromOrderUseCase(
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(itemId: String): Result<Unit> {
        return transactionRepository.removeItemFromOrder(itemId)
    }
}
