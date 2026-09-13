package dev.dionisioc.checkout.smells

import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.StoreCredit

/**
 * "Let VIPs spend past their balance." Breaks the domain invariant through a StoreCredit
 * reference: no exception, the balance just goes negative, and every caller that was entitled to
 * assume `balance() >= 0` is wrong at once without one changed line of its own code.
 */
class VipStoreCredit : StoreCredit() {
    override fun redeem(amount: Money) {
        credit -= amount
    }
}
