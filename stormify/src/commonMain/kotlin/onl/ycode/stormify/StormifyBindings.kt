// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.biglist.ReferencePath
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

@PublishedApi
internal fun stormify(): Stormify =
    Stormify.defaultInstance
        ?: throw SQLException("No default Stormify instance. Call stormify.asDefault() first.")

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
    ((this as? StormifyEntity)?._stormify ?: stormify()).create(this)

/** Updates this entity in the database based on its primary key. */
fun <T : Any> T.update(): T =
    ((this as? StormifyEntity)?._stormify ?: stormify()).update(this)

/** Deletes this entity from the database based on its primary key. */
fun <T : Any> T.delete() =
    ((this as? StormifyEntity)?._stormify ?: stormify()).delete(this)

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

/**
 * Type-safe variant of [details] that accepts an annotation-processor-generated
 * reference path (e.g. `Paths.AuditEntry_.createdBy`) instead of a string.
 */
inline fun <reified D : Any> Any.details(referenceField: ReferencePath): List<D> =
    stormify().getDetails(this, referenceField)

// --- Transactions ---

/**
 * Executes [block] as a transaction on the default [Stormify] instance.
 * Inside the block, any convenience call (top-level extensions, [CRUDTable],
 * lazy-loaders, `PagedList`, …) runs on the transaction's connection.
 * Nested calls become savepoints automatically — see [Stormify.transaction].
 */
fun <R> transaction(block: () -> R): R = stormify().transaction(block)

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

    /**
     * Delegate read: triggers lazy-load from the database on first access when the
     * entity is a [AutoTable] stub (i.e. only its primary key is set and no `db` field
     * has been written yet). Otherwise returns the in-memory value.
     */
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val entity = thisRef as? AutoTable
        if (entity != null && !entity._hasRun.value && !entity._userTouched) {
            // Entity has never been populated from DB, and the user has not written any
            // field on it. This means only the ID is set — a clear lazy-load attempt.
            // If no Stormify instance is available, lazy-load is impossible — fail loudly
            // so the user isn't silently handed the delegate's default value.
            if (entity._stormify == null && Stormify.defaultInstance == null)
                throw SQLException(
                    "Cannot lazy-load property '${property.name}' on ${entity::class.qualifiedName}: " +
                            "no Stormify instance is attached to this entity and no default instance " +
                            "has been set. Either attach an instance or call Stormify.asDefault()."
                )
            entity.populate()
        }
        return prop
    }

    /**
     * Delegate write: marks the owning [AutoTable] as user-touched so it is no longer
     * considered a stub (which prevents a subsequent read from triggering lazy-load),
     * then stores the new value in memory. No immediate database write.
     */
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        val entity = thisRef as? AutoTable
        entity?.populate()
        prop = value
        if (entity != null) entity._userTouched = true
    }
}

/**
 * Property delegate that lazy-loads child (detail) records on first access.
 *
 * When omitted, [propertyName] is resolved automatically by scanning the child type
 * ([T]) for exactly one field whose type matches the declaring parent class. When the
 * child type has **multiple** foreign keys pointing to the same parent type, supply
 * the **Kotlin property name on the child class** (not the database column name) that
 * should be used for the lookup — i.e. the name of the `var`/`val` in the child
 * source, even if it has been remapped with `@DbField(name = "…")` to a different
 * column. The value must be a single field identifier, not a dotted traversal path.
 *
 * ```kotlin
 * class Order : AutoTable() {
 *     var items: List<OrderItem> by lazyDetails()
 * }
 *
 * class User : AutoTable() {
 *     // AuditEntry has both `createdBy: User` and `modifiedBy: User`,
 *     // so we disambiguate by the child-side Kotlin property name:
 *     var createdEntries: List<AuditEntry> by lazyDetails("createdBy")
 *     var modifiedEntries: List<AuditEntry> by lazyDetails("modifiedBy")
 * }
 * ```
 */
inline fun <reified T : Any> lazyDetails(propertyName: String = ""): ReadWriteProperty<Any?, List<T>> =
    LazyDetailsProperty(T::class, propertyName)

/**
 * Type-safe variant of [lazyDetails] that accepts an annotation-processor-generated
 * reference path (e.g. `Paths.AuditEntry_.modifiedBy`) instead of a magic string. The
 * compiler guarantees that the referenced property actually exists on the child type,
 * so typos and renames are caught at build time instead of first query execution.
 *
 * The runtime value passed to the underlying lookup is identical to the plain-string
 * form — `lazyDetails(Paths.AuditEntry_.modifiedBy)` resolves to the same field lookup
 * as `lazyDetails("modifiedBy")`. Only direct reference fields on the child class are
 * accepted; chained paths (e.g. `Paths.AuditEntry_.modifiedBy.somethingElse`) produce
 * a [onl.ycode.stormify.biglist.ScalarPath] which the overload resolution rejects at
 * compile time, and any lingering trailing `.` from the path builder is stripped
 * before the lookup runs.
 *
 * ```kotlin
 * class User : AutoTable() {
 *     @DbField(primaryKey = true)
 *     var id: Int? = null
 *     var createdEntries: List<AuditEntry> by lazyDetails(Paths.AuditEntry_.createdBy)
 *     var modifiedEntries: List<AuditEntry> by lazyDetails(Paths.AuditEntry_.modifiedBy)
 * }
 * ```
 */
inline fun <reified T : Any> lazyDetails(referenceField: ReferencePath): ReadWriteProperty<Any?, List<T>> =
    LazyDetailsProperty(T::class, referenceField.toString().trimEnd('.'))

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
            val s = (thisRef as? StormifyEntity)?._stormify ?: stormify()
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
