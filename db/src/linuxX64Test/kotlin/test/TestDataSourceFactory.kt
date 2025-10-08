// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.PoolConfig
import onl.ycode.kdbc.sqlite.SqliteDataSource
// Uncomment when PostgreSQL and MariaDB native drivers are ready:
// import onl.ycode.kdbc.postgres.PostgresDataSource
// import onl.ycode.kdbc.mariadb.MariadbDataSource

/**
 * Native (Linux x64) implementation providing KDBC-based DataSources for testing.
 *
 * Currently tests with:
 * - SQLite (in-memory) - lightweight, works natively
 *
 * Planned support (when native drivers are stable):
 * - PostgreSQL (requires libpq)
 * - MariaDB (requires libmariadb)
 */
actual fun createTestDatabases(): List<TestDatabase> {
    val databases = mutableListOf<TestDatabase>()

    // SQLite in-memory database
    databases.add(
        TestDatabase(
            name = "SQLite Native (in-memory)",
            dataSource = SqliteDataSource(
                url = ":memory:",
                poolConfig = PoolConfig(enabled = false)
            )
        )
    )

    // TODO: Add PostgreSQL when native driver is ready
    // Requires libpq shared library
    // databases.add(
    //     TestDatabase(
    //         name = "PostgreSQL Native",
    //         dataSource = PostgresDataSource(
    //             host = "localhost",
    //             port = 5432,
    //             database = "stormify_test",
    //             user = "test",
    //             password = "test",
    //             poolConfig = PoolConfig(enabled = false)
    //         )
    //     )
    // )

    // TODO: Add MariaDB when native driver is ready
    // Requires libmariadb shared library
    // databases.add(
    //     TestDatabase(
    //         name = "MariaDB Native",
    //         dataSource = MariadbDataSource(
    //             host = "localhost",
    //             port = 3306,
    //             database = "stormify_test",
    //             user = "test",
    //             password = "test",
    //             poolConfig = PoolConfig(enabled = false)
    //         )
    //     )
    // )

    return databases
}
