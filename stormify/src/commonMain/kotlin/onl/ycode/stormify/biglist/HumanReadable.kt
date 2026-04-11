// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * Interface for entities or enum values that provide a localized, human-readable display name.
 *
 * Used by [PagedListBase] enum/quantize columns to map
 * between display names (what the user sees/types) and database values.
 *
 * Example (mirrors the [onl.ycode.stormify.DbValue] pattern — constructor param
 * propagated via `override val`):
 * ```kotlin
 * enum class Status(override val displayName: String) : HumanReadable {
 *     ACTIVE("Ενεργή"),
 *     INACTIVE("Ανενεργή"),
 *     BANNED("Αποκλεισμένη")
 * }
 * ```
 */
interface HumanReadable {
    /** The localized, human-readable display name for this value. */
    val displayName: String
}
