// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * The sort direction of a [Facet].
 *
 * Use `Facet.ASCENDING` or `Facet.DESCENDING` for convenience.
 */
enum class SortState {
    /** Sort in ascending order (A-Z, 0-9). */
    ASCENDING,
    /** Sort in descending order (Z-A, 9-0). */
    DESCENDING
}
