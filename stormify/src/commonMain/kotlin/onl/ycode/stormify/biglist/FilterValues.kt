// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import kotlin.math.min
import kotlin.reflect.KClass

/**
 * Shared base for [FilterValues] and [FilterCountedValues]. Holds the pagination
 * state and the per-page buffer. Subclasses only implement [loadPage] to produce
 * either `String` values or [FilterCountedValue] objects.
 *
 * The constructor is `internal` so only the two built-in subclasses can extend it.
 */
abstract class FilterValuesBase<E> internal constructor(
    internal val core: PagedQueryCore<*>,
    internal val column: Facet
) : AbstractList<E>() {

    /** Page size for loading values. Default is 20. */
    var pageSize = 20
        set(value) {
            require(value >= 1) { "Page size must be at least 1" }
            if (field != value) invalidate()
            field = value
        }

    private var fragment: List<E>? = null
    private var lowBound = 0
    private var upperBound = 0
    private var _size: Int? = null

    /**
     * Number of distinct values for the owning column under the parent list's current
     * filter state (excluding the owning column's own filter).
     */
    override val size: Int
        get() = _size ?: run {
            val stormify = core.stormify
            val columnExpr = core.resolveFacetExpression(column)
            val (where, args) = core.buildConstraintPart(excludeFacet = column)
            (stormify.readOne<Int>(
                "SELECT COUNT(DISTINCT $columnExpr) FROM ${core.tablesPart}$where",
                *args.toTypedArray()
            ) ?: 0).also { _size = it }
        }

    /**
     * Returns the value at [index]. Out-of-range indices propagate the underlying
     * `IndexOutOfBoundsException`.
     */
    override fun get(index: Int): E = ensurePage(index)[index - lowBound]

    /** Loads the page between [low] inclusive and [high] exclusive. */
    protected abstract fun loadPage(low: Int, high: Int): List<E>

    private fun ensurePage(index: Int): List<E> {
        if (index < 0 || index >= size)
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        if (index < lowBound || index >= upperBound)
            fragment = null
        return fragment ?: run {
            val page = index / pageSize
            lowBound = page * pageSize
            upperBound = min(size, (page + 1) * pageSize)
            loadPage(lowBound, upperBound).also { fragment = it }
        }
    }

    internal fun invalidate() {
        fragment = null
        _size = null
    }
}

/**
 * A lazy-loading paginated list of distinct string values for a [Facet].
 *
 * Used to populate selection popups / autocomplete widgets. Values are
 * filtered by the active filters of **other** columns and the list constraints,
 * so the user only sees values that would produce results.
 *
 * Values are loaded in pages on demand — not all at once.
 *
 * Obtained via [Facet.getFilterValues]. For value + count pairs (facet picker
 * style), use [withCounts].
 */
class FilterValues internal constructor(
    core: PagedQueryCore<*>,
    column: Facet
) : FilterValuesBase<String>(core, column) {

    private var _counted: FilterCountedValues? = null

    override fun loadPage(low: Int, high: Int): List<String> {
        val stormify = core.stormify
        val columnExpr = core.resolveFacetExpression(column)
        val (where, args) = core.buildConstraintPart(excludeFacet = column)
        val sql = stormify.sqlDialect.queryFormatter(
            columnExpr, "DISTINCT ", core.tablesPart, where, columnExpr,
            low, high
        )
        return stormify.read<String>(null, String::class, sql, *args.toTypedArray())
    }

    /**
     * Returns a sibling view of this [FilterValues] that exposes the distinct values
     * together with their row counts (as [FilterCountedValue]s). The result is cached,
     * so repeated calls on the same [FilterValues] return the same instance.
     *
     * Useful for facet pickers — "Category: Books (12), Movies (4)".
     */
    fun withCounts(): FilterCountedValues =
        _counted ?: FilterCountedValues(core, column).also { _counted = it }

    internal fun invalidateAll() {
        invalidate()
        _counted?.invalidate()
    }

    /**
     * Returns a JSON array of quoted strings — `["a","b","c"]` — composed by
     * iterating the full list. Triggers page loads for any values not yet
     * cached, so prefer this only after `size` is known to be reasonable.
     */
    override fun toString(): String =
        joinToString(",", "[", "]") { jsonQuote(it) }
}

/**
 * A lazy-loading paginated list of distinct values for a [Facet] together with
 * the row count of each value under the parent list's current filter state
 * (excluding the owning column's own filter). Obtained via
 * [FilterValues.withCounts].
 */
class FilterCountedValues internal constructor(
    core: PagedQueryCore<*>,
    column: Facet
) : FilterValuesBase<FilterCountedValue>(core, column) {

    override fun loadPage(low: Int, high: Int): List<FilterCountedValue> {
        val stormify = core.stormify
        val columnExpr = core.resolveFacetExpression(column)
        val (where, args) = core.buildConstraintPart(excludeFacet = column)
        // Use a subquery so LIMIT/OFFSET apply to the grouped output and the
        // dialect formatter can wrap it like any other paginated SELECT.
        val inner = "SELECT $columnExpr AS fv_val, COUNT(*) AS fv_cnt " +
                "FROM ${core.tablesPart}$where GROUP BY $columnExpr"
        val sql = stormify.sqlDialect.queryFormatter(
            "fv_val, fv_cnt", "", "($inner) fv_sub", "", "fv_val", low, high
        )
        @Suppress("UNCHECKED_CAST")
        val rows = stormify.read(
            null,
            Map::class as KClass<Map<String, Any?>>,
            sql,
            *args.toTypedArray()
        )
        return rows.map { row ->
            val rawValue = row["fv_val"]
            val rawCount = row["fv_cnt"] ?: 0L
            FilterCountedValue(
                value = rawValue?.toString() ?: "",
                count = when (rawCount) {
                    is Long -> rawCount
                    is Number -> rawCount.toLong()
                    else -> rawCount.toString().toLong()
                }
            )
        }
    }

    /**
     * Returns a JSON array of counted-value objects — `[{"value":"X","count":N},…]`.
     * Like [FilterValues.toString], triggers loads for any uncached pages.
     */
    override fun toString(): String = joinToString(",", "[", "]") {
        "{\"value\":${jsonQuote(it.value)},\"count\":${it.count}}"
    }
}

/**
 * A distinct value for a column together with the row count of that value
 * under the parent list's current filter state. Returned by
 * [FilterCountedValues].
 */
data class FilterCountedValue(
    /** The distinct column value (stringified, as with [FilterValues]). */
    val value: String,
    /** Number of rows in the parent list that match this value. */
    val count: Long
)
