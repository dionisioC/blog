package dev.dionisioc.checkout.app

import dev.dionisioc.checkout.domain.BizumPayment
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.CheckoutService
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.GiftCardPayment
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentMethodRegistry
import dev.dionisioc.checkout.domain.PaypalPayment
import dev.dionisioc.checkout.domain.PriceCalculator
import dev.dionisioc.checkout.domain.ReceiptFormatter
import dev.dionisioc.checkout.domain.record
import dev.dionisioc.checkout.infrastructure.IdempotentGateway
import dev.dionisioc.checkout.infrastructure.InMemoryOrderRepository
import dev.dionisioc.checkout.infrastructure.KeyStore
import dev.dionisioc.checkout.infrastructure.Meter
import dev.dionisioc.checkout.infrastructure.MeteredGateway
import dev.dionisioc.checkout.infrastructure.RetryingGateway
import dev.dionisioc.checkout.infrastructure.StripeClient
import dev.dionisioc.checkout.infrastructure.StripePaymentGateway
import java.time.Instant

/**
 * The whole article on one screen. Every abstraction becomes an object here — the only place
 * that's allowed — and nothing here holds business logic, so its single reason to change is
 * "the wiring changed." SRP applied to `main` itself.
 */
fun main() {
    val meter = Meter()

    // Composition: cross-cutting concerns as a decorator stack. Metered outside Retrying = one
    // logical charge per checkout, however many attempts it takes.
    val gateway: PaymentGateway =
        MeteredGateway(
            RetryingGateway(
                IdempotentGateway(StripePaymentGateway(StripeClient()), KeyStore()),
            ),
            meter,
        )

    // Adding a payment method is one plain line here — nothing else moves.
    val methods = PaymentMethodRegistry(
        "card" to { CardPayment(gateway) },
        "paypal" to { PaypalPayment(gateway) },
        "bizum" to { BizumPayment(gateway) },
        "giftcard" to { GiftCardPayment(gateway) },   // claims no refund contract
    )

    // DIP: the domain takes its details from outside. The clock is its own port, satisfied by
    // a lambda — not java.time.Clock leaking inward.
    val orders = InMemoryOrderRepository()   // prod: DynamoOrderRepository — same port, one line
    val checkout = CheckoutService(
        PriceCalculator(),      // SRP: Finance's class
        methods,
        orders,
        Clock { Instant.now() },
    )

    val cart = Cart(
        id = OrderId("order-1"),
        items = listOf(Line("book", Money(2_999)), Line("gift wrap", Money(500), giftWrap = true)),
        paymentMethod = "card",
    )

    val result = checkout.checkout(cart)
    println("checkout result: ${record(result)}  (logical charges metered: ${meter.count("charges")})")

    // SRP: Marketing's class — receipt copy lives here, not in the pricing math or the flow.
    orders.find(cart.id)?.let { println("receipt: ${ReceiptFormatter().format(it)}") }
}
