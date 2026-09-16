package br.com.bebidaspdv.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ReceiptAccountingTest {
    private val sale = Movement("s", Instant.EPOCH, MovementType.SAIDA,
        listOf(SaleItem("p", "Produto", 1, 7000, 3000)), 7000, 4000, receivableCents = 7000)
    @Test fun `fiado only contributes when collected and installments allocate cost exactly`() {
        assertEquals(0L, CalculateReceiptReport()(listOf(sale), emptyList(), emptyList()).revenueCents)
        val first = DebtPayment("r1", "s", "c", Instant.EPOCH, 2000, PaymentMethod.PIX,
            costRecognizedCents = ReceiptAccounting.receiptCost(sale, 0, 2000))
        val second = first.copy(id = "r2", amountCents = 5000, costRecognizedCents = ReceiptAccounting.receiptCost(sale, 2000, 5000))
        assertEquals(857L, first.costRecognizedCents)
        assertEquals(2143L, second.costRecognizedCents)
        val report = CalculateReceiptReport()(emptyList(), listOf(first, second), emptyList())
        assertEquals(7000L, report.revenueCents); assertEquals(3000L, report.costCents); assertEquals(4000L, report.profitCents)
    }
    @Test fun `reversal of partially collected sale cancels only realized cost and refunds`() {
        val reversal = SaleReversal("r", "s", "session", Instant.EPOCH, "Cancelamento", "u", "Operador",
            mapOf(PaymentMethod.CASH to 2000L), 2000, 5000, 7000, 4000)
        val report = CalculateReceiptReport()(emptyList(), emptyList(), listOf(reversal))
        assertEquals(-2000L, report.revenueCents); assertEquals(-857L, report.costCents)
    }
    @Test fun `box keeps exact purchase cost while unit rounds half up`() {
        val product = Product("p", "Produto", "", 12, PurchaseCost.unitCents(1000, 6), 300,
            packageSize = 6, purchaseByPackage = true, packageCostCents = 1000)
        assertEquals(167L, product.costCents)
        assertEquals(1000L, CartLine(product, 1, 6).costUnitCents)
        assertEquals(167L, CartLine(product, 1).costUnitCents)
        assertEquals(1000L, CreateMovement()(listOf(CartLine(product, 1, 6)), MovementType.ENTRADA).totalCents)
    }
    @Test(expected = IllegalArgumentException::class) fun `unmigrated receipt cannot silently yield incorrect profit`() {
        CalculateReceiptReport()(emptyList(), listOf(DebtPayment("r", "s", "c", Instant.EPOCH, 100, PaymentMethod.CASH)), emptyList())
    }
}
