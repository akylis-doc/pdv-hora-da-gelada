package br.com.bebidaspdv.ui

import br.com.bebidaspdv.core.DebtPayment
import br.com.bebidaspdv.core.Movement
import br.com.bebidaspdv.core.MovementType
import br.com.bebidaspdv.core.PaymentMethod
import br.com.bebidaspdv.core.Product
import java.time.YearMonth
import java.time.ZoneId

internal val businessZone: ZoneId = ZoneId.of("America/Sao_Paulo")

internal data class ProductPerformance(
    val productId: String,
    val name: String,
    val units: Long = 0,
    val revenueCents: Long = 0,
    val profitCents: Long = 0,
)

internal data class OperatorPerformance(
    val operatorId: String,
    val name: String,
    val salesCount: Int,
    val revenueCents: Long,
    val profitCents: Long,
    val creditCents: Long,
)

internal data class MonthReceipts(
    val immediateCents: Long,
    val creditPaymentsCents: Long,
    val salesWithoutPaymentDetails: Int,
) {
    val totalCents: Long get() = immediateCents + creditPaymentsCents
}

internal fun salesInMonth(movements: List<Movement>, month: YearMonth): List<Movement> =
    movements.filter { it.type == MovementType.SAIDA && YearMonth.from(it.occurredAt.atZone(businessZone)) == month }

/** Rank in base stock units so a box of twelve can be compared to twelve individual units. */
internal fun productPerformance(products: List<Product>, sales: List<Movement>): List<ProductPerformance> {
    val rows = linkedMapOf<String, ProductPerformance>()
    products.filter { it.active }.forEach { rows[it.id] = ProductPerformance(it.id, it.name) }
    sales.filter { it.type == MovementType.SAIDA }.sortedBy { it.occurredAt }.forEach { sale ->
        sale.items.forEach { item ->
            val previous = rows[item.productId] ?: ProductPerformance(item.productId, item.nameSnapshot)
            rows[item.productId] = previous.copy(
                units = previous.units + item.quantity.toLong() * item.unitsPerPackage,
                revenueCents = previous.revenueCents + item.saleUnitCents * item.quantity,
                profitCents = previous.profitCents + (item.saleUnitCents - item.historicalCostCents) * item.quantity,
            )
        }
    }
    return rows.values.toList()
}

internal fun operatorPerformance(sales: List<Movement>): List<OperatorPerformance> =
    sales.filter { it.type == MovementType.SAIDA }
        .groupBy { it.operatorId.ifBlank { "legacy:${it.operatorName}" } }
        .map { (id, entries) ->
            OperatorPerformance(
                operatorId = id,
                name = entries.maxBy { it.occurredAt }.operatorLabel(),
                salesCount = entries.size,
                revenueCents = entries.sumOf { it.totalCents },
                profitCents = entries.sumOf { it.profitCents },
                creditCents = entries.sumOf { it.receivableCents },
            )
        }.sortedByDescending { it.revenueCents }

/** Credit principal is revenue at sale time. Later settlements are receipts only. */
internal fun monthReceipts(movements: List<Movement>, debtPayments: List<DebtPayment>, month: YearMonth): MonthReceipts {
    val sales = salesInMonth(movements, month)
    return MonthReceipts(
        immediateCents = sales.sumOf { sale ->
            (sale.payments.filter { it.method != PaymentMethod.FIADO }.sumOf { it.amountCents } - sale.changeCents).coerceAtLeast(0)
        },
        creditPaymentsCents = debtPayments.filter { YearMonth.from(it.occurredAt.atZone(businessZone)) == month }.sumOf { it.amountCents },
        salesWithoutPaymentDetails = sales.count { it.payments.isEmpty() },
    )
}

internal fun remainingDebt(sale: Movement, payments: List<DebtPayment>): Long =
    (sale.receivableCents - payments.filter { it.saleId == sale.id }.sumOf { it.amountCents }).coerceAtLeast(0)

internal fun Movement.operatorLabel(): String = operatorName.ifBlank { operatorId.ifBlank { "Conta não registrada (venda antiga)" } }
internal fun Movement.customerLabel(): String = customerName.ifBlank { "Cliente não informado" }

internal fun PaymentMethod.paymentLabel(): String = when (this) {
    PaymentMethod.CASH -> "Dinheiro"
    PaymentMethod.PIX -> "Pix"
    PaymentMethod.DEBIT -> "Cartão de débito"
    PaymentMethod.CREDIT -> "Cartão de crédito"
    PaymentMethod.FIADO -> "Fiado"
}

internal fun expenseCategoryLabel(category: String): String = when (category) {
    "AGUA" -> "Água"
    "ENERGIA" -> "Energia"
    "ALUGUEL" -> "Aluguel"
    "OUTROS" -> "Outros"
    else -> category
}
