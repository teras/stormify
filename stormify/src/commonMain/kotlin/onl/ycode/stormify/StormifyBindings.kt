// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

fun stormify(): Stormify =
    Stormify.defaultInstance ?: error("No default Stormify instance. Call stormify.asDefault() first.")

// String extensions
fun String.executeUpdate(vararg args: Any?) = stormify().executeUpdate(this, *args)

inline fun <reified T : Any> String.read(vararg args: Any?): List<T> =
    stormify().read(this, *args)

inline fun <reified T : Any> String.readOne(vararg args: Any?): T? =
    stormify().readOne(this, *args)

inline fun <reified T : Any> String.readCursor(vararg args: Any?, noinline consumer: (T) -> Unit): Int =
    stormify().readCursor(this, *args, consumer = consumer)

// Entity extensions
fun <T : Any> T.create(): T =
    ((this as? StormifyEntity)?.`!stormify` ?: stormify()).create(this)

fun <T : Any> T.update(): T =
    ((this as? StormifyEntity)?.`!stormify` ?: stormify()).update(this)

fun <T : Any> T.delete() =
    ((this as? StormifyEntity)?.`!stormify` ?: stormify()).delete(this)

// Query helpers
inline fun <reified T : Any> findAll(whereClause: String = "", vararg args: Any?): List<T> =
    stormify().findAll(whereClause, *args)

inline fun <reified T : Any> findById(id: Any): T? =
    stormify().findById(id)

inline fun <reified D : Any> Any.details(propertyName: String? = null): List<D> =
    stormify().getDetails(this, propertyName)

// Transactions
fun transaction(block: TransactionContext.() -> Unit) = stormify().transaction(block)

// Stored procedure shortcut — IN args pass through raw, OUT/INOUT via Sp.Out/Sp.InOut
// (or the reified helpers spOut<T>() / spInOut(value)).
fun String.storedProcedure(vararg args: Any?) = stormify().procedure(null, this, *args)

// Property delegate — auto-populate on access (for AutoTable)
class db<T>(private val defaultValue: T) : ReadWriteProperty<Any?, T> {
    private var prop: T = defaultValue

    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        (thisRef as? AutoTable)?.populate()
        return prop
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        (thisRef as? AutoTable)?.populate()
        prop = value
    }
}

// Property delegate — lazy-load details (child records)
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
