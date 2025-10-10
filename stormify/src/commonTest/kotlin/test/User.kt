// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.DbField

/**
 * Simple test entity representing a user.
 */
data class User(
    @DbField(primaryKey = true, primarySequence = "user_id_seq")
    var id: Int = 0,
    var name: String = "",
    var email: String = "",
    var age: Int? = null
)
