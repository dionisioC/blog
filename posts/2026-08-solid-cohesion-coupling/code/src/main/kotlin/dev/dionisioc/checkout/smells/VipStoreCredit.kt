package dev.dionisioc.checkout.smells

import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.StoreCredit

/**
 * "Let VIPs spend past their balance." Breaks the domain invariant through a StoreCredit
 * reference: no exception, the balance just goes negative, and every caller that was entitled to
 * assume `balance() >= 0` is wrong at once without one changed line of its own code.
 *
 * It keeps the parent's guard on purpose, so the invariant is the *only* promise it breaks.
 * Without it, `redeem(Money(-1_000_000))` would mint credit, a second bug unrelated to LSP.
 */
class VipStoreCredit : StoreCredit() {
    override fun redeem(amount: Money) {
        require(amount >= Money(0)) { "redemption must not be negative: $amount" }
        credit -= amount
    }
}
