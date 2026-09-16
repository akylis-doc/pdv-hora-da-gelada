package br.com.bebidaspdv.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class CashAccountingTest {
    private fun sale(payments: List<Payment>, total: Long, change: Long = 0, receivable: Long = 0) = Movement(
        "f6b6b2b6-3daa-4c83-872d-cc7a57da82a8", Instant.EPOCH, MovementType.SAIDA, emptyList(), total, 0,
        payments = payments, changeCents = change, receivableCents = receivable,
    )

    @Test fun `mixed payments count only cash after change in drawer`() {
        assertEquals(700L, CashAccounting.saleCash(listOf(Payment(PaymentMethod.CASH, 1000), Payment(PaymentMethod.PIX, 500)), 300))
        assertEquals(2400L, CashAccounting.expected(1000, 700, 1000, 300, 400, 200))
    }

    @Test fun `closing records positive and negative differences`() {
        assertEquals(-50L, CashAccounting.difference(950, 1000))
        assertEquals(50L, CashAccounting.difference(1050, 1000))
    }

    @Test fun `reversal refunds original money and settled credit and cancels only remaining balance`() {
        val original = sale(listOf(Payment(PaymentMethod.CASH, 500), Payment(PaymentMethod.PIX, 300), Payment(PaymentMethod.FIADO, 1200)), 2000, receivable = 1200)
        val preview = CashAccounting.reversalPreview(original, mapOf(PaymentMethod.CASH to 400, PaymentMethod.CREDIT to 200))
        assertEquals(900L, preview.cashRefundCents)
        assertEquals(300L, preview.refundByMethod[PaymentMethod.PIX])
        assertEquals(200L, preview.refundByMethod[PaymentMethod.CREDIT])
        assertEquals(600L, preview.cancelledReceivableCents)
        assertEquals(2000L, preview.totalRefundCents + preview.cancelledReceivableCents)
    }

    @Test fun `fully paid credit sale refunds all settlements without cancelling debt twice`() {
        val preview = CashAccounting.reversalPreview(sale(listOf(Payment(PaymentMethod.FIADO, 1500)), 1500, receivable = 1500), mapOf(PaymentMethod.PIX to 1500))
        assertEquals(0L, preview.cancelledReceivableCents)
        assertEquals(1500L, preview.totalRefundCents)
        assertEquals(0L, preview.cashRefundCents)
    }

    @Test fun `cash change is not refunded twice`() {
        val preview = CashAccounting.reversalPreview(sale(listOf(Payment(PaymentMethod.CASH, 2000)), 1500, change = 500), emptyMap())
        assertEquals(1500L, preview.cashRefundCents)
    }

    @Test(expected = IllegalArgumentException::class) fun `refuse settlements beyond original receivable`() {
        CashAccounting.reversalPreview(sale(listOf(Payment(PaymentMethod.FIADO, 1000)), 1000, receivable = 1000), mapOf(PaymentMethod.CASH to 1001))
    }

    @Test(expected = IllegalArgumentException::class) fun `legacy receipt without known payments cannot invent refund`() {
        CashAccounting.reversalPreview(sale(emptyList(), 1000), emptyMap())
    }

    @Test(expected = IllegalArgumentException::class) fun `negative receipts are rejected`() {
        CashAccounting.reversalPreview(sale(listOf(Payment(PaymentMethod.FIADO, 1000)), 1000, receivable = 1000), mapOf(PaymentMethod.CASH to -1))
    }

    @Test(expected = ArithmeticException::class) fun `cash totals never silently overflow`() {
        CashAccounting.expected(Long.MAX_VALUE, 1, 0, 0, 0, 0)
    }
}
