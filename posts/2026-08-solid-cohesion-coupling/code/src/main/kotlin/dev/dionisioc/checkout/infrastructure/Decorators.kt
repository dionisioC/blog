package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.Declined
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentResult

/**
 * Composition over inheritance. Each wrapper holds the PaymentGateway *port* and forwards
 * everything it doesn't care about via `by inner` — black-box reuse, no dispatch back into us,
 * no fragile base class. Because they all speak the same port, they stack in any order, and the
 * ORDER is a decision made in wiring (see Main.kt), not frozen into a class name.
 */
class MeteredGateway(
    private val inner: PaymentGateway,
    private val meter: Meter,
) : PaymentGateway by inner {
    override fun charge(req: ChargeRequest): PaymentResult {
        meter.increment("charges")
        return inner.charge(req)
    }
}

/** Sample-level policy: retries any non-Approved result. Production would retry only transient
 * outcomes (Timeout), never hard declines. */
class RetryingGateway(
    private val inner: PaymentGateway,
    private val maxAttempts: Int = 3,
) : PaymentGateway by inner {
    override fun charge(req: ChargeRequest): PaymentResult {
        var last: PaymentResult = Declined("not attempted")
        repeat(maxAttempts) {
            last = inner.charge(req)
            if (last is Approved) return last
        }
        return last
    }
}

/**
 * Caches only Approved results: replaying a key must never double-charge, but a transient
 * failure must stay retryable — cache it and any retry layer above just replays the failure
 * (CompositionTest proves both directions). A blank key opts out of caching entirely —
 * unrelated charges must never dedupe just because neither carried a key.
 *
 * Scope: a local memory of approvals, for calls made one at a time. A response lost after the PSP
 * approved, or two calls racing past `get` together, both reach the PSP again — which is why the
 * adapter also sends the key to the PSP, where the guarantee that survives those cases lives.
 */
class IdempotentGateway(
    private val inner: PaymentGateway,
    private val keys: KeyStore,
) : PaymentGateway by inner {
    override fun charge(req: ChargeRequest): PaymentResult =
        if (req.idempotencyKey.isBlank()) inner.charge(req)
        else keys.get(req.idempotencyKey) ?: chargeAndCache(req)

    private fun chargeAndCache(req: ChargeRequest): PaymentResult {
        val result = inner.charge(req)
        if (result is Approved) keys.put(req.idempotencyKey, result)
        return result
    }
}
