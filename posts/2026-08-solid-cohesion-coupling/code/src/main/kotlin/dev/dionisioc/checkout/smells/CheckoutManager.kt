package dev.dionisioc.checkout.smells

import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.sum

private const val REWARD_PERCENTAGE_DIVISOR = 10

/**
 * The SRP smell. `total()` answers to Finance, which runs a loyalty discount; `renderReceipt()`
 * answers to Marketing, which runs a points program and prints the lines that earned points. Two
 * programs that happened to cover the same lines, so they share the private helper
 * `rewardedItems()`. Finance stops discounting gift wrap, and Marketing's receipt silently stops
 * awarding points on it too — two actors, one class. (`rewardGiftWrap` stands in for that edit.)
 *
 * Note the guard rail that keeps the smell realistic: `total()` still charges every item in the
 * cart, so the shared change can *never* undercharge. What it does is shrink the discount
 * (intended, Finance-visible) while quietly rewriting the receipt's points lines (unintended,
 * Marketing). The total moves exactly as Finance asked — the diff looks right — which is what
 * lets the receipt change ride along unnoticed.
 */
class CheckoutManager(
    private val cart: Cart,
    private val rewardGiftWrap: Boolean,
) {
    fun total(): Money = cart.items.sum() - loyaltyDiscount()

    fun renderReceipt(): LoyaltyReceipt =
        LoyaltyReceipt(cart.id.value, total(), rewarded = rewardedItems())

    // 10% back on rewarded items
    private fun loyaltyDiscount(): Money = rewardedItems().sum() / REWARD_PERCENTAGE_DIVISOR

    // Finance's discount and Marketing's points, one list — and that's the trap.
    private fun rewardedItems(): List<Line> =
        cart.items.filter { rewardGiftWrap || !it.giftWrap }
}

data class LoyaltyReceipt(val orderRef: String, val total: Money, val rewarded: List<Line>)
