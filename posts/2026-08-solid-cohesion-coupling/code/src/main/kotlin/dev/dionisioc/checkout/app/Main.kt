package dev.dionisioc.checkout.app

import dev.dionisioc.checkout.clients.RefundHandler
import dev.dionisioc.checkout.clients.StatementsScreen
import dev.dionisioc.checkout.domain.AlreadySettled
import dev.dionisioc.checkout.domain.BizumPayment
import dev.dionisioc.checkout.domain.CardPayment
import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.CheckoutService
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.DateRange
import dev.dionisioc.checkout.domain.GiftCardPayment
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.NotRefundable
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.OrderRepository
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentMethodRegistry
import dev.dionisioc.checkout.domain.PaypalPayment
import dev.dionisioc.checkout.domain.PriceCalculator
import dev.dionisioc.checkout.domain.ReceiptFormatter
import dev.dionisioc.checkout.domain.RefundFlow
import dev.dionisioc.checkout.domain.Refunded
import dev.dionisioc.checkout.domain.StoreCredit
import dev.dionisioc.checkout.domain.SupportCreditFlow
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
    val clock = Clock { Instant.now() }   // the domain's own port; java.time.Clock never leaks inward

    // ISP: one Stripe adapter, two roles. The money-moving role gets the decorator stack below;
    // the read-only role gets the adapter itself, since a statement needs no retries and no keys.
    val stripe = StripePaymentGateway(StripeClient(clock))

    // Composition: cross-cutting concerns as a decorator stack. The ORDER is a
    // decision, and it lives here, in wiring, not in a class hierarchy.
    val gateway: PaymentGateway =
        MeteredGateway(
            RetryingGateway(
                IdempotentGateway(stripe, KeyStore()),
            ),
            meter,
        )

    // OCP's real cost, concentrated: one plain line per payment method.
    val methods = PaymentMethodRegistry(
        "card" to { CardPayment(gateway) },
        "paypal" to { PaypalPayment(gateway) },
        "bizum" to { BizumPayment(gateway) },
        "giftcard" to { GiftCardPayment(gateway) },   // claims no refund capability
    )

    // DIP: details handed to a domain that has never heard of them.
    val orders = InMemoryOrderRepository()   // prod: DynamoOrderRepository, same port, one line
    val checkout = CheckoutService(
        PriceCalculator(),                   // SRP: Finance's class, alone
        methods,
        orders,
        clock,                               // the same clock the PSP stand-in stamps with
    )

    // The clients, each holding only the role it plays.
    val statements = StatementsScreen(stripe)                     // ISP: reads, can't move money
    val refundDesk = RefundHandler(RefundFlow(orders, methods))   // LSP: refunds only through the gate
    val storeCredit = SupportCreditFlow(StoreCredit())            // where a gift card's refund lands

    demo(checkout, orders, statements, refundDesk, storeCredit)
    println("logical charges metered: ${meter.count("charges")}")
}

/**
 * A script playing a customer and the support desk through the entry points `main` wired: a card
 * order and a gift-card order, the statement, then both refunds and the card's button pressed twice.
 */
private fun demo(
    checkout: CheckoutService,
    orders: OrderRepository,
    statements: StatementsScreen,
    refundDesk: RefundHandler,
    storeCredit: SupportCreditFlow,
) {
    val byCard = Cart(
        id = OrderId("order-1"),
        items = listOf(Line("book", Money(2_999)), Line("gift wrap", Money(500), giftWrap = true)),
        paymentMethod = "card",
    )
    val byGiftCard = Cart(
        id = OrderId("order-2"),
        items = listOf(Line("notebook", Money(1_200))),
        paymentMethod = "giftcard",
    )
    for (cart in listOf(byCard, byGiftCard)) {
        println("checkout ${cart.id.value}: ${record(checkout.checkout(cart))}")
    }

    // SRP: Marketing's class — receipt copy lives here, not in the pricing math or the flow.
    orders.find(byCard.id)?.let { println("receipt: ${ReceiptFormatter().format(it)}") }
    println("on the statement: ${statements.transactionCount(DateRange(Instant.MIN, Instant.MAX))}")

    for (id in listOf(byCard.id, byGiftCard.id, byCard.id)) {
        val outcome = when (val refund = refundDesk.refund(id)) {
            is Refunded -> "reversed ${refund.txn.value}"
            is NotRefundable -> "not refundable, store credit balance now ${storeCredit.compensate(refund.amount)}"
            is AlreadySettled -> "already settled, nothing moved"
        }
        println("refund ${id.value}: $outcome")
    }
}
