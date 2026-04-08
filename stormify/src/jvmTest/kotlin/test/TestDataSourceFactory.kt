// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import onl.ycode.kdbc.JdbcDataSource

private val cachedDatabases: List<TestDatabase> by lazy {
    val configPath = System.getProperty("stormify.test.config")?.ifEmpty { null }
    val dbName = System.getProperty("stormify.test.db") ?: "sqlite"

    val ds = if (configPath != null) {
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

    listOf(TestDatabase(name = dbName, dataSource = JdbcDataSource(ds)))
}

actual fun createTestDatabases(): List<TestDatabase> = cachedDatabases
