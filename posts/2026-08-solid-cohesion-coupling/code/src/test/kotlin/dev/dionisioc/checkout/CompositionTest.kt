package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.CheckoutService
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.Declined
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.OrderRepository
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentMethodRegistry
import dev.dionisioc.checkout.domain.PaymentResult
import dev.dionisioc.checkout.domain.PriceCalculator
import dev.dionisioc.checkout.domain.Timeout
import dev.dionisioc.checkout.domain.TxnId
import dev.dionisioc.checkout.infrastructure.IdempotentGateway
import dev.dionisioc.checkout.infrastructure.InMemoryOrderRepository
import dev.dionisioc.checkout.infrastructure.KeyStore
import dev.dionisioc.checkout.infrastructure.Meter
import dev.dionisioc.checkout.infrastructure.MeteredGateway
import dev.dionisioc.checkout.infrastructure.RetryingGateway
import dev.dionisioc.checkout.infrastructure.StripeClient
import dev.dionisioc.checkout.infrastructure.StripePaymentGateway
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompositionTest {

    private val req = ChargeRequest(Money(500), "card", idempotencyKey = "k1")

    // Same parts, different order, different metric.
    @Test
    fun `metered outside retrying counts one logical charge`() {
        val meter = Meter()
        val flaky = FlakyGateway(failuresBeforeSuccess = 2)
        val gateway = MeteredGateway(RetryingGateway(flaky), meter)

        assertTrue(gateway.charge(req) is Approved)
        assertEquals(3, flaky.attempts)               // three attempts, two of them retries
        assertEquals(1, meter.count("charges"))       // but only one logical charge counted
    }

    @Test
    fun `retrying outside metered counts every attempt`() {
        val meter = Meter()
        val flaky = FlakyGateway(failuresBeforeSuccess = 2)
        val gateway = RetryingGateway(MeteredGateway(flaky, meter))

        assertTrue(gateway.charge(req) is Approved)
        assertEquals(3, flaky.attempts)
        assertEquals(3, meter.count("charges"))       // every PSP attempt counted
    }

    // A decline is an answer, not a transient failure: asking again only asks the same question.
    @Test
    fun `a hard decline is never retried`() {
        val declining = DecliningGateway()
        val gateway = RetryingGateway(declining)

        assertTrue(gateway.charge(req) is Declined)
        assertEquals(1, declining.attempts)
    }

    @Test
    fun `a retry layer that would never try is refused at wiring time`() {
        assertFailsWith<IllegalArgumentException> { RetryingGateway(CountingGateway(), maxAttempts = 0) }
    }

    // Retry × idempotency: because only Approved results are cached, a retry layer above the
    // idempotency layer can still recover from a transient failure. (Cache failures too and
    // every retry would just replay the first Timeout — the retry decorator goes inert.)
    @Test
    fun `retrying outside idempotent recovers from a transient failure`() {
        val flaky = FlakyGateway(failuresBeforeSuccess = 1)
        val gateway = RetryingGateway(IdempotentGateway(flaky, KeyStore()))

        assertTrue(gateway.charge(req) is Approved)   // attempt 1 timed out, attempt 2 approved
        assertEquals(2, flaky.attempts)               // the failure was not replayed from cache
    }

    // Idempotency: same key, the PSP is charged once.
    @Test
    fun `idempotent gateway dedupes by key`() {
        val counting = CountingGateway()
        val gateway = IdempotentGateway(counting, KeyStore())

        val first = gateway.charge(req)
        val second = gateway.charge(req)              // a retry carrying the same key
        assertEquals(first, second)                   // the stored result, replayed
        assertEquals(1, counting.calls)               // the PSP saw exactly one charge
    }

    // A key answers only for the request it was first used for. Hand back the old approval for a
    // different amount and the caller records a payment that never happened.
    @Test
    fun `a key reused for a different amount is refused, not answered`() {
        val counting = CountingGateway()
        val gateway = IdempotentGateway(counting, KeyStore())

        gateway.charge(req)
        assertTrue(gateway.charge(req.copy(amount = Money(900))) is Declined)
        assertTrue(gateway.charge(req.copy(method = "paypal")) is Declined)
        assertEquals(1, counting.calls)               // neither reached the PSP
    }

    // The system-level claim, and the one that actually protects a customer. The test above
    // proves the *class* dedupes a key handed to it; this proves the wired flow ever produces
    // a key worth deduping. Key the request on the attempt (a per-call UUID) instead of the
    // order and every assertion above still passes while this one says 2 — a green suite over
    // a double charge.
    @Test
    fun `the same cart checked out twice charges the PSP once`() {
        val psp = StripeClient()
        val gateway = IdempotentGateway(StripePaymentGateway(psp), KeyStore())
        val checkout = CheckoutService(
            PriceCalculator(),
            PaymentMethodRegistry("card" to { CardPayment(gateway) }),
            InMemoryOrderRepository(),
            Clock { Instant.EPOCH },
        )
        val cart = Cart(OrderId("order-1"), listOf(Line("book", Money(2_999))), "card")

        checkout.checkout(cart)
        checkout.checkout(cart)                       // the customer double-clicked Pay

        assertEquals(1, psp.list().size)              // one order, one charge
    }

    // The same order id with a different total: the key is the order, so this is a reuse. Answer
    // it from the cache and the order is recorded at a total the PSP never charged.
    @Test
    fun `a cart edited after payment is refused, and the order still matches the charge`() {
        val psp = StripeClient()
        val orders = InMemoryOrderRepository()
        val checkout = wiredCheckout(psp, orders, Clock { Instant.EPOCH })
        val book = Line("book", Money(2_000))
        val edited = listOf(book, Line("gift wrap", Money(500), giftWrap = true))

        assertTrue(checkout.checkout(Cart(OrderId("order-1"), listOf(book), "card")) is Approved)
        assertTrue(checkout.checkout(Cart(OrderId("order-1"), edited, "card")) is Declined)

        assertEquals(1, psp.list().size)
        assertEquals(psp.list().single().amount, orders.find(OrderId("order-1"))?.total)
    }

    @Test
    fun `a replayed checkout keeps the order it first recorded`() {
        var now = Instant.EPOCH
        val orders = InMemoryOrderRepository()
        val checkout = wiredCheckout(StripeClient(), orders, Clock { now })
        val cart = Cart(OrderId("order-1"), listOf(Line("book", Money(2_999))), "card")

        checkout.checkout(cart)
        now = Instant.EPOCH.plusSeconds(60)
        checkout.checkout(cart)                       // the double-click, a minute later

        assertEquals(Instant.EPOCH, orders.find(cart.id)?.placedAt)
    }

    // The inverse guard: a blank key means "no idempotency claim" — it must never dedupe.
    @Test
    fun `blank idempotency keys are never deduped`() {
        val counting = CountingGateway()
        val gateway = IdempotentGateway(counting, KeyStore())
        val noKey = ChargeRequest(Money(500), "card")  // idempotencyKey defaults to ""

        gateway.charge(noKey)
        gateway.charge(noKey)
        assertEquals(2, counting.calls)               // two unrelated charges, two PSP calls
    }

    // Metered charges are not PSP calls: with the idempotency layer inside the meter — as in
    // Main.kt, whichever way retry and meter are nested — a replay answered from the cache is
    // counted though it never reaches the PSP.
    @Test
    fun `a replay answered from the cache is metered but never reaches the PSP`() {
        val meter = Meter()
        val counting = CountingGateway()
        val gateway = MeteredGateway(IdempotentGateway(counting, KeyStore()), meter)

        gateway.charge(req)
        gateway.charge(req)                           // answered from the cache
        assertEquals(2, meter.count("charges"))       // two charges metered
        assertEquals(1, counting.calls)               // one PSP call
    }

    // The local cache only remembers approvals it saw. If the PSP approved but the response was
    // lost, nothing was cached: the retry reaches the PSP, and only the key sent with it stops a
    // second charge.
    @Test
    fun `a retry after a lost response is deduped by the PSP, not by the cache`() {
        val psp = StripeClient()
        StripePaymentGateway(psp).charge(req)         // approved at the PSP; the response never arrived
        val gateway = IdempotentGateway(StripePaymentGateway(psp), KeyStore())   // so the cache is empty

        assertTrue(gateway.charge(req) is Approved)
        assertEquals(1, psp.list().size)              // one charge, not two
    }

    // Same lost response, but the retry changed the amount. The cache can't catch it (it's empty);
    // the PSP does, the way Stripe refuses a key reused with different parameters.
    @Test
    fun `the PSP refuses a key reused with a different amount`() {
        val psp = StripeClient()
        StripePaymentGateway(psp).charge(req)
        val gateway = IdempotentGateway(StripePaymentGateway(psp), KeyStore())

        assertTrue(gateway.charge(req.copy(amount = Money(900))) is Declined)
        assertEquals(1, psp.list().size)
    }

    private fun wiredCheckout(psp: StripeClient, orders: OrderRepository, clock: Clock): CheckoutService {
        val gateway = IdempotentGateway(StripePaymentGateway(psp), KeyStore())
        val methods = PaymentMethodRegistry("card" to { CardPayment(gateway) })
        return CheckoutService(PriceCalculator(), methods, orders, clock)
    }

    private class FlakyGateway(private val failuresBeforeSuccess: Int) : PaymentGateway {
        var attempts = 0
            private set

        override fun charge(req: ChargeRequest): PaymentResult {
            attempts++
            return if (attempts > failuresBeforeSuccess) Approved(TxnId("ok"), req.amount)
            else Timeout
        }

        override fun refund(txn: TxnId) = Unit
    }

    private class DecliningGateway : PaymentGateway {
        var attempts = 0
            private set

        override fun charge(req: ChargeRequest): PaymentResult {
            attempts++
            return Declined("insufficient funds")
        }

        override fun refund(txn: TxnId) = Unit
    }

    private class CountingGateway : PaymentGateway {
        var calls = 0
            private set

        override fun charge(req: ChargeRequest): PaymentResult {
            calls++
            return Approved(TxnId("txn-$calls"), req.amount)
        }

        override fun refund(txn: TxnId) = Unit
    }
}
