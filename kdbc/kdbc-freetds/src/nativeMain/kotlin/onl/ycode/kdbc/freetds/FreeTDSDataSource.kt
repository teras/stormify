package onl.ycode.kdbc.freetds

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.PoolableDataSource
import onl.ycode.kdbc.PoolConfig

/**
 * FreeTDS DataSource implementation for MS SQL Server with built-in connection pooling.
 *
 * Example usage:
 * ```kotlin
 * // Basic connection
 * val dataSource = FreeTDSDataSource("localhost", 1433, "mydb", "user", "pass")
 *
 * // With custom pool configuration
 * val dataSource = FreeTDSDataSource(
 *     host = "db.example.com",
 *     port = 1433,
 *     database = "production",
 *     user = "app_user",
 *     password = "secure_password",
 *     poolConfig = PoolConfig(
 *         minConnections = 5,
 *         maxConnections = 20,
 *         validationQuery = "SELECT 1"
 *     )
 * )
 *
 * // With instance name (named instance instead of port)
 * val dataSource = FreeTDSDataSource(
 *     host = "localhost",
 *     database = "mydb",
 *     user = "user",
 *     password = "pass",
 *     instanceName = "SQLEXPRESS"
 * )
 * ```
 */
class FreeTDSDataSource(
    private val host: String,
    private val port: Int = 1433,
    private val database: String,
    private val user: String,
    private val password: String,
    private val instanceName: String? = null,
    private val appName: String? = null,
    poolConfig: PoolConfig = PoolConfig()
) : PoolableDataSource(poolConfig) {

    override fun createNewConnection(): Connection {
        return FreeTDSConnection(
            host = host,
            port = port,
            database = database,
            user = user,
            password = password,
            instanceName = instanceName,
            appName = appName
        )
    }
}
