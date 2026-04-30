package com.example.shop

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@DbTable(name = "categories")
data class Category(
    @DbField(primaryKey = true)
    var id: UUID = UUID.randomUUID(),
    var name: String = "",
    var description: String? = null,
    var parent: Category? = null,
    var sortOrder: Int = 0,
)

@DbTable(name = "products")
data class Product(
    @DbField(primaryKey = true)
    var sku: String = "",
    var name: String = "",
    var description: String? = null,
    var category: Category,
    var price: BigDecimal = BigDecimal.ZERO,
    var weightKg: BigDecimal = BigDecimal.ZERO,
    var inStock: Boolean = true,
    var launchDate: LocalDate? = null,
    var lastUpdatedAt: Instant? = null,
)

@DbTable(name = "tags")
data class Tag(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Int = 0,
    var label: String = "",
    var color: String = "#000000",
)

@DbTable(name = "product_tags")
data class ProductTag(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var product: Product,
    var tag: Tag,
)
