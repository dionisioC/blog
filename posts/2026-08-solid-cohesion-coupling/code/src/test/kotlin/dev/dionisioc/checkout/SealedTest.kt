package dev.dionisioc.checkout

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.Declined
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.Receipt
import dev.dionisioc.checkout.domain.Timeout
import dev.dionisioc.checkout.domain.record
import kotlin.test.Test
import kotlin.test.assertEquals

class SealedTest {

    // The exhaustive `when` in `record` maps every variant with no `else`. If a fourth
    // PaymentResult is ever added, this file stops compiling until it's handled.
    @Test
    fun `exhaustive when maps every result variant`() {
        assertEquals("approved:r1", record(Approved(Receipt("r1", Money(100)))))
        assertEquals("declined:funds", record(Declined("funds")))
        assertEquals("timeout", record(Timeout))
    }
}
