package com.example.shop

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import java.time.Instant

@DbTable
data class Review(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var product: Product,
    var author: User,
    var rating: Int = 5,
    var title: String = "",
    var body: String? = null,
    var verified: Boolean = false,
    var postedAt: Instant? = null,
)

@DbTable
data class AuditLog(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var actor: User,
    var action: String = "",
    var entityType: String = "",
    var entityId: String = "",
    var occurredAt: Instant? = null,
)

@DbTable
data class Setting(
    @DbField(primaryKey = true)
    var key: String = "",
    var value: String = "",
    var description: String? = null,
)
