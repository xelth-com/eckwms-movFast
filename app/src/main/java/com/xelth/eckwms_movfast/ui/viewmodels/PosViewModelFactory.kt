package com.xelth.eckwms_movfast.ui.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xelth.eckwms_movfast.core.domain.usecase.AddItemToOrderUseCase
import com.xelth.eckwms_movfast.core.domain.usecase.GetActiveOrderUseCase
import com.xelth.eckwms_movfast.core.domain.usecase.GetCompletedTransactionsUseCase
import com.xelth.eckwms_movfast.core.domain.usecase.RemoveItemFromOrderUseCase
import com.xelth.eckwms_movfast.data.TransactionRepositoryImpl
import com.xelth.eckwms_movfast.data.local.AppDatabase
import com.xelth.eckwms_movfast.debug.DebugEventBus

/**
 * Factory for PosViewModel that provides Room-backed DI (no Hilt in this project).
 */
class PosViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {

    companion object {
        private val debugEventBus = DebugEventBus()
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PosViewModel::class.java)) {
            val db = AppDatabase.getInstance(application)
            val repository = TransactionRepositoryImpl(
                activeTransactionDao = db.activeTransactionDao(),
                syncQueueDao = db.syncQueueDao()
            )
            val referenceDao = db.referenceDao()

            return PosViewModel(
                getActiveOrderUseCase = GetActiveOrderUseCase(repository),
                addItemToOrderUseCase = AddItemToOrderUseCase(repository),
                removeItemFromOrderUseCase = RemoveItemFromOrderUseCase(repository),
                getCompletedTransactionsUseCase = GetCompletedTransactionsUseCase(repository),
                debugEventBus = debugEventBus,
                referenceDao = referenceDao
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
