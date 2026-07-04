package com.xelth.eckwms_movfast.core.domain.usecase

import com.xelth.eckwms_movfast.core.domain.model.Transaction
import com.xelth.eckwms_movfast.core.domain.model.TransactionItem
import com.xelth.eckwms_movfast.core.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/**
 * Hand-written fake (no mockk) that records calls and returns canned
 * Flows / Results. Every field is configurable per-test.
 */
private class FakeTransactionRepository : TransactionRepository {

    // ── Canned returns ──────────────────────────────────────────────────
    var activeFlow: Flow<Transaction?> = flowOf(null)
    var createResult: Transaction = tx("created-tx")
    var addResult: Result<TransactionItem> = Result.success(item(1))
    var removeFromOrderResult: Result<Unit> = Result.success(Unit)
    var completedFlow: Flow<List<Transaction>> = flowOf(emptyList())

    // ── Recorded calls ──────────────────────────────────────────────────
    var createTransactionCallCount = 0
    var addItemCallCount = 0
    var lastAddItemTransactionId: String? = null
    var lastAddItemProductId: Int? = null
    var lastAddItemProductName: String? = null
    var lastAddItemUnitPrice: Double? = null
    var removeFromOrderCallCount = 0
    var lastRemovedItemId: String? = null

    override fun getActiveTransaction(): Flow<Transaction?> = activeFlow

    override suspend fun createTransaction(): Transaction {
        createTransactionCallCount++
        return createResult
    }

    override suspend fun addItemToTransaction(
        transactionId: String,
        productId: Int,
        productName: String,
        unitPrice: Double
    ): Result<TransactionItem> {
        addItemCallCount++
        lastAddItemTransactionId = transactionId
        lastAddItemProductId = productId
        lastAddItemProductName = productName
        lastAddItemUnitPrice = unitPrice
        return addResult
    }

    override suspend fun removeItemFromOrder(itemId: String): Result<Unit> {
        removeFromOrderCallCount++
        lastRemovedItemId = itemId
        return removeFromOrderResult
    }

    override fun getCompletedTransactions(): Flow<List<Transaction>> = completedFlow

    // ── Unused interface members ────────────────────────────────────────
    override suspend fun updateItemQuantity(
        transactionId: String,
        itemId: String,
        newQuantity: Int
    ): Result<TransactionItem> = error("not exercised")

    override suspend fun removeItemFromTransaction(
        transactionId: String,
        itemId: String
    ): Result<Unit> = error("not exercised")

    override suspend fun clearActiveTransaction(): Result<Unit> = error("not exercised")

    override suspend fun completeTransaction(transactionId: String): Result<Transaction> =
        error("not exercised")
}

private fun tx(id: String): Transaction =
    Transaction(
        transactionId = id,
        createdAt = Date(0),
        updatedAt = Date(0),
        items = emptyList(),
        totalAmount = 0.0
    )

private fun item(productId: Int): TransactionItem =
    TransactionItem(
        itemId = "item-$productId",
        productId = productId,
        productName = "Product $productId",
        unitPrice = 1.0,
        quantity = 1,
        totalPrice = 1.0
    )

class AddItemToOrderUseCaseTest {

    @Test
    fun `no active transaction creates new one and adds to new id`() = runTest {
        val repo = FakeTransactionRepository().apply {
            activeFlow = flowOf(null)              // no active transaction
            createResult = tx("new-tx")
            addResult = Result.success(item(42))
        }
        val useCase = AddItemToOrderUseCase(repo)

        val result = useCase(productId = 42, productName = "Widget", unitPrice = 9.99)

        assertEquals(1, repo.createTransactionCallCount)     // created exactly once
        assertEquals(1, repo.addItemCallCount)
        assertEquals("new-tx", repo.lastAddItemTransactionId) // added to the NEW id
        assertEquals(42, repo.lastAddItemProductId)
        assertEquals("Widget", repo.lastAddItemProductName)
        assertEquals(9.99, repo.lastAddItemUnitPrice!!, 1e-9)
        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull()!!.productId)
    }

    @Test
    fun `existing active transaction adds to existing id without creating`() = runTest {
        val repo = FakeTransactionRepository().apply {
            activeFlow = flowOf(tx("existing-tx"))
            addResult = Result.success(item(7))
        }
        val useCase = AddItemToOrderUseCase(repo)

        val result = useCase(productId = 7, productName = "Bolt", unitPrice = 2.50)

        assertEquals(0, repo.createTransactionCallCount)      // never created
        assertEquals(1, repo.addItemCallCount)
        assertEquals("existing-tx", repo.lastAddItemTransactionId)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `repository add failure is propagated`() = runTest {
        val boom = IllegalStateException("db down")
        val repo = FakeTransactionRepository().apply {
            activeFlow = flowOf(tx("existing-tx"))
            addResult = Result.failure(boom)
        }
        val useCase = AddItemToOrderUseCase(repo)

        val result = useCase(productId = 1, productName = "X", unitPrice = 1.0)

        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
    }
}

class RemoveItemFromOrderUseCaseTest {

    @Test
    fun `delegates to repository with item id on success`() = runTest {
        val repo = FakeTransactionRepository().apply {
            removeFromOrderResult = Result.success(Unit)
        }
        val useCase = RemoveItemFromOrderUseCase(repo)

        val result = useCase("item-99")

        assertEquals(1, repo.removeFromOrderCallCount)
        assertEquals("item-99", repo.lastRemovedItemId)
        assertTrue(result.isSuccess)
    }

    @Test
    fun `propagates repository failure`() = runTest {
        val boom = RuntimeException("nope")
        val repo = FakeTransactionRepository().apply {
            removeFromOrderResult = Result.failure(boom)
        }
        val useCase = RemoveItemFromOrderUseCase(repo)

        val result = useCase("item-1")

        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
    }
}

class GetActiveOrderUseCaseTest {

    @Test
    fun `emits active transaction from repository`() = runTest {
        val active = tx("active-tx")
        val repo = FakeTransactionRepository().apply { activeFlow = flowOf(active) }
        val useCase = GetActiveOrderUseCase(repo)

        val emitted = useCase().first()

        assertEquals("active-tx", emitted!!.transactionId)
    }

    @Test
    fun `emits null when no active transaction`() = runTest {
        val repo = FakeTransactionRepository().apply { activeFlow = flowOf(null) }
        val useCase = GetActiveOrderUseCase(repo)

        val emitted = useCase().firstOrNull()

        assertNull(emitted)
    }
}

class GetCompletedTransactionsUseCaseTest {

    @Test
    fun `emits completed transactions list from repository`() = runTest {
        val list = listOf(tx("done-1"), tx("done-2"))
        val repo = FakeTransactionRepository().apply { completedFlow = flowOf(list) }
        val useCase = GetCompletedTransactionsUseCase(repo)

        val emitted = useCase().first()

        assertEquals(2, emitted.size)
        assertEquals("done-1", emitted[0].transactionId)
        assertEquals("done-2", emitted[1].transactionId)
    }

    @Test
    fun `emits empty list when none completed`() = runTest {
        val repo = FakeTransactionRepository().apply { completedFlow = flowOf(emptyList()) }
        val useCase = GetCompletedTransactionsUseCase(repo)

        val emitted = useCase().first()

        assertTrue(emitted.isEmpty())
        assertFalse(emitted.isNotEmpty())
    }
}
