// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * Runs [initSql] on this freshly opened connection: the string is split on `;` and each
 * non-blank statement is executed in order. If any statement fails the connection is closed
 * and the exception propagates. No-op when [initSql] is null or blank.
 *
 * Used by the platform DataSource wrappers to apply per-connection initialization
 * (e.g. `"PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000"`).
 */
internal fun Connection.runInitSql(initSql: String?): Connection {
    val statements = (initSql ?: "").split(';').map { it.trim() }.filter { it.isNotEmpty() }
    if (statements.isEmpty()) return this
    try {
        for (statement in statements)
            initStatement(statement, false, null).use { it.executeUpdate() }
    } catch (e: Throwable) {
        try { close() } catch (_: Throwable) {}
        throw e
    }
    return this
}
