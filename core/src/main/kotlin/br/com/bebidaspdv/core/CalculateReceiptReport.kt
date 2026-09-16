package br.com.bebidaspdv.core

/** Inputs are already filtered to the selected receipt month, including reversal dates. */
class CalculateReceiptReport {
    operator fun invoke(movements: List<Movement>, receipts: List<DebtPayment>, reversals: List<SaleReversal>): MonthlyReport {
        val sales = movements.filter { it.type == MovementType.SAIDA }
        require(receipts.all { it.costRecognizedCents != null }) {
            "Os recebimentos antigos precisam da atualização de dados antes de exibir o resumo."
        }
        val revenue = sales.sumOf { it.totalCents - it.receivableCents } + receipts.sumOf { it.amountCents } -
            reversals.sumOf { it.refundByMethod.values.sum() }
        val cost = sales.sumOf(ReceiptAccounting::initialCost) + receipts.sumOf { it.costRecognizedCents!! } -
            reversals.sumOf { ReceiptAccounting.allocatedCost(it.originalTotalCents - it.originalProfitCents,
                it.originalTotalCents - it.cancelledReceivableCents, it.originalTotalCents) }
        return MonthlyReport(revenue, cost, revenue - cost, sales.sumOf { sale -> sale.items.sumOf { it.stockQuantity } }, sales.size)
    }
}
