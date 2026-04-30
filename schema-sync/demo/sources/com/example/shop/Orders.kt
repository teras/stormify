package com.example.shop

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import java.math.BigDecimal
import java.time.Instant

@DbTable(name = "orders")
data class Order(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var customer: User,
    var totalAmount: BigDecimal = BigDecimal.ZERO,
    var taxAmount: BigDecimal = BigDecimal.ZERO,
    var currency: String = "EUR",
    var notes: String? = null,
    var statusOrdinal: Int = 0,
    var placedAt: Instant? = null,
    var shippedAt: Instant? = null,
)

@DbTable(name = "order_items")
data class OrderItem(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    @DbField(name = "order_id")
    var order: Order,
    @DbField(name = "product_sku")
    var product: Product,
    var quantity: Int = 1,
    var unitPrice: BigDecimal = BigDecimal.ZERO,
    var discountPercent: BigDecimal = BigDecimal.ZERO,
)

@DbTable(name = "invoices")
data class Invoice(
    @DbField(primaryKey = true, primarySequence = "invoice_seq")
    var id: Long = 0,
    @DbField(name = "order_id")
    var order: Order,
    var amount: BigDecimal = BigDecimal.ZERO,
    var paid: Boolean = false,
    var issuedAt: Instant? = null,
    var dueDate: java.time.LocalDate? = null,

    @DbField(creatable = false, updatable = false)
    var generatedAt: Instant? = null,
)

@DbTable(name = "payments")
data class Payment(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var invoice: Invoice,
    var method: String = "",
    var transactionRef: String = "",
    var amount: BigDecimal = BigDecimal.ZERO,
    var occurredAt: Instant? = null,
)
