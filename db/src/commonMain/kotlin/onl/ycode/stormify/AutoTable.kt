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
 *
 * This class supports the method [.autoPopulate] which is able to fetch the data from the database when
 * triggered. It is still important to add the call to [.autoPopulate] before accessing (setting or getting)
 * any of the fields that should be auto-populated.
 *
 *
 * The idea is, to trigger the call to the [.autoPopulate] method early, before accessing any fields, so
 * when the fields are accessed, they are already populated.
 *
 *
 * The primary key fields are required to pre-exist, when the population action takes place. These properties
 * should never be used together with the [.autoPopulate] method.
 *
 *
 * In addition, this class supports the common Object methods [.equals], [.hashCode] and
 * [.toString]. They use the primary keys of the table to calculate the hash code and the equality, while
 * the [.toString] method prints the primary keys and their values together with the class name.
 *
 *
 * An example of a class that extends this class is:
 * <pre>
 * public class MyTable extends AutoTable {
 * private Integer id = null;
 * private String name = null;
 * ...
 *
 * public Integer getId() {
 * return id;
 * }
 *
 * public void setId(Integer id) {
 * this.id = id;
 * }
 *
 * public String getName() {
 * autoPopulate();
 * return name;
 * }
 *
 * public void setName(String name) {
 * autoPopulate();
 * this.name = name;
 * }
 * ...
 * }
</pre> *
 * Note that the [.autoPopulate] method should be called before accessing the fields itself. This method is similar to what JPA
 * does with the lazy loading of an object. The main difference is, this method needs to be defined explicitly by the developer, instead
 * of adding arbitrary code to pojo classes.
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