package com.example.shop

import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import java.time.Instant
import java.util.UUID

@DbTable
data class User(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var username: String = "",
    var email: String = "",
    var passwordHash: String = "",
    var bio: String? = null,
    var active: Boolean = true,
    var emailVerified: Boolean = false,
    var createdAt: Instant? = null,
    var lastLoginAt: Instant? = null,

    @Transient
    var sessionToken: String = "",
)

@DbTable
data class Address(
    @DbField(primaryKey = true, autoIncrement = true)
    var id: Long = 0,
    var owner: User,
    var street: String = "",
    var city: String = "",
    var state: String = "",
    var zip: String = "",
    var country: String = "",
    var isPrimary: Boolean = false,
    var validatedAt: Instant? = null,
)

@DbTable
data class Wishlist(
    @DbField(primaryKey = true)
    var id: UUID = UUID.randomUUID(),
    var owner: User,
    var name: String = "",
    var publicShared: Boolean = false,
    var createdAt: Instant? = null,
)
