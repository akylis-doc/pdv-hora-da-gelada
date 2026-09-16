package br.com.bebidaspdv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MoneyTest {
    @Test
    fun `Brazilian and plain decimals produce exact cents`() {
        assertEquals(123456L, Money.fromDecimal("1.234,56"))
        assertEquals(123456L, Money.fromDecimal("1234,56"))
        assertEquals(123456L, Money.fromDecimal("1234.56"))
        assertEquals(1200L, Money.fromDecimal(" 12 "))
        assertEquals(10L, Money.fromDecimal("0,1"))
        assertEquals(1L, Money.fromDecimal("0,01"))
        assertEquals(-250L, Money.fromDecimal("-2,50"))
        assertEquals(0L, Money.fromDecimal("0"))
    }

    @Test
    fun `malformed values and fractional cents are rejected`() {
        listOf("", " ", "1,001", "1.000", "1.23,45", "1,234.56", "R$ 5,00", "1e3", "NaN")
            .forEach { input ->
                assertThrows("Input: $input", IllegalArgumentException::class.java) {
                    Money.fromDecimal(input)
                }
            }
    }

    @Test
    fun `parsing overflow reports invalid input`() {
        assertThrows(IllegalArgumentException::class.java) {
            Money.fromDecimal("92233720368547758,08")
        }
    }

    @Test
    fun `Firestore monetary boundary preserves allowed cent values`() {
        listOf(0L, 1L, 10L, 29L, 1990L, 123456L, BusinessLimits.MAX_MONEY_CENTS, -123L)
            .forEach { cents -> assertEquals(cents, Money.fromDouble(Money.toDouble(cents))) }
        assertEquals(1990L, Money.fromDouble(19.9))
    }

    @Test
    fun `nonfinite doubles and fractional cents cannot enter calculations`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.001, 0.1 + 0.2)
            .forEach { amount ->
                assertThrows(IllegalArgumentException::class.java) { Money.fromDouble(amount) }
            }
    }
}
