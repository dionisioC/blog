package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.infrastructure.StripeClient
import kotlin.test.Test
import kotlin.test.assertEquals

class StripeClientTest {

    // Regression: transaction ids were derived from the list size, so refunding one and charging
    // again minted an id that was already in use — and a later refund removed both transactions.
    @Test
    fun `transaction ids are never reused after a refund`() {
        val client = StripeClient()

        val first = client.charge(Money(100))
        val second = client.charge(Money(200))
        client.refund(first)
        val third = client.charge(Money(300))

        assertEquals(listOf(second, third), client.list().map { it.id })
        assertEquals(3, listOf(first, second, third).distinct().size)

        client.refund(third)
        assertEquals(listOf(second), client.list().map { it.id })   // only the one asked for
    }
}
