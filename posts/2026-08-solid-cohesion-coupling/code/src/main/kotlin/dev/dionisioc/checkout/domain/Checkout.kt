package dev.dionisioc.checkout.domain

private const val LOYALTY_DISCOUNT_DIVISOR = 10

/**
 * SRP done right — the split CheckoutManager (see the `smells` package) refused to make.
 * PriceCalculator answers to Finance, ReceiptFormatter to Marketing, CheckoutService owns the
 * flow. Three actors, three classes.
 *
 * The part that looks wrong is the point: each actor keeps its *own* copy of "which lines count".
 * Finance's discount skips gift wrap; Marketing's points still cover it. The two rules agreed once,
 * so CheckoutManager shared them, and one department's edit rewrote the other's output. Two copies
 * of a rule that change for different reasons aren't duplication.
 */
class PriceCalculator {
    fun total(cart: Cart): Money = cart.items.sum() - loyaltyDiscount(cart.items)

    // Finance's rule: 10% back on every line except gift wrap.
    private fun loyaltyDiscount(lines: List<Line>): Money =
        lines.filterNot { it.giftWrap }.sum() / LOYALTY_DISCOUNT_DIVISOR
}

class ReceiptFormatter {
    // Marketing's rule: every line earns points, gift wrap included.
    fun format(order: Order): Receipt = Receipt(order.id, order.total, pointsEarnedOn = order.lines)
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
        // A replayed approval is the same payment: keep the order it already recorded.
        if (result is Approved && orders.find(cart.id) == null) {
            orders.save(Order(cart.id, total, clock.now(), result.txn, cart.paymentMethod, cart.items))
        }
        return result
    }
}
