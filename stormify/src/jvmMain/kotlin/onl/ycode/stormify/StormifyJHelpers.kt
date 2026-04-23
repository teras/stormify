// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:JvmName("StormifyJHelpers")
@file:Suppress("unused")

package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.biglist.ReferencePath
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * Static helpers for Java callers that operate on the library-wide default
 * [StormifyJ] wrapper (set via [StormifyJ.asDefault]). Mirror of the Kotlin
 * top-level functions in `StormifyBindings.kt`, so Java code can stay terse:
 *
 * ```java
 * import static onl.ycode.stormify.StormifyJHelpers.*;
 *
 * transaction(() -> {
 *     User u = create(new User("Alice"));
 *     create(new Profile(u.getId()));
 *     executeUpdate("DELETE FROM audit WHERE id = ?", 42);
 * });
 * ```
 *
 * Every helper throws [IllegalStateException] if no default [StormifyJ] has been
 * registered yet — call `new StormifyJ(ds).asDefault()` once during startup.
 */
private inline fun <R> withDefault(block: (StormifyJ) -> R): R {
    val s = StormifyJ.getDefault() ?: throw IllegalStateException(
        "No default StormifyJ instance. Call `new StormifyJ(dataSource).asDefault()` " +
                "during application startup before using StormifyJHelpers."
    )
    return block(s)
}

/** Runs [block] as a transaction on the default [StormifyJ]; see [StormifyJ.transaction]. */
@Throws(SQLException::class)
@JvmName("transaction")
fun transactionJ(block: Runnable) = withDefault { it.transaction(block) }

/** Returning variant of [transactionJ] — see [StormifyJ.transaction]. */
@Throws(SQLException::class)
@JvmName("transaction")
fun <R> transactionJ(block: Supplier<R>): R = withDefault { it.transaction(block) }

/** Inserts [item] on the default [StormifyJ]; see [StormifyJ.create]. */
@Throws(SQLException::class)
fun <T : Any> create(item: T): T = withDefault { it.create(item) }

/** Batch INSERT on the default [StormifyJ]; see [StormifyJ.create]. */
@Throws(SQLException::class)
@JvmName("createAll")
fun <T : Any> create(items: Collection<T>): List<T> = withDefault { it.create(items) }

/** UPDATE on the default [StormifyJ]; see [StormifyJ.update]. */
@Throws(SQLException::class)
fun <T : Any> update(updatedItem: T): T = withDefault { it.update(updatedItem) }

/** Batch UPDATE on the default [StormifyJ]; see [StormifyJ.update]. */
@Throws(SQLException::class)
@JvmName("updateAll")
fun <T : Any> update(items: Collection<T>): List<T> = withDefault { it.update(items) }

/** DELETE on the default [StormifyJ]; see [StormifyJ.delete]. */
@Throws(SQLException::class)
fun delete(deletedItem: Any) = withDefault { it.delete(deletedItem) }

/** Batch DELETE on the default [StormifyJ]; see [StormifyJ.delete]. */
@Throws(SQLException::class)
@JvmName("deleteAll")
fun <T : Any> delete(items: Collection<T>) = withDefault { it.delete(items) }

/** Refreshes [entity] from the database via the default [StormifyJ]; see [StormifyJ.populate]. */
@Throws(SQLException::class)
fun <T : Any> populate(entity: T): T = withDefault { it.populate(entity) }

/** SELECT on the default [StormifyJ] returning a list; see [StormifyJ.read]. */
@Throws(SQLException::class)
fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?): List<T> =
    withDefault { it.read(baseClass, query, *params) }

/** SELECT on the default [StormifyJ] returning the first row or null; see [StormifyJ.readOne]. */
@Throws(SQLException::class)
fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?): T? =
    withDefault { it.readOne(baseClass, query, *params) }

/** Streaming SELECT on the default [StormifyJ]; see [StormifyJ.readCursor]. */
@Throws(SQLException::class)
fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?): Int =
    withDefault { it.readCursor(baseClass, query, consumer, *params) }

/** INSERT / UPDATE / DELETE on the default [StormifyJ]; see [StormifyJ.executeUpdate]. */
@Throws(SQLException::class)
fun executeUpdate(query: String, vararg params: Any?): Int =
    withDefault { it.executeUpdate(query, *params) }

/** Looks up an entity by primary key on the default [StormifyJ]; see [StormifyJ.findById]. */
@Throws(SQLException::class)
fun <T : Any> findById(baseClass: Class<T>, id: Any): T? =
    withDefault { it.findById(baseClass, id) }

/** `SELECT * FROM <table> <whereClause>` on the default [StormifyJ]; see [StormifyJ.findAll]. */
@JvmOverloads
@Throws(SQLException::class)
fun <T : Any> findAll(baseClass: Class<T>, whereClause: String = "", vararg arguments: Any?): List<T> =
    withDefault { it.findAll(baseClass, whereClause, *arguments) }

/** Parent → details lookup on the default [StormifyJ]; see [StormifyJ.getDetails]. */
@JvmOverloads
@Throws(SQLException::class)
fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null): List<T> =
    withDefault { it.getDetails(parent, detailsClass, propertyName) }

/** Type-safe parent → details lookup on the default [StormifyJ]; see [StormifyJ.getDetails]. */
@Throws(SQLException::class)
fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, referenceField: ReferencePath): List<T> =
    withDefault { it.getDetails(parent, detailsClass, referenceField) }

/** Stored procedure call on the default [StormifyJ]; see [StormifyJ.procedure]. */
@Throws(SQLException::class)
fun procedure(name: String, vararg args: Any?) = withDefault { it.procedure(name, *args) }
