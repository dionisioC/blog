package dev.dionisioc.checkout.clients

import dev.dionisioc.checkout.domain.DateRange
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentReader
import dev.dionisioc.checkout.domain.TxnId

/**
 * ISP in action: two clients, two role-views of the same gateway instance.
 *
 * These sit outside `domain/` on purpose. They are the *driving* side — the callers that use the
 * ports — so this package's arrow points inward exactly like `infrastructure/`: it imports the
 * domain, the domain has never heard of it. A Screen living inside the package that promises zero
 * outward imports would be the DIP section's easiest counter-example.
 *
 * StatementsScreen holds only a PaymentReader — it has no `charge`/`refund` in scope, so a
 * reporting screen can't move money by accident. That narrows access rather than proving it: a
 * cast to PaymentGateway would still reach the other role of the same object. RefundHandler holds the
 * PaymentGateway role because refunding is its whole job. (RefundFlow decides whether a refund
 * is allowed; this client is the mechanism that carries it out through the port.)
 */
class StatementsScreen(private val payments: PaymentReader) {
    fun transactionCount(range: DateRange): Int = payments.transactions(range).size
}

class RefundHandler(private val payments: PaymentGateway) {
    fun refund(txn: TxnId) = payments.refund(txn)
}
