// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import onl.ycode.logger.LogManager

private val logger = LogManager.getLogger("onl.ycode.kdbc.KdbcDataSource")

/**
 * DataSource factory of the native targets.
 *
 * Takes a standard JDBC URL (`jdbc:postgresql://...`, `jdbc:sqlite:...`, etc.) and returns
 * a [DataSource] backed by the unified kdbc C library (`libkdbc.a`): the URL is parsed via
 * [JdbcUrlParser] and dispatched to the matching C driver (SQLite, PostgreSQL,
 * MariaDB/MySQL, Oracle, MSSQL). On the JVM use `JdbcDataSource`, on Android
 * `AndroidDataSource`, which wrap a platform data source.
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
fun KdbcDataSource(
    url: String,
    user: String? = null,
    password: String? = null,
    initSql: String? = null
): DataSource {
    val parsed = JdbcUrlParser.parse(url, user, password)
    if (parsed.extraParams.isNotEmpty()) {
        logger.warn(
            "KdbcDataSource: the following URL parameters are not supported by the native " +
                    "kdbc driver for ${parsed.kind} and will be ignored: " +
                    parsed.extraParams.entries.joinToString(", ") { "${it.key}=${it.value}" }
        )
    }
    return NativeKdbcDataSource(
        kind = parsed.kind,
        nativeUrl = parsed.nativeUrl,
        user = parsed.user,
        password = parsed.password,
        initSql = initSql
    )
}
