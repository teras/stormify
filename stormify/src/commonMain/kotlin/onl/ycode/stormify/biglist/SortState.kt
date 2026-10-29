// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * A sort direction: of a [Facet], of the primary key ([PagedList.keySort]), and of
 * the entries of [PageSpec.sorts] and [PagedListState.sorts].
 */
enum class SortState {
    /** Sort in ascending order (A-Z, 0-9). */
    ASCENDING,
    /** Sort in descending order (Z-A, 9-0). */
    DESCENDING
}
