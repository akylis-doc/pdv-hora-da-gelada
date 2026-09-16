package br.com.bebidaspdv.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Semicolon-separated UTF-8 CSV. Text and trusted numeric cells have different encoders. */
object CsvExport {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val dateTime = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm:ss")
    private val date = DateTimeFormatter.ofPattern("dd/MM/uuuu")
    private sealed interface Cell {
        data class Text(val value: String) : Cell
        data class Number(val value: String) : Cell
    }

    fun products(products: List<Product>): String = table(
        listOf("produto_id", "nome", "codigo_barras", "categoria", "unidade", "estoque_quantidade",
            "itens_por_caixa", "custo_por_item_R$", "custo_informado_por", "custo_caixa_R$", "venda_por_item_R$", "venda_caixa_R$", "estoque_minimo_quantidade", "imagem"),
        products.filter(Product::active).sortedBy { it.name.lowercase() }.map { product ->
            listOf(text(product.id), text(product.name), text(product.barcode), text(product.category), text(product.unitLabel),
                number(product.stock.toLong()), number(product.packageSize.toLong()), money(product.costCents),
                text(if (product.purchaseByPackage) "CAIXA" else "ITEM"),
                money(if (product.purchaseByPackage) product.packageCostCents else product.costCents * product.packageSize),
                money(product.saleCents), money(product.packageSaleCents.takeIf { it > 0 } ?: product.saleCents * product.packageSize),
                number(product.lowStockThreshold.toLong()), text(product.imageKey))
        },
    )

    fun customers(customers: List<Customer>): String = table(
        listOf("cliente_id", "nome", "telefone", "limite_fiado_R$", "limite_definido", "fiado_em_aberto_R$"),
        customers.sortedBy { it.name.lowercase() }.map { customer ->
            listOf(text(customer.id), text(customer.name), text(customer.phone), money(customer.creditLimitCents),
                text(if (customer.creditLimitCents > 0) "SIM" else "NAO"), money(customer.balanceCents))
        },
    )

    /** Original sales remain immutable. Fiado here is the amount originally granted, not today's balance. */
    fun sales(movements: List<Movement>): String = table(
        listOf("venda_id", "data_hora_Brasilia", "operador_id", "operador", "cliente_id", "cliente", "caixa_id",
            "itens_avulsos_quantidade", "total_venda_R$", "custo_historico_R$", "lucro_bruto_venda_R$",
            "recebido_na_venda_R$", "fiado_original_R$", "dinheiro_liquido_R$", "pix_R$", "debito_R$", "credito_R$", "troco_R$", "vencimento_fiado"),
        movements.filter { it.type == MovementType.SAIDA }.sortedBy(Movement::occurredAt).map { sale ->
            fun paid(method: PaymentMethod): Cell = if (sale.payments.isEmpty()) text("") else money(
                sale.payments.filter { it.method == method }.sumOf(Payment::amountCents) - if (method == PaymentMethod.CASH) sale.changeCents else 0L,
            )
            listOf(text(sale.id), text(sale.occurredAt.atZone(zone).format(dateTime)), text(sale.operatorId), text(sale.operatorName),
                text(sale.customerId), text(sale.customerName), text(sale.cashSessionId),
                number(sale.items.sumOf { it.quantity.toLong() * it.unitsPerPackage }), money(sale.totalCents),
                money(ReceiptAccounting.cost(sale)), money(sale.profitCents), money(sale.totalCents - sale.receivableCents),
                money(sale.receivableCents), paid(PaymentMethod.CASH), paid(PaymentMethod.PIX), paid(PaymentMethod.DEBIT),
                paid(PaymentMethod.CREDIT), money(sale.changeCents), text(due(sale.dueEpochDay)))
        },
    )

    fun receivables(accounts: List<ReceivableAccount>, customers: List<Customer>): String {
        val names = customers.associateBy(Customer::id)
        return table(
            listOf("venda_id", "cliente_id", "cliente", "telefone", "fiado_original_R$", "recebido_R$", "cancelado_R$", "restante_R$", "vencimento"),
            accounts.filter { it.remainingCents > 0 }.sortedWith(compareBy<ReceivableAccount> { it.dueEpochDay }.thenBy { it.saleId }).map { account ->
                val customer = names[account.customerId]
                listOf(text(account.saleId), text(account.customerId), text(customer?.name.orEmpty()), text(customer?.phone.orEmpty()),
                    money(account.originalCents), money(account.settledCents), money(account.cancelledCents), money(account.remainingCents), text(due(account.dueEpochDay)))
            },
        )
    }

    private fun due(epochDay: Long): String = if (epochDay == 0L) "" else LocalDate.ofEpochDay(epochDay).format(date)
    private fun text(value: String): Cell = Cell.Text(value)
    private fun number(value: Long): Cell = Cell.Number(value.toString())
    private fun money(cents: Long): Cell = Cell.Number(BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ','))

    private fun table(headers: List<String>, rows: List<List<Cell>>): String = buildString {
        append('\uFEFF')
        append(headers.joinToString(";") { encode(Cell.Text(it)) }).append("\r\n")
        rows.forEach { row ->
            require(row.size == headers.size) { "Colunas da exportação inconsistentes." }
            append(row.joinToString(";", transform = ::encode)).append("\r\n")
        }
    }

    private fun encode(cell: Cell): String {
        val value = when (cell) {
            is Cell.Number -> cell.value
            is Cell.Text -> {
                // Whitespace, line breaks and BOM can hide a formula prefix from a visual check.
                val first = cell.value.dropWhile { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' }.firstOrNull()
                val numericText = cell.value.isNotEmpty() && cell.value.all(Char::isDigit)
                if (numericText || first in listOf('=', '+', '-', '@') || cell.value.firstOrNull() in listOf('\t', '\r', '\n')) "'${cell.value}" else cell.value
            }
        }
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
