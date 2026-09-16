package br.com.bebidaspdv.core

import java.time.YearMonth
import java.time.ZoneId

class CalculateMonthlyReport {
    operator fun invoke(
        movements: List<Movement>,
        month: YearMonth,
        zone: ZoneId,
    ): MonthlyReport {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val endExclusive = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val sales = movements.filter {
            it.type == MovementType.SAIDA &&
                !it.occurredAt.isBefore(start) && it.occurredAt.isBefore(endExclusive)
        }
        var revenue = 0L
        var cost = 0L
        var units = 0
        for (sale in sales) {
            for (item in sale.items) {
                revenue = Math.addExact(revenue, Math.multiplyExact(item.saleUnitCents, item.quantity.toLong()))
                cost = Math.addExact(cost, Math.multiplyExact(item.historicalCostCents, item.quantity.toLong()))
                units = Math.addExact(units, item.stockQuantity)
            }
        }
        return MonthlyReport(
            revenueCents = revenue,
            costCents = cost,
            profitCents = Math.subtractExact(revenue, cost),
            unitsSold = units,
            salesCount = sales.size,
        )
    }
}
