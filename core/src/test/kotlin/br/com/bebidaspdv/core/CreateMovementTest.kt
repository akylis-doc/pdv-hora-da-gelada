package br.com.bebidaspdv.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CreateMovementTest {
    private val now = Instant.parse("2026-09-06T15:30:00Z")
    private val create = CreateMovement(Clock.fixed(now, ZoneOffset.UTC))
    private val product = Product(
        id = UUID.randomUUID().toString(),
        name = "Refrigerante 2 L",
        barcode = "7891234567890",
        stock = 25,
        costCents = 650,
        saleCents = 1000,
    )

    @Test
    fun `checkout snapshots cost and computes exact sale and profit`() {
        val movement = create(listOf(CartLine(product, 3)), MovementType.SAIDA)
        assertEquals(MovementType.SAIDA, movement.type)
        assertEquals(now, movement.occurredAt)
        assertEquals(3000L, movement.totalCents)
        assertEquals(1050L, movement.profitCents)
        assertEquals(product.id, movement.items.single().productId)
        assertEquals(product.name, movement.items.single().nameSnapshot)
        assertEquals(650L, movement.items.single().historicalCostCents)
        assertEquals(1000L, movement.items.single().saleUnitCents)
        assertEquals(3, movement.items.single().quantity)
        assertEquals(25, product.stock)
    }

    @Test
    fun `product price changes cannot rewrite an existing sale`() {
        val movement = create(listOf(CartLine(product, 3)), MovementType.SAIDA)
        val revised = product.copy(name = "Novo nome", costCents = 900, saleCents = 1500)
        val later = create(listOf(CartLine(revised, 3)), MovementType.SAIDA)
        assertEquals(650L, movement.items.single().historicalCostCents)
        assertEquals("Refrigerante 2 L", movement.items.single().nameSnapshot)
        assertEquals(1050L, movement.profitCents)
        assertEquals(900L, later.items.single().historicalCostCents)
        assertEquals(1800L, later.profitCents)
    }

    @Test
    fun `movement IDs are independent random UUIDs`() {
        val first = create(listOf(CartLine(product, 1)), MovementType.SAIDA)
        val second = create(listOf(CartLine(product, 1)), MovementType.SAIDA)
        assertEquals(4, UUID.fromString(first.id).version())
        assertEquals(first.id, UUID.fromString(first.id).toString())
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `entry records procurement cost and no profit`() {
        val movement = create(listOf(CartLine(product.copy(stock = 0), 100)), MovementType.ENTRADA)
        assertEquals(65000L, movement.totalCents)
        assertEquals(0L, movement.profitCents)
        assertEquals(650L, movement.items.single().saleUnitCents)
        assertEquals(650L, movement.items.single().historicalCostCents)
    }

    @Test
    fun `entry can replenish stock made negative by concurrent offline sales`() {
        val movement = create(listOf(CartLine(product.copy(stock = -2), 10)), MovementType.ENTRADA)
        assertEquals(10, movement.items.single().quantity)
        assertEquals(6500L, movement.totalCents)
    }

    @Test
    fun `sale below cost records the actual loss`() {
        val movement = create(listOf(CartLine(product.copy(saleCents = 500), 2)), MovementType.SAIDA)
        assertEquals(1000L, movement.totalCents)
        assertEquals(-300L, movement.profitCents)
    }

    @Test
    fun `sale checks current local stock including exact remaining quantity`() {
        assertEquals(25, create(listOf(CartLine(product, 25)), MovementType.SAIDA).items.single().quantity)
        assertThrows(IllegalArgumentException::class.java) {
            create(listOf(CartLine(product, 26)), MovementType.SAIDA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            create(listOf(CartLine(product.copy(stock = -1), 1)), MovementType.SAIDA)
        }
    }

    @Test
    fun `invalid quantities and empty cart are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { create(emptyList(), MovementType.SAIDA) }
        listOf(Int.MIN_VALUE, -1, 0, 10_000, Int.MAX_VALUE).forEach { quantity ->
            assertThrows(IllegalArgumentException::class.java) {
                create(listOf(CartLine(product, quantity)), MovementType.ENTRADA)
            }
        }
        assertEquals(
            BusinessLimits.MAX_QUANTITY,
            create(listOf(CartLine(product.copy(costCents = 1), BusinessLimits.MAX_QUANTITY)), MovementType.ENTRADA)
                .items.single().quantity,
        )
    }

    @Test
    fun `duplicates are rejected even when their snapshots differ`() {
        assertThrows(IllegalArgumentException::class.java) {
            create(listOf(CartLine(product, 1), CartLine(product.copy(costCents = 700), 1)), MovementType.SAIDA)
        }
    }

    @Test
    fun `eight distinct items fit the batch authorization budget and nine do not`() {
        val lines = (1..9).map { CartLine(product.copy(id = UUID.randomUUID().toString()), 1) }
        assertEquals(8, create(lines.take(8), MovementType.SAIDA).items.size)
        assertThrows(IllegalArgumentException::class.java) { create(lines, MovementType.SAIDA) }
    }

    @Test
    fun `negative or excessive monetary values and excessive totals are rejected`() {
        listOf(-1L, BusinessLimits.MAX_MONEY_CENTS + 1, Long.MAX_VALUE).forEach { amount ->
            assertThrows(IllegalArgumentException::class.java) {
                create(listOf(CartLine(product.copy(costCents = amount), 1)), MovementType.SAIDA)
            }
            assertThrows(IllegalArgumentException::class.java) {
                create(listOf(CartLine(product.copy(saleCents = amount), 1)), MovementType.SAIDA)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            create(listOf(CartLine(product.copy(saleCents = BusinessLimits.MAX_MONEY_CENTS), 2)), MovementType.SAIDA)
        }
        assertThrows(IllegalArgumentException::class.java) {
            create(listOf(CartLine(product.copy(costCents = BusinessLimits.MAX_MONEY_CENTS), 2)), MovementType.ENTRADA)
        }
        assertEquals(
            BusinessLimits.MAX_MONEY_CENTS,
            create(listOf(CartLine(product.copy(saleCents = BusinessLimits.MAX_MONEY_CENTS), 1)), MovementType.SAIDA)
                .totalCents,
        )
    }
}
