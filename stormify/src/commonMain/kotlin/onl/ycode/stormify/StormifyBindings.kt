// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/** Returns the [default Stormify instance][Stormify.defaultInstance], or throws if none has been set. */
fun stormify(): Stormify =
    Stormify.defaultInstance ?: error("No default Stormify instance. Call stormify.asDefault() first.")

// --- String extensions ---

/** Executes this SQL string as an UPDATE/INSERT/DELETE and returns the number of affected rows. */
fun String.executeUpdate(vararg args: Any?) = stormify().executeUpdate(this, *args)

/** Executes this SQL string as a SELECT and returns all results as a list. */
inline fun <reified T : Any> String.read(vararg args: Any?): List<T> =
    stormify().read(this, *args)

/** Executes this SQL string as a SELECT and returns a single result, or null if none found. */
inline fun <reified T : Any> String.readOne(vararg args: Any?): T? =
    stormify().readOne(this, *args)

/** Executes this SQL string as a SELECT and processes results row-by-row via [consumer]. Returns the row count. */
inline fun <reified T : Any> String.readCursor(vararg args: Any?, noinline consumer: (T) -> Unit): Int =
    stormify().readCursor(this, *args, consumer = consumer)

// --- Entity extensions ---

/** Inserts this entity into the database and returns it with generated values populated. */
fun <T : Any> T.create(): T =
    ((this as? StormifyEntity)?.`!stormify` ?: stormify()).create(this)

/** Updates this entity in the database based on its primary key. */
fun <T : Any> T.update(): T =
    ((this as? StormifyEntity)?.`!stormify` ?: stormify()).update(this)

/** Deletes this entity from the database based on its primary key. */
fun <T : Any> T.delete() =
    ((this as? StormifyEntity)?.`!stormify` ?: stormify()).delete(this)

// --- Query helpers ---

/** Finds all entities of type [T], optionally filtered by a [whereClause]. */
inline fun <reified T : Any> findAll(whereClause: String = "", vararg args: Any?): List<T> =
    stormify().findAll(whereClause, *args)

/** Finds a single entity of type [T] by its primary key [id], or null if not found. */
inline fun <reified T : Any> findById(id: Any): T? =
    stormify().findById(id)

/** Returns all detail (child) entities of type [D] related to this parent through a foreign key. */
inline fun <reified D : Any> Any.details(propertyName: String? = null): List<D> =
    stormify().getDetails(this, propertyName)

// --- Transactions ---

/** Executes a block within a database transaction with automatic commit/rollback. */
fun transaction(block: TransactionContext.() -> Unit) = stormify().transaction(block)

// --- Stored procedures ---

/** Calls the stored procedure named by this string. OUT/INOUT parameters use [Sp.Out]/[Sp.InOut]. */
fun String.procedure(vararg args: Any?) = stormify().procedure(this, *args)

/**
 * Property delegate that triggers auto-population from the database on first access.
 * Use with [AutoTable] entities: properties delegated to `db` will lazily load
 * the entity's data when read or written for the first time.
 *
 * ```kotlin
 * class User : AutoTable() {
 *     var name: String by db("")
 * }
 * ```
 */
class db<T>(private val defaultValue: T) : ReadWriteProperty<Any?, T> {
    private var prop: T = defaultValue

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val entity = thisRef as? AutoTable
        if (entity != null && !entity.`!hasRun`.value && !entity.`!userTouched`) {
            // Entity has never been populated from DB, and the user has not written any
            // field on it. This means only the ID is set — a clear lazy-load attempt.
            // If no Stormify instance is available, lazy-load is impossible — fail loudly
            // so the user isn't silently handed the delegate's default value.
            if (entity.`!stormify` == null && Stormify.defaultInstance == null)
                throw SQLException(
                    "Cannot lazy-load property '${property.name}' on ${entity::class.qualifiedName}: " +
                            "no Stormify instance is attached to this entity and no default instance " +
                            "has been set. Either attach an instance or call Stormify.asDefault()."
                )
            entity.populate()
        }
        return prop
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val entity = thisRef as? AutoTable
        entity?.populate()
        prop = value
        if (entity != null) entity.`!userTouched` = true
    }
}

/**
 * Property delegate that lazy-loads child (detail) records on first access.
 * Optionally specify [propertyName] when the detail class has multiple foreign key fields
 * referencing different parent types.
 *
 * ```kotlin
 * class Order : AutoTable() {
 *     var items: List<OrderItem> by lazyDetails()
 * }
 * ```
 */
inline fun <reified T : Any> lazyDetails(propertyName: String = ""): ReadWriteProperty<Any?, List<T>> =
    LazyDetailsProperty(T::class, propertyName)

@PublishedApi
internal class LazyDetailsProperty<T : Any>(
    private val cls: KClass<T>,
    private val propertyName: String
) : ReadWriteProperty<Any?, List<T>> {
    private var initialized = false
    private var value: List<T> = emptyList()

    override fun getValue(thisRef: Any?, property: KProperty<*>): List<T> {
        if (!initialized) {
            initialized = true
            val s = (thisRef as? StormifyEntity)?.`!stormify` ?: stormify()
            @Suppress("UNCHECKED_CAST")
            value = s.getDetails(null, thisRef!!, cls, propertyName.ifBlank { null })
        }
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<T>) {
        initialized = true
        this.value = value
    }
}
