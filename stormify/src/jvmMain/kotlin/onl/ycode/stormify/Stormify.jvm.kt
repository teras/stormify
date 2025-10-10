@file:JvmName("StormifyExtKt")
package onl.ycode.stormify

import onl.ycode.kdbc.toKdbcDataSource

/**
 * Creates a Stormify instance from a JDBC DataSource.
 *
 * This is a convenience function for JVM/Kotlin that automatically wraps
 * the javax.sql.DataSource into a KDBC DataSource interface.
 *
 * ## Usage
 * ```kotlin
 * val hikariDS = HikariDataSource(config)
 * val stormify = Stormify(hikariDS)
 * ```
 */
@JvmName("fromJdbcDataSource")
fun Stormify(jdbcDataSource: javax.sql.DataSource): Stormify =
    Stormify(jdbcDataSource.toKdbcDataSource())
