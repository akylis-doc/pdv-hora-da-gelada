package br.com.bebidaspdv.core

import java.math.BigDecimal
import java.math.RoundingMode

object PurchaseCost {
    fun unitCents(boxCents: Long, size: Int): Long {
        require(boxCents >= 0 && size > 0)
        return BigDecimal.valueOf(boxCents).divide(BigDecimal.valueOf(size.toLong()), 0, RoundingMode.HALF_UP).longValueExact()
    }
}

/** Cumulative allocation prevents partial receipts from losing or creating cents. */
object ReceiptAccounting {
    fun allocatedCost(totalCost: Long, paid: Long, total: Long): Long {
        require(totalCost >= 0 && total >= 0 && paid in 0..total)
        if (total == 0L) return totalCost
        return BigDecimal.valueOf(totalCost).multiply(BigDecimal.valueOf(paid))
            .divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP).longValueExact()
    }
    fun cost(sale: Movement): Long = sale.items.sumOf { Math.multiplyExact(it.historicalCostCents, it.quantity.toLong()) }
    fun initialCost(sale: Movement): Long = allocatedCost(cost(sale), sale.totalCents - sale.receivableCents, sale.totalCents)
    fun receiptCost(sale: Movement, settledBefore: Long, amount: Long): Long {
        val paid = sale.totalCents - sale.receivableCents + settledBefore
        return allocatedCost(cost(sale), paid + amount, sale.totalCents) - allocatedCost(cost(sale), paid, sale.totalCents)
    }
}
