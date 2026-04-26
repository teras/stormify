// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

/**
 * JVM actual for [KdbcDataSource]. Not yet implemented.
 *
 * On JVM, use [JdbcDataSource] directly to wrap a `javax.sql.DataSource` (e.g. HikariCP).
 * A future implementation may parse the JDBC URL and call `DriverManager.getConnection(url, user, password)`
 * to provide true write-once multiplatform code — tracked as TODO.
 */
actual fun KdbcDataSource(
    url: String,
    user: String?,
    password: String?,
    initSql: String?
): DataSource {
    throw SQLException(
        "KdbcDataSource(url) is not yet implemented on JVM. " +
                "Use JdbcDataSource(javax.sql.DataSource) to wrap a HikariCP / driver-managed data source instead."
    )
}
