package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.smells.CheckoutManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SrpTest {

    private val cart = Cart(
        OrderId("c1"),
        listOf(Line("book", Money(2_000)), Line("gift wrap", Money(500), giftWrap = true)),
        paymentMethod = "card",
    )

    @Test
    fun `changing the reward rule shifts discount and receipt, but never the charged total`() {
        val rewarded = CheckoutManager(cart, rewardGiftWrap = true)
        val notRewarded = CheckoutManager(cart, rewardGiftWrap = false)

        // The bill charges every item, both ways — the shared change cannot undercharge.
        assertEquals(Money(2_500), rewarded.grossCharge())
        assertEquals(Money(2_500), notRewarded.grossCharge())

        // Finance's intended effect: dropping gift wrap from rewards shrinks the discount.
        assertTrue(notRewarded.total() > rewarded.total())

        // Marketing's silent break: the very same edit rewrote the receipt's rewarded lines.
        assertTrue(rewarded.renderReceipt().rewarded.any { it.giftWrap })
        assertFalse(notRewarded.renderReceipt().rewarded.any { it.giftWrap })
    }
}
