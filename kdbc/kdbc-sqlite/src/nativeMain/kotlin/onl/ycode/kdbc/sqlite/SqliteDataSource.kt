package onl.ycode.kdbc.sqlite

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.PoolableDataSource
import onl.ycode.kdbc.PoolConfig

/**
 * SQLite DataSource implementation with built-in connection pooling.
 *
 * Note: SQLite is a file-based database. Connection pooling is less critical
 * for SQLite but can still be useful for managing concurrent access.
 *
 * Example usage:
 * ```kotlin
 * // With default pooling (enabled, 2-10 connections)
 * val dataSource = SqliteDataSource("/path/to/database.db")
 *
 * // Disable pooling for simple use cases
 * val dataSource = SqliteDataSource(
 *     url = "/path/to/database.db",
 *     poolConfig = PoolConfig(enabled = false)
 * )
 * ```
 */
class SqliteDataSource(
    private val url: String,
    poolConfig: PoolConfig = PoolConfig()
) : PoolableDataSource(poolConfig) {

    override fun createNewConnection(): Connection {
        return SqliteConnection(url)
    }
}
