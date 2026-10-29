// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import onl.ycode.kdbc.JdbcDataSource

/** The JDBC data source behind the test database, for tests that wrap the driver itself. */
internal val testJdbcDataSource: javax.sql.DataSource by lazy {
    val configPath = System.getProperty("stormify.test.config")?.ifEmpty { null }
    if (configPath != null) {
        HikariDataSource(HikariConfig(configPath))
    } else {
        val tmpFile = java.io.File.createTempFile("stormify-test", ".db")
        tmpFile.deleteOnExit()
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${tmpFile.absolutePath}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 5
        })
    }
}

private val cachedDatabases: List<TestDatabase> by lazy {
    val dbName = System.getProperty("stormify.test.db") ?: "sqlite"
    val probe = System.getProperty("stormify.probe")?.toBoolean() == true
    val dataSource = if (probe) installJvmTypeProbe(testJdbcDataSource) else testJdbcDataSource
    listOf(TestDatabase(name = dbName, dataSource = JdbcDataSource(dataSource)))
}

actual fun createTestDatabases(): List<TestDatabase> = cachedDatabases
