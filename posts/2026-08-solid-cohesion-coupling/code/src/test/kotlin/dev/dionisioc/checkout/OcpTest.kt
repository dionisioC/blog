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
        // IllegalArgumentException: the name isn't registered. The registry can't tell a typo in
        // cart data from a method the composition root forgot to register — to it, both are an
        // unknown name.
        assertFailsWith<IllegalArgumentException> { registry.resolve("crypto") }
    }

    // One line per method cuts both ways: a second "card" line would silently replace the first.
    @Test
    fun `registering a name twice fails at wiring time`() {
        assertFailsWith<IllegalArgumentException> {
            PaymentMethodRegistry(
                "card" to { CardPayment(gateway) },
                "card" to { BizumPayment(gateway) },
            )
        }
    }
}
