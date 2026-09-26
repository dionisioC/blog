package dev.dionisioc.checkout.domain

/**
 * OCP: the closed abstraction the checkout flow charges against. Adding a way to pay is a new
 * class + one registry entry — never a new branch in tested code.
 *
 * LSP (Form 2 fix): refund is not on PaymentMethod. Only methods that can really refund
 * implement RefundableMethod, so a gift card has no `refund` to call and nothing to throw.
 * Segregating the interface repaired the broken substitutability.
 *
 * `order` is here for idempotency, and that is a DIP point: the key has to identify the
 * *operation*, and only the domain knows that this charge "is" order-1. A gateway can't invent
 * that identity, so the domain hands it down.
 */
interface PaymentMethod {
    fun charge(order: OrderId, amount: Money): PaymentResult

    /**
     * The refund capability, asked of the object instead of read off its type. The base promises
     * only an answer, and `null` keeps that promise: a method has to claim the capability to have
     * it. It survives decoration, too: a wrapper written as `PaymentMethod by inner` forwards the
     * question to the method it wraps, where an `as? RefundableMethod` would stop at the wrapper and
     * quietly turn a card's refund into store credit.
     */
    fun refundable(): RefundableMethod? = null
}

interface RefundableMethod : PaymentMethod {
    fun refund(txn: TxnId)

    override fun refundable(): RefundableMethod = this
}

/**
 * The idempotency key is the order, not the attempt — a fresh UUID per call would key the
 * *invocation* and never dedupe a double-click.
 *
 * Keying the order makes its first answer final. A real PSP replays that answer, decline
 * included, for the key's lifetime, and refuses the key outright if it comes back with a different
 * amount or method. Paying another way after a decline therefore needs a new key (the order plus an
 * attempt number), which this sample leaves out.
 */
private fun request(order: OrderId, amount: Money, method: String) =
    ChargeRequest(amount, method, idempotencyKey = order.value)

class CardPayment(private val gateway: PaymentGateway) : RefundableMethod {
    override fun charge(order: OrderId, amount: Money) =
        gateway.charge(request(order, amount, "card"))

    override fun refund(txn: TxnId) = gateway.refund(txn)
}

class PaypalPayment(private val gateway: PaymentGateway) : RefundableMethod {
    override fun charge(order: OrderId, amount: Money) =
        gateway.charge(request(order, amount, "paypal"))

    override fun refund(txn: TxnId) = gateway.refund(txn)
}

class BizumPayment(private val gateway: PaymentGateway) : RefundableMethod {
    override fun charge(order: OrderId, amount: Money) =
        gateway.charge(request(order, amount, "bizum"))

    override fun refund(txn: TxnId) = gateway.refund(txn)
}

/** Deliberately only a PaymentMethod — gift cards cannot be refunded. */
class GiftCardPayment(private val gateway: PaymentGateway) : PaymentMethod {
    override fun charge(order: OrderId, amount: Money) =
        gateway.charge(request(order, amount, "giftcard"))
}

sealed interface RefundResult

/** The money went back the way it came, reversing [txn]. */
data class Refunded(val txn: TxnId) : RefundResult

/** The method that took [amount] can't take it back — SupportCreditFlow settles it as store credit. */
data class NotRefundable(val amount: Money) : RefundResult

/** The order was settled already, as [settlement]: pressing the button again moves no money. */
data class AlreadySettled(val settlement: RefundResult) : RefundResult

/**
 * The segregation made load-bearing. Refunds enter the domain here, by *order*, never by a bare
 * transaction id: the order recorded which method took the money and which transaction to reverse,
 * so no caller can pair a card's refund with a gift card's charge. This decides a refund is
 * allowed; the gateway port behind the method carries the money movement out.
 *
 * It also decides only once. The order keeps its settlement, so a second press of the refund
 * button comes back AlreadySettled: no second refund, and no second NotRefundable to issue store
 * credit twice. The money moves before the order is saved: a crash in between errs toward asking
 * the PSP twice, which it can refuse, never toward an order that says "refunded" over money that
 * never went back.
 */
class RefundFlow(
    private val orders: OrderRepository,
    private val methods: PaymentMethodRegistry,
) {
    fun refund(id: OrderId): RefundResult {
        val order = requireNotNull(orders.find(id)) { "unknown order: ${id.value}" }
        val settled = order.settlement
        if (settled != null) return AlreadySettled(settled)

        val method = methods.refundable(order.method)
        val settlement = if (method == null) {
            NotRefundable(order.total)
        } else {
            method.refund(order.txn)
            Refunded(order.txn)
        }
        orders.save(order.copy(settlement = settlement))
        return settlement
    }
}

/**
 * OCP's real cost: dispatch by name against a map of factories. Adding a method is one
 * entry at the composition root.
 */
class PaymentMethodRegistry(vararg entries: Pair<String, () -> PaymentMethod>) {
    private val factories: Map<String, () -> PaymentMethod> = entries.toMap()

    init {
        // toMap() keeps the last of two same-named entries; a second "card" line must not win silently.
        val duplicates = entries.groupBy { it.first }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "payment methods registered twice: $duplicates" }
    }

    fun resolve(method: String): PaymentMethod =
        requireNotNull(factories[method]) { "unknown payment method: $method" }.invoke()

    /**
     * The one runtime question left, because methods arrive by name: does this one have the refund
     * capability? It asks the method about the *contract*, never tests for a concrete class, so a
     * new refundable method still needs no edit here — the difference from an `is GiftCardPayment`
     * patch.
     */
    fun refundable(method: String): RefundableMethod? = resolve(method).refundable()
}
