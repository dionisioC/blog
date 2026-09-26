package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.Declined
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.Timeout
import dev.dionisioc.checkout.domain.TxnId
import dev.dionisioc.checkout.domain.record
import kotlin.test.Test
import kotlin.test.assertEquals

class SealedTest {

    // The exhaustive `when` in `record` maps every variant with no `else`. If a fourth
    // PaymentResult is ever added, `record` (and RetryingGateway's retry policy) stop compiling
    // until it's handled — the build fails before this test can run.
    @Test
    fun `exhaustive when maps every result variant`() {
        assertEquals("approved:txn-1", record(Approved(TxnId("txn-1"), Money(100))))
        assertEquals("declined:funds", record(Declined("funds")))
        assertEquals("timeout", record(Timeout))
    }
}
