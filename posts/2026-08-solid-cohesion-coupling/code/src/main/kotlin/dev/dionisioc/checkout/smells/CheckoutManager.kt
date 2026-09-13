package dev.dionisioc.checkout.smells

import dev.dionisioc.checkout.domain.Cart
import dev.dionisioc.checkout.domain.Line
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.sum

private const val REWARD_PERCENTAGE_DIVISOR = 10

/**
 * The SRP smell. `total()` answers to Finance, `renderReceipt()` answers to Marketing, and
 * they share the private helper `rewardedItems()`. Change the reward-eligibility rule for
 * Finance and Marketing's receipt silently changes with it — two actors, one class.
 *
 * Note the guard rail that keeps the smell realistic: `total()` charges `grossCharge()` (every item
 * in the cart), so the shared change can *never* undercharge. What it does is shrink the discount
 * (intended, Finance-visible) while quietly rewriting the receipt's rewarded lines (unintended,
 * Marketing). The total moves exactly as Finance asked — the diff looks right — which is what
 * lets the receipt change ride along unnoticed.
 */
class CheckoutManager(
    private val cart: Cart,
    private val rewardGiftWrap: Boolean,
) {
    fun grossCharge(): Money = cart.items.sum()

    fun total(): Money = grossCharge() - loyaltyDiscount()

    fun renderReceipt(): LoyaltyReceipt =
        LoyaltyReceipt(cart.id.value, total(), rewarded = rewardedItems())

    // 10% back on rewarded items
    private fun loyaltyDiscount(): Money = rewardedItems().sum() / REWARD_PERCENTAGE_DIVISOR

    // Shared by the discount (Finance) and the receipt (Marketing) — and that's the trap.
    private fun rewardedItems(): List<Line> =
        cart.items.filter { rewardGiftWrap || !it.giftWrap }
}

data class LoyaltyReceipt(val orderRef: String, val total: Money, val rewarded: List<Line>)
