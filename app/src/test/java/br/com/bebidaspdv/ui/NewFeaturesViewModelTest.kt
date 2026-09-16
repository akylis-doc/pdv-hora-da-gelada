package br.com.bebidaspdv.ui

import androidx.lifecycle.viewModelScope
import br.com.bebidaspdv.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class NewFeaturesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val product = Product("00000000-0000-4000-8000-000000000001", "Cerveja", "123", 120, 500, 700,
        packageSize = 6, packageSaleCents = 3600)
    private val customer = Customer("00000000-0000-4000-8000-000000000002", "Maria", "11999999999")
    private lateinit var repository: FakeRepository
    private lateinit var model: PosViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeRepository()
        model = PosViewModel(repository, operatorId = "operator-1", operatorName = "Caixa 1")
    }
    @After fun tearDown() { model.viewModelScope.cancel(); Dispatchers.resetMain() }

    @Test fun `keyboard quantity ten creates one cart row and split sale with seller and customer`() = runTest(dispatcher) {
        runCurrent(); model.setQuantity(product, "10")
        model.setCustomerName("João")
        model.addPayment(PaymentMethod.CASH, "50")
        model.addPayment(PaymentMethod.PIX, "20")
        model.checkoutDraft(); runCurrent()
        val sale = repository.sales.single()
        assertEquals(10, sale.items.single().quantity)
        assertEquals(7000L, sale.totalCents)
        assertEquals(0L, sale.changeCents)
        assertEquals("João", sale.customerName)
        assertEquals("operator-1", sale.operatorId)
        assertEquals(2, sale.payments.size)
        assertTrue(model.cart.value.isEmpty())
        assertTrue(model.checkoutDraft.value.payments.isEmpty())
    }

    @Test fun `box uses common unit stock and historical procurement cost`() = runTest(dispatcher) {
        runCurrent(); model.setQuantity(product, "2", 6)
        model.checkout(); runCurrent()
        val item = repository.sales.single().items.single()
        assertEquals(2, item.quantity)
        assertEquals(12, item.stockQuantity)
        assertEquals(3000L, item.historicalCostCents)
        assertEquals(3600L, item.saleUnitCents)
        assertEquals(1200L, repository.sales.single().profitCents)
    }

    @Test fun `fiado cannot finish without registered customer`() = runTest(dispatcher) {
        runCurrent(); model.setQuantity(product, "10")
        model.setCustomerName("Maria")
        model.addPayment(PaymentMethod.CASH, "50"); model.addPayment(PaymentMethod.FIADO, "20")
        model.checkoutDraft(); runCurrent()
        assertTrue(repository.sales.isEmpty())
        assertFalse(model.working.value)
        assertTrue(model.cart.value.isNotEmpty())
        assertNotNull(model.checkoutError.value)
        model.selectCustomer(customer); model.checkoutDraft(); runCurrent()
        assertEquals(customer.id, repository.sales.single().customerId)
        assertEquals(2000L, repository.sales.single().receivableCents)
    }

    @Test fun `cash change is persisted while overpaid pix blocks checkout`() = runTest(dispatcher) {
        runCurrent(); model.setQuantity(product, "10")
        model.addPayment(PaymentMethod.PIX, "100"); model.checkoutDraft(); runCurrent()
        assertTrue(repository.sales.isEmpty())
        model.removePayment(PaymentMethod.PIX)
        model.addPayment(PaymentMethod.CASH, "100"); model.checkoutDraft(); runCurrent()
        assertEquals(3000L, repository.sales.single().changeCents)
    }

    @Test fun `deleted product vanishes from catalogue but cannot silently complete an existing cart`() = runTest(dispatcher) {
        runCurrent(); model.add(product)
        repository.products.value = Feed(listOf(product.copy(active = false))); runCurrent()
        assertTrue(model.products.value.value.isEmpty())
        assertFalse(model.cart.value.single().product.active)
        model.checkout(); runCurrent()
        assertTrue(repository.sales.isEmpty())
        assertFalse(model.working.value)
    }

    @Test fun `product editor preserves photo and packaging when used through original API`() = runTest(dispatcher) {
        val photoProduct = product.copy(imageKey = "cerveja-lata", lowStockThreshold = 12)
        repository.products.value = Feed(listOf(photoProduct)); runCurrent()
        model.saveProduct(photoProduct, "Cerveja nova", "123", "5", "7") {}; runCurrent()
        assertEquals("cerveja-lata", repository.products.value.value.single().imageKey)
        assertEquals(6, repository.products.value.value.single().packageSize)
        assertEquals(3600L, repository.products.value.value.single().packageSaleCents)
    }

    @Test fun `account switch is blocked by cart or pending metadata and locks edits during final drain`() = runTest(dispatcher) {
        runCurrent(); var signedOut = false
        model.add(product); model.switchAccount { signedOut = true }; runCurrent(); assertFalse(signedOut)
        model.clearCart(); repository.products.value = Feed(listOf(product), pendingWrites = true); runCurrent()
        model.switchAccount { signedOut = true }; runCurrent(); assertEquals(0, repository.drains)
        repository.products.value = Feed(listOf(product)); runCurrent()
        model.switchAccount { signedOut = true }; runCurrent()
        assertTrue(model.working.value)
        model.add(product); assertTrue(model.cart.value.isEmpty())
        repository.drained.complete(Unit); runCurrent()
        assertTrue(signedOut)
    }

    @Test fun `account switch timeout keeps operator logged in`() = runTest(dispatcher) {
        runCurrent(); var signedOut = false
        model.switchAccount { signedOut = true }; runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertFalse(signedOut); assertFalse(model.working.value)
    }

    @Test fun `uncertain debt retries same UUID and definitive rejection allows corrected amount`() = runTest(dispatcher) {
        runCurrent()
        val sale = CreateMovement()(listOf(CartLine(product, 10)), MovementType.SAIDA,
            listOf(Payment(PaymentMethod.FIADO, 7000)), customer)
        repository.settleError = IllegalStateException("Resposta incerta")
        model.settleDebt(sale, "20", PaymentMethod.CASH) {}; runCurrent()
        model.settleDebt(sale, "20", PaymentMethod.CASH) {}; runCurrent()
        assertEquals(repository.receipts[0].id, repository.receipts[1].id)
        repository.settleError = DebtSettlementRejected("Saldo mudou")
        model.settleDebt(sale, "20", PaymentMethod.CASH) {}; runCurrent()
        repository.settleError = null
        model.settleDebt(sale, "10", PaymentMethod.CASH) {}; runCurrent()
        assertEquals(1000L, repository.receipts.last().amountCents)
        assertNotEquals(repository.receipts[0].id, repository.receipts.last().id)
    }

    @Test fun `expense records chosen local date and seller without creating revenue`() = runTest(dispatcher) {
        runCurrent()
        model.saveExpense("Conta de água", "AGUA", "150,45", LocalDate.of(2026, 1, 10)) {}; runCurrent()
        val expense = repository.expenses.single()
        assertEquals(15045L, expense.amountCents)
        assertEquals("operator-1", expense.operatorId)
        assertEquals(LocalDate.of(2026, 1, 10), expense.occurredAt.atZone(ZoneId.of("America/Sao_Paulo")).toLocalDate())
        assertTrue(repository.sales.isEmpty())
    }

    @Test fun `receipt recovered after restart blocks account switch until reconciled with same UUID`() = runTest(dispatcher) {
        val receipt = DebtPayment("00000000-0000-4000-8000-000000000003", "00000000-0000-4000-8000-000000000004",
            customer.id, Instant.parse("2026-09-07T15:00:00Z"), 2000, PaymentMethod.PIX, "operator-1", "Caixa 1")
        repository.pendingDebtPayments.value = listOf(receipt); runCurrent()
        var signedOut = false
        assertTrue(model.pendingWrites.value)
        model.switchAccount { signedOut = true }; runCurrent()
        assertFalse(signedOut); assertEquals(0, repository.drains)
        model.retryPendingDebt(receipt); runCurrent()
        assertEquals(receipt, repository.receipts.single())
        assertFalse(model.pendingWrites.value)
        repository.drained.complete(Unit)
        model.switchAccount { signedOut = true }; runCurrent()
        assertTrue(signedOut)
    }

    @Test fun `new payment with same amount after restart requires pending receipt reconciliation`() = runTest(dispatcher) {
        val sale = CreateMovement()(listOf(CartLine(product, 10)), MovementType.SAIDA,
            listOf(Payment(PaymentMethod.FIADO, 7000)), customer)
        val receipt = DebtPayment("00000000-0000-4000-8000-000000000003", sale.id,
            customer.id, Instant.parse("2026-09-07T15:00:00Z"), 2000, PaymentMethod.PIX, "operator-1", "Caixa 1")
        model.viewModelScope.cancel()
        repository.pendingDebtPayments.value = listOf(receipt)
        model = PosViewModel(repository, operatorId = "operator-1", operatorName = "Caixa 1")
        runCurrent()

        var saved = false
        model.settleDebt(sale, "20", PaymentMethod.PIX) { saved = true }; runCurrent()
        assertFalse(saved)
        assertTrue(repository.receipts.isEmpty())
        assertTrue(model.formError.value.orEmpty().contains("Conferir pendência"))

        model.retryPendingDebt(receipt); runCurrent()
        assertEquals(receipt, repository.receipts.single())
        assertTrue(model.pendingDebtPayments.value.isEmpty())

        model.settleDebt(sale, "20", PaymentMethod.PIX) { saved = true }; runCurrent()
        assertTrue(saved)
        assertEquals(2, repository.receipts.size)
        assertEquals(receipt.amountCents, repository.receipts.last().amountCents)
        assertNotEquals(receipt.id, repository.receipts.last().id)
    }

    private inner class FakeRepository : PosRepository {
        val products = MutableStateFlow(Feed(listOf(product)))
        override val operations = MutableStateFlow<List<OperationStatus>>(emptyList())
        override val pendingDebtPayments = MutableStateFlow<List<DebtPayment>>(emptyList())
        val sales = mutableListOf<Movement>()
        val expenses = mutableListOf<Expense>()
        val receipts = mutableListOf<DebtPayment>()
        var settleError: Exception? = null
        var drains = 0
        val drained = CompletableDeferred<Unit>()
        override fun observeProducts() = products
        override fun observeMovements(month: YearMonth, zone: ZoneId) = flowOf(Feed(emptyList<Movement>()))
        override fun observeCustomers() = flowOf(Feed(listOf(customer)))
        override suspend fun submit(movement: Movement) { sales += movement }
        override suspend fun saveProduct(product: Product) { products.value = Feed(listOf(product)) }
        override suspend fun saveExpense(expense: Expense) { expenses += expense }
        override suspend fun settleDebt(payment: DebtPayment) {
            receipts += payment; settleError?.let { throw it }
            pendingDebtPayments.value = pendingDebtPayments.value.filterNot { it.id == payment.id }
        }
        override suspend fun waitForPendingWrites() { drains++; drained.await() }
    }
}
