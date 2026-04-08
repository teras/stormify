// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Mixin interface that adds [create], [update], and [delete] convenience methods
 * directly on an entity class, delegating to the default [Stormify] instance.
 */
interface CRUDTable {
    /** Inserts this entity into the database. */
    fun create() { ((this as? StormifyEntity)?.`!stormify` ?: stormify()).create(this) }

    /** Updates this entity in the database based on its primary key. */
    fun update() { ((this as? StormifyEntity)?.`!stormify` ?: stormify()).update(this) }

    /** Deletes this entity from the database based on its primary key. */
    fun delete() { ((this as? StormifyEntity)?.`!stormify` ?: stormify()).delete(this) }
}
