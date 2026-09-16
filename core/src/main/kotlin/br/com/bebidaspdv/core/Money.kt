package br.com.bebidaspdv.core

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
    private val plainAmount = Regex("[+-]?\\d+(?:[.,]\\d{1,2})?")
    private val groupedBrazilianAmount = Regex("[+-]?\\d{1,3}(?:\\.\\d{3})+,\\d{1,2}")

    /**
     * Accepts 1234,56; 1.234,56; 1234.56; or 1234. Currency symbols are not input.
     * Extra fractional digits are rejected instead of silently rounded. A negative value
     * can be parsed, allowing the business operation to apply the appropriate validation.
     */
    fun fromDecimal(text: String): Long {
        val input = text.trim()
        require(plainAmount.matches(input) || groupedBrazilianAmount.matches(input)) {
            "Informe um valor válido com até duas casas decimais."
        }
        val normalized = if (',' in input) input.replace(".", "").replace(',', '.') else input
        return decimalToCents(BigDecimal(normalized))
    }

    /** Firestore uses Double, but calculations never do. Invalid fractional cents are rejected. */
    fun fromDouble(value: Double): Long {
        require(value.isFinite()) { "O valor monetário deve ser finito." }
        return decimalToCents(BigDecimal.valueOf(value))
    }

    fun toDouble(cents: Long): Double = BigDecimal.valueOf(cents, 2).toDouble()

    private fun decimalToCents(value: BigDecimal): Long = try {
        value.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
    } catch (exception: ArithmeticException) {
        throw IllegalArgumentException("Valor fora do limite ou com mais de duas casas decimais.", exception)
    }
}
