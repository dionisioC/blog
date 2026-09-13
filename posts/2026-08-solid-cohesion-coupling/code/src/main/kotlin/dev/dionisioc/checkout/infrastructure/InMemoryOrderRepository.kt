package dev.dionisioc.checkout.infrastructure

import dev.dionisioc.checkout.domain.Order
import dev.dionisioc.checkout.domain.OrderId
import dev.dionisioc.checkout.domain.OrderRepository

/**
 * The runnable adapter for the OrderRepository port. In production this is the DynamoDB adapter
 * the article names; here it's an in-memory map so the domain can be tested and the sample run
 * with no database — which is exactly DIP's payoff.
 */
class InMemoryOrderRepository : OrderRepository {
    private val store = mutableMapOf<OrderId, Order>()

    override fun find(id: OrderId): Order? = store[id]

    override fun save(order: Order) {
        store[order.id] = order
    }

    fun all(): List<Order> = store.values.toList()
}
