// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * JVM implementation of [extractDriverMetadata]. Walks the cause chain looking
 * for a `java.sql.SQLException` and returns its `sqlState` / `errorCode`.
 * Cycles in the chain are guarded against by stopping when `cause === self`.
 */
internal actual fun extractDriverMetadata(cause: Throwable?): Pair<String?, Int?> {
    var cur: Throwable? = cause
    while (cur != null) {
        if (cur is java.sql.SQLException) return cur.sqlState to cur.errorCode
        val nxt = cur.cause
        cur = if (nxt === cur) null else nxt
    }
    return null to null
}
