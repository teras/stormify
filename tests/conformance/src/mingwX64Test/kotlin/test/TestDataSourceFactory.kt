// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.generated.GeneratedEntities
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import onl.ycode.kdbc.KdbcDataSource
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.remove

/**
 * Native (Windows x64) implementation of [createTestDatabases].
 *
 * Picks a single target database based on the `STORMIFY_TEST_DB` environment variable.
 * When unset or equals "sqlite", uses a temp-file SQLite database.
 *
 * For network databases, the corresponding server must be reachable.
 * Credentials and ports match `testing/docker-compose.yml`.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createTestDatabases(): List<TestDatabase> {
    GeneratedEntities.register()

    val dbName = (getenv("STORMIFY_TEST_DB")?.toKString() ?: "sqlite").lowercase().ifEmpty { "sqlite" }
    val host = getenv("STORMIFY_TEST_HOST")?.toKString() ?: "localhost"

    val (displayName, url) = when (dbName) {
        "sqlite" -> {
            val tempDir = getenv("TEMP")?.toKString() ?: getenv("TMP")?.toKString() ?: "."
            val path = "$tempDir\\stormify_native_test_${getpid()}.db"
            remove(path) // start clean
            "SQLite (file: $path)" to "jdbc:sqlite:$path"
        }
        "postgresql", "postgres" -> "PostgreSQL 16 Native" to
                "jdbc:postgresql://$host:15432/stormify_test"
        "postgresql9" -> "PostgreSQL 9.6 Native" to
                "jdbc:postgresql://$host:15431/stormify_test"
        "mysql" -> "MySQL 8.0 Native" to
                "jdbc:mysql://$host:13306/stormify_test"
        "mysql5" -> "MySQL 5.7 Native" to
                "jdbc:mysql://$host:13305/stormify_test"
        "mariadb" -> "MariaDB Native" to
                "jdbc:mariadb://$host:13307/stormify_test"
        "oracle" -> "Oracle Native" to
                "jdbc:oracle:thin:@$host:11521/XEPDB1"
        "oracle11" -> "Oracle 11g Native (EL8ISO8859P7)" to
                "jdbc:oracle:thin:@$host:11524/XE"
        "mssql", "sqlserver" -> "MSSQL Native" to
                "jdbc:sqlserver://$host:11433;databaseName=stormify_test"
        else -> error("Unknown STORMIFY_TEST_DB value: '$dbName'")
    }

    val user = if (dbName == "mssql" || dbName == "sqlserver") "sa" else "stormify"
    val password = "Stormify1!"

    val ds = if (dbName == "sqlite") {
        KdbcDataSource(url)
    } else {
        KdbcDataSource(url, user, password)
    }

    return listOf(TestDatabase(name = displayName, dataSource = ds))
}
