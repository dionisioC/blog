package dev.dionisioc.checkout.domain

/**
 * The closed set from the Open/Closed section: a sealed hierarchy — OCP's deliberate inverse.
 * Add a fourth outcome and every `when` over it stops compiling until you handle it. That's the
 * point.
 */
sealed interface PaymentResult

data class Approved(val receipt: Receipt) : PaymentResult
data class Declined(val reason: String) : PaymentResult
data object Timeout : PaymentResult

/** Exhaustive `when`, no `else` — adding a variant above breaks this at compile time. */
fun record(result: PaymentResult): String = when (result) {
    is Approved -> "approved:${result.receipt.orderRef}"
    is Declined -> "declined:${result.reason}"
    Timeout -> "timeout"
}
