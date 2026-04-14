// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import kotlin.jvm.JvmField

/**
 * Represents a column in a [PagedListBase] — the unit of filtering and sorting.
 *
 * A column can contain one or more field paths. When multiple fields are present,
 * filtering uses OR logic between them (e.g., searching "firstName" OR "lastName"),
 * while filtering between different columns uses AND logic.
 *
 * Columns are defined at setup time via [PagedListBase.addColumn] or [PagedListBase.addRawColumn].
 * After setup, the user activates/deactivates filters and sorting at runtime.
 */
class Column internal constructor(
    private val pagedList: PagedListBase<*>,
    internal val fields: List<FieldPath>,
    internal val type: Type,
    internal val enumValues: Map<String, Any>?,
    internal val rawExpression: String?,
    internal val sqlGenerator: SqlGenerator?
) {
    /**
     * The type of column, which determines how filter values
     * are interpreted and converted to SQL conditions.
     *
     * Use as `Column.TEXT`, `Column.NUMERIC`, etc.
     */
    enum class Type {
        /** Text — supports wildcards (`*`), quoted exact match (`"text"`), case-insensitive LIKE. */
        TEXT,

        /** Numeric — supports exact match, comparisons (`>`, `<`, `>=`, `<=`), and ranges (`10 ... 20`). */
        NUMERIC,

        /** Temporal — covers date, time, datetime, timestamp. Supports comparisons and ranges. */
        TEMPORAL,

        /** Enum/quantize — maps display names to DB values via reverse substring matching. */
        ENUM,

        /** Raw/custom — user provides the SQL expression and filtering logic. */
        RAW
    }

    /** Exposes column [Type] and [SortState] constants as short aliases (e.g. `Column.TEXT`, `Column.ASCENDING`). */
    companion object {
        /** Text column type. */
        @JvmField val TEXT = Type.TEXT
        /** Numeric column type. */
        @JvmField val NUMERIC = Type.NUMERIC
        /** Temporal column type (date, time, datetime, timestamp). */
        @JvmField val TEMPORAL = Type.TEMPORAL
        /** Enum/quantize column type. */
        @JvmField val ENUM = Type.ENUM
        /** Raw/custom column type. */
        @JvmField val RAW = Type.RAW

        /** Ascending sort order. */
        @JvmField val ASCENDING = SortState.ASCENDING
        /** Descending sort order. */
        @JvmField val DESCENDING = SortState.DESCENDING

        /**
         * Sentinel string used to filter a column for SQL `NULL`. Assign as the
         * column filter (`column.filter = Column.NULL`) to generate
         * `WHERE <column> IS NULL` in the underlying query.
         */
        const val NULL: String = "―"
    }

    /**
     * The current filter value, or null if no filter is active.
     *
     * Setting a value activates the filter; setting null deactivates it.
     * The value is interpreted according to the column [type].
     */
    var filter: String? = null
        set(value) {
            if (field != value) {
                field = value
                pagedList.refresh()
            }
        }

    /**
     * The current sort state, or null if this column is not sorted.
     */
    var sort: SortState? = null
        set(value) {
            if (field != value) {
                field = value
                pagedList.refresh()
            }
        }

    /**
     * Clears the filter on this column.
     */
    fun clearFilter() {
        filter = null
    }

    /**
     * Clears sorting on this column.
     */
    fun clearSort() {
        sort = null
    }

    /**
     * Whether the filter is case-sensitive. Only relevant for [Type.TEXT] columns.
     * Default is false (case-insensitive).
     */
    var isCaseSensitive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                pagedList.refresh()
            }
        }

    /**
     * Parser that transforms user input before it reaches the database.
     * Used for locale-aware parsing of numbers, dates, etc.
     *
     * Resolution order: column → list → global → identity (no transformation).
     *
     * @see PagedListBase.inputParser
     * @see PagedListBase.defaultInputParser
     */
    var inputParser: InputParser? = null

    private var _filterValues: FilterValues? = null

    /**
     * Returns the distinct values available for this column, filtered by
     * the active filters of **other** columns and the list constraints.
     *
     * The result is a lazy paginated list — values are loaded on demand.
     * The same instance is reused across calls and stays in sync with the
     * parent list's current state.
     *
     * Use [FilterValues.withCounts] to obtain a counted view that also
     * exposes per-value row counts (for facet / picker UIs).
     */
    fun getFilterValues(): FilterValues =
        _filterValues ?: FilterValues(pagedList, this).also { _filterValues = it }

    internal fun invalidateFilterValues() {
        _filterValues?.invalidateAll()
    }

    internal fun hasActiveFilter(): Boolean = filter != null

    internal fun hasActiveSort(): Boolean = sort != null

    /**
     * Deterministic key for this column used by [PagedListBase.saveState] /
     * [PagedListBase.restoreState]. Raw columns use their SQL expression; field
     * columns use their paths joined alphabetically so that path order does not
     * affect the key.
     */
    internal fun stateKey(): String = when {
        rawExpression != null -> rawExpression
        fields.isNotEmpty() -> fields.map { it.path }.sorted().joinToString(",")
        else -> ""
    }
}

/**
 * Represents a field path through FK relationships, using dot notation.
 *
 * Examples:
 * - `"name"` — direct field on the entity
 * - `"contactPerson.firstName"` — FK traversal to related entity's field
 */
data class FieldPath(
    /** The ordered path segments, e.g. `["contactPerson", "firstName"]`. */
    val segments: List<String>
) {
    init {
        require(segments.isNotEmpty()) { "Field path cannot be empty" }
        require(segments.all { it.any(Char::isLetterOrDigit) }) {
            // Rejects blank strings AND pure-symbol strings ("$$$", ":::", "@@@"),
            // which could otherwise slip past a naive `isNotBlank()` check and
            // surface only later as an obscure "field not found" tree-traversal
            // error — or worse, as a raw SQL syntax error from the driver.
            "Field path segments must contain at least one letter or digit: $segments"
        }
    }

    /** Builds a path from a dot-notation string such as `"contactPerson.firstName"`. */
    constructor(dotPath: String) : this(dotPath.split("."))

    /** The full dot-notation path string. */
    val path: String get() = segments.joinToString(".")

    /** Returns the dot-notation path string. */
    override fun toString() = path
}


/**
 * Transforms user filter input before it reaches the database — e.g. locale-aware
 * number/date parsing. Resolution chain: [Column.inputParser] → [PagedListBase.inputParser]
 * → [PagedListBase.defaultInputParser] → identity.
 */
typealias InputParser = (String, Column.Type) -> String


/**
 * Callback used by [SqlGenerator] implementations to stage a bind parameter for the
 * generated SQL placeholder. Each call adds one `?` value to the query in order.
 */
fun interface SqlArgsCollector {
    /** Adds [arg] as a bind parameter. */
    fun accept(arg: Any)
}

/**
 * Generates the SQL condition for a custom (raw) column's filter. Implementations receive
 * the column expression and the user-typed filter value, and must return a SQL fragment
 * (e.g. `"col = ?"`). Bind parameters are added via the [SqlArgsCollector] callback.
 */
fun interface SqlGenerator {
    /** Returns the SQL fragment and stages its parameters via the [SqlArgsCollector] callback. */
    fun generate(column: String, value: String, args: SqlArgsCollector): String
}
