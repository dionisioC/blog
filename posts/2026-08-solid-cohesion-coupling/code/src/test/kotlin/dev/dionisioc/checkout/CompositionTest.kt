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
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentMethodRegistry
import dev.dionisioc.checkout.domain.PaymentResult
import dev.dionisioc.checkout.domain.PriceCalculator
import dev.dionisioc.checkout.domain.Receipt
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
        assertEquals(3, flaky.attempts)               // it retried three times
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

    // Retry × idempotency: because only Approved results are cached, a retry layer above the
    // idempotency layer can still recover from a transient failure. (Cache failures too and
    // every retry would just replay the first Declined — the retry decorator goes inert.)
    @Test
    fun `retrying outside idempotent recovers from a transient failure`() {
        val flaky = FlakyGateway(failuresBeforeSuccess = 1)
        val gateway = RetryingGateway(IdempotentGateway(flaky, KeyStore()))

        assertTrue(gateway.charge(req) is Approved)   // attempt 1 declined, attempt 2 approved
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

    // Attempts are not PSP calls: with idempotency inside the meter — the article's stack — a
    // replay answered from the cache is counted though it never reaches the PSP.
    @Test
    fun `retrying outside metered counts attempts, cached replays included`() {
        val meter = Meter()
        val counting = CountingGateway()
        val gateway = RetryingGateway(MeteredGateway(IdempotentGateway(counting, KeyStore()), meter))

        gateway.charge(req)
        gateway.charge(req)                           // answered from the cache
        assertEquals(2, meter.count("charges"))       // two attempts metered
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

    private class FlakyGateway(private val failuresBeforeSuccess: Int) : PaymentGateway {
        var attempts = 0
            private set

        override fun charge(req: ChargeRequest): PaymentResult {
            attempts++
            return if (attempts > failuresBeforeSuccess) Approved(Receipt("ok", req.amount))
            else Declined("temporary")
        }

        override fun refund(txn: TxnId) = Unit
    }

    private class CountingGateway : PaymentGateway {
        var calls = 0
            private set

        override fun charge(req: ChargeRequest): PaymentResult {
            calls++
            return Approved(Receipt("txn-$calls", req.amount))
        }

        override fun refund(txn: TxnId) = Unit
    }
}
