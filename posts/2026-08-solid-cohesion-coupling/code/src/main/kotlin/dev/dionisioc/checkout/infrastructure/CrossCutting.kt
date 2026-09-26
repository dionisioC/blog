package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.ChargeRequest
import dev.dionisioc.checkout.domain.PaymentResult

/** A minimal metrics sink — enough to make "metered twice" an observable number. */
class Meter {
    private val counts = mutableMapOf<String, Int>()

    fun increment(name: String, by: Int = 1) {
        counts[name] = (counts[name] ?: 0) + by
    }

    fun count(name: String): Int = counts[name] ?: 0
}

/**
 * Stores approved charges by idempotency key — the request *with* its result, so a replay can be
 * checked against what the key was first used for, not just answered.
 */
class KeyStore {
    private val seen = mutableMapOf<String, Stored>()

    fun get(key: String): Stored? = seen[key]

    fun put(key: String, request: ChargeRequest, result: PaymentResult) {
        seen[key] = Stored(request, result)
    }

    data class Stored(val request: ChargeRequest, val result: PaymentResult)
}
