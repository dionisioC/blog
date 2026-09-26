package dev.dionisioc.checkout.domain

class InsufficientCreditException : RuntimeException()

/**
 * LSP, the invariant form — and a working part of the system, not an exhibit: this is where a
 * gift-card refund lands, because GiftCardPayment claims no refund capability and RefundFlow
 * answers its orders NotRefundable.
 *
 * Invariant: `balance()` is never negative, after any sequence of calls. Every caller may assume
 * it without checking — reconciliation, the balance the app displays, the liability line Finance
 * reports — and none of them do. `smells/VipStoreCredit` is what that costs when a subclass
 * breaks it through this reference.
 *
 * The base keeps the invariant on every way in first: both mutators reject negative amounts, and
 * Money's arithmetic throws on overflow instead of wrapping. Without that, a plain StoreCredit could
 * go negative with no subclass involved, and the LSP example would be blaming the wrong class.
 */
open class StoreCredit {
    protected var credit: Money = Money(0)   // invariant: credit >= Money(0), always

    fun balance(): Money = credit            // the invariant, observable by every caller

    fun topUp(amount: Money) {
        require(amount >= Money(0)) { "top-up must not be negative: $amount" }
        credit += amount
    }

    open fun redeem(amount: Money) {
        require(amount >= Money(0)) { "redemption must not be negative: $amount" }
        if (amount > credit) throw InsufficientCreditException()
        credit -= amount
    }
}

/**
 * The support path the segregation forces into existence. A gift card claims no refund
 * capability, so RefundFlow answers its orders NotRefundable: the money cannot go back the way it
 * came, and it comes back as store credit instead.
 *
 * Note what is *not* here: no `if (method is GiftCardPayment)`. RefundFlow asks each method for a
 * capability, never tests for a class, so this flow never learns which methods lack one.
 */
class SupportCreditFlow(private val credit: StoreCredit) {
    /** Issues [amount] as store credit and returns the customer's new balance. */
    fun compensate(amount: Money): Money {
        credit.topUp(amount)
        return credit.balance()
    }
}
