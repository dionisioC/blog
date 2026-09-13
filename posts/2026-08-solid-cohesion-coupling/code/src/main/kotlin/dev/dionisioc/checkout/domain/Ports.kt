package dev.dionisioc.checkout.domain

import java.time.Instant

/**
 * DIP: the domain OWNS these interfaces. Infrastructure implements them and depends on this
 * module; this module has never heard of Stripe, Dynamo, or the JVM clock.
 *
 * ISP also lives here: one adapter (Stripe) may implement both PaymentGateway and
 * PaymentReader, but a client that only reports never sees the money-moving methods.
 */
interface PaymentGateway {
    fun charge(req: ChargeRequest): PaymentResult
    fun refund(txn: TxnId)
}

interface PaymentReader {
    fun transactions(range: DateRange): List<Txn>
}

interface OrderRepository {
    fun find(id: OrderId): Order?
    fun save(order: Order)
}

/** A `fun interface` so the composition root can satisfy it with `Clock { Instant.now() }`. */
fun interface Clock {
    fun now(): Instant
}
