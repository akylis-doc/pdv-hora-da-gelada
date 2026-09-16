package br.com.bebidaspdv.data

import br.com.bebidaspdv.core.Movement
import br.com.bebidaspdv.core.MovementType
import br.com.bebidaspdv.core.Product
import br.com.bebidaspdv.core.SaleItem
import br.com.bebidaspdv.core.Customer
import br.com.bebidaspdv.core.DebtPayment
import br.com.bebidaspdv.core.Expense
import br.com.bebidaspdv.core.Payment
import br.com.bebidaspdv.core.PaymentMethod
import br.com.bebidaspdv.core.ReceivableAccount
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/** The integer fields are authoritative. Double fields preserve the requested Firestore schema. */
internal object FirestoreMapper {
    fun productFields(product: Product): Map<String, Any> = mapOf(
        "id" to product.id,
        "nome" to product.name,
        "codigo_barras" to product.barcode,
        "preco_custo_atual" to product.costCents / 100.0,
        "preco_venda_atual" to product.saleCents / 100.0,
        "cost_cents" to product.costCents,
        "sale_cents" to product.saleCents,
        "imagem" to product.imageKey,
        "ativo" to product.active,
        "estoque_minimo" to product.lowStockThreshold,
        "unidades_caixa" to product.packageSize,
        "preco_caixa_cents" to product.packageSaleCents,
        "categoria" to product.category, "unidade_venda" to product.unitLabel,
        "custo_por_caixa" to product.purchaseByPackage, "custo_caixa_cents" to product.packageCostCents,
    )

    fun product(document: DocumentSnapshot): Product = Product(
        id = document.id,
        name = document.getString("nome") ?: error("Produto sem nome: ${document.id}"),
        barcode = document.getString("codigo_barras").orEmpty(),
        stock = Math.toIntExact(document.getLong("estoque_atual") ?: 0L),
        costCents = cents(document.data.orEmpty(), "cost_cents", "preco_custo_atual"),
        saleCents = cents(document.data.orEmpty(), "sale_cents", "preco_venda_atual"),
        imageKey = document.getString("imagem").orEmpty(),
        active = document.getBoolean("ativo") ?: true,
        lowStockThreshold = Math.toIntExact(document.getLong("estoque_minimo") ?: 5L),
        packageSize = Math.toIntExact(document.getLong("unidades_caixa") ?: 1L),
        packageSaleCents = document.getLong("preco_caixa_cents") ?: 0L,
        category = document.getString("categoria") ?: "OUTROS",
        unitLabel = document.getString("unidade_venda") ?: "UNIDADE",
        purchaseByPackage = document.getBoolean("custo_por_caixa") ?: false,
        packageCostCents = document.getLong("custo_caixa_cents") ?: 0,
    )

    fun movementFields(movement: Movement): Map<String, Any> = mapOf(
        "id" to movement.id,
        "dataHora" to Timestamp(movement.occurredAt.epochSecond, movement.occurredAt.nano),
        "tipo" to movement.type.name,
        "valorTotal" to movement.totalCents / 100.0,
        "lucroTotal" to movement.profitCents / 100.0,
        "total_cents" to movement.totalCents,
        "profit_cents" to movement.profitCents,
        "produtoIds" to movement.items.map { it.productId },
        "schema_version" to if (movement.cashSessionId.isNotBlank()) 3 else 2,
        "operador_id" to movement.operatorId,
        "operador_nome" to movement.operatorName,
        "cliente_id" to movement.customerId,
        "cliente_nome" to movement.customerName,
        "pagamentos" to PaymentMethod.entries.associate { method -> method.name to movement.payments.filter { it.method == method }.sumOf { it.amountCents } },
        "troco_cents" to movement.changeCents,
        "receivable_cents" to movement.receivableCents,
        "Itens" to movement.items.map { item ->
            mapOf(
                "produtoId" to item.productId,
                "nome_snapshot" to item.nameSnapshot,
                "quantidade" to item.quantity,
                "precoUnitarioVendido" to item.saleUnitCents / 100.0,
                "custoUnitarioHistorico" to item.historicalCostCents / 100.0,
                "sale_unit_cents" to item.saleUnitCents,
                "historical_cost_cents" to item.historicalCostCents,
                "unidades_embalagem" to item.unitsPerPackage,
                "imagem_snapshot" to item.imageKeySnapshot,
            )
        },
    ) + if (movement.cashSessionId.isNotBlank()) mapOf(
        "caixa_id" to movement.cashSessionId, "aparelho_id" to movement.deviceId,
        "due_epoch_day" to movement.dueEpochDay, "credit_override_reason" to movement.creditOverrideReason,
    ) else emptyMap()

    fun movement(document: DocumentSnapshot): Movement {
        val fields = document.data ?: error("Movimentação vazia")
        val rawItems = fields["Itens"] as? List<*> ?: error("Movimentação sem itens")
        return Movement(
            id = document.id,
            occurredAt = document.getTimestamp("dataHora")?.let { Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong()) }
                ?: error("Movimentação sem data"),
            type = MovementType.valueOf(document.getString("tipo") ?: error("Movimentação sem tipo")),
            items = rawItems.map { raw ->
                val item = raw as? Map<*, *> ?: error("Item inválido")
                SaleItem(
                    productId = item["produtoId"] as String,
                    nameSnapshot = item["nome_snapshot"] as String,
                    quantity = Math.toIntExact((item["quantidade"] as Number).toLong()),
                    saleUnitCents = cents(item, "sale_unit_cents", "precoUnitarioVendido"),
                    historicalCostCents = cents(item, "historical_cost_cents", "custoUnitarioHistorico"),
                    unitsPerPackage = Math.toIntExact((item["unidades_embalagem"] as? Number)?.toLong() ?: 1L),
                    imageKeySnapshot = item["imagem_snapshot"] as? String ?: "",
                )
            },
            totalCents = cents(fields, "total_cents", "valorTotal"),
            profitCents = cents(fields, "profit_cents", "lucroTotal"),
            operatorId = document.getString("operador_id").orEmpty(),
            operatorName = document.getString("operador_nome").orEmpty(),
            customerId = document.getString("cliente_id").orEmpty(),
            customerName = document.getString("cliente_nome").orEmpty(),
            payments = (fields["pagamentos"] as? Map<*, *>)?.mapNotNull { (method, amount) ->
                val cents = (amount as? Number)?.toLong() ?: return@mapNotNull null
                if (cents > 0) Payment(PaymentMethod.valueOf(method as String), cents) else null
            }.orEmpty(),
            changeCents = document.getLong("troco_cents") ?: 0,
            receivableCents = document.getLong("receivable_cents") ?: 0,
            cashSessionId = document.getString("caixa_id").orEmpty(), deviceId = document.getString("aparelho_id").orEmpty(),
            dueEpochDay = document.getLong("due_epoch_day") ?: 0,
            creditOverrideReason = document.getString("credit_override_reason").orEmpty(),
        )
    }

    fun customerFields(customer: Customer): Map<String, Any> = mapOf("id" to customer.id, "nome" to customer.name,
        "telefone" to customer.phone, "credit_limit_cents" to customer.creditLimitCents)
    fun customer(document: DocumentSnapshot) = Customer(document.id, document.getString("nome").orEmpty(),
        document.getString("telefone").orEmpty(), document.getLong("credit_limit_cents") ?: 0, document.getLong("balance_cents") ?: 0)
    fun receivable(document: DocumentSnapshot) = ReceivableAccount(document.id, document.getString("cliente_id").orEmpty(),
        document.getLong("original_cents") ?: 0, document.getLong("settled_cents") ?: 0,
        document.getLong("due_epoch_day") ?: 0, document.getLong("cancelled_cents") ?: 0)
    fun expenseFields(expense: Expense): Map<String, Any> = mapOf(
        "id" to expense.id, "dataHora" to Timestamp(expense.occurredAt.epochSecond, expense.occurredAt.nano),
        "descricao" to expense.description, "categoria" to expense.category, "amount_cents" to expense.amountCents,
        "operador_id" to expense.operatorId, "operador_nome" to expense.operatorName,
    )
    fun expense(document: DocumentSnapshot) = Expense(
        document.id, instant(document), document.getString("descricao").orEmpty(), document.getString("categoria").orEmpty(),
        document.getLong("amount_cents") ?: 0, document.getString("operador_id").orEmpty(), document.getString("operador_nome").orEmpty(),
    )
    fun debtPaymentFields(payment: DebtPayment): Map<String, Any> = mapOf(
        "id" to payment.id, "venda_id" to payment.saleId, "cliente_id" to payment.customerId,
        "dataHora" to Timestamp(payment.occurredAt.epochSecond, payment.occurredAt.nano),
        "amount_cents" to payment.amountCents, "metodo" to payment.method.name,
        "operador_id" to payment.operatorId, "operador_nome" to payment.operatorName,
        "caixa_id" to payment.cashSessionId, "aparelho_id" to payment.deviceId,
        "cost_recognized_cents" to (payment.costRecognizedCents ?: 0), "paid_before_cents" to payment.paidBeforeCents,
    )
    fun debtPayment(document: DocumentSnapshot) = DebtPayment(
        document.id, document.getString("venda_id").orEmpty(), document.getString("cliente_id").orEmpty(), instant(document),
        document.getLong("amount_cents") ?: 0, PaymentMethod.valueOf(document.getString("metodo") ?: "CASH"),
        document.getString("operador_id").orEmpty(), document.getString("operador_nome").orEmpty(),
        document.getString("caixa_id").orEmpty(), document.getString("aparelho_id").orEmpty(),
        document.getLong("cost_recognized_cents"), document.getLong("paid_before_cents") ?: 0,
    )
    private fun instant(document: DocumentSnapshot): Instant = document.getTimestamp("dataHora")?.let {
        Instant.ofEpochSecond(it.seconds, it.nanoseconds.toLong())
    } ?: error("Registro sem data")

    private fun cents(fields: Map<*, *>, integerKey: String, legacyKey: String): Long =
        (fields[integerKey] as? Number)?.toLong()
            ?: BigDecimal((fields[legacyKey] as? Number ?: error("Campo ausente: $legacyKey")).toString())
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
}
