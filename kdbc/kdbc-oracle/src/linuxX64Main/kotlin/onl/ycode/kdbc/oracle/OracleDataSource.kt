package onl.ycode.kdbc.oracle

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.PoolableDataSource
import onl.ycode.kdbc.PoolConfig
import onl.ycode.kdbc.SslConfig
import onl.ycode.kdbc.SQLException

/**
 * Oracle Database DataSource implementation with built-in connection pooling and SSL/TLS support.
 *
 * Example usage:
 * ```kotlin
 * // Basic connection
 * val dataSource = OracleDataSource(
 *     host = "localhost",
 *     port = 1521,
 *     serviceName = "XE",
 *     user = "system",
 *     password = "oracle"
 * )
 *
 * // With SSL (Oracle Wallet)
 * val dataSource = OracleDataSource(
 *     host = "adb.oraclecloud.com",
 *     port = 1522,
 *     serviceName = "mydb_high",
 *     user = "ADMIN",
 *     password = "password",
 *     sslConfig = SslConfig(
 *         enabled = true,
 *         caCertPath = "/path/to/wallet_MYDB"
 *     )
 * )
 *
 * // With connection pooling
 * val dataSource = OracleDataSource(
 *     host = "oracle-db.example.com",
 *     port = 1521,
 *     serviceName = "PROD",
 *     user = "app_user",
 *     password = "password",
 *     poolConfig = PoolConfig(
 *         minConnections = 5,
 *         maxConnections = 20,
 *         validationQuery = "SELECT 1 FROM DUAL"
 *     )
 * )
 * ```
 */
class OracleDataSource(
    private val host: String,
    private val port: Int = 1521,
    private val serviceName: String? = null,
    private val sid: String? = null,
    private val user: String,
    private val password: String,
    private val sslConfig: SslConfig? = null,
    poolConfig: PoolConfig = PoolConfig()
) : PoolableDataSource(poolConfig) {

    init {
        require(serviceName != null || sid != null) {
            "Either serviceName or sid must be specified"
        }
    }

    private val connectString: String = buildConnectString()

    private fun buildConnectString(): String {
        return when {
            serviceName != null -> "$host:$port/$serviceName"
            sid != null -> "$host:$port:$sid"
            else -> throw SQLException("Either serviceName or sid must be specified")
        }
    }

    override fun createNewConnection(): Connection {
        return OracleConnection(connectString, user, password)
    }
}
