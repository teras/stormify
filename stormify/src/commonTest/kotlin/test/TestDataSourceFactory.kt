// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.DataSource

/**
 * Represents a test database configuration with its name and DataSource.
 */
data class TestDatabase(
    val name: String,
    val dataSource: DataSource
)

/**
 * Platform-specific DataSource factory for tests.
 *
 * - JVM: Uses JDBC DataSource (e.g., HikariCP for MySQL, PostgreSQL, SQLite)
 * - Native: Uses KDBC DataSource (e.g., SqliteDataSource, PostgresDataSource, MariadbDataSource)
 *
 * Returns a list of all available test databases for the current platform.
 */
expect fun createTestDatabases(): List<TestDatabase>
