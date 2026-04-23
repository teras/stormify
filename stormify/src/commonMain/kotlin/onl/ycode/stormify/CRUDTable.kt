// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Mixin interface that adds [create], [update], and [delete] convenience methods
 * directly on an entity class, delegating to the entity's attached [Stormify]
 * instance (if any) or the library-wide default.
 *
 * Each call routes through the ambient-transaction registry, so when invoked
 * from inside a `stormify.transaction { }` block the work runs on the
 * transaction's connection and participates in its commit/rollback. Outside
 * a transaction each call runs in auto-commit on a fresh pool connection.
 *
 * ```kotlin
 * class User(var id: Int = 0, var name: String = "") : CRUDTable
 *
 * stormify.asDefault()
 * stormify.transaction {
 *     User(name = "Alice").create()      // uses tx connection
 *     User(1, "Bob").update()            // same tx
 * }
 *
 * User(2, "Carol").create()              // auto-commit on a pool connection
 * ```
 *
 * Especially useful from Java, where Kotlin extension functions are not
 * available — implementing this interface exposes the CRUD methods as
 * plain instance methods that the Java compiler recognises.
 */
interface CRUDTable {
    /** Inserts this entity into the database. */
    fun create() { ((this as? StormifyEntity)?._stormify ?: stormify()).create(this) }

    /** Updates this entity in the database based on its primary key. */
    fun update() { ((this as? StormifyEntity)?._stormify ?: stormify()).update(this) }

    /** Deletes this entity from the database based on its primary key. */
    fun delete() { ((this as? StormifyEntity)?._stormify ?: stormify()).delete(this) }
}
