package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.GiftCardPayment
import dev.dionisioc.checkout.domain.InsufficientCreditException
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.PaymentMethod
import dev.dionisioc.checkout.domain.RefundFlow
import dev.dionisioc.checkout.domain.RefundableMethod
import dev.dionisioc.checkout.domain.StoreCredit
import dev.dionisioc.checkout.domain.SupportCreditFlow
import dev.dionisioc.checkout.domain.TxnId
import dev.dionisioc.checkout.infrastructure.StripeClient
import dev.dionisioc.checkout.infrastructure.StripePaymentGateway
import dev.dionisioc.checkout.smells.VipStoreCredit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    // Form 2 — the fix: segregation makes a gift-card refund a compile error, not a crash.
    @Test
    fun `only refundable methods carry refund`() {
        val gateway = StripePaymentGateway(StripeClient())
        val card: PaymentMethod = CardPayment(gateway)
        val giftCard: PaymentMethod = GiftCardPayment(gateway)

        assertTrue(card is RefundableMethod)
        assertFalse(giftCard is RefundableMethod)    // RefundFlow can't even accept it
    }

    // The gate, exercised: RefundFlow's parameter type is the fix doing its job.
    @Test
    fun `refund flow reverses a card charge — a gift card cannot even be handed to it`() {
        val client = StripeClient()
        val card = CardPayment(StripePaymentGateway(client))
        val approved = card.charge(OrderId("o1"), Money(500)) as Approved

        RefundFlow().refund(card, TxnId(approved.receipt.orderRef))
        // RefundFlow().refund(GiftCardPayment(...), txn) — does not compile.

        assertTrue(client.list().isEmpty())          // the charge was reversed
    }

    // The other half of that fix: where the money goes when refund isn't reachable at all.
    @Test
    fun `a gift card refund comes back as store credit instead`() {
        val giftCard = GiftCardPayment(StripePaymentGateway(StripeClient()))
        giftCard.charge(OrderId("o2"), Money(2_500))
        // RefundFlow().refund(giftCard, txn) — does not compile: GiftCardPayment isn't refundable.

        val credit = StoreCredit()
        val support = SupportCreditFlow(credit)

        assertEquals(Money(2_500), support.compensate(Money(2_500)))
        assertEquals(Money(2_500), credit.balance())
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
}
