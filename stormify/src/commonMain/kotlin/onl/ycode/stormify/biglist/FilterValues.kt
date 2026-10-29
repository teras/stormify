// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import onl.ycode.stormify.TypeUtils
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
     * filter state (excluding the owning column's own filter). SQL `NULL`, when
     * present, counts as one value.
     */
    override val size: Int
        get() = _size ?: countValues().also { _size = it }

    /** Counts the distinct values, the `NULL` group as one; [size] caches the result. */
    internal open fun countValues(): Int {
        val stormify = core.stormify
        val (columnExpr, fromWhere, args) = core.planFilterValuesPieces(column, null)
        return stormify.readOne<Int>(
            buildFilterValuesCountSql(stormify.sqlDialect, columnExpr, fromWhere),
            *args.toTypedArray()
        ) ?: 0
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
 * SQL `NULL`, when present, is the last value and appears as the configured
 * [FilterSyntax.nullToken], so setting it back as the facet's filter selects
 * the `NULL` rows.
 *
 * Known limit: a stored text equal to the null token (for example the string
 * `"NULL"` with the default token) is listed as well, so the token then
 * appears twice, once for that text and once, last, for SQL `NULL`; both
 * select only the `NULL` rows when chosen as the filter. Choose a null token
 * that cannot occur in the data (such as `--`) if this matters.
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
        val (columnExpr, fromWhere, args) = core.planFilterValuesPieces(column, null)
        val sql = buildFilterValuesOnlySql(stormify.sqlDialect, columnExpr, fromWhere)
        val nullToken = stormify.filterSyntax.nullToken
        return stormify.readNullableStrings(sql, *(args + stormify.sqlDialect.pageBinds(low, high)).toTypedArray())
            .map { if (it == null) nullToken else column.displayValue(it) }
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
 * A lazy-loading paginated list of the distinct values of a [Facet] as they come from
 * the database, not converted to text. The counterpart of [FilterValues] for callers
 * that format the values themselves (amounts, dates, codes mapped to labels).
 *
 * - A facet with several field paths lists the values of **all** its paths, each
 *   distinct value once (SQL `UNION`).
 * - SQL `NULL`, when present, is one `null` element, always last.
 * - Values are converted to the field type of the facet's paths (an enum field gives
 *   enum constants, a `Short` field gives `Short`s), so they are the same classes on
 *   every platform. All paths must have the same field type. For SQL facets values
 *   are what the driver returns.
 * - Values of an [ENUM][Facet.Type.ENUM] facet with a display-name map are the stored
 *   values, not the display names.
 *
 * Like [FilterValues], the values follow the active filters of the **other** facets
 * and the list constraints, are ordered by value and are loaded page by page.
 * Obtained via [Facet.getFilterRawValues].
 */
class FilterRawValues internal constructor(
    core: PagedQueryCore<*>,
    column: Facet
) : FilterValuesBase<Any?>(core, column) {

    override fun countValues(): Int {
        val stormify = core.stormify
        val (union, args) = core.planFilterRawValues(column)
        return stormify.readOne<Int>(buildRawValuesCountSql(stormify.sqlDialect, union), *args.toTypedArray()) ?: 0
    }

    override fun loadPage(low: Int, high: Int): List<Any?> {
        val stormify = core.stormify
        val (union, args) = core.planFilterRawValues(column)
        @Suppress("UNCHECKED_CAST")
        val rows = stormify.read(
            null,
            Map::class as KClass<Map<String, Any?>>,
            buildRawValuesPageSql(stormify.sqlDialect, union),
            *(args + stormify.sqlDialect.pageBinds(low, high)).toTypedArray()
        )
        val type = core.rawValueType(column)
        return rows.map { row ->
            val value = row[FV_VAL_ALIAS]
            if (value == null || type == null) value else TypeUtils.castTo(type, value, stormify)
        }
    }
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
        val (columnExpr, fromWhere, args) = core.planFilterValuesPieces(column, null)
        // Use a subquery so LIMIT/OFFSET apply to the grouped output and the
        // dialect formatter can wrap it like any other paginated SELECT.
        val sql = buildFilterValuesSql(stormify.sqlDialect, columnExpr, fromWhere)
        @Suppress("UNCHECKED_CAST")
        val rows = stormify.read(
            null,
            Map::class as KClass<Map<String, Any?>>,
            sql,
            *(args + stormify.sqlDialect.pageBinds(low, high)).toTypedArray()
        )
        val nullToken = stormify.filterSyntax.nullToken
        return rows.map { FilterCountedValue.fromRow(it, nullToken, column::displayValue) }
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
    /**
     * The distinct column value (stringified, as with [FilterValues]); SQL
     * `NULL` appears as the configured [FilterSyntax.nullToken].
     */
    val value: String,
    /** Number of rows in the parent list that match this value. */
    val count: Long
) {
    internal companion object {
        fun fromRow(row: Map<String, Any?>, nullToken: String, display: (String) -> String): FilterCountedValue {
            val rawValue = row[FV_VAL_ALIAS]
            val rawCount = row[FV_CNT_ALIAS] ?: 0L
            return FilterCountedValue(
                value = rawValue?.toString()?.let(display) ?: nullToken,
                count = when (rawCount) {
                    is Long -> rawCount
                    is Number -> rawCount.toLong()
                    else -> rawCount.toString().toLong()
                }
            )
        }
    }
}
