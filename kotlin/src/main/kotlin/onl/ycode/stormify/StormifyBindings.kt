// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis

package onl.ycode.stormify

import onl.ycode.stormify.StormifyManager.stormify
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * Execute a query, such as an insert, update, or delete, and return the number of rows affected.
 * @param arguments The arguments to pass to the query.
 * @return The number of rows affected.
 */
fun String.executeUpdate(vararg arguments: Any?) = stormify().executeUpdate(this, *arguments)

/**
 * Exevute a read operation and return the result as a single object. If no object is found, null is returned. If more
 * than one object is found, an exception is thrown.
 * @param arguments The arguments to pass to the query.
 * @return The object found, or null if no object is found.
 */
inline fun <reified T : Any> String.readOne(vararg arguments: Any?): T? =
    stormify().readOne(T::class.java, this, *arguments)

/**
 * Execute a read operation and return the result as a list of objects.
 * @param arguments The arguments to pass to the query.
 * @return The list of objects found.
 */
inline fun <reified T : Any> String.read(vararg arguments: Any?): List<T> =
    stormify().read(T::class.java, this, *arguments)

/**
 * Execute a read operation and return the result as a cursor. Use this method when the strategy of parsing the result
 * row by row is preferred, instead of fetching all the results at once. Thus, data are consumed as they are fetched
 * from the database, making it ideal for large data sets.
 *
 * @param arguments The arguments to pass to the query.
 * @param consumer The consumer to process the results.
 * @return The number of rows affected.
 *
 */
inline fun <reified T : Any> String.readCursor(vararg arguments: Any?, crossinline consumer: (T) -> Unit): Int =
    stormify().readCursor(T::class.java, this, { consumer.invoke(it) }, *arguments)

/**
 * Create a new object in the database.
 */
fun <T : Any> T.create(): T = stormify().create(this)

/**
 * Update an object in the database.
 */
fun <T : Any> T.update(): T = stormify().update(this)

/**
 * Delete an object from the database.
 */
fun <T : Any> T.delete() = stormify().delete(this)

/**
 * Populate an object with its details. This means that, if an object has defined only by its ID, with this method, the
 * object will be populated with all its data from the database.
 */
fun <T : Any> T.populate(): T = stormify().populate(this)

/**
 * Find all objects of a specific type. Optionally, a where clause can be provided.
 * @param whereClause The where clause to use in the query.
 * @param arguments The arguments to pass to the query.
 * @return The list of objects found.
 */
inline fun <reified T : Any> findAll(whereClause: String = "", vararg arguments: Any?): List<T> =
    stormify().findAll(T::class.java, whereClause, *arguments)

/**
 * Find an object by its ID.
 * @param id The ID of the object.
 * @return The object found, or null if no object is found.
 */
inline fun <reified T : Any> findById(id: Any): T? = stormify().findById(T::class.java, id)

/**
 * Find the details of a parent object. Use the parent's ID to fetch all objects that are related to the parent.
 * @param property  The name of the reference property in the details class (i.e. the foreign key property name).
 *          If empty, the first field of the parent class that matches the details class will be used.
 *          If more than one field matches, an exception will be thrown.
 * @return The list of objects found.
 */
inline fun <reified T : Any> Any.details(property: String = ""): List<T> =
    stormify().getDetails(this, T::class.java, property)

/**
 * Begin a transaction. All operations that are executed within the transaction will be committed or rolled back as a
 * single unit.
 */
fun transaction(block: () -> Unit) = stormify().transaction(block)

/**
 * Execute a stored procedure.
 * @param params The parameters to pass to the stored procedure.
 * @return The number of rows affected.
 */
fun String.storedProcedure(vararg params: SPParam<*>) = stormify().storedProcedure(this, *params)

/**
 * Define the IN parameter of a stored procedure.
 * @param value The value of the parameter.
 * @return The parameter.
 */
inline fun <reified T : Any> IN(value: T?): SPParam<T> = SPParam.`in`(T::class.java, value)

/**
 * Define the OUT parameter of a stored procedure.'
 * @return The parameter.
 */
inline fun <reified T : Any> OUT(): SPParam<T> = SPParam.out(T::class.java)

/**
 * Define the INOUT parameter of a stored procedure.
 * @param value The value of the parameter.
 * @return The parameter.
 */
inline fun <reified T : Any> INOUT(value: T?): SPParam<T> = SPParam.inout(T::class.java, value)

/**
 * Get the table name of a class.
 */
inline val <reified T : Any> KClass<T>.db get() = stormify().getTableInfo(T::class.java).tableName

/**
 * @suppress
 */
class db<T>(defaultValue: T) : ReadWriteProperty<Any?, T> {
    var prop: T = defaultValue
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        stormify().populate(thisRef)
        return prop
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        stormify().populate(thisRef)
        prop = value
    }
}

/**
 * Define a lazy property that fetches the details of a parent object.
 * @param propertyName The name of the reference property in the details class (i.e. the foreign key property name).
 *         If empty, the first field of the parent class that matches the details class will be used.
 *         If more than one field matches, an exception will be thrown.
 */
inline fun <reified T : Any> lazyDetails(propertyName: String = ""): ReadWriteProperty<Any?, List<T>> {
    return LazyDetailsProperty(T::class.java, propertyName)
}

/**
 * @suppress
 */
class LazyDetailsProperty<T>(private val cls: Class<*>, private val propertyName: String) :
    ReadWriteProperty<Any?, List<T>> {
    private var initialized = false
    private var value: List<T> = emptyList()
    override fun getValue(thisRef: Any?, property: KProperty<*>): List<T> {
        if (!initialized) {
            initialized = true
            @Suppress("UNCHECKED_CAST")
            value = stormify().getDetails(thisRef!!, cls, propertyName) as List<T>
        }
        return value
    }

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: List<T>) {
        initialized = true
        this.value = value
    }
}