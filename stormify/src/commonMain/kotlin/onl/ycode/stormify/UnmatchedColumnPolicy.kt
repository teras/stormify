// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

/**
 * Controls how Stormify reacts when a `ResultSet` column has no matching field
 * on the target entity — typical when the database has extra columns the
 * entity does not declare (audit columns, trigger-populated fields, legacy
 * leftovers).
 */
enum class UnmatchedColumnPolicy {
    /** Throws `SQLException` on the first unmatched column. */
    THROW,

    /** Logs a warning for every unmatched column and continues. */
    WARN,

    /** Silently skips unmatched columns. No log output. Default. */
    IGNORE,
}
