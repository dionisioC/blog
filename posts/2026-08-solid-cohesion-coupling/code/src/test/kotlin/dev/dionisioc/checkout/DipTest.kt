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
import dev.dionisioc.checkout.domain.TxnId
import dev.dionisioc.checkout.infrastructure.InMemoryOrderRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DipTest {

    // A fake gateway — the whole point of DIP: no PSP, no SDK, no network.
    private class FakeGateway(private val outcome: PaymentResult) : PaymentGateway {
        override fun charge(req: ChargeRequest): PaymentResult = outcome
        override fun refund(txn: TxnId) = Unit
    }

    private fun serviceWith(outcome: PaymentResult, repo: InMemoryOrderRepository, at: Instant) =
        CheckoutService(
            PriceCalculator(),
            PaymentMethodRegistry("card" to { CardPayment(FakeGateway(outcome)) }),
            repo,
            Clock { at },
        )

    private val cart = Cart(OrderId("o1"), listOf(Line("book", Money(2_000))), paymentMethod = "card")

    @Test
    fun `checkout runs against fakes, with no database`() {
        val repo = InMemoryOrderRepository()
        val at = Instant.parse("2026-07-04T00:00:00Z")
        val service = serviceWith(Approved(TxnId("fake"), Money(2_000)), repo, at)

        val result = service.checkout(cart)

        assertTrue(result is Approved)
        assertEquals(1, repo.all().size)
        assertEquals(at, repo.all().first().placedAt)   // the injected Clock port was used
    }

    @Test
    fun `a declined charge is never persisted`() {
        val repo = InMemoryOrderRepository()
        val service = serviceWith(Declined("insufficient funds"), repo, Instant.now())

        assertTrue(service.checkout(cart) is Declined)
        assertEquals(0, repo.all().size)
    }
}
