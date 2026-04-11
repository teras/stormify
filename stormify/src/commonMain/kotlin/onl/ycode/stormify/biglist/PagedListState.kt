// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * Serializable snapshot of a [PagedListBase]'s user-controllable state —
 * the per-column filters, sorts, case-sensitivity flags, the page size and
 * the distinct flag.
 *
 * Obtained via [PagedListBase.saveState] and re-applied via
 * [PagedListBase.restoreState]. Use to persist a grid / picker screen's state
 * across navigation.
 *
 * All fields are plain data (primitives, strings, maps of primitives) so the
 * state can be round-tripped through any serialization framework — JSON,
 * Parcelable, Kotlinx Serialization, etc. — without extra annotations or
 * custom type adapters.
 *
 * Constraints, selection, input parsers and the columns themselves are
 * **not** part of the state — they are structural.
 */
data class PagedListState(
    /** Per-column filter strings, keyed by the column's deterministic state key. */
    val filters: Map<String, String> = emptyMap(),
    /**
     * Per-column sort directions, keyed by the column's deterministic state key.
     * Values are [PagedListSort.ASC] or [PagedListSort.DESC]; other strings are
     * ignored on restore.
     */
    val sorts: Map<String, String> = emptyMap(),
    /** Per-column case-sensitivity flags, keyed by the column's deterministic state key. */
    val caseSensitive: Map<String, Boolean> = emptyMap(),
    /** The persisted page size. */
    val pageSize: Int = 15,
    /** Whether the list was in `DISTINCT` mode. */
    val isDistinct: Boolean = false
)

/**
 * Sentinel sort-direction values for [PagedListState.sorts] entries. Declared
 * outside [PagedListState] so the data class stays strictly plain data with no
 * companion members that could confuse a naive reflection-based serializer.
 */
object PagedListSort {
    /** Ascending sort direction. */
    const val ASC: String = "ASC"
    /** Descending sort direction. */
    const val DESC: String = "DESC"
}
