package br.com.bebidaspdv.core

import java.util.UUID

data class PaymentSummary(
    val paidCents: Long, val remainingCents: Long, val changeCents: Long, val fiadoCents: Long,
) {
    fun requireComplete(customer: Customer? = null) {
        require(remainingCents == 0L) { "Informe o pagamento restante antes de finalizar." }
        if (fiadoCents > 0) {
            require(customer != null && customer.name.isNotBlank()) { "Selecione um cliente cadastrado para vender fiado." }
            require(runCatching { UUID.fromString(customer.id).toString() == customer.id }.getOrDefault(false)) { "Cliente sem identificação válida." }
        }
    }
}

/** Cash may include change. Electronic payments and credit never generate change. */
class CalculatePayment {
    operator fun invoke(totalCents: Long, payments: List<Payment>): PaymentSummary {
        require(totalCents in 0..BusinessLimits.MAX_MONEY_CENTS) { "Total inválido." }
        require(payments.size <= 5 && payments.map { it.method }.distinct().size == payments.size) { "Use uma linha por forma de pagamento." }
        require(payments.all { it.amountCents in 0..BusinessLimits.MAX_MONEY_CENTS }) { "Valor do pagamento inválido." }
        val cash = payments.filter { it.method == PaymentMethod.CASH }.sumOf { it.amountCents }
        val electronic = payments.filter { it.method != PaymentMethod.CASH && it.method != PaymentMethod.FIADO }.sumOf { it.amountCents }
        val fiado = payments.filter { it.method == PaymentMethod.FIADO }.sumOf { it.amountCents }
        require(electronic <= totalCents) { "Cartão e Pix não podem ultrapassar o total da compra." }
        val paid = cash + electronic
        require(fiado <= (totalCents - paid).coerceAtLeast(0)) { "O fiado deve corresponder somente ao valor que falta pagar." }
        return PaymentSummary(paid, (totalCents - paid - fiado).coerceAtLeast(0), (paid - totalCents).coerceAtLeast(0), fiado)
    }
}
