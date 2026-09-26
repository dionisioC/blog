package dev.dionisioc.checkout

import dev.dionisioc.checkout.clients.StatementsScreen
import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.DateRange
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.infrastructure.StripeClient
import dev.dionisioc.checkout.infrastructure.StripePaymentGateway
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class IspTest {

    private val start: Instant = Instant.parse("2026-07-01T00:00:00Z")

    private fun days(n: Long): Instant = start.plus(n, ChronoUnit.DAYS)

    @Test
    fun `one adapter, two role-views of the same object`() {
        val stripe = StripePaymentGateway(StripeClient(Clock { start }))
        val screen = StatementsScreen(stripe)     // sees only PaymentReader — cannot move money
        val card = CardPayment(stripe)            // sees only PaymentGateway — cannot read statements
        val today = DateRange(start, days(1))

        assertEquals(0, screen.transactionCount(today))

        val approved = card.charge(OrderId("o1"), Money(500)) as Approved
        assertEquals(1, screen.transactionCount(today))    // the reader view sees the write

        card.refund(approved.txn)                          // the gateway view reverses it
        assertEquals(0, screen.transactionCount(today))    // same underlying object
    }

    // The range is part of the contract: a reader that answered with everything would be a silent
    // wrong answer through the port — the same failure as a subtype quietly breaking its promise,
    // committed by an adapter instead.
    @Test
    fun `the reader answers for the range it was asked, not for everything`() {
        var now = start
        val stripe = StripePaymentGateway(StripeClient(Clock { now }))
        val screen = StatementsScreen(stripe)

        stripe.charge(ChargeRequest(Money(500), "card"))   // stamped on day 0
        now = days(2)
        stripe.charge(ChargeRequest(Money(700), "card"))   // stamped on day 2

        assertEquals(1, screen.transactionCount(DateRange(start, days(1))))
        assertEquals(1, screen.transactionCount(DateRange(days(2), days(3))))
        assertEquals(2, screen.transactionCount(DateRange(start, days(3))))
        assertEquals(0, screen.transactionCount(DateRange(days(3), days(4))))
    }

    // Half-open, so a day-boundary transaction is counted once across two adjacent ranges.
    @Test
    fun `adjacent ranges never double-count a transaction`() {
        val stripe = StripePaymentGateway(StripeClient(Clock { days(1) }))
        val screen = StatementsScreen(stripe)

        stripe.charge(ChargeRequest(Money(500), "card"))   // stamped exactly on the boundary

        assertEquals(0, screen.transactionCount(DateRange(start, days(1))))   // `to` is exclusive
        assertEquals(1, screen.transactionCount(DateRange(days(1), days(2)))) // `from` inclusive
    }
}
