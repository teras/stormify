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
 *   (SQLite, PostgreSQL, MariaDB/MySQL, Oracle, FreeTDS/MSSQL).
 * - **JVM / Android**: not yet implemented — throws [SQLException] with a hint pointing
 *   to `JdbcDataSource` / `AndroidDataSource` which wrap an existing platform DataSource.
 *
 * Explicit [user] / [password] arguments override any credentials embedded in the URL
 * (mirrors `java.sql.DriverManager.getConnection(url, user, password)` behaviour).
 *
 * Unknown URL parameters (e.g. `ssl=true`, `connectTimeout=10`) that the underlying C
 * driver does not recognize are emitted as warnings via the logger, not silently ignored.
 *
 * Example:
 * ```kotlin
 * val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
 * val stormify = Stormify(ds)
 * ```
 */
expect fun KdbcDataSource(
    url: String,
    user: String? = null,
    password: String? = null,
    poolConfig: PoolConfig = PoolConfig()
): DataSource
