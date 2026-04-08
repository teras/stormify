// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Interface for enum classes that need custom numeric mapping to the database.
 *
 * By default, enum properties are stored as their [ordinal][Enum.ordinal] value.
 * Implement this interface to use a custom integer value instead.
 *
 * ## Usage
 *
 * ```kotlin
 * enum class Status(override val dbValue: Int) : DbValue {
 *     ACTIVE(1),
 *     INACTIVE(5),
 *     BANNED(99)
 * }
 * ```
 *
 * When reading from the database, Stormify looks up the enum entry whose [dbValue]
 * matches the stored integer. If no match is found, nullable properties receive `null`;
 * non-null properties throw an exception.
 *
 * @see DbField
 * @see DbTable
 */
interface DbValue {
    /**
     * The integer value stored in the database for this enum entry.
     */
    val dbValue: Int
}
