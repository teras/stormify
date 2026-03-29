// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

interface CRUDTable {
    fun create() { ((this as? StormifyEntity)?.`!stormify` ?: stormify()).create(this) }
    fun update() { ((this as? StormifyEntity)?.`!stormify` ?: stormify()).update(this) }
    fun delete() { ((this as? StormifyEntity)?.`!stormify` ?: stormify()).delete(this) }
}
