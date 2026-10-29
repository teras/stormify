// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import kotlin.jvm.JvmOverloads

/**
 * Serializable snapshot of a [PagedList]'s user-controllable state —
 * the per-column filters, sorts, case-sensitivity flags, the page size and
 * the distinct flag.
 *
 * Obtained via [PagedList.saveState] and re-applied via
 * [PagedList.restoreState]. Use to persist a grid / picker screen's state
 * across navigation.
 *
 * All fields are plain data (primitives, strings, enums and maps of them) so the
 * state can be round-tripped through any serialization framework — JSON,
 * Parcelable, Kotlinx Serialization, etc. — without extra annotations or
 * custom type adapters.
 *
 * Constraints, selection, input parsers and the columns themselves are
 * **not** part of the state — they are structural.
 */
data class PagedListState @JvmOverloads constructor(
    /** Per-column filter strings, keyed by the column's deterministic state key. */
    val filters: Map<String, String> = emptyMap(),
    /** Per-column sort directions, keyed by the column's deterministic state key, in sorting order. */
    val sorts: Map<String, SortState> = emptyMap(),
    /** Per-column case-sensitivity flags, keyed by the column's deterministic state key. */
    val caseSensitive: Map<String, Boolean> = emptyMap(),
    /** The persisted page size. */
    val pageSize: Int = 15,
    /** Whether the list was in `DISTINCT` mode. */
    val isDistinct: Boolean = false
)
