// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * Platform-agnostic DataSource factory.
 *
 * Takes a standard JDBC URL (`jdbc:postgresql://...`, `jdbc:sqlite:...`, etc.) and returns
 * a [DataSource] that works on every supported platform:
 *
 * - **Native (Linux x64)**: backed by the unified kdbc C library (`libkdbc.a`). Parses
 *   the JDBC URL via [JdbcUrlParser] and dispatches to the appropriate C driver
 *   (SQLite, PostgreSQL, MariaDB/MySQL, Oracle, MSSQL).
 * - **JVM / Android**: not yet implemented — throws [SQLException] with a hint pointing
 *   to `JdbcDataSource` / `AndroidDataSource` which wrap an existing platform DataSource.
 *
 * Explicit [user] / [password] arguments override any credentials embedded in the URL
 * (mirrors `java.sql.DriverManager.getConnection(url, user, password)` behaviour).
 *
 * Unknown URL parameters (e.g. `ssl=true`, `connectTimeout=10`) that the underlying C
 * driver does not recognize are emitted as warnings via the logger, not silently ignored.
 *
 * [initSql] runs on every freshly opened connection before it is returned to the caller —
 * like HikariCP's `connectionInitSql`. Separate more than one statement with `;`; each is run
 * in order. Typical uses: `PRAGMA foreign_keys = ON` for SQLite, session settings
 * (`SET TIME ZONE 'UTC'`) for PostgreSQL/Oracle. If any statement fails the connection is
 * closed and the exception propagates.
 *
 * Example:
 * ```kotlin
 * val ds = KdbcDataSource(
 *     "jdbc:sqlite:/tmp/app.db",
 *     initSql = "PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000",
 * )
 * val stormify = Stormify(ds)
 * ```
 */
expect fun KdbcDataSource(
    url: String,
    user: String? = null,
    password: String? = null,
    initSql: String? = null
): DataSource

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
