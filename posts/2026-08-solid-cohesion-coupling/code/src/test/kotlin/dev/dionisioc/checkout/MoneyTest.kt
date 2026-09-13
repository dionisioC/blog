package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {

    // Regression: `%.2f` over an integer-divided Long once crashed Main.kt's receipt line.
    @Test
    fun `formats cents as euros with two decimals`() {
        assertEquals("€29.99", Money(2_999).toString())
        assertEquals("€5.00", Money(500).toString())
        assertEquals("€0.09", Money(9).toString())
    }

    // A wrapped Long is a silently wrong balance; an exception at least announces itself.
    @Test
    fun `arithmetic throws on overflow instead of wrapping`() {
        assertFailsWith<ArithmeticException> { Money(Long.MAX_VALUE) + Money(1) }
        assertFailsWith<ArithmeticException> { Money(Long.MIN_VALUE) - Money(1) }
    }
}
