package br.com.bebidaspdv.ui

import br.com.bebidaspdv.core.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.YearMonth

class FinanceInsightsTest {
    private val month = YearMonth.of(2026, 9)
    private val date = Instant.parse("2026-09-07T15:00:00Z")
    private fun sale(id: String = "sale", receivable: Long = 0, payments: List<Payment> = emptyList(), occurredAt: Instant = date) =
        Movement(id, occurredAt, MovementType.SAIDA, listOf(SaleItem("p", "Bebida", 2, 3600, 3000, 6)),
            7200, 1200, operatorId = "operator", operatorName = "Caixa 1", payments = payments, receivableCents = receivable)

    @Test fun `rank uses stock units historical cost and includes unsold catalogue products`() {
        val products = listOf(Product("p", "Bebida", "", 100, 9999, 9999), Product("unsold", "Água", "", 5, 200, 400))
        val ranking = productPerformance(products, listOf(sale()))
        val sold = ranking.single { it.productId == "p" }
        assertEquals(12L, sold.units)
        assertEquals(1200L, sold.profitCents)
        assertEquals(7200L, sold.revenueCents)
        assertEquals(0L, ranking.single { it.productId == "unsold" }.units)
    }

    @Test fun `later credit receipt is not counted again as sale revenue`() {
        val oldSale = sale(receivable = 7200, payments = listOf(Payment(PaymentMethod.FIADO, 7200)), occurredAt = Instant.parse("2026-08-15T15:00:00Z"))
        val receipt = DebtPayment("receipt", oldSale.id, "customer", date, 3000, PaymentMethod.PIX)
        val result = monthReceipts(listOf(oldSale), listOf(receipt), month)
        assertEquals(0L, result.immediateCents)
        assertEquals(3000L, result.creditPaymentsCents)
        assertEquals(3000L, result.totalCents)
        assertTrue(salesInMonth(listOf(oldSale), month).isEmpty())
    }

    @Test fun `received cash excludes change and principal of fiado`() {
        val paid = sale(payments = listOf(Payment(PaymentMethod.CASH, 10000))).copy(changeCents = 2800)
        val credit = sale("fiado", 2200, listOf(Payment(PaymentMethod.CASH, 5000), Payment(PaymentMethod.FIADO, 2200)))
        val result = monthReceipts(listOf(paid, credit), emptyList(), month)
        assertEquals(12200L, result.immediateCents)
        assertEquals(0, result.salesWithoutPaymentDetails)
    }

    @Test fun `legacy payment information remains unknown and is visibly counted`() {
        val result = monthReceipts(listOf(sale()), emptyList(), month)
        assertEquals(1, result.salesWithoutPaymentDetails)
        assertEquals(0L, result.immediateCents)
    }

    @Test fun `operator summary groups UID and stock entries are excluded`() {
        val a = sale()
        val b = sale("second").copy(operatorName = "Caixa renomeado", occurredAt = date.plusSeconds(60))
        val entry = sale("entry").copy(type = MovementType.ENTRADA)
        val result = operatorPerformance(listOf(a, b, entry)).single()
        assertEquals(2, result.salesCount)
        assertEquals(14400L, result.revenueCents)
        assertEquals("Caixa renomeado", result.name)
    }

    @Test fun `debt balance only subtracts receipts from matching sale`() {
        val a = sale(receivable = 7200)
        val receipts = listOf(DebtPayment("a", a.id, "c", date, 2000, PaymentMethod.CASH), DebtPayment("b", "other", "c", date, 5000, PaymentMethod.PIX))
        assertEquals(5200L, remainingDebt(a, receipts))
    }
}
