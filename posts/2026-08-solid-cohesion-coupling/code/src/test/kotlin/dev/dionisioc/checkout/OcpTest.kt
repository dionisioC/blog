package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.BizumPayment
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.PaymentMethodRegistry
import dev.dionisioc.checkout.infrastructure.StripeClient
import dev.dionisioc.checkout.infrastructure.StripePaymentGateway
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OcpTest {

    private val gateway = StripePaymentGateway(StripeClient())
    private val registry = PaymentMethodRegistry(
        "card" to { CardPayment(gateway) },
        "bizum" to { BizumPayment(gateway) },   // adding a method was one entry — no branch edit
    )

    @Test
    fun `dispatches by name with no type switch`() {
        assertTrue(registry.resolve("card") is CardPayment)
        assertTrue(registry.resolve("bizum") is BizumPayment)
    }

    @Test
    fun `unknown method fails fast`() {
        // IllegalArgumentException, not IllegalState: the registry is fine, the argument isn't —
        // so callers can tell bad cart data from a misconfigured composition root.
        assertFailsWith<IllegalArgumentException> { registry.resolve("crypto") }
    }
}
