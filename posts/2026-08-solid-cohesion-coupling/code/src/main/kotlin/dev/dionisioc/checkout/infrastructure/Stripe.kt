package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.DateRange
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentReader
import dev.dionisioc.checkout.domain.PaymentResult
import dev.dionisioc.checkout.domain.Receipt
import dev.dionisioc.checkout.domain.Txn
import dev.dionisioc.checkout.domain.TxnId
import java.time.Instant

/**
 * A stand-in for the vendor SDK — no network, so the sample runs anywhere. The real PSP stamps
 * each transaction server-side; this one takes a Clock so tests can place charges in time.
 */
class StripeClient(private val clock: Clock = Clock { Instant.now() }) {
    private val processed = mutableListOf<Txn>()
    private val chargedByKey = mutableMapOf<String, TxnId>()
    private var issued = 0   // monotonic: sizing off `processed` would reuse an id after a refund

    /**
     * Like the real API, a repeated idempotency key returns the original transaction instead of
     * charging again. This is the guarantee a client-side cache can't give: it still holds when the
     * first response never reached the caller. A blank key opts out.
     */
    fun charge(amount: Money, idempotencyKey: String = ""): TxnId {
        val earlier = chargedByKey[idempotencyKey]
        if (idempotencyKey.isNotBlank() && earlier != null) return earlier

        val id = TxnId("txn-${++issued}")
        processed += Txn(id, amount, at = clock.now())
        if (idempotencyKey.isNotBlank()) chargedByKey[idempotencyKey] = id
        return id
    }

    fun refund(txn: TxnId) {
        processed.removeAll { it.id == txn }
    }

    fun list(): List<Txn> = processed.toList()
}

/**
 * ISP: one adapter, both roles. It moves money (PaymentGateway) and it reports (PaymentReader);
 * each client is handed only the role it plays.
 */
class StripePaymentGateway(private val client: StripeClient) : PaymentGateway, PaymentReader {
    override fun charge(req: ChargeRequest): PaymentResult {
        val txn = client.charge(req.amount, req.idempotencyKey)   // the PSP holds the real guarantee
        return Approved(Receipt(orderRef = txn.value, total = req.amount))
    }

    override fun refund(txn: TxnId) = client.refund(txn)

    /**
     * The range is the contract, not decoration: a PaymentReader that returned everything would
     * hand every caller a silently wrong answer through the port, with nothing thrown to notice.
     * Against the real API the range would go out as query parameters instead of filtering here.
     */
    override fun transactions(range: DateRange): List<Txn> = client.list().filter { it.at in range }
}
