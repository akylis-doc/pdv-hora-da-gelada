package br.com.bebidaspdv.core

import java.time.Clock
import java.util.UUID

class CreateMovement(private val clock: Clock = Clock.systemUTC()) {
    operator fun invoke(
        lines: List<CartLine>, type: MovementType,
        payments: List<Payment> = emptyList(), customer: Customer? = null,
        operatorId: String = "", operatorName: String = "",
    ): Movement {
        require(lines.isNotEmpty()) { "Adicione ao menos um produto." }
        require(lines.size <= BusinessLimits.MAX_DISTINCT_PRODUCTS) {
            "Cada movimento aceita até ${BusinessLimits.MAX_DISTINCT_PRODUCTS} produtos diferentes."
        }
        require(lines.map { it.product.id }.distinct().size == lines.size) {
            "Um produto não pode aparecer em duas linhas do mesmo movimento."
        }

        val items = lines.map { line ->
            val product = line.product
            require(product.id.isNotBlank()) { "Produto sem identificação." }
            require(product.name.isNotBlank()) { "Produto sem nome." }
            require(product.active) { "${product.name} foi excluído do catálogo." }
            require(line.unitsPerPackage == 1 || line.unitsPerPackage == product.packageSize) { "Embalagem inválida." }
            require(line.unitsPerPackage in 1..BusinessLimits.MAX_QUANTITY) { "Quantidade por caixa inválida." }
            require(line.quantity in 1..BusinessLimits.MAX_QUANTITY) {
                "A quantidade deve ser de 1 a ${BusinessLimits.MAX_QUANTITY}."
            }
            require(product.costCents in 0..BusinessLimits.MAX_MONEY_CENTS) {
                "O custo de ${product.name} está fora do limite permitido."
            }
            require(product.saleCents in 0..BusinessLimits.MAX_MONEY_CENTS) {
                "O preço de ${product.name} está fora do limite permitido."
            }
            if (type == MovementType.SAIDA) {
                require(product.stock >= line.stockQuantity) {
                    "Estoque local insuficiente para ${product.name}."
                }
            }
            SaleItem(
                productId = product.id,
                nameSnapshot = product.name,
                quantity = line.quantity,
                saleUnitCents = if (type == MovementType.SAIDA) line.saleUnitCents else line.costUnitCents,
                historicalCostCents = line.costUnitCents,
                unitsPerPackage = line.unitsPerPackage,
                imageKeySnapshot = product.imageKey,
            )
        }
        // Bounded input amounts and quantities make these Long multiplications safe.
        val total = items.sumOf { it.saleUnitCents * it.quantity }
        require(total <= BusinessLimits.MAX_MONEY_CENTS) {
            "O total do movimento excede o limite permitido."
        }
        val profit = if (type == MovementType.SAIDA) {
            items.sumOf { (it.saleUnitCents - it.historicalCostCents) * it.quantity }
        } else {
            0L
        }

        require(items.all { it.saleUnitCents <= BusinessLimits.MAX_MONEY_CENTS && it.historicalCostCents <= BusinessLimits.MAX_MONEY_CENTS }) { "O valor da embalagem excede o limite permitido." }
        // Empty payments remain supported for existing callers/legacy receipts. New checkout supplies them.
        val settlement = if (type == MovementType.SAIDA && payments.isNotEmpty()) {
            CalculatePayment()(total, payments).also { it.requireComplete(customer) }
        } else null
        require(type == MovementType.SAIDA || payments.isEmpty()) { "Entrada de estoque não recebe pagamentos de clientes." }
        return Movement(
            id = UUID.randomUUID().toString(),
            occurredAt = clock.instant(),
            type = type,
            items = items,
            totalCents = total,
            profitCents = profit,
            operatorId = operatorId,
            operatorName = operatorName,
            customerId = customer?.id.orEmpty(),
            customerName = customer?.name.orEmpty(),
            payments = payments,
            changeCents = settlement?.changeCents ?: 0,
            receivableCents = settlement?.fiadoCents ?: 0,
        )
    }
}
