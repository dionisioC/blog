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
 *
 * The branch is an exhaustive `when`, not an `if (result is Approved)`: this is where an outcome
 * leaves an order behind or doesn't, so a new PaymentResult must stop compiling here until someone
 * decides which.
 */
class CheckoutService(
    private val prices: PriceCalculator,
    private val methods: PaymentMethodRegistry,
    private val orders: OrderRepository,
    private val clock: Clock,
) {
    fun checkout(cart: Cart): PaymentResult {
        val total = prices.total(cart)
        return when (val result = methods.resolve(cart.paymentMethod).charge(cart.id, total)) {
            is Approved -> recordOrReplay(cart, total, result)
            // Nothing to record: a decline moved no money, and after a conflict or a timeout this
            // service can't vouch for what did.
            is Declined, is Conflict, Timeout -> result
        }
    }

    /**
     * The key is the order, so an approval for an order that already exists is a replay — but only
     * the *same* payment if it's the same cart. The PSP compares amount and method, never the lines,
     * so a same-price edit (size M swapped for L) comes back approved while the order still says M.
     * Only the domain knows the lines, so the domain checks them.
     */
    private fun recordOrReplay(cart: Cart, total: Money, result: Approved): PaymentResult {
        val existing = orders.find(cart.id)
        return when {
            existing == null -> {
                orders.save(Order(cart.id, total, clock.now(), result.txn, cart.paymentMethod, cart.items))
                result
            }
            // A replayed approval is the same payment: keep the order it already recorded.
            existing.lines == cart.items && existing.method == cart.paymentMethod -> result
            else -> Conflict("order ${cart.id.value} was already paid for a different cart")
        }
    }
}
