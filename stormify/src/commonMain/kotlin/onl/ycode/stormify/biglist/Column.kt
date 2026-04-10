// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist


/**
 * Represents a column in a [PagedList] — the unit of filtering and sorting.
 *
 * A column can contain one or more field paths. When multiple fields are present,
 * filtering uses OR logic between them (e.g., searching "firstName" OR "lastName"),
 * while filtering between different columns uses AND logic.
 *
 * Columns are defined at setup time via [PagedList.addColumn] or [PagedList.addRawColumn].
 * After setup, the user activates/deactivates filters and sorting at runtime.
 *
 * @param T The entity type of the parent [PagedList]
 */
class Column<T : Any> internal constructor(
    private val pagedList: PagedListBase<T>,
    internal val fields: List<FieldPath>,
    internal val type: Type,
    internal val enumValues: Map<String, Any>?,
    internal val rawExpression: String?,
    internal val sqlGenerator: SqlGenerator?
) {
    /**
     * The type of a column, which determines how filter values
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
                pagedList.invalidate()
            }
        }

    /**
     * The current sort state, or null if this column is not sorted.
     */
    var sort: SortState? = null
        set(value) {
            if (field != value) {
                field = value
                pagedList.invalidate()
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

    /**
     * Parser that transforms user input before it reaches the database.
     * Used for locale-aware parsing of numbers, dates, etc.
     *
     * Resolution order: column → list → global → identity (no transformation).
     * Set to [NoInputParser] (default) to fall through to the next level.
     *
     * @see PagedList.inputParser
     * @see PagedList.defaultInputParser
     */
    var inputParser: InputParser = NoInputParser

    private var _selectionValues: SelectionList<T>? = null

    /**
     * Returns the distinct values available for this column, filtered by
     * the active filters of **other** columns and the list constraints.
     *
     * The result is a lazy paginated list — values are loaded on demand.
     * The same instance is reused across calls; it auto-invalidates when
     * any filter or constraint changes on the parent list.
     */
    fun getSelectionValues(): SelectionList<T> =
        _selectionValues ?: SelectionList(pagedList, this).also { _selectionValues = it }

    internal fun invalidateSelectionValues() {
        _selectionValues?.invalidate()
    }

    internal fun hasActiveFilter(): Boolean = filter != null

    internal fun hasActiveSort(): Boolean = sort != null

}

/**
 * Represents a field path through FK relationships, using dot notation.
 *
 * Examples:
 * - `"name"` — direct field on the entity
 * - `"contactPerson.firstName"` — FK traversal to related entity's field
 */
data class FieldPath(val segments: List<String>) {
    init {
        require(segments.isNotEmpty()) { "Field path cannot be empty" }
    }

    constructor(dotPath: String) : this(dotPath.split("."))

    /** The full dot-notation path string. */
    val path: String get() = segments.joinToString(".")

    override fun toString() = path
}

/**
 * Transforms raw user input before it reaches the database query.
 *
 * Use this to handle locale-specific formatting of numbers, dates, etc.
 * For example, converting `"1.234,56"` (Greek locale) to `"1234.56"`,
 * or `"31/12/2026"` to `"2026-12-31"`.
 *
 * Set at three levels (resolution order: column → list → global):
 * - [Column.inputParser] — per column
 * - [PagedListBase.inputParser] — per list
 * - [PagedListBase.defaultInputParser] — global for all lists
 */
fun interface InputParser {
    /** Transforms [input] — the raw filter text — into the form the database expects. */
    fun parse(input: String, type: Column.Type): String
}

/** Sentinel value indicating no parser is set. Passes input through unchanged. */
val NoInputParser: InputParser = InputParser { input, _ -> input }

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
 * (e.g. `"col = ?"`). Bind parameters are added via [args].
 */
fun interface SqlGenerator {
    /** Returns the SQL fragment and stages its parameters via [args]. */
    fun generate(column: String, value: String, args: SqlArgsCollector): String
}
