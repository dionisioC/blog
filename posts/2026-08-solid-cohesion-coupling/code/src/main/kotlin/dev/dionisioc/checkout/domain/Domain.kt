package dev.dionisioc.checkout.domain

import java.time.Instant
import java.util.Locale

private const val CENTS_IN_ONE_EURO = 100.0

/**
 * A value type over integer cents — the only safe way to hold money. It carries just the
 * operators this domain uses: summing a cart, subtracting a discount, taking a percentage with
 * integer division, and comparing a redemption against a balance. Addition and subtraction throw on
 * overflow instead of wrapping — a wrapped Long would be a silently wrong balance.
 */
@JvmInline
value class Money(val cents: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(cents, other.cents))
    operator fun minus(other: Money) = Money(Math.subtractExact(cents, other.cents))
    operator fun div(divisor: Int) = Money(cents / divisor)
    override fun compareTo(other: Money) = cents.compareTo(other.cents)
    override fun toString() = "€%.2f".format(Locale.ROOT, cents / CENTS_IN_ONE_EURO)
}

fun Iterable<Line>.sum(): Money = fold(Money(0)) { total, line -> total + line.price }

@JvmInline
value class OrderId(val value: String)

@JvmInline
value class TxnId(val value: String)

data class Line(val name: String, val price: Money, val giftWrap: Boolean = false)

data class Cart(val id: OrderId, val items: List<Line>, val paymentMethod: String)

data class Order(val id: OrderId, val total: Money, val placedAt: Instant)

data class Receipt(val orderRef: String, val total: Money)

data class ChargeRequest(val amount: Money, val method: String, val idempotencyKey: String = "")

data class Txn(val id: TxnId, val amount: Money, val at: Instant)

data class DateRange(val from: Instant, val to: Instant) {
    /**
     * Half-open — `from` inclusive, `to` exclusive — so two adjacent ranges never double-count the
     * same transaction. The rule lives here rather than in each adapter: `transactions(range)`
     * promises the transactions *in that range*, and every implementation owes the same answer.
     */
    operator fun contains(at: Instant): Boolean = at >= from && at < to
}
