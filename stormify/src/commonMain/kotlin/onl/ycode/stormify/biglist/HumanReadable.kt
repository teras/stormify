// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

/**
 * Interface for entities or enum values that provide a localized, human-readable display name.
 *
 * Used by [PagedList] enum/quantize columns to map
 * between display names (what the user sees/types) and database values.
 *
 * Example:
 * ```kotlin
 * enum class Status(@DbValue val code: Int) : HumanReadable {
 *     ACTIVE(1) { override fun displayName() = "Ενεργή" },
 *     INACTIVE(0) { override fun displayName() = "Ανενεργή" }
 * }
 * ```
 */
interface HumanReadable {
    /**
     * Returns the localized, human-readable display name for this value.
     */
    fun displayName(): String
}
