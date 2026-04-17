// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/**
 * Per-request specification passed to [PagedQueryBase.execute] and friends.
 * Describes the filters, sorts, case-sensitivity flags and pagination the
 * caller wants for this single query. Keys in the map fields are facet
 * aliases (see [Facet.alias]); any alias not present in the map contributes
 * nothing to the generated SQL.
 *
 * All fields are plain data so a spec can be round-tripped through any
 * serialization framework — JSON, Parcelable, Kotlinx Serialization — and
 * handed to the engine unchanged.
 *
 * ## Construction
 *
 * Two constructors are provided:
 *
 * - **Paginated** — `PageSpec(page, pageSize, filters?, sorts?, cs?)`. Use
 *   for [PagedQueryBase.execute] / [PagedQueryBase.filterValues] where page
 *   and pageSize control which slice is returned.
 * - **Non-paginated** — `PageSpec(filters?, sorts?, cs?)`. Equivalent to
 *   `PageSpec(0, 15, ...)`. Use for
 *   [PagedQueryBase.forEachStreaming] / [PagedQueryBase.getAggregator]
 *   where pagination is ignored.
 *
 * Both constructors have `@JvmOverloads` so Java callers can omit trailing
 * arguments in either form.
 *
 * @property page Zero-based page index.
 * @property pageSize Number of rows per page. Must be at least 1.
 * @property filters Per-alias filter values. Missing aliases → no filter.
 *   The configured `nullToken` (default `"NULL"`) produces `IS NULL`.
 * @property sorts Per-alias sort directions.
 * @property caseSensitive Per-alias case-sensitivity overrides for text
 *   facets. Missing aliases default to `false`.
 */
data class PageSpec @JvmOverloads constructor(
    val page: Int,
    val pageSize: Int,
    val filters: Map<String, String> = emptyMap(),
    val sorts: Map<String, SortDir> = emptyMap(),
    val caseSensitive: Map<String, Boolean> = emptyMap(),
) {
    init {
        require(page >= 0) { "page must be >= 0 (got $page)" }
        require(pageSize >= 1) { "pageSize must be >= 1 (got $pageSize)" }
    }

    /**
     * Non-paginated spec constructor. Delegates to the paginated form with
     * `page = 0`, `pageSize = 15`. The pagination defaults are **ignored**
     * by use cases that do not paginate ([PagedQueryBase.forEachStreaming],
     * [PagedQueryBase.getAggregator]); for paginated callers that accept
     * defaults, they represent "first page, 15 rows".
     */
    @JvmOverloads
    constructor(
        filters: Map<String, String> = emptyMap(),
        sorts: Map<String, SortDir> = emptyMap(),
        caseSensitive: Map<String, Boolean> = emptyMap(),
    ) : this(0, 15, filters, sorts, caseSensitive)

    /** Factory and JSON-parsing helpers for [PageSpec]. */
    companion object {
        /**
         * Parses a JSON object matching the exact shape of [PageSpec] into a
         * new instance. Unknown top-level keys are silently skipped (forward-
         * compatible). Unknown sort values (outside [SortDir]) are silently
         * dropped. Malformed input (wrong types, truncated escapes, integer
         * overflow, invalid characters) throws [IllegalArgumentException].
         *
         * Shape:
         * ```json
         * {
         *   "page": 0,
         *   "pageSize": 15,
         *   "filters": { "alias": "value", … },
         *   "sorts": { "alias": "ASC"|"DESC", … },
         *   "caseSensitive": { "alias": true|false, … }
         * }
         * ```
         */
        @JvmStatic
        fun fromJson(json: String): PageSpec {
            val r = JsonReader(json)
            r.expect('{')
            var page = 0
            var pageSize = 15
            var filters: Map<String, String> = emptyMap()
            var sorts: Map<String, SortDir> = emptyMap()
            var cs: Map<String, Boolean> = emptyMap()
            if (r.peek() != '}') {
                while (true) {
                    val key = r.readString()
                    r.expect(':')
                    when (key) {
                        "page" -> page = r.readInt()
                        "pageSize" -> pageSize = r.readInt()
                        "filters" -> filters = r.readMap { r.readString() }
                        "sorts" -> sorts = r.readMap {
                            val v = r.readString()
                            try { SortDir.valueOf(v) } catch (_: IllegalArgumentException) { null }
                        }
                        "caseSensitive" -> cs = r.readMap { r.readBool() }
                        else -> r.skipValue()   // forward-compatible: ignore unknown keys
                    }
                    if (r.peek() == ',') r.expect(',') else break
                }
            }
            r.expect('}')
            r.expectEndOfInput()
            return PageSpec(page, pageSize, filters, sorts, cs)
        }
    }
}

/**
 * Sort direction for entries in [PageSpec.sorts]. Declared as an enum (not a
 * [SortState] alias) so the public REST-facing contract stays decoupled from
 * the UI-model's internal sort-state representation.
 */
enum class SortDir {
    /** Ascending sort. */
    ASC,

    /** Descending sort. */
    DESC;

    internal fun toSortState(): SortState = when (this) {
        ASC -> SortState.ASCENDING
        DESC -> SortState.DESCENDING
    }
}

/**
 * Result of a [PagedQueryBase.execute] call. Contains the rows for the
 * requested page, the total matching row count, and pagination metadata —
 * everything a typical REST response needs without a second round-trip.
 */
data class Page<T>(
    /** The rows for the requested page, in sort order. */
    val rows: List<T>,
    /** Total matching rows under the current spec, ignoring pagination. */
    val total: Long,
    /** The zero-based page index that was requested and returned. */
    val page: Int,
    /** The page size that was requested and used. */
    val pageSize: Int,
) {
    /** Total number of pages — `ceil(total / pageSize)`. Zero if [total] is zero. */
    val totalPages: Int
        get() = if (total == 0L) 0 else ((total + pageSize - 1) / pageSize).toInt()
}
