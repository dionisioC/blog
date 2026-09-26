package dev.dionisioc.checkout.clients

import dev.dionisioc.checkout.domain.DateRange
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.PaymentReader
import dev.dionisioc.checkout.domain.RefundFlow
import dev.dionisioc.checkout.domain.RefundResult

/**
 * The *driving* side — the callers of the system. These sit outside `domain/` on purpose, so this
 * package's arrow points inward exactly like `infrastructure/`: it imports the domain, the domain
 * has never heard of it. A Screen living inside the package that promises zero outward imports
 * would be the DIP section's easiest counter-example.
 *
 * StatementsScreen is ISP's read-only role view: it holds only a PaymentReader, so a reporting
 * screen has no `charge`/`refund` in scope and can't move money by accident. That narrows access
 * rather than proving it: a cast to PaymentGateway would still reach the other role of the same
 * object. (The money-moving role, PaymentGateway, is held by the payment methods in the domain.)
 */
class StatementsScreen(private val payments: PaymentReader) {
    fun transactionCount(range: DateRange): Int = payments.transactions(range).size
}

/**
 * The support desk's refund button. A client that moves money gets the *use case*, never the
 * gateway port: holding PaymentGateway here would let it refund any transaction and walk straight
 * past RefundFlow, the one place that decides whether a refund is allowed.
 */
class RefundHandler(private val refunds: RefundFlow) {
    fun refund(order: OrderId): RefundResult = refunds.refund(order)
}
