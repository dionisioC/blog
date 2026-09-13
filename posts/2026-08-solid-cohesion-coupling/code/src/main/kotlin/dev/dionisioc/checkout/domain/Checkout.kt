package dev.dionisioc.checkout.domain

/**
 * SRP done right — the split CheckoutManager (see the `smells` package) refused to make.
 * PriceCalculator answers to Finance, ReceiptFormatter to Marketing, CheckoutService owns the
 * flow. Three actors, three classes.
 */
class PriceCalculator {
    fun total(cart: Cart): Money = cart.items.sum()
}

class ReceiptFormatter {
    fun format(order: Order): Receipt = Receipt(order.id.value, order.total)
}

/**
 * The one use case, end to end. Depends only on ports it owns (DIP), resolves a payment method
 * by name (OCP), and branches on a sealed result (exhaustiveness). Zero infrastructure imports.
 */
class CheckoutService(
    private val prices: PriceCalculator,
    private val methods: PaymentMethodRegistry,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    fun checkout(cart: Cart): PaymentResult {
        val total = prices.total(cart)
        val result = methods.resolve(cart.paymentMethod).charge(cart.id, total)
        if (result is Approved) {
            orders.save(Order(cart.id, total, clock.now()))
        }
        return result
    }
}
