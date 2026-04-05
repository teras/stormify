// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import db.stormify.GeneratedEntities
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import onl.ycode.kdbc.KdbcDataSource
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.unlink

/**
 * Native (Linux x64) implementation of [createTestDatabases].
 *
 * Picks a single target database based on the `STORMIFY_TEST_DB` environment variable
 * (mirrors the `stormify.test.db` system property used on JVM). When the variable is
 * unset or equals "sqlite", uses an in-memory SQLite database with no external dependencies.
 *
 * For network databases, start the corresponding Docker container from
 * `testing/docker-compose.yml` before running the tests. Credentials and ports match
 * that file.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun createTestDatabases(): List<TestDatabase> {
    // Native targets have no reflection, so test entities must be registered explicitly
    // via the annproc-generated EntityRegistrar.
    GeneratedEntities.register()

    val dbName = (getenv("STORMIFY_TEST_DB")?.toKString() ?: "sqlite").lowercase().ifEmpty { "sqlite" }

    val (displayName, url) = when (dbName) {
        "sqlite" -> {
            // Use a temp-file DB rather than `:memory:` so every connection obtained from the
            // pool-disabled data source sees the same schema. `file::memory:?cache=shared`
            // would also work but depends on the embedded SQLite version supporting shared
            // cache via URI, which is not guaranteed across distributions.
            val path = "/tmp/stormify_native_test_${getpid()}.db"
            unlink(path) // start clean
            "SQLite (file: $path)" to "jdbc:sqlite:$path"
        }
        "postgresql", "postgres" -> "PostgreSQL Native" to
                "jdbc:postgresql://localhost:15432/stormify_test"
        "mysql" -> "MySQL Native" to
                "jdbc:mysql://localhost:13306/stormify_test"
        "mariadb" -> "MariaDB Native" to
                "jdbc:mariadb://localhost:13307/stormify_test"
        "oracle" -> "Oracle Native" to
                "jdbc:oracle:thin:@localhost:11521/XEPDB1"
        "mssql", "sqlserver" -> "MSSQL Native" to
                "jdbc:sqlserver://localhost:11433;databaseName=stormify_test"
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
