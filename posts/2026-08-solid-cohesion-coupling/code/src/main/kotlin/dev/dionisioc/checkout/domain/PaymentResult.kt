package dev.dionisioc.checkout.domain

/**
 * The closed set from the Open/Closed section: a sealed hierarchy — OCP's deliberate inverse.
 * Add an outcome and every exhaustive `when` over it stops compiling until you handle it. That's
 * the point. An `if (result is Approved)` opts out as quietly as an `else` does, which is why
 * CheckoutService decides with a `when`.
 */
sealed interface PaymentResult

/** The PSP took [amount] under [txn] — the transaction a refund will later reverse. */
data class Approved(val txn: TxnId, val amount: Money) : PaymentResult
data class Declined(val reason: String) : PaymentResult
data object Timeout : PaymentResult

/**
 * The order already has a payment on other terms: its key came back with a different amount,
 * method or cart. Not a decline — a decline means no money moved, and here some may have, so the
 * customer must not be told to simply try again.
 */
data class Conflict(val reason: String) : PaymentResult

/** Exhaustive `when`, no `else` — adding a variant above breaks this at compile time. */
fun record(result: PaymentResult): String = when (result) {
    is Approved -> "approved:${result.txn.value}"
    is Declined -> "declined:${result.reason}"
    Timeout -> "timeout"
    is Conflict -> "conflict:${result.reason}"
}
