package br.com.bebidaspdv.ui

import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.Feed
import br.com.bebidaspdv.core.Movement
import br.com.bebidaspdv.core.MovementType
import br.com.bebidaspdv.core.OperationStatus
import br.com.bebidaspdv.core.PosRepository
import br.com.bebidaspdv.core.Product
import br.com.bebidaspdv.core.SyncState
import br.com.bebidaspdv.core.logging.*
import br.com.bebidaspdv.diagnostics.RecordingLogger
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PosViewModelTest {
    private lateinit var dispatcher: TestDispatcher
    private lateinit var repository: FakePosRepository
    private val models = mutableListOf<PosViewModel>()
    private val product = Product(
        id = "00000000-0000-4000-8000-000000000001",
        name = "Refrigerante 2 L",
        barcode = "7891234567890",
        stock = 20,
        costCents = 650,
        saleCents = 1000,
    )

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        repository = FakePosRepository(product)
    }

    @After
    fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun createModel(): PosViewModel = PosViewModel(repository).also(models::add)

    @Test
    fun `each cart action has its own trace and revision without mutating prior lists`() = runTest(dispatcher) {
        val logger = RecordingLogger()
        val model = PosViewModel(repository, logger = logger).also(models::add)
        runCurrent()
        model.addFromUi(product, 1, 0)
        val original = model.cart.value
        model.addFromUi(product, 1, 0) // Same captured UI value, before a new frame.
        model.setQuantityFromUi(product, "5", 1, 2)
        model.removeFromUi(product, 1) // Uses current 5, not the stale UI value 1.
        assertEquals(1, original.single().quantity)
        assertEquals(4, model.cart.value.single().quantity)
        assertEquals(4L, model.cartRender.value?.revision)
        assertEquals(4000L, model.cart.value.sumOf { it.quantity * it.saleUnitCents })
        assertEquals(20, repository.products.value.value.single().stock)
        val emissions = logger.records.filter { it.event == "STATEFLOW_EMITTED" }
        assertEquals(listOf("1", "2", "3", "4"), emissions.map { it.metadata["revision"] })
        assertEquals(4, emissions.map { it.traceId }.distinct().size)
        assertEquals(1, emissions.map { it.metadata["parent_trace_id"] }.distinct().size)
        assertTrue(repository.submissions.isEmpty())
    }

    @Test
    fun `unchanged and rejected quantities do not invent state emissions or revisions`() = runTest(dispatcher) {
        val logger = RecordingLogger()
        val model = PosViewModel(repository, logger = logger).also(models::add)
        runCurrent()
        model.setQuantityFromUi(product, "2", 1, 0)
        model.setQuantityFromUi(product, "2", 1, 2)
        model.setQuantityFromUi(product, "21", 1, 2)
        assertEquals(1L, model.cartRender.value?.revision)
        assertEquals(1, logger.records.count { it.event == "STATEFLOW_EMITTED" })
        assertEquals("false", logger.records.last { it.event == "SET_PRODUCT_QUANTITY_COMPLETED" }.metadata["state_changed"])
        assertEquals("INSUFFICIENT_STOCK", logger.records.last().errorCode)
    }

    @Test
    fun `render receipt must match visible quantities and is acknowledged only once`() = runTest(dispatcher) {
        val logger = RecordingLogger()
        val model = PosViewModel(repository, logger = logger).also(models::add)
        runCurrent()
        model.addFromUi(product, 1, 0)
        val receipt = requireNotNull(model.cartRender.value)
        model.cartStateReceived(receipt, emptyList(), "RESUMED")
        model.cartRendered(receipt, emptyList())
        assertTrue(logger.records.none { it.event == "CART_RENDERED" || it.event == "CASHIER_SCREEN_STATE_RECEIVED" })
        model.cartStateReceived(receipt, model.cart.value, "RESUMED")
        model.cartRenderStarted(receipt, model.cart.value)
        model.cartRendered(receipt, model.cart.value)
        model.cartRendered(receipt, model.cart.value)
        val draw = logger.records.single { it.event == "CART_RENDERED" }
        assertEquals("1", draw.metadata["ui_quantity_after"])
        assertEquals("1", draw.metadata["revision"])
        assertEquals(receipt.trace.traceId, draw.traceId)
    }

    @Test
    fun `rapid package additions preserve unit factor and respect shared stock`() = runTest(dispatcher) {
        val boxed = product.copy(packageSize = 6, packageSaleCents = 5000)
        repository.products.value = Feed(listOf(boxed))
        val model = createModel()
        runCurrent()
        repeat(4) { model.addFromUi(boxed, 6, 0) }
        assertEquals(3, model.cart.value.single().quantity)
        assertEquals(18, model.cart.value.single().stockQuantity)
        assertEquals(15000L, model.cart.value.sumOf { it.quantity * it.saleUnitCents })
        model.setQuantityFromUi(boxed, "0", 6, 3)
        model.addFromUi(boxed, 1, 0)
        assertEquals(1, model.cart.value.single().stockQuantity)
        assertEquals(20, repository.products.value.value.single().stock)
    }

    @Test
    fun `UI cart payment and offline checkout preserve trace through repository and release before remote ack`() = runTest(dispatcher) {
        val logger = RecordingLogger()
        val model = PosViewModel(repository, logger = logger).also(models::add)
        runCurrent()
        model.setQuantityFromUi(product, "2")
        val rendered = requireNotNull(model.cartRender.value)
        model.cartStateReceived(rendered, model.cart.value, "RESUMED")
        model.cartRendered(rendered, model.cart.value)
        model.cartRendered(rendered, model.cart.value)
        model.addPayment(br.com.bebidaspdv.core.PaymentMethod.CASH, "20,00")
        model.checkoutFromUi()
        runCurrent()
        val trace = requireNotNull(repository.submissionTrace)
        assertEquals(repository.submissions.single().id, trace.operationId)
        assertEquals(rendered.metadata["parent_trace_id"], trace.traceId)
        assertNotEquals(rendered.trace.traceId, trace.traceId)
        assertTrue(logger.records.none { it.event == "SALE_COMPLETED" })
        repository.acceptLocally()
        runCurrent()
        val events = logger.records.filter { it.traceId == rendered.trace.traceId }.map { it.event }
        assertTrue(events.containsAll(listOf("QUANTITY_INPUT_CHANGED", "SET_PRODUCT_QUANTITY_REQUEST",
            "PRODUCT_LOOKUP_COMPLETED", "PRODUCT_ADDED", "STATEFLOW_EMITTED", "CASHIER_SCREEN_STATE_RECEIVED", "CART_RENDERED")))
        assertTrue(logger.records.filter { it.traceId == trace.traceId }.map { it.event }
            .containsAll(listOf("SALE_STARTED", "PAYMENT_REGISTERED", "SALE_FINALIZE_CLICK", "SALE_COMPLETED")))
        assertEquals(1, events.count { it == "CART_RENDERED" })
        assertEquals(product.name, logger.records.single { it.event == "PRODUCT_LOOKUP_COMPLETED" }.metadata["product_name"])
        // Only repository callbacks describe sync; UI completion cannot overwrite a fast server ack.
        assertEquals(LogSyncStatus.NOT_APPLICABLE, logger.records.single { it.event == "SALE_COMPLETED" }.syncStatus)
        assertFalse(model.working.value)
        model.setQuantityFromUi(product, "1")
        assertNotEquals(trace.traceId, model.cartRender.value?.trace?.traceId)
    }

    @Test
    fun `rejected quantity records warning without applying cart change`() = runTest(dispatcher) {
        val logger = RecordingLogger()
        val model = PosViewModel(repository, logger = logger).also(models::add)
        runCurrent()
        model.setQuantityFromUi(product, "9999")
        assertTrue(model.cart.value.isEmpty())
        val rejected = logger.records.single { it.event == "CART_CHANGE_REJECTED" }
        assertEquals(LogLevel.WARNING, rejected.level)
        assertEquals("INSUFFICIENT_STOCK", rejected.errorCode)
        assertEquals(product.id, rejected.entityId)
    }

    @Test
    fun `sale failure stays on same trace and retains cart for operator`() = runTest(dispatcher) {
        val logger = RecordingLogger()
        val model = PosViewModel(repository, logger = logger).also(models::add)
        runCurrent()
        model.add(product)
        model.checkout()
        runCurrent()
        repository.rejectDefinitively("Rejected")
        runCurrent()
        val failed = logger.records.single { it.event == "SALE_FAILED" }
        assertEquals(repository.submissionTrace?.traceId, failed.traceId)
        assertEquals(repository.submissions.single().id, failed.operationId)
        assertEquals(IllegalStateException::class.java.name, failed.exceptionType)
        assertEquals(1, model.cart.value.single().quantity)
        assertFalse(model.working.value)
    }

    @Test
    fun `repeated checkout taps while local submission is pending submit only once`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        model.add(product)

        model.checkout()
        model.checkout()
        runCurrent()
        model.checkout()
        model.add(product)
        model.remove(product.id)
        model.clearCart()
        runCurrent()

        assertEquals(1, repository.submissions.size)
        assertEquals(1, repository.submissions.single().items.single().quantity)
        assertEquals(1, model.cart.value.single().quantity)
        assertTrue(model.working.value)

        repository.acceptLocally()
        runCurrent()
        assertFalse(model.working.value)
    }

    @Test
    fun `checkout freezes latest observed cost despite later product updates`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        model.add(product)
        model.add(product)
        repository.products.value = Feed(listOf(product.copy(costCents = 700, saleCents = 1100)))
        runCurrent()

        model.checkout()
        runCurrent()
        val submitted = repository.submissions.single()
        repository.products.value = Feed(listOf(product.copy(costCents = 900, saleCents = 1500)))
        runCurrent()

        assertEquals(700L, submitted.items.single().historicalCostCents)
        assertEquals(1100L, submitted.items.single().saleUnitCents)
        assertEquals(2200L, submitted.totalCents)
        assertEquals(800L, submitted.profitCents)
        assertEquals(900L, model.products.value.value.single().costCents)
        repository.acceptLocally()
        runCurrent()
        assertEquals(700L, submitted.items.single().historicalCostCents)
    }

    @Test
    fun `local acceptance clears cart and releases checkout before server confirmation`() = runTest(dispatcher) {
        val model = createModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { model.events.toList(messages) }
        runCurrent()
        model.add(product)
        model.checkout()
        runCurrent()

        assertTrue(model.working.value)
        assertTrue(model.cart.value.isNotEmpty())
        repository.acceptLocally()
        runCurrent()

        assertTrue(model.cart.value.isEmpty())
        assertFalse(model.working.value)
        assertEquals(SyncState.PENDING, model.operations.value.single().state)
        assertTrue(messages.any { it.contains("Venda registrada") })
        model.add(product)
        assertEquals(1, model.cart.value.single().quantity)
    }

    @Test
    fun `definitive submission failure preserves cart and releases UI`() = runTest(dispatcher) {
        val model = createModel()
        val messages = mutableListOf<String>()
        backgroundScope.launch { model.events.toList(messages) }
        runCurrent()
        model.add(product)
        model.add(product)
        model.checkout()
        runCurrent()

        repository.rejectDefinitively("Permissão recusada pelo servidor.")
        runCurrent()

        assertFalse(model.working.value)
        assertEquals(2, model.cart.value.single().quantity)
        assertEquals(1, repository.submissions.size)
        assertEquals(SyncState.REJECTED, model.operations.value.single().state)
        assertTrue(messages.any { it.contains("Permissão recusada") })
        model.remove(product.id)
        assertEquals(1, model.cart.value.single().quantity)
    }

    @Test
    fun `stock entry uses procurement cost and waits only for local acceptance`() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        var saved = false

        model.receiveStock(product, "12") { saved = true }
        runCurrent()
        val submitted = repository.submissions.single()

        assertEquals(MovementType.ENTRADA, submitted.type)
        assertEquals(12, submitted.items.single().quantity)
        assertEquals(7800L, submitted.totalCents)
        assertEquals(0L, submitted.profitCents)
        assertFalse(saved)
        assertTrue(model.working.value)

        repository.acceptLocally()
        runCurrent()
        assertTrue(saved)
        assertFalse(model.working.value)
        assertEquals(SyncState.PENDING, model.operations.value.single().state)
        assertTrue(model.cart.value.isEmpty())
    }

    private class FakePosRepository(product: Product) : PosRepository {
        val products = MutableStateFlow(Feed(listOf(product)))
        private val movements = MutableStateFlow(Feed(emptyList<Movement>()))
        override val operations = MutableStateFlow<List<OperationStatus>>(emptyList())
        val submissions = mutableListOf<Movement>()
        var submissionTrace: TraceContext? = null
        private val localAcceptance = CompletableDeferred<Unit>()

        override fun observeProducts(): Flow<Feed<List<Product>>> = products

        override fun observeMovements(month: YearMonth, zone: ZoneId): Flow<Feed<List<Movement>>> = movements

        override suspend fun submit(movement: Movement) {
            submissionTrace = currentLogTrace()
            submissions += movement
            operations.value = operations.value + OperationStatus(movement.id, SyncState.PENDING)
            localAcceptance.await()
        }

        override suspend fun saveProduct(product: Product) {
            products.value = Feed(products.value.value.filterNot { it.id == product.id } + product)
        }

        fun acceptLocally() {
            localAcceptance.complete(Unit)
        }

        fun rejectDefinitively(message: String) {
            operations.value = operations.value.map { it.copy(state = SyncState.REJECTED, message = message) }
            localAcceptance.completeExceptionally(IllegalStateException(message))
        }
    }
}
