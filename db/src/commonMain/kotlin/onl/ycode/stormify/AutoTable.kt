// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import onl.ycode.logger.LogManager


/**
 * A common abstract class to support auto-populating of fields.
 *
 * This class supports the [populate] method which fetches data from the database when triggered.
 * It is important to call [populate] before accessing any fields that should be auto-populated.
 *
 * The idea is to trigger the call to the [populate] method early, before accessing any fields, so
 * when the fields are accessed, they are already populated.
 *
 * The primary key fields are required to pre-exist when the population action takes place. These properties
 * should never be used together with the [populate] method.
 *
 * In addition, this class overrides [equals], [hashCode] and [toString]. They use the primary keys
 * of the table to calculate the hash code and equality, while [toString] prints the primary keys and
 * their values together with the class name.
 *
 * ## Example
 *
 * ```kotlin
 * class MyTable : AutoTable() {
 *     var id: Int? = null
 *     var name: String? = null
 *
 *     fun getName(): String? {
 *         populate()
 *         return name
 *     }
 *
 *     fun setName(value: String?) {
 *         populate()
 *         name = value
 *     }
 * }
 * ```
 *
 * Note that the [populate] method should be called before accessing the fields. This method is similar to what JPA
 * does with lazy loading of an object. The main difference is that this method needs to be called explicitly by the
 * developer, instead of relying on bytecode manipulation.
 */
abstract class AutoTable {
    private val lock = SynchronizedObject()
    private val hasRun = atomic(false)

    internal var `!stormify`: Stormify? = null

    /**
     * Populates the fields of this object. The ID field should already have been set.
     *
     * This method could run at most once.
     */
    fun populate() {
        if (hasRun.value) return
        val ctr = `!stormify` ?: return LogManager.getLogger(AutoTable::class)
            .error("Stormify is not set for class ${this::class.qualifiedName}.")
        synchronized(lock) {
            if (!hasRun.value) {
                ctr.populate(this)
                hasRun.value = true
            }
        }
    }

    /**
     * Marks this object as already populated, so no further population needs to be done.
     */
    fun markPopulated() {
        hasRun.value = true
    }

    fun attach(stormify: Stormify) {
        `!stormify` = stormify
        tableInfo.getRestValues(this).forEach { if (it is AutoTable) it.attach(stormify) }
    }

    private val tableInfo by lazy { TableInfo.retrieve(this::class) }

    override fun toString() = toString(tableInfo)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AutoTable) return false
        if (this.tableInfo.type != other.tableInfo.type) return false
        val idThis = tableInfo.getIdValues(this)
        val idOther = tableInfo.getIdValues(other)
        return idThis == idOther
    }

    override fun hashCode(): Int {
        val primaryKeys = tableInfo.getIdValues(this)
        return if (primaryKeys.size == 1)
            primaryKeys[0].hashCode()
        else
            primaryKeys.map { it.hashCode() }.reduce { acc, hash -> acc xor hash }
    }
}

internal fun <T : Any> T.toString(tableInfo: TableInfo<T>) = "${this::class.simpleName}[${
    tableInfo.idNames.zip(tableInfo.getIdValues(this))
        .joinToString(", ") { (name, value) -> "$name=$value" }
}]"