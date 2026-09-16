package br.com.bebidaspdv.core

import java.util.UUID
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows

class PaymentAndPackagesTest {
    private inline fun <reified T: Throwable> assertFailsWith(noinline block: () -> Unit) = assertThrows(T::class.java, block)
    private val calculator = CalculatePayment()
    private val product = Product(UUID.randomUUID().toString(), "Cerveja", "", 30, 300, 500,
        imageKey = "cerveja", packageSize = 6, packageSaleCents = 2700)

    @Test fun `cash and pix cover sale without change`() {
        val result = calculator(7000, listOf(Payment(PaymentMethod.CASH, 5000), Payment(PaymentMethod.PIX, 2000)))
        assertEquals(PaymentSummary(7000, 0, 0, 0), result)
        result.requireComplete()
    }
    @Test fun `incomplete payment remains visible and cannot finalize`() {
        val result = calculator(7000, listOf(Payment(PaymentMethod.PIX, 2000)))
        assertEquals(5000L, result.remainingCents)
        assertFailsWith<IllegalArgumentException> { result.requireComplete() }
    }
    @Test fun `cash change never comes from electronic overpayment`() {
        assertEquals(1000L, calculator(7000, listOf(Payment(PaymentMethod.CASH, 6000), Payment(PaymentMethod.PIX, 2000))).changeCents)
        assertFailsWith<IllegalArgumentException> { calculator(7000, listOf(Payment(PaymentMethod.PIX, 8000))) }
    }
    @Test fun `credit requires registered customer and cannot create cash change`() {
        val payments = listOf(Payment(PaymentMethod.CASH, 5000), Payment(PaymentMethod.FIADO, 2000))
        val result = calculator(7000, payments)
        assertEquals(2000L, result.fiadoCents)
        assertFailsWith<IllegalArgumentException> { result.requireComplete() }
        result.requireComplete(Customer(UUID.randomUUID().toString(), "Maria"))
        assertFailsWith<IllegalArgumentException> { calculator(7000, listOf(Payment(PaymentMethod.CASH, 8000), Payment(PaymentMethod.FIADO, 100))) }
    }
    @Test fun `negative excessive and duplicate method inputs are refused`() {
        assertFailsWith<IllegalArgumentException> { calculator(100, listOf(Payment(PaymentMethod.CASH, -1))) }
        assertFailsWith<IllegalArgumentException> { calculator(100, listOf(Payment(PaymentMethod.CASH, 100), Payment(PaymentMethod.CASH, 100))) }
        assertFailsWith<IllegalArgumentException> { calculator(100, listOf(Payment(PaymentMethod.CASH, Long.MAX_VALUE))) }
    }
    @Test fun `boxes share base inventory and freeze full package cost`() {
        val sale = CreateMovement()(listOf(CartLine(product, 2, 6)), MovementType.SAIDA)
        assertEquals(12, sale.items.single().stockQuantity)
        assertEquals(1800L, sale.items.single().historicalCostCents)
        assertEquals(5400L, sale.totalCents)
        assertEquals(1800L, sale.profitCents)
        assertEquals("cerveja", sale.items.single().imageKeySnapshot)
        val month = YearMonth.from(sale.occurredAt.atZone(ZoneId.of("America/Sao_Paulo")))
        assertEquals(12, CalculateMonthlyReport()(listOf(sale), month, ZoneId.of("America/Sao_Paulo")).unitsSold)
    }
    @Test fun `missing package price falls back to unit price times count`() {
        val sale = CreateMovement()(listOf(CartLine(product.copy(packageSaleCents = 0), 2, 6)), MovementType.SAIDA)
        assertEquals(6000L, sale.totalCents)
    }
    @Test fun `unavailable inventory archived product and invalid package cannot sell`() {
        assertFailsWith<IllegalArgumentException> { CreateMovement()(listOf(CartLine(product, 6, 6)), MovementType.SAIDA) }
        assertFailsWith<IllegalArgumentException> { CreateMovement()(listOf(CartLine(product.copy(active = false), 1)), MovementType.SAIDA) }
        assertFailsWith<IllegalArgumentException> { CreateMovement()(listOf(CartLine(product, 1, 2)), MovementType.SAIDA) }
    }
    @Test fun `credit sale freezes customer operator and outstanding amount`() {
        val customer = Customer(UUID.randomUUID().toString(), "Maria")
        val sale = CreateMovement()(listOf(CartLine(product, 2)), MovementType.SAIDA,
            listOf(Payment(PaymentMethod.FIADO, 1000)), customer, "cashier", "Caixa")
        assertEquals(customer.id, sale.customerId)
        assertEquals("Maria", sale.customerName)
        assertEquals("cashier", sale.operatorId)
        assertEquals(1000L, sale.receivableCents)
    }
}

