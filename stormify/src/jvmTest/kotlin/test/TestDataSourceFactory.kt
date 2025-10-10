// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import onl.ycode.kdbc.JdbcDataSource

/**
 * JVM implementation providing JDBC-based DataSources for testing.
 *
 * Currently tests with:
 * - SQLite (in-memory) - lightweight, no external dependencies
 *
 * Can be extended to include:
 * - MySQL/MariaDB (requires running server)
 * - PostgreSQL (requires running server)
 */
actual fun createTestDatabases(): List<TestDatabase> {
    val databases = mutableListOf<TestDatabase>()

    // SQLite in-memory database
    databases.add(
        TestDatabase(
            name = "SQLite JDBC (in-memory)",
            dataSource = JdbcDataSource(createSqliteDataSource())
        )
    )

    // Uncomment to test with MySQL (requires running MySQL server)
    // databases.add(
    //     TestDatabase(
    //         name = "MySQL JDBC",
    //         dataSource = createMySQLDataSource()
    //     )
    // )

    // Uncomment to test with PostgreSQL (requires running PostgreSQL server)
    // databases.add(
    //     TestDatabase(
    //         name = "PostgreSQL JDBC",
    //         dataSource = createPostgreSQLDataSource()
    //     )
    // )

    return databases
}

private fun createSqliteDataSource(): HikariDataSource {
    val config = HikariConfig()
    config.jdbcUrl = "jdbc:sqlite::memory:"
    config.driverClassName = "org.sqlite.JDBC"
    config.maximumPoolSize = 1
    return HikariDataSource(config)
}

private fun createMySQLDataSource(): HikariDataSource {
    val config = HikariConfig()
    config.jdbcUrl = "jdbc:mysql://localhost:3306/stormify_test"
    config.username = "test"
    config.password = "test"
    config.driverClassName = "com.mysql.cj.jdbc.Driver"
    config.maximumPoolSize = 5
    return HikariDataSource(config)
}

private fun createPostgreSQLDataSource(): HikariDataSource {
    val config = HikariConfig()
    config.jdbcUrl = "jdbc:postgresql://localhost:5432/stormify_test"
    config.username = "test"
    config.password = "test"
    config.driverClassName = "org.postgresql.Driver"
    config.maximumPoolSize = 5
    return HikariDataSource(config)
}
