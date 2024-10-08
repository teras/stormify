// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import kotlinx.atomicfu.atomic
import onl.ycode.stormify.NativeBigInteger
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.TypeUtils
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * A list that loads its elements in pages. The list is divided into pages of
 * fixed size and only the current page is loaded in memory. The list is
 * accessed as if it were a single list.
 *
 * @param <T> The type of elements in the list
 */
class PagedList<T : Any>(val classType: KClass<T>, private val stormify: Stormify) : AbstractList<T>() {
    private val info: TableInfo<T> = TableInfo.retrieve(classType)
    private val tableCounter = atomic(0)
    private val custom = mutableListOf<CustomReference>()
    private val where = mutableListOf<MutableList<FilterReference>>()
    private val sort = mutableListOf<SortReference>()
    private val root = NodeTable("", "", classType, { invalidate() }, null)
    private var treeIsDirty = true

    /**
     * Sets the page size of the list. The page size is the number of elements loaded
     * into memory at a time. The default page size is 15.
     */
    var pageSize = 15
        set(value) {
            require(value >= 1) { "Page size must be at least 1" }
            if (field != value) invalidate()
            field = value
        }

    /**
     * Set the constraint clause that will be used in the query. This clause is appended
     * to the WHERE keyword.

     * If the constraint is on the main table, it is optional to use the name of the table
     * as a prefix for the column name. If other tables are required, either use constructs
     * like `REFERENCE_ID IN (...)`or might get a [CustomReference] to get a reference to any
     * table in the table tree.
     */
    fun setConstraints(query: String, vararg args: Any) {
        constraintClause = query.trim()
        this.constraintArgs = args
        invalidate()
    }

    private var constraintClause = ""
    private var constraintArgs: Array<out Any> = emptyArray()


    /**
     * Set an entity as selected. The entity must be part of the list. The selected entity will
     * appear first in the list, followed by the rest of the entities.
     */
    var selected: T? = null
        set(value) {
            if (field != value) {
                selectedID = if (value == null) null
                else TypeUtils.castTo(NativeBigInteger::class, info.getIdValues(value).first(), stormify)
                invalidate()
                field = value
            }
        }


    private var selectedID: NativeBigInteger? = null

    /**
     * Check if the list will have unique results.
     */
    var isDistinct = false
        set(value) {
            if (field != value) invalidate()
            field = value
        }

    private var fragment: List<T>? = null
    private var lowBound = 0 // inclusize
    private var upperBound = 0 // exclusive


    private var _size: Int? = null
    override val size: Int
        get() = _size ?: run {
            val (query, arguments) = constraintPart
            (stormify.readOne<Int>("SELECT ${distinctPart}COUNT(*) FROM ${tablesPart}$query", arguments) ?: 0)
                .also { _size = it }
        }

    override fun get(index: Int): T {
        return ensurePage(index)[index - lowBound]
    }

    private val distinctPart: String
        get() = if (isDistinct) "DISTINCT " else ""

    private val sortingPart: String
        get() {
            resolveCurrentTree()
            if (sort.isEmpty())
                return (stormify.sqlDialect.orderByIdDialect(info.singleKeyName, selectedID)
                    ?.let { "$it, " } ?: "") +
                        info.table + "." + info.singleKeyName
            return sort.joinToString(", ") { "${it.node.columnHandler}${if (!it.isAscending) " DESC" else ""}" }
        }

    private val tablesPart: String
        get() {
            resolveCurrentTree()
            val out = StringBuilder(info.table)
            root.getForeignKeys(out)
            return out.toString()
        }

    private val constraintPart: Pair<String, List<Any>>
        get() {
            resolveCurrentTree()
            val andOut = StringBuilder()
            if (constraintClause.isNotEmpty())
                andOut.append(constraintClause)
            val constraintArgs = constraintArgs.toMutableList()

            for (group in where) {
                val orOut = StringBuilder()
                var found = 0
                for (value in group)
                    if (value.appendConstraint(orOut, constraintArgs))
                        found++
                if (found > 0) {
                    if (andOut.isNotEmpty())
                        andOut.append(" AND ")
                    andOut.append(if (found > 1) "($orOut)" else orOut)
                }
            }
            return (if (andOut.isEmpty()) "" else " WHERE $andOut") to constraintArgs
        }

    private fun ensurePage(index: Int): List<T> {
        if (index < 0 || index >= size)
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        if (index < lowBound || index >= upperBound)
            fragment = null
        return fragment ?: run {
            val page = index / pageSize
            lowBound = page * pageSize
            upperBound = min(size, (page + 1) * pageSize)
            val (query, arguments) = constraintPart
            stormify.read(
                null, classType, stormify.sqlDialect.queryFormatter(
                    distinctPart,
                    tablesPart,
                    query,
                    sortingPart,
                    lowBound,
                    upperBound
                ), arguments
            ).also { fragment = it }
        }
    }

    /**
     * Invalidates the current fragment of the list. The fragment is reloaded when the list is accessed.
     */
    internal fun invalidate() {
        fragment = null
        _size = null
        treeIsDirty = true
    }

    private fun resolveCurrentTree() {
        if (!treeIsDirty) return
        treeIsDirty = false
        root.deactivate()
        root.activate()
        for (list in where)
            for (t in list)
                if (t.valueExists())
                    t.node.activate()
        for (t in sort)
            if (t.isActivated)
                t.node.activate()
        for (t in custom)
            if (t.isActivated)
                t.node.activate()
    }


    /**
     * Adds a custom reference to the list. The custom reference is based on the given field path.
     *
     * @param fields The field path, based on the object property names. It is possible to use foreign keys, which
     * will be automatically joined in the SQL query. The last field in the path must be a table.
     * @return The custom reference, which can be used to access the specifix table.
     */
    fun addCustomReference(vararg fields: String): CustomReference {
        val result = CustomReference(findNode(fields))
        custom.add(result)
        invalidate()
        return result
    }

    /**
     * Adds a sorting order to the list. The sorting order is based on the given field path.
     *
     * @param ascending True if the sorting order is ascending, false if it is descending
     * @param fields    The field path, based on the object property names. It is possible to use foreign keys, which
     * will be automatically joined in the SQL query. The last field in the path must be a primitive.
     * @return The table reference, which can be used to access the specific table.
     */
    fun addSortingOrder(ascending: Boolean, vararg fields: String) {
        val ref = SortReference(findNode(fields))
        ref.isAscending = ascending
        sort.add(ref)
        invalidate()
    }

    /**
     * Clears the sorting order of the list.
     */
    fun clearSortingOrder() {
        sort.clear()
        invalidate()
    }

    /**
     * Adds a filter to the list. The filter is based on the given field path.
     * Each filter is combined with the previous filters using the AND operator.
     *
     * @param fields The field path, based on the object property names. It is possible to use foreign keys, which
     * will be automatically joined in the SQL query. The last field in the path must be a primitive.
     * @return The filter reference, which can be used to set the filter value and manipulate the specific filter.
     */
    fun addFilter(vararg fields: String) = FilterReference(findNode(fields)).also { ref ->
        where += mutableListOf<FilterReference>().also { grp -> grp += ref }
        invalidate()
    }

    /**
     * Adds a filter to the list. The filter is based on the given field path.
     * This filter is combined with the previous filter using the OR operator.
     *
     * @param fields The field path, based on the object property names. It is possible to use foreign keys, which
     * will be automatically joined in the SQL query. The last field in the path must be a primitive.
     * @return The filter reference, which can be used to set the filter value and manipulate the specific filter.
     */
    fun addAlsoWithFilter(vararg fields: String) = FilterReference(findNode(fields)).also { ref ->
        check(where.isNotEmpty()) { "No filter to append to; this is the first filter ever" }
        where[where.lastIndex] += ref
        invalidate()
    }

    private fun findNode(fields: Array<out String>): NodeField {
        require(fields.isNotEmpty()) { "Fields cannot be empty" }
        var last: Node = root
        for (fieldName in fields)
            last = last.findChild(fieldName, { tableCounter.incrementAndGet() })
        require(last is NodeField) { "Last field in path must be primitive" }
        return last
    }

    companion object {
        /**
         * The string representation of a null value in the database. If we want to search for NULL values,
         * it is important to use this constant, instead of the regular null value.
         */
        const val NULL: String = "―"
    }
}
