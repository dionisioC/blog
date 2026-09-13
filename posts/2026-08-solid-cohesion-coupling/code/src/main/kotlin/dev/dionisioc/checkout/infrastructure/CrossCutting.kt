package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.PaymentResult

/** A minimal metrics sink — enough to make "metered twice" an observable number. */
class Meter {
    private val counts = mutableMapOf<String, Int>()

    fun increment(name: String, by: Int = 1) {
        counts[name] = (counts[name] ?: 0) + by
    }

    fun count(name: String): Int = counts[name] ?: 0
}

/** Stores approved charge results by idempotency key so a replayed request can't charge twice. */
class KeyStore {
    private val seen = mutableMapOf<String, PaymentResult>()

    fun get(key: String): PaymentResult? = seen[key]

    fun put(key: String, result: PaymentResult) {
        seen[key] = result
    }
}
