// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import kotlin.math.min

/**
 * A lazy-loading paginated list of distinct string values for a [Column].
 *
 * Used to populate selection popups / autocomplete widgets. Values are
 * filtered by the active filters of **other** columns and the list constraints,
 * so the user only sees values that would produce results.
 *
 * Values are loaded in pages on demand — not all at once.
 *
 * Obtained via [Column.getSelectionValues].
 */
class SelectionList<T : Any> internal constructor(
    private val pagedList: PagedListBase<T>,
    private val column: Column<T>
) : AbstractList<String>() {

    /**
     * Page size for loading values. Default is 20.
     */
    var pageSize = 20
        set(value) {
            require(value >= 1) { "Page size must be at least 1" }
            if (field != value) invalidate()
            field = value
        }

    private var fragment: List<String>? = null
    private var lowBound = 0
    private var upperBound = 0
    private var _size: Int? = null

    override val size: Int
        get() = _size ?: run {
            val stormify = pagedList.getStormify()
            val columnExpr = pagedList.resolveColumnExpression(column)
            val (where, args) = pagedList.buildConstraintPart(excludeColumn = column)
            (stormify.readOne<Int>(
                "SELECT COUNT(DISTINCT $columnExpr) FROM ${pagedList.getTablesPart()}$where",
                *args.toTypedArray()
            ) ?: 0).also { _size = it }
        }

    override fun get(index: Int): String = ensurePage(index)[index - lowBound]

    private fun ensurePage(index: Int): List<String> {
        if (index < 0 || index >= size)
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        if (index < lowBound || index >= upperBound)
            fragment = null
        return fragment ?: run {
            val stormify = pagedList.getStormify()
            val columnExpr = pagedList.resolveColumnExpression(column)
            val (where, args) = pagedList.buildConstraintPart(excludeColumn = column)
            val page = index / pageSize
            lowBound = page * pageSize
            upperBound = min(size, (page + 1) * pageSize)
            val sql = stormify.sqlDialect.queryFormatter(
                columnExpr, "DISTINCT ", pagedList.getTablesPart(), where, columnExpr,
                lowBound, upperBound
            )
            val result = stormify.read<String>(null, String::class, sql, *args.toTypedArray())
            fragment = result
            result
        }
    }

    internal fun invalidate() {
        fragment = null
        _size = null
    }
}
