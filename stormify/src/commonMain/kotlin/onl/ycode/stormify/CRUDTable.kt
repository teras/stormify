// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Mixin interface that adds [create], [update], and [delete] convenience methods
 * directly on an entity class, delegating to the default [Stormify] instance.
 *
 * These methods always resolve the **default Stormify instance** (the one
 * registered via `asDefault()`) and therefore always run in auto-commit mode —
 * they **do not** participate in a surrounding `transaction { }` block even when
 * invoked from inside one. If you call `entity.create()` inside a transaction,
 * the insert is committed immediately on a separate connection and will not be
 * rolled back with the enclosing transaction.
 *
 * Prefer the receiver-style extensions available on `TransactionContext` (Kotlin)
 * or the explicit method-style API on `TransactionContextJ` (Java), both of
 * which correctly route the work through the active transaction's connection.
 */
@Deprecated(
    message = "CRUDTable routes every call through the default Stormify instance in " +
            "auto-commit mode, silently bypassing any surrounding transaction. Use the " +
            "receiver-style extensions on TransactionContext (Kotlin) or explicit " +
            "TransactionContextJ methods (Java) instead.",
    level = DeprecationLevel.WARNING,
)
interface CRUDTable {
    /** Inserts this entity into the database. */
    fun create() { ((this as? StormifyEntity)?._stormify ?: stormify()).create(this) }

    /** Updates this entity in the database based on its primary key. */
    fun update() { ((this as? StormifyEntity)?._stormify ?: stormify()).update(this) }

    /** Deletes this entity from the database based on its primary key. */
    fun delete() { ((this as? StormifyEntity)?._stormify ?: stormify()).delete(this) }
}
