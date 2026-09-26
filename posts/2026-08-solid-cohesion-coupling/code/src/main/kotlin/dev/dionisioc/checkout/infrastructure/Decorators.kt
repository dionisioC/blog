package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.Approved
import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.Declined
import dev.dionisioc.checkout.domain.PaymentGateway
import dev.dionisioc.checkout.domain.PaymentResult
import dev.dionisioc.checkout.domain.Timeout

/**
 * Composition over inheritance. Each wrapper holds the PaymentGateway *port* and forwards
 * everything it doesn't care about via `by inner` — black-box reuse, no dispatch back into us,
 * no fragile base class. Because they all speak the same port, they stack in any order, and the
 * ORDER is a decision made in wiring (see Main.kt), not frozen into a class name.
 *
 * The cost of `by`: it forwards whatever it isn't told about. `refund` passes through all three
 * wrappers unmetered, unretried and without a key, and a method added to the port tomorrow would
 * too — silently. That's the open default; a sealed `when` is the closed one.
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

/**
 * Retries only what might succeed next time. A decline is an answer, not a failure — retrying it
 * just asks the same question again — so only a Timeout goes round again.
 */
class RetryingGateway(
    private val inner: PaymentGateway,
    private val maxAttempts: Int = 3,
) : PaymentGateway by inner {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be at least 1: $maxAttempts" }
    }

    override fun charge(req: ChargeRequest): PaymentResult {
        var result = inner.charge(req)
        var attempts = 1
        while (attempts < maxAttempts && isTransient(result)) {
            result = inner.charge(req)
            attempts++
        }
        return result
    }

    // Exhaustive, no `else`: a fourth PaymentResult has to decide here whether it's worth retrying.
    private fun isTransient(result: PaymentResult): Boolean = when (result) {
        is Approved, is Declined -> false
        Timeout -> true
    }
}

/**
 * Caches only Approved results: replaying a key must never double-charge, but a transient
 * failure must stay retryable — cache it and any retry layer above just replays the failure
 * (CompositionTest proves both directions). A blank key opts out of caching entirely —
 * unrelated charges must never dedupe just because neither carried a key.
 *
 * A key is only answered for the request it was first used for. The same order at a different
 * amount, or with a different method, is refused rather than handed the old approval — otherwise a
 * cart edited after payment would come back "approved" at a total nobody charged. The real PSP
 * refuses a reused key the same way.
 *
 * Scope: a local memory of approvals, for calls made one at a time. A response lost after the PSP
 * approved, or two calls racing past `get` together, both reach the PSP again — which is why the
 * adapter also sends the key to the PSP, where the guarantee that survives those cases lives.
 */
class IdempotentGateway(
    private val inner: PaymentGateway,
    private val keys: KeyStore,
) : PaymentGateway by inner {
    override fun charge(req: ChargeRequest): PaymentResult {
        val stored = keys.get(req.idempotencyKey)
        return when {
            req.idempotencyKey.isBlank() -> inner.charge(req)
            stored == null -> chargeAndCache(req)
            stored.request == req -> stored.result
            else -> Declined("idempotency key reused with different parameters: ${req.idempotencyKey}")
        }
    }

    private fun chargeAndCache(req: ChargeRequest): PaymentResult {
        val result = inner.charge(req)
        if (result is Approved) keys.put(req.idempotencyKey, req, result)
        return result
    }
}
