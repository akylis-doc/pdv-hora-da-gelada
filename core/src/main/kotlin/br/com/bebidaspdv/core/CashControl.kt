package br.com.bebidaspdv.core

import java.time.Instant

enum class CashSessionStatus { OPEN, CLOSED }
enum class CashEventType { ABERTURA, SANGRIA, SUPRIMENTO, FECHAMENTO, ESTORNO }

data class CashSession(
    val id: String,
    val deviceId: String,
    val operatorId: String,
    val operatorName: String,
    val openedAt: Instant,
    val status: CashSessionStatus,
    val openingCents: Long,
    val expectedCents: Long,
    val cashSalesCents: Long = 0,
    val cashReceiptsCents: Long = 0,
    val supplyCents: Long = 0,
    val withdrawalCents: Long = 0,
    val refundCents: Long = 0,
    val revision: Long = 0,
    val closedAt: Instant? = null,
    val countedCents: Long? = null,
    val differenceCents: Long? = null,
)

data class CashEvent(
    val id: String, val sessionId: String, val type: CashEventType,
    val occurredAt: Instant, val amountCents: Long, val reason: String,
    val operatorId: String, val operatorName: String,
)

/** An immutable cancellation record. The original receipt and its payments remain intact. */
data class SaleReversal(
    val id: String, val saleId: String, val sessionId: String,
    val occurredAt: Instant, val reason: String, val operatorId: String, val operatorName: String,
    val refundByMethod: Map<PaymentMethod, Long>,
    val settledCents: Long, val cancelledReceivableCents: Long,
    val originalTotalCents: Long, val originalProfitCents: Long,
)

data class ReversalPreview(
    val sale: Movement,
    val settledCents: Long,
    val refundByMethod: Map<PaymentMethod, Long>,
    val cancelledReceivableCents: Long,
) {
    val cashRefundCents: Long get() = refundByMethod[PaymentMethod.CASH] ?: 0L
    val totalRefundCents: Long get() = refundByMethod.values.fold(0L, Math::addExact)
}

data class PendingCashOperation(val id: String, val description: String, val occurredAt: Instant)

data class CashMethodTotals(val salesCents: Long = 0, val receiptsCents: Long = 0, val refundsCents: Long = 0) {
    val netCents: Long get() = Math.subtractExact(Math.addExact(salesCents, receiptsCents), refundsCents)
}

/** Received values by method. Fiado issued is displayed separately and is never income. */
data class CashPaymentSummary(
    val sessionId: String,
    val sessionRevision: Long,
    val methods: Map<PaymentMethod, CashMethodTotals>,
    val creditIssuedCents: Long,
) {
    val netCents: Long get() = methods.values.fold(0L) { sum, method -> Math.addExact(sum, method.netCents) }
}

/** Pure calculations shared by previews and persistence. Values are always integer cents. */
object CashAccounting {
    fun saleCash(payments: List<Payment>, changeCents: Long): Long {
        require(payments.all { it.amountCents >= 0 } && changeCents >= 0)
        val cash = payments.filter { it.method == PaymentMethod.CASH }.fold(0L) { sum, p -> Math.addExact(sum, p.amountCents) }
        require(changeCents <= cash) { "Troco maior que o dinheiro recebido." }
        return cash - changeCents
    }

    fun expected(opening: Long, sales: Long, receipts: Long, supplies: Long, withdrawals: Long, refunds: Long): Long {
        require(listOf(opening, sales, receipts, supplies, withdrawals, refunds).all { it >= 0 })
        return Math.subtractExact(Math.subtractExact(listOf(opening, sales, receipts, supplies).fold(0L, Math::addExact), withdrawals), refunds)
    }

    fun difference(counted: Long, expected: Long): Long {
        require(counted in 0..BusinessLimits.MAX_MONEY_CENTS)
        return Math.subtractExact(counted, expected)
    }

    fun reversalPreview(sale: Movement, settledByMethod: Map<PaymentMethod, Long>): ReversalPreview {
        require(sale.type == MovementType.SAIDA) { "Somente vendas podem ser estornadas." }
        require(settledByMethod.all { (method, cents) -> method != PaymentMethod.FIADO && cents >= 0 })
        val settled = settledByMethod.values.fold(0L, Math::addExact)
        require(settled in 0..sale.receivableCents) { "Recebimentos incompatíveis com a venda." }
        val refunds = PaymentMethod.entries.filter { it != PaymentMethod.FIADO }.associateWith { method ->
            val original = if (method == PaymentMethod.CASH) saleCash(sale.payments, sale.changeCents)
                else sale.payments.filter { it.method == method }.fold(0L) { sum, payment -> Math.addExact(sum, payment.amountCents) }
            Math.addExact(original, settledByMethod[method] ?: 0L)
        }
        require(Math.addExact(refunds.values.fold(0L, Math::addExact), sale.receivableCents - settled) == sale.totalCents) {
            "A venda não possui pagamentos completos para um estorno seguro."
        }
        return ReversalPreview(sale, settled, refunds, sale.receivableCents - settled)
    }
}
