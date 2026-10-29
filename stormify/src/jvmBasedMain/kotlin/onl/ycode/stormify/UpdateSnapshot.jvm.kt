// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

internal actual fun platformSnapshotCopy(value: Any): Any = when (value) {
    // Covers java.sql.Date, Time and Timestamp (clone keeps the nanoseconds).
    is java.util.Date -> value.clone()
    is java.util.Calendar -> value.clone()
    else -> value
}
