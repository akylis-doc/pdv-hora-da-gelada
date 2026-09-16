package br.com.bebidaspdv.ui

import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.*
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class V3ViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: TrackingRepository
    private val models = mutableListOf<PosViewModel>()
    private val operatorId = "operator-v3"
    private val product = Product("00000000-0000-4000-8000-000000000001", "Produto", "7891234567890", 9999, 650, 1000)
    private val customer = Customer("00000000-0000-4000-8000-000000000002", "Cliente", "11999999999", creditLimitCents = 1500, balanceCents = 1000)

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = TrackingRepository(product, customer)
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun model(capabilities: OperatorCapabilities = OperatorCapabilities.ADMIN, cash: CashControlRepository? = null) =
        PosViewModel(repository, operatorId = operatorId, operatorName = "Operador", cashControlRepository = cash, capabilities = capabilities).also(models::add)

    @Test fun `startup and pending write status never open reports or historical credit queries`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.pendingWrites.collect() }
        // Even a retained UI subscriber must not query reports while the current screen is Caixa.
        backgroundScope.launch { model.reports.collect() }
        runCurrent()
        repository.operations.value = listOf(OperationStatus("pending-sale", SyncState.PENDING))
        runCurrent()

        assertTrue(model.pendingWrites.value)
        assertEquals(0, repository.movementQueries)
        assertEquals(0, repository.expenseQueries)
        assertEquals(0, repository.receiptQueries)
        assertEquals(0, repository.accountQueries)
        assertEquals(0, repository.legacyCreditQueries)
        assertEquals(1, repository.products.active)
        assertEquals(1, repository.customers.active)
    }

    @Test fun `reports subscribe only on screen and replace month listeners before stopping on exit`() = runTest(dispatcher) {
        val cash = TrackingCashRepository()
        val model = model(cash = cash)
        val display = backgroundScope.launch { model.reports.collect() }
        runCurrent()
        model.setActiveSection("relatorios")
        runCurrent()

        assertEquals(1, repository.movements.active)
        assertEquals(1, repository.expenses.active)
        assertEquals(1, repository.receipts.active)
        assertEquals(1, cash.reversals.active)
        assertFalse(model.reports.value.loading)
        val initialMonth = model.month.value

        model.changeMonth(-1)
        runCurrent()
        assertEquals(listOf(initialMonth, initialMonth.minusMonths(1)), repository.requestedMonths)
        assertEquals(1, repository.movements.active)
        assertEquals(1, repository.movements.closed)

        model.setActiveSection("estoque")
        runCurrent()
        assertEquals(0, repository.movements.active)
        assertEquals(0, repository.expenses.active)
        assertEquals(0, repository.receipts.active)
        assertEquals(0, cash.reversals.active)

        model.setActiveSection("relatorios")
        runCurrent()
        assertEquals(1, repository.movements.active)
        display.cancel()
        runCurrent()
        assertEquals(0, repository.movements.active)
        assertEquals(0, repository.expenses.active)
        assertEquals(0, repository.receipts.active)
        assertEquals(0, cash.reversals.active)
    }

    @Test fun `operator without report permission cannot query by forcing report route`() = runTest(dispatcher) {
        val cash = TrackingCashRepository()
        val model = model(OperatorCapabilities.NONE, cash)
        backgroundScope.launch { model.reports.collect() }
        runCurrent()
        model.setActiveSection("relatorios")
        model.changeMonth(-1)
        runCurrent()

        assertEquals(0, repository.movementQueries)
        assertEquals(0, repository.expenseQueries)
        assertEquals(0, repository.receiptQueries)
        assertEquals(0, cash.reversals.started)
        assertFalse(model.reports.value.loading)
    }

    @Test fun `leaving credit with pending snapshot does not block after durable operation confirms`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.setActiveSection("fiado")
        repository.accounts.value.value = Feed(emptyList(), pendingWrites = true)
        repository.operations.value = listOf(OperationStatus("sale", SyncState.PENDING))
        runCurrent()
        assertTrue(model.pendingWrites.value)
        model.setActiveSection("caixa")
        runCurrent()
        repository.operations.value = listOf(OperationStatus("sale", SyncState.CONFIRMED))
        runCurrent()
        assertFalse(model.pendingWrites.value)
        var switched = false
        model.switchAccount { switched = true }
        runCurrent()
        assertTrue(switched)
    }

    @Test fun `expense write stays pending without keeping its report query alive`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        repository.pendingLocalWrites.value = true
        runCurrent()
        assertTrue(model.pendingWrites.value)
        var switched = false
        model.switchAccount { switched = true }
        runCurrent()
        assertFalse(switched)
        repository.pendingLocalWrites.value = false
        runCurrent()
        model.switchAccount { switched = true }
        runCurrent()
        assertTrue(switched)
        assertEquals(0, repository.movementQueries)
    }

    @Test fun `profile revocation stops reports without discarding current cart`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.reports.collect() }
        runCurrent()
        model.add(product)
        model.setActiveSection("relatorios")
        runCurrent()
        assertEquals(1, repository.movements.active)
        model.updateProfile(OperatorCapabilities.NONE, "Novo nome")
        runCurrent()
        assertEquals(0, repository.movements.active)
        assertEquals(1, model.cart.value.size)
        assertEquals("Novo nome", model.operatorName)
        assertFalse(model.capabilities.canViewReports)
    }

    @Test fun `credit listeners open on demand and disconnect when returning to cashier`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.setActiveSection("fiado")
        runCurrent()
        assertEquals(1, repository.accounts.active)
        assertEquals(1, repository.receipts.active)
        assertFalse(model.creditLoading.value)
        assertEquals(0, repository.movementQueries)

        model.setActiveSection("caixa")
        runCurrent()
        assertEquals(0, repository.accounts.active)
        assertEquals(0, repository.receipts.active)
        assertEquals(0, repository.legacyCreditQueries)

        model.setActiveSection("fiado")
        runCurrent()
        assertEquals(1, repository.accounts.active)
        assertEquals(1, repository.receipts.active)
        assertEquals(2, repository.accounts.started)
    }

    @Test fun `reopening credit retries queries after temporary listener failure`() = runTest(dispatcher) {
        repository.failAccounts = true
        repository.failReceipts = true
        val model = model()
        runCurrent()
        model.setActiveSection("fiado")
        runCurrent()
        assertNotNull(model.creditError.value)

        model.setActiveSection("caixa")
        runCurrent()
        repository.failAccounts = false
        repository.failReceipts = false
        model.setActiveSection("fiado")
        runCurrent()

        assertEquals(1, repository.accounts.active)
        assertEquals(1, repository.receipts.active)
        assertNull(model.creditError.value)
        assertFalse(model.creditLoading.value)
    }

    @Test fun `checkout requires an open session belonging to this operator and this phone`() = runTest(dispatcher) {
        val cash = TrackingCashRepository()
        val model = model(cash = cash)
        runCurrent()
        model.add(product)

        val validSession = CashSession("00000000-0000-4000-8000-000000000003", cash.deviceId, operatorId, "Operador",
            Instant.parse("2026-09-10T12:00:00Z"), CashSessionStatus.OPEN, 0, 0)
        val invalidSessions = listOf(emptyList(), listOf(validSession.copy(status = CashSessionStatus.CLOSED)),
            listOf(validSession.copy(operatorId = "another-operator")), listOf(validSession.copy(deviceId = "another-phone")))
        invalidSessions.forEach { sessions ->
            cash.sessions.value = Feed(sessions)
            runCurrent()
            model.checkout()
            runCurrent()
            assertTrue(repository.submitted.isEmpty())
            assertTrue(model.checkoutError.value.orEmpty().contains("Abra o caixa"))
            assertFalse(model.working.value)
            assertEquals(1, model.cart.value.size)
        }

        cash.sessions.value = Feed(listOf(validSession))
        runCurrent()
        model.checkout()
        runCurrent()
        assertEquals(validSession.id, repository.submitted.single().cashSessionId)
        assertEquals(cash.deviceId, repository.submitted.single().deviceId)
        assertEquals(operatorId, repository.submitted.single().operatorId)
        assertFalse(model.working.value)
        assertTrue(model.cart.value.isEmpty())
    }

    @Test fun `operator cannot override customer credit limit but can still accept cash`() = runTest(dispatcher) {
        val model = model(OperatorCapabilities.NONE)
        runCurrent()
        model.add(product)
        model.selectCustomer(customer)
        model.addPayment(PaymentMethod.FIADO, "10,00")
        model.setCreditOverrideReason("Autorização indevida")
        model.checkoutDraft()
        runCurrent()

        assertTrue(repository.submitted.isEmpty())
        assertTrue(model.checkoutError.value.orEmpty().contains("limite"))
        assertEquals("", model.checkoutDraft.value.creditOverrideReason)
        assertFalse(model.working.value)

        model.removePayment(PaymentMethod.FIADO)
        model.addPayment(PaymentMethod.CASH, "10,00")
        model.checkoutDraft()
        runCurrent()
        assertEquals(0L, repository.submitted.single().receivableCents)
        assertTrue(model.cart.value.isEmpty())
    }

    @Test fun `admin credit override requires a reason and stores trimmed reason in original sale`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        model.add(product)
        model.selectCustomer(customer)
        model.addPayment(PaymentMethod.FIADO, "10,00")
        model.checkoutDraft()
        runCurrent()
        assertTrue(repository.submitted.isEmpty())

        model.setCreditOverrideReason("  sim  ")
        model.checkoutDraft()
        runCurrent()
        assertTrue(repository.submitted.isEmpty())

        model.setCreditOverrideReason("  Cliente autorizado pelo responsável  ")
        model.checkoutDraft()
        runCurrent()
        assertEquals(1000L, repository.submitted.single().receivableCents)
        assertEquals("Cliente autorizado pelo responsável", repository.submitted.single().creditOverrideReason)
        assertTrue(repository.submitted.single().dueEpochDay > 0)
        assertFalse(model.working.value)
    }

    @Test fun `many sales finish without a snackbar consumer and never keep cashier locked`() = runTest(dispatcher) {
        val model = model()
        runCurrent()
        // Exceeds Channel.BUFFERED capacity; an awaited event send would block subsequent sales.
        repeat(160) { index ->
            model.add(product)
            model.checkout()
            runCurrent()
            assertFalse("Checkout remained locked on sale ${index + 1}", model.working.value)
            assertTrue(model.cart.value.isEmpty())
        }
        assertEquals(160, repository.submitted.size)
        assertEquals(160, repository.submitted.map(Movement::id).distinct().size)
        assertEquals(0, repository.movementQueries)
        assertEquals(0, repository.receiptQueries)
    }

    private class TrackedFeed<T>(initial: T) {
        val value = MutableStateFlow(Feed(initial))
        var active = 0
        var started = 0
        var closed = 0
        fun flow(): Flow<Feed<T>> = callbackFlow {
            active++
            started++
            val copy = launch { value.collect { trySend(it) } }
            awaitClose {
                copy.cancel()
                active--
                closed++
            }
        }
    }

    private class TrackingRepository(product: Product, customer: Customer) : PosRepository {
        val products = TrackedFeed(listOf(product))
        val customers = TrackedFeed(listOf(customer))
        val movements = TrackedFeed(emptyList<Movement>())
        val expenses = TrackedFeed(emptyList<Expense>())
        val receipts = TrackedFeed(emptyList<DebtPayment>())
        val accounts = TrackedFeed(emptyList<ReceivableAccount>())
        override val operations = MutableStateFlow(emptyList<OperationStatus>())
        override val pendingLocalWrites = MutableStateFlow(false)
        var movementQueries = 0
        var expenseQueries = 0
        var receiptQueries = 0
        var accountQueries = 0
        var legacyCreditQueries = 0
        var failAccounts = false
        var failReceipts = false
        val requestedMonths = mutableListOf<YearMonth>()
        val submitted = mutableListOf<Movement>()
        override fun observeProducts() = products.flow()
        override fun observeCustomers() = customers.flow()
        override fun observeMovements(month: YearMonth, zone: ZoneId): Flow<Feed<List<Movement>>> {
            movementQueries++
            requestedMonths += month
            return movements.flow()
        }
        override fun observeExpenses(month: YearMonth, zone: ZoneId): Flow<Feed<List<Expense>>> {
            expenseQueries++
            return expenses.flow()
        }
        override fun observeDebtPayments(month: YearMonth, zone: ZoneId): Flow<Feed<List<DebtPayment>>> {
            receiptQueries++
            return if (failReceipts) flow { error("Falha temporária nos recebimentos") } else receipts.flow()
        }
        override fun observeReceivables(): Flow<Feed<List<ReceivableAccount>>> {
            accountQueries++
            return if (failAccounts) flow { error("Falha temporária nas contas") } else accounts.flow()
        }
        override fun observeCreditSales(): Flow<Feed<List<Movement>>> {
            legacyCreditQueries++
            return movements.flow()
        }
        override fun observeDebtPayments(): Flow<Feed<List<DebtPayment>>> {
            legacyCreditQueries++
            return receipts.flow()
        }
        override suspend fun submit(movement: Movement) { submitted += movement }
        override suspend fun saveProduct(product: Product) = Unit
    }

    private class TrackingCashRepository : CashControlRepository {
        override val deviceId = "00000000-0000-4000-8000-000000000004"
        override val pendingOperations = MutableStateFlow(emptyList<PendingCashOperation>())
        val sessions = MutableStateFlow(Feed(emptyList<CashSession>()))
        val reversals = TrackedFeed(emptyList<SaleReversal>())
        override fun observeSessions(): Flow<Feed<List<CashSession>>> = sessions
        override fun observeEvents(): Flow<Feed<List<CashEvent>>> = flowOf(Feed(emptyList()))
        override fun observeReversals(month: YearMonth, zone: ZoneId) = reversals.flow()
        override suspend fun openSession(openingCents: Long) = Unit
        override suspend fun addMovement(sessionId: String, type: CashEventType, amountCents: Long, reason: String) = Unit
        override suspend fun closeSession(sessionId: String, countedCents: Long, expectedRevision: Long) = Unit
        override suspend fun previewReversal(saleId: String): ReversalPreview = error("Not used by these tests")
        override suspend fun reverseSale(preview: ReversalPreview, reason: String) = Unit
        override suspend fun retryPending(id: String) = Unit
    }
}
