package br.com.bebidaspdv.core

import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf

data class Feed<T>(
    val value: T,
    val fromCache: Boolean = false,
    val pendingWrites: Boolean = false,
)

enum class SyncState { PENDING, CONFIRMED, REJECTED }

data class OperationStatus(
    val id: String,
    val state: SyncState,
    val message: String? = null,
)

interface PosRepository {
    fun observeProducts(): Flow<Feed<List<Product>>>

    fun observeMovements(month: YearMonth, zone: ZoneId): Flow<Feed<List<Movement>>>

    val operations: StateFlow<List<OperationStatus>>
    /** Writes such as expenses remain visible to sync status after their screen closes. */
    val pendingLocalWrites: StateFlow<Boolean> get() = MutableStateFlow(false)

    /**
     * Returns once the atomic write appears in the local persistent Firestore view.
     * It must not wait for server acknowledgement, which may take indefinitely offline.
     * Remote confirmation or rejection is delivered through [operations].
     * A retry must reuse [Movement.id], never create another movement for the same checkout.
     */
    suspend fun submit(movement: Movement)

    /** New products start at zero stock. Stock changes belong to ENTRADA/SAIDA batches. */
    suspend fun saveProduct(product: Product)

    suspend fun deleteProduct(product: Product) { error("Exclusão indisponível.") }
    fun observeCustomers(): Flow<Feed<List<Customer>>> = flowOf(Feed(emptyList()))
    suspend fun saveCustomer(customer: Customer) { error("Cadastro de clientes indisponível.") }
    fun observeExpenses(month: YearMonth, zone: ZoneId): Flow<Feed<List<Expense>>> = flowOf(Feed(emptyList()))
    suspend fun saveExpense(expense: Expense) { error("Despesas indisponíveis.") }
    fun observeCreditSales(): Flow<Feed<List<Movement>>> = flowOf(Feed(emptyList()))
    fun observeDebtPayments(): Flow<Feed<List<DebtPayment>>> = flowOf(Feed(emptyList()))
    fun observeDebtPayments(month: YearMonth, zone: ZoneId): Flow<Feed<List<DebtPayment>>> = observeDebtPayments()
    fun observeReceivables(): Flow<Feed<List<ReceivableAccount>>> = flowOf(Feed(emptyList()))
    suspend fun loadMovements(ids: List<String>): List<Movement> = emptyList()
    suspend fun loadReceivables(ids: List<String>): List<ReceivableAccount> = emptyList()
    /** Server transaction: online only, UUID idempotency and a shared outstanding balance. */
    suspend fun settleDebt(payment: DebtPayment) { error("Recebimentos indisponíveis.") }
    /** Durable requests whose server result still needs reconciliation on this device. */
    val pendingDebtPayments: StateFlow<List<DebtPayment>> get() = MutableStateFlow(emptyList())
    /** Must complete before switching accounts; callers should apply an appropriate timeout. */
    suspend fun waitForPendingWrites() {}
}
