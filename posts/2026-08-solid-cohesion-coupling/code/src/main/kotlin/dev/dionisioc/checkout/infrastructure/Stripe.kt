package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.Clock
import dev.dionisioc.checkout.domain.DateRange
import dev.dionisioc.checkout.domain.Declined
import dev.dionisioc.checkout.domain.Money
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentReader
import dev.dionisioc.checkout.domain.PaymentResult
import dev.dionisioc.checkout.domain.Txn
import dev.dionisioc.checkout.domain.TxnId
import java.time.Instant

/** What the vendor SDK throws when an idempotency key comes back with different parameters. */
class IdempotencyKeyReusedException(val key: String) :
    RuntimeException("idempotency key reused with different parameters: $key")

/**
 * A stand-in for the vendor SDK — no network, so the sample runs anywhere. The real PSP stamps
 * each transaction server-side; this one takes a Clock so tests can place charges in time.
 */
class StripeClient(private val clock: Clock = Clock { Instant.now() }) {
    private val processed = mutableListOf<Txn>()
    private val chargedByKey = mutableMapOf<String, Charged>()
    private var issued = 0   // monotonic: sizing off `processed` would reuse an id after a refund

    private data class Charged(val amount: Money, val method: String, val txn: TxnId)

    /**
     * Like the real API, a repeated idempotency key returns the original transaction instead of
     * charging again. This is the guarantee a client-side cache can't give: it still holds when the
     * first response never reached the caller. Also like the real API, a key reused with a different
     * amount or method is refused, not answered. A blank key opts out.
     *
     * One difference: the real API replays a *failed* first attempt too, for the key's lifetime.
     * This stand-in never fails, so there is nothing to replay.
     */
    fun charge(amount: Money, method: String = "", idempotencyKey: String = ""): TxnId {
        if (idempotencyKey.isBlank()) return record(amount)
        val first = chargedByKey.getOrPut(idempotencyKey) { Charged(amount, method, record(amount)) }
        if (first.amount != amount || first.method != method) throw IdempotencyKeyReusedException(idempotencyKey)
        return first.txn
    }

    fun refund(txn: TxnId) {
        processed.removeAll { it.id == txn }
    }

    fun list(): List<Txn> = processed.toList()

    private fun record(amount: Money): TxnId {
        val id = TxnId("txn-${++issued}")
        processed += Txn(id, amount, at = clock.now())
        return id
    }
}

/**
 * ISP: one adapter, both roles. It moves money (PaymentGateway) and it reports (PaymentReader);
 * each client is handed only the role it plays.
 */
class StripePaymentGateway(private val client: StripeClient) : PaymentGateway, PaymentReader {
    override fun charge(req: ChargeRequest): PaymentResult =
        try {
            // the PSP holds the real guarantee, so the key goes with the request
            val txn = client.charge(req.amount, req.method, req.idempotencyKey)
            Approved(txn, req.amount)
        } catch (e: IdempotencyKeyReusedException) {
            // The vendor's exception stops here: the domain only ever sees its own sealed result.
            Declined("idempotency key reused with different parameters: ${e.key}")
        }

    override fun refund(txn: TxnId) = client.refund(txn)

    /**
     * The range is the contract, not decoration: a PaymentReader that returned everything would
     * hand every caller a silently wrong answer through the port, with nothing thrown to notice.
     * Against the real API the range would go out as query parameters instead of filtering here.
     */
    override fun transactions(range: DateRange): List<Txn> = client.list().filter { it.at in range }
}
