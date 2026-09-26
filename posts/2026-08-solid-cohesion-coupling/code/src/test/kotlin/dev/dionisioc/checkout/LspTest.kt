package dev.dionisioc.checkout

import dev.dionisioc.checkout.clients.RefundHandler
import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.CheckoutService
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.GiftCardPayment
import dev.dionisioc.checkout.domain.InsufficientCreditException
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.NotRefundable
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.PaymentMethod
import dev.dionisioc.checkout.domain.PaymentMethodRegistry
import dev.dionisioc.checkout.domain.PriceCalculator
import dev.dionisioc.checkout.domain.RefundFlow
import dev.dionisioc.checkout.domain.RefundableMethod
import dev.dionisioc.checkout.domain.Refunded
import dev.dionisioc.checkout.domain.StoreCredit
import dev.dionisioc.checkout.domain.SupportCreditFlow
import dev.dionisioc.checkout.infrastructure.InMemoryOrderRepository
import dev.dionisioc.checkout.infrastructure.StripeClient
import dev.dionisioc.checkout.infrastructure.StripePaymentGateway
import dev.dionisioc.checkout.smells.VipStoreCredit
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LspTest {

    // Form 1 — the silent wrong answer, seen through the base reference. No exception, no crash:
    // every caller that trusted "balance is never negative" is simply wrong at once.
    @Test
    fun `store credit holds its non-negative invariant — VIP breaks it`() {
        val credit = StoreCredit().apply { topUp(Money(100)) }
        credit.redeem(Money(60))
        assertEquals(Money(40), credit.balance())
        assertFailsWith<InsufficientCreditException> { credit.redeem(Money(1_000)) }
        assertEquals(Money(40), credit.balance())    // state unchanged on refusal

        val vip: StoreCredit = VipStoreCredit().apply { topUp(Money(50)) }
        vip.redeem(Money(200))                       // parent would have thrown
        assertEquals(Money(-150), vip.balance())     // invariant balance >= 0 is gone
    }

    // The exhibit breaks one promise and only one: a negative redemption is still refused, so the
    // negative balance above can't be blamed on anything but the broken invariant.
    @Test
    fun `the VIP subclass keeps the parent's guard`() {
        val vip: StoreCredit = VipStoreCredit().apply { topUp(Money(50)) }

        assertFailsWith<IllegalArgumentException> { vip.redeem(Money(-1_000_000)) }
        assertEquals(Money(50), vip.balance())
    }

    // Form 2 — the fix: segregation leaves a gift card with no `refund` to call at all.
    @Test
    fun `only refundable methods carry refund`() {
        val gateway = StripePaymentGateway(StripeClient())
        val card: PaymentMethod = CardPayment(gateway)
        val giftCard: PaymentMethod = GiftCardPayment(gateway)

        assertTrue(card is RefundableMethod)
        assertFalse(giftCard is RefundableMethod)    // GiftCardPayment(...).refund(txn) doesn't compile
    }

    // The capability question the registry answers, by contract rather than by class.
    @Test
    fun `the registry hands out a refundable view only where the capability exists`() {
        val shop = Shop()

        assertNotNull(shop.methods.refundable("card"))
        assertNull(shop.methods.refundable("giftcard"))
    }

    // The gate, exercised: a refund starts from the order, which knows how it was paid.
    @Test
    fun `refund flow reverses a card order`() {
        val shop = Shop()
        val approved = shop.pay(OrderId("o1"), "card")

        assertEquals(Refunded(approved.txn), shop.refunds.refund(OrderId("o1")))
        assertTrue(shop.psp.list().isEmpty())        // the charge was reversed
    }

    // The other half of that fix: where the money goes when the method can't take it back.
    @Test
    fun `a gift card order comes back as store credit instead`() {
        val shop = Shop()
        val approved = shop.pay(OrderId("o2"), "giftcard")

        val result = assertIs<NotRefundable>(shop.refunds.refund(OrderId("o2")))
        assertEquals(approved.amount, result.amount)
        assertEquals(1, shop.psp.list().size)        // the gift-card charge stands

        val credit = StoreCredit()
        val support = SupportCreditFlow(credit)
        assertEquals(result.amount, support.compensate(result.amount))
        assertEquals(result.amount, credit.balance())
    }

    // The client that moves money gets the use case, not the port: through RefundHandler a
    // gift-card order is exactly as unrefundable as through RefundFlow.
    @Test
    fun `the support desk's refund button goes through the gate`() {
        val shop = Shop()
        shop.pay(OrderId("o3"), "giftcard")

        assertIs<NotRefundable>(RefundHandler(shop.refunds).refund(OrderId("o3")))
        assertEquals(1, shop.psp.list().size)
    }

    // The base keeps its own promise first. Without these guards a plain StoreCredit goes negative
    // with no subclass involved, and Form 1 would be blaming inheritance for a bug the parent had.
    @Test
    fun `the base class rejects negative amounts on every way in`() {
        val credit = StoreCredit().apply { topUp(Money(100)) }

        assertFailsWith<IllegalArgumentException> { credit.topUp(Money(-1)) }
        assertFailsWith<IllegalArgumentException> { credit.redeem(Money(-1)) }
        assertEquals(Money(100), credit.balance())   // state unchanged on refusal
    }

    private class Shop {
        val psp = StripeClient()
        private val gateway = StripePaymentGateway(psp)
        val methods = PaymentMethodRegistry(
            "card" to { CardPayment(gateway) },
            "giftcard" to { GiftCardPayment(gateway) },
        )
        private val orders = InMemoryOrderRepository()
        private val checkout = CheckoutService(PriceCalculator(), methods, orders, Clock { Instant.EPOCH })
        val refunds = RefundFlow(orders, methods)

        fun pay(order: OrderId, method: String): Approved =
            assertIs<Approved>(checkout.checkout(Cart(order, listOf(Line("book", Money(2_500))), method)))
    }
}
