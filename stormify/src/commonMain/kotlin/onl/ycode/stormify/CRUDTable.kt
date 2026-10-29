// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Mixin interface that adds [create], [update], and [delete] convenience methods
 * directly on an entity class, delegating to the entity's attached [Stormify]
 * instance, else the instance of the enclosing transaction, else the default.
 * [T] is the entity class itself, so [create] and [update] return it, as
 * `stormify.create(entity)` does.
 *
 * Each call routes through the ambient-transaction registry, so when invoked
 * from inside a `stormify.transaction { }` block the work runs on the
 * transaction's connection and participates in its commit/rollback. Outside
 * a transaction each call runs in auto-commit on a fresh pool connection.
 *
 * ```kotlin
 * class User(var id: Int = 0, var name: String = "") : CRUDTable<User>
 *
 * stormify.asDefault()
 * stormify.transaction {
 *     val alice = User(name = "Alice").create()      // uses tx connection
 *     User(1, "Bob").update()                        // same tx
 * }
 * ```
 *
 * ```java
 * public class User implements CRUDTable<User> { ... }
 *
 * User alice = new User("Alice").create();
 * ```
 *
 * Especially useful from Java, where Kotlin extension functions are not
 * available: implementing this interface exposes the CRUD methods as
 * plain instance methods that the Java compiler recognises.
 */
interface CRUDTable<T : CRUDTable<T>> {
    /** Inserts this entity into the database and returns it, with a generated key if any. */
    @Suppress("UNCHECKED_CAST")
    fun create(): T = owner().create(this as T)

    /** Updates this entity in the database based on its primary key and returns it. */
    @Suppress("UNCHECKED_CAST")
    fun update(): T = owner().update(this as T)

    /** Deletes this entity from the database based on its primary key. */
    fun delete() { owner().delete(this) }

    private fun owner(): Stormify = (this as? StormifyEntity)?._stormify ?: stormify()
}
