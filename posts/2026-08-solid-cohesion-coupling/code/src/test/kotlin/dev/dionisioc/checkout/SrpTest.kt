package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.Order
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.PriceCalculator
import dev.dionisioc.checkout.domain.ReceiptFormatter
import dev.dionisioc.checkout.domain.TxnId
import dev.dionisioc.checkout.smells.CheckoutManager
import java.time.Instant
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

        // The bill charges every item (€25.00) both ways; only the 10% discount moves.
        assertEquals(Money(2_250), rewarded.total())      // 2 500 − 10% of 2 500
        assertEquals(Money(2_300), notRewarded.total())   // 2 500 − 10% of 2 000

        // Finance's intended effect: dropping gift wrap from rewards shrinks the discount.
        assertTrue(notRewarded.total() > rewarded.total())

        // Marketing's silent break: the very same edit rewrote the receipt's rewarded lines.
        assertTrue(rewarded.renderReceipt().rewarded.any { it.giftWrap })
        assertFalse(notRewarded.renderReceipt().rewarded.any { it.giftWrap })
    }

    // The split: Finance's rule and Marketing's rule are two copies, so they can disagree. The
    // exact disagreement CheckoutManager can't express — no discount on gift wrap, points on it
    // anyway — is just the two classes each doing their own job.
    @Test
    fun `in the split, Finance's rule change never reaches Marketing's receipt`() {
        val total = PriceCalculator().total(cart)
        assertEquals(Money(2_300), total)                 // gift wrap earns no discount...

        val order = Order(cart.id, total, Instant.EPOCH, TxnId("t1"), cart.paymentMethod, cart.items)
        val receipt = ReceiptFormatter().format(order)
        assertTrue(receipt.pointsEarnedOn.any { it.giftWrap })   // ...and still earns points
    }
}
