package br.com.bebidaspdv.core

import java.time.Instant

/** Monetary calculations stay in integer cents until the Firestore boundary. */
data class Product(
    val id: String,
    val name: String,
    val barcode: String,
    val stock: Int,
    val costCents: Long,
    val saleCents: Long,
    val imageKey: String = "",
    val active: Boolean = true,
    val lowStockThreshold: Int = 5,
    val packageSize: Int = 1,
    val packageSaleCents: Long = 0,
    val category: String = "OUTROS",
    val unitLabel: String = "UNIDADE",
    val purchaseByPackage: Boolean = false,
    val packageCostCents: Long = 0,
)

enum class MovementType { SAIDA, ENTRADA }

data class CartLine(val product: Product, val quantity: Int, val unitsPerPackage: Int = 1) {
    val stockQuantity: Int get() = Math.multiplyExact(quantity, unitsPerPackage)
    val saleUnitCents: Long get() = if (unitsPerPackage > 1 && product.packageSaleCents > 0)
        product.packageSaleCents else Math.multiplyExact(product.saleCents, unitsPerPackage.toLong())
    val costUnitCents: Long get() = if (unitsPerPackage > 1 && product.purchaseByPackage)
        product.packageCostCents else Math.multiplyExact(product.costCents, unitsPerPackage.toLong())
}

/** Immutable prices copied when the movement is prepared, independent of later product edits. */
data class SaleItem(
    val productId: String,
    val nameSnapshot: String,
    val quantity: Int,
    val saleUnitCents: Long,
    val historicalCostCents: Long,
    val unitsPerPackage: Int = 1,
    val imageKeySnapshot: String = "",
) {
    val stockQuantity: Int get() = Math.multiplyExact(quantity, unitsPerPackage)
}

data class Movement(
    val id: String,
    val occurredAt: Instant,
    val type: MovementType,
    val items: List<SaleItem>,
    val totalCents: Long,
    val profitCents: Long,
    val operatorId: String = "",
    val operatorName: String = "",
    val customerId: String = "",
    val customerName: String = "",
    val payments: List<Payment> = emptyList(),
    val changeCents: Long = 0,
    val receivableCents: Long = 0,
    val cashSessionId: String = "",
    val deviceId: String = "",
    val dueEpochDay: Long = 0,
    val creditOverrideReason: String = "",
)

enum class PaymentMethod { CASH, PIX, DEBIT, CREDIT, FIADO }
data class Payment(val method: PaymentMethod, val amountCents: Long)
data class Customer(val id: String, val name: String, val phone: String = "",
    val creditLimitCents: Long = 0, val balanceCents: Long = 0)
data class Expense(
    val id: String, val occurredAt: Instant, val description: String, val category: String,
    val amountCents: Long, val operatorId: String = "", val operatorName: String = "",
)
data class DebtPayment(
    val id: String, val saleId: String, val customerId: String, val occurredAt: Instant,
    val amountCents: Long, val method: PaymentMethod,
    val operatorId: String = "", val operatorName: String = "",
    val cashSessionId: String = "", val deviceId: String = "",
    val costRecognizedCents: Long? = null, val paidBeforeCents: Long = 0,
)
data class ReceivableAccount(val saleId: String, val customerId: String, val originalCents: Long,
    val settledCents: Long, val dueEpochDay: Long = 0, val cancelledCents: Long = 0) {
    val remainingCents: Long get() = (originalCents - settledCents - cancelledCents).coerceAtLeast(0)
}
/** The server or transaction rejected the request before any payment was committed. */
class DebtSettlementRejected(message: String) : IllegalStateException(message)

/** Units and sale count intentionally have separate fields from monetary metrics. */
data class MonthlyReport(
    val revenueCents: Long,
    val costCents: Long,
    val profitCents: Long,
    val unitsSold: Int,
    val salesCount: Int,
)

object BusinessLimits {
    const val MAX_DISTINCT_PRODUCTS = 8
    const val MAX_QUANTITY = 9_999
    const val MAX_MONEY_CENTS = 99_999_999L
}
