// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import kotlin.jvm.JvmField

/**
 * A facet is a named, user-facing filter/sort slot exposed by a [PagedList]
 * or [PagedQuery]. It is the only point of interaction between the outside
 * world and the underlying query — the facet's [alias] is the opaque public
 * identifier, and SQL paths / expressions behind it are kept internal.
 *
 * A facet can reference one or more entity field paths (OR-combined during
 * filtering) or a raw SQL expression. Filtering between different facets uses
 * AND logic; filtering between multiple fields on the same facet uses OR.
 *
 * Facets are defined at setup time via [PagedList.addFacet] /
 * [PagedList.addSqlFacet] (or their [PagedQuery] equivalents).
 * After setup, UI consumers toggle [filter] / [sort] per facet; REST
 * consumers pass the same information through `PageSpec` by alias.
 */
class Facet internal constructor(
    internal val core: PagedQueryCore<*>,
    internal val fields: List<FieldPath>,
    internal val type: Type,
    internal val enumValues: Map<String, Any>?,
    internal val rawExpression: String?,
    initialConverter: Converter?,
    initialAlias: String,
) {
    /**
     * Custom converter for this facet's filter. When `null` (the default),
     * the engine uses its built-in converter for the facet's [type]
     * (e.g., boolean text query with LIKE for [Type.TEXT], comparison
     * parsing for [Type.NUMERIC], …).
     *
     * Set to a non-null value to override the default and translate the
     * filter string into an arbitrary SQL fragment. Works on both
     * field-backed facets ([PagedList.addFacet]) and SQL-backed facets
     * ([PagedList.addSqlFacet]).
     *
     * The number of `?` placeholders in the returned fragment must exactly
     * match the number of arguments pushed via the [SqlArgsCollector];
     * otherwise the next query build throws.
     *
     * Setting this on a facet belonging to a stateless [PagedQuery] is
     * forbidden — configuration is supposed to happen at setup time only.
     */
    var converter: Converter? = initialConverter
        set(value) {
            core.checkMutable()
            field = value
            core.invalidate()
        }

    /**
     * Opaque, stable identifier for this facet — the only identity exposed
     * to the outside world. Used as the key in [PagedList.saveState] /
     * [PagedList.restoreState] output and (in the stateless REST façade)
     * as the key in filter / sort maps on the wire.
     *
     * Defaults to the facet's index-at-creation as a string (`"0"`, `"1"`, …).
     * Assign a stable, human-friendly value (`"name"`, `"total"`) if you plan
     * to persist state across code changes or expose it over REST.
     *
     * **Never use** field paths, SQL expressions, or anything derived from
     * the database schema as an alias — that would leak the very internals
     * the alias mechanism exists to hide.
     *
     * @throws IllegalArgumentException if the new alias is blank or collides
     *   with another facet on the same query.
     */
    var alias: String = initialAlias
        set(value) {
            core.checkMutable()
            require(value.isNotBlank()) { "Facet alias must not be blank" }
            if (field == value) return
            require(core.facets.none { it !== this && it.alias == value }) {
                "Duplicate facet alias '$value'"
            }
            field = value
        }

    /**
     * Whether this facet accepts a filter. When `false`, attempting to set
     * [filter] throws. Defaults to `true`.
     *
     * Intended for the mixed case where a facet is defined for sorting only
     * (or vice versa). If a facet should be neither filterable nor sortable,
     * simply do not add it.
     */
    var isFilterable: Boolean = true
        set(value) {
            core.checkMutable()
            field = value
        }

    /**
     * Whether this facet accepts a sort direction. When `false`, attempting
     * to set [sort] throws. Defaults to `true`.
     */
    var isSortable: Boolean = true
        set(value) {
            core.checkMutable()
            field = value
        }

    /**
     * The type of facet, which determines how filter values are interpreted
     * and converted to SQL conditions.
     *
     * Use the short aliases exposed on the [Facet] companion object
     * (`Facet.Type.TEXT`, `Facet.Type.NUMERIC`, etc.) rather than the nested
     * [Type] path directly.
     */
    enum class Type {
        /** Text — supports wildcards (`*`), quoted exact match (`"text"`), case-insensitive LIKE. */
        TEXT,

        /** Numeric — supports exact match, comparisons (`>`, `<`, `>=`, `<=`), and ranges (`10 ... 20`). */
        NUMERIC,

        /** Date-only — calendar dates without time component. Supports comparisons and ranges. */
        DATE,

        /** Time-only — time of day without date. Supports comparisons and ranges. */
        TIME,

        /** Timestamp/datetime — wall-clock date+time or absolute instant. Supports comparisons and ranges. */
        TIMESTAMP,

        /** Enum/quantize — maps display names to DB values via reverse substring matching. */
        ENUM
    }

    /**
     * The current filter value, or null if no filter is active.
     *
     * Setting a value activates the filter; setting null, an empty or a blank
     * string deactivates it (the property then reads back as null).
     * The value is interpreted according to the facet [type].
     */
    var filter: String? = null
        set(input) {
            val value = input?.takeIf { it.isNotBlank() }
            if (field == value) return
            require(!core.isStateless) {
                "Facet state is immutable on a stateless PagedQuery — pass filters via execute(PageSpec)"
            }
            require(value == null || isFilterable) { "Facet '$alias' is not filterable" }
            field = value
            core.invalidate()
        }

    /**
     * The current sort state, or null if this facet is not sorted.
     */
    var sort: SortState? = null
        set(value) {
            if (field == value) return
            require(!core.isStateless) {
                "Facet state is immutable on a stateless PagedQuery — pass sorts via execute(PageSpec)"
            }
            require(value == null || isSortable) { "Facet '$alias' is not sortable" }
            if (field == null) sortOrder = core.nextSortOrder()
            field = value
            core.invalidate()
        }

    /**
     * Position of this facet among the sorted ones: the `ORDER BY` lists the sorted
     * facets in the order their [sort] was set, so a facet sorted later comes after
     * one sorted earlier, and changing the direction keeps the position.
     */
    internal var sortOrder = 0L

    /** The field path this facet sorts by: [sortPath] when set, otherwise the first path. */
    internal val sortField: FieldPath get() = _sortField ?: fields.first()
    private var _sortField: FieldPath? = null

    /**
     * The field path, in dot notation, that this facet sorts by. A facet with several
     * paths filters on all of them but sorts on one: by default the first, or the one
     * set here, which must be one of the facet's paths. `null` restores the default.
     * Always `null` for SQL facets, which sort by their expression.
     *
     * [PagedList.sort] sets it to the path it is given. On a
     * [PagedQuery] set it at setup time; the per-request sort
     * direction then applies to this path.
     */
    var sortPath: String?
        get() = _sortField?.path
        set(value) {
            core.checkMutable()
            val path = value?.let { FieldPath(it) }
            require(path == null || path in fields) { "'$value' is not a field path of facet '$alias'" }
            if (_sortField == path) return
            _sortField = path
            core.invalidate()
        }

    /**
     * Clears the filter on this facet.
     */
    fun clearFilter() {
        filter = null
    }

    /**
     * Clears sorting on this facet.
     */
    fun clearSort() {
        sort = null
    }

    /**
     * Whether the filter is case-sensitive. Only relevant for [Type.TEXT] facets.
     * Default is false (case-insensitive).
     */
    var isCaseSensitive: Boolean = false
        set(value) {
            if (field == value) return
            require(!core.isStateless) {
                "Facet state is immutable on a stateless PagedQuery — pass caseSensitive via execute(PageSpec)"
            }
            field = value
            core.invalidate()
        }

    /**
     * Parser that transforms user input before it reaches the database.
     * Used for locale-aware parsing of numbers, dates, etc.
     *
     * Resolution order: facet → list → global → identity (no transformation).
     *
     * @see PagedList.inputParser
     * @see onl.ycode.stormify.Stormify.inputParser
     */
    var inputParser: InputParser? = null
        set(value) {
            core.checkMutable()
            field = value
        }

    private var _filterValues: FilterValues? = null

    /**
     * Database value to display name, the reverse of [enumValues]. Keys are
     * compared in a canonical text form, so a value read back as `10.0` still
     * finds the `10` it was declared as. On duplicate values the first name wins.
     */
    private val enumDisplayNames: Map<String, String>? by lazy {
        enumValues?.entries?.reversed()?.associate { canonicalEnumValue(it.value.toString()) to it.key }
    }

    /**
     * The text a filter-values view shows for the stored value [raw]: the
     * display name for an enum facet, or [raw] itself when it has none.
     */
    internal fun displayValue(raw: String): String =
        enumDisplayNames?.get(canonicalEnumValue(raw)) ?: raw

    /**
     * Returns the distinct values available for this facet, filtered by
     * the active filters of **other** facets and the list constraints.
     *
     * The result is a lazy paginated list — values are loaded on demand.
     * The same instance is reused across calls and stays in sync with the
     * parent list's current state.
     *
     * Use [FilterValues.withCounts] to obtain a counted view that also
     * exposes per-value row counts (for facet / picker UIs).
     */
    fun getFilterValues(): FilterValues =
        _filterValues ?: FilterValues(core, this).also { _filterValues = it }

    private var _filterRawValues: FilterRawValues? = null

    /**
     * Returns the distinct values of this facet as they come from the database, not
     * converted to text: the values of **all** its field paths, with SQL `NULL` as a
     * last `null` element. See [FilterRawValues].
     *
     * @throws IllegalArgumentException if the facet's paths have different field types.
     *
     * Like [getFilterValues], the result is a lazy paginated list filtered by the
     * active filters of the other facets and the list constraints, and the same
     * instance is reused across calls.
     */
    fun getFilterRawValues(): FilterRawValues = _filterRawValues ?: run {
        val types = fields.map { core.resolveFieldPath(it).type }.distinct()
        // A UNION of text with numbers fails on most databases (PostgreSQL, Oracle,
        // SQL Server), and the values would have no common type to convert to.
        require(types.size <= 1) {
            "Facet '$alias' has paths of different types (${types.joinToString { it.simpleName ?: "?" }}): " +
                    "their raw values cannot be listed together; use getFilterValues() or one facet per type"
        }
        FilterRawValues(core, this).also { _filterRawValues = it }
    }

    internal fun invalidateFilterValues() {
        _filterValues?.invalidateAll()
        _filterRawValues?.invalidate()
    }

    /**
     * Opaque key used by [PagedList.saveState] / [PagedList.restoreState]
     * — simply the facet's [alias]. Never derived from fields or SQL
     * expressions, so persisted state never leaks schema or query internals.
     */
    internal fun stateKey(): String = alias
}

private val DECIMAL_TEXT = Regex("-?\\d+\\.\\d*")

/** Drops the trailing fractional zeros of a decimal number's text (`"10.0"` -> `"10"`). */
private fun canonicalEnumValue(text: String): String =
    if (DECIMAL_TEXT.matches(text)) text.trimEnd('0').trimEnd('.') else text

/**
 * Represents a field path through FK relationships, using dot notation.
 *
 * Examples:
 * - `"name"` — direct field on the entity
 * - `"contactPerson.firstName"` — FK traversal to related entity's field
 */
internal data class FieldPath(
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
 * number/date parsing. Resolution chain: [Facet.inputParser] → [PagedList.inputParser]
 * → [onl.ycode.stormify.Stormify.inputParser] → identity.
 */
fun interface InputParser {
    /** Returns [input], the filter text of a facet of [type], as the database should compare it. */
    operator fun invoke(input: String, type: Facet.Type): String
}


/** Receives the rows of a streaming read one at a time, as `forEachStreaming` reads them. */
fun interface RowConsumer<in T> {
    /** Handles one [row]. */
    fun accept(row: T)
}

/**
 * Callback used by [Converter] implementations to stage a bind parameter for the
 * generated SQL placeholder. Each call adds one `?` value to the query in order.
 */
fun interface SqlArgsCollector {
    /** Adds [arg] as a bind parameter. */
    fun add(arg: Any)
}

/**
 * Converts a facet filter value into a SQL condition fragment.
 *
 * Parameters: column reference, raw filter value, [InputParser] for locale-aware
 * transformation, and [SqlArgsCollector] for staging bind parameters.
 *
 * Built-in converters handle text (Google-like boolean syntax), numeric
 * (operators and ranges), temporal (date/time operators), and enum (set
 * algebra over display names). Set [Facet.converter] to override the
 * default for any facet type.
 *
 * The number of `?` placeholders in the returned fragment must match the
 * number of values pushed through the args collector — the engine validates
 * this and throws if they disagree.
 */
fun interface Converter {
    /**
     * Returns the SQL condition on [column] for the filter text [input], parsed with
     * [parser], with its bind values added to [args].
     */
    operator fun invoke(column: String, input: String, parser: InputParser, args: SqlArgsCollector): String
}
