package br.com.bebidaspdv.core

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PurchaseCostTest {
    private val product = Product(UUID.randomUUID().toString(), "Caixa de alimentos", "", 12,
        costCents = PurchaseCost.unitCents(1001, 3), saleCents = 500, packageSize = 3,
        category = "ALIMENTO", purchaseByPackage = true, packageCostCents = 1001)

    @Test fun `unit normalization rounds half cent up and rejects invalid input`() {
        assertEquals(334L, PurchaseCost.unitCents(1001, 3))
        assertEquals(101L, PurchaseCost.unitCents(201, 2))
        assertEquals(0L, PurchaseCost.unitCents(0, 12))
        assertThrows(IllegalArgumentException::class.java) { PurchaseCost.unitCents(100, 0) }
        assertThrows(IllegalArgumentException::class.java) { PurchaseCost.unitCents(-1, 12) }
    }

    @Test fun `whole box sale freezes exact paid box cost while loose item uses normalized cost`() {
        val create = CreateMovement()
        val box = create(listOf(CartLine(product, 2, 3)), MovementType.SAIDA)
        val loose = create(listOf(CartLine(product, 1)), MovementType.SAIDA)
        assertEquals(1001L, box.items.single().historicalCostCents)
        assertEquals(998L, box.profitCents)
        assertEquals(334L, loose.items.single().historicalCostCents)
        assertEquals(166L, loose.profitCents)
        val later = product.copy(costCents = 500, packageCostCents = 1500)
        assertEquals(1001L, box.items.single().historicalCostCents)
        assertEquals(1500L, CartLine(later, 1, 3).costUnitCents)
    }

    @Test fun `stock entry uses original cost of the boxes instead of rounded unit sum`() {
        val entry = CreateMovement()(listOf(CartLine(product, 2, 3)), MovementType.ENTRADA)
        assertEquals(2002L, entry.totalCents)
        assertEquals(0L, entry.profitCents)
        assertEquals(6, entry.items.single().stockQuantity)
    }
}
