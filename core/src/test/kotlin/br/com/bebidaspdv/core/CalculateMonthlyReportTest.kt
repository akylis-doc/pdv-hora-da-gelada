package br.com.bebidaspdv.core

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class CalculateMonthlyReportTest {
    private val calculate = CalculateMonthlyReport()
    private val september = YearMonth.of(2026, 9)
    private val saoPaulo = ZoneId.of("America/Sao_Paulo")

    private fun sale(
        at: String = "2026-09-06T15:00:00Z",
        quantity: Int = 3,
        price: Long = 1000,
        cost: Long = 650,
        type: MovementType = MovementType.SAIDA,
    ) = Movement(
        id = UUID.randomUUID().toString(),
        occurredAt = Instant.parse(at),
        type = type,
        items = listOf(SaleItem(UUID.randomUUID().toString(), "Refrigerante 2 L", quantity, price, cost)),
        totalCents = price * quantity,
        profitCents = if (type == MovementType.SAIDA) (price - cost) * quantity else 0,
    )

    @Test
    fun `report separates units and sale count from revenue cost and profit`() {
        val result = calculate(listOf(sale(), sale(quantity = 2, price = 800, cost = 500)), september, saoPaulo)
        assertEquals(4600L, result.revenueCents)
        assertEquals(2950L, result.costCents)
        assertEquals(1650L, result.profitCents)
        assertEquals(5, result.unitsSold)
        assertEquals(2, result.salesCount)
    }

    @Test
    fun `entries are not revenue sales units or monthly COGS`() {
        val result = calculate(listOf(sale(), sale(quantity = 90, type = MovementType.ENTRADA)), september, saoPaulo)
        assertEquals(MonthlyReport(3000, 1950, 1050, 3, 1), result)
    }

    @Test
    fun `historical item snapshots remain the source of truth for monthly COGS`() {
        val historical = sale().copy(totalCents = 999999, profitCents = 999999)
        val result = calculate(listOf(historical), september, saoPaulo)
        assertEquals(3000L, result.revenueCents)
        assertEquals(1950L, result.costCents)
        assertEquals(1050L, result.profitCents)
    }

    @Test
    fun `Brazilian month includes start excludes end and handles UTC date difference`() {
        val movements = listOf(
            sale(at = "2026-09-01T02:59:59.999999999Z"),
            sale(at = "2026-09-01T03:00:00Z", quantity = 1),
            sale(at = "2026-10-01T02:59:59.999999999Z", quantity = 2),
            sale(at = "2026-10-01T03:00:00Z"),
        )
        assertEquals(MonthlyReport(3000, 1950, 1050, 3, 2), calculate(movements, september, saoPaulo))
    }

    @Test
    fun `calendar boundaries respect timezone offset changes within the month`() {
        val movements = listOf(
            sale(at = "2026-03-01T04:59:59Z"),
            sale(at = "2026-03-01T05:00:00Z", quantity = 1),
            sale(at = "2026-04-01T03:59:59Z", quantity = 2),
            sale(at = "2026-04-01T04:00:00Z"),
        )
        val result = calculate(movements, YearMonth.of(2026, 3), ZoneId.of("America/New_York"))
        assertEquals(MonthlyReport(3000, 1950, 1050, 3, 2), result)
    }

    @Test
    fun `empty month returns explicit zero metrics`() {
        assertEquals(MonthlyReport(0, 0, 0, 0, 0), calculate(emptyList(), september, saoPaulo))
        assertEquals(
            MonthlyReport(0, 0, 0, 0, 0),
            calculate(listOf(sale(at = "2026-08-01T15:00:00Z")), september, saoPaulo),
        )
    }

    @Test
    fun `losses are retained instead of clamped to zero`() {
        assertEquals(
            MonthlyReport(1500, 1950, -450, 3, 1),
            calculate(listOf(sale(price = 500)), september, saoPaulo),
        )
    }
}
