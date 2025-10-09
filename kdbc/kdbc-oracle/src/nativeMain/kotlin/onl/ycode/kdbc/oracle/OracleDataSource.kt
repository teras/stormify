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
        // Use TCPS protocol for SSL connections, TCP for regular connections
        val protocol = if (sslConfig?.enabled == true) "tcps" else ""
        val baseString = when {
            serviceName != null -> "$host:$port/$serviceName"
            sid != null -> "$host:$port:$sid"
            else -> throw SQLException("Either serviceName or sid must be specified")
        }

        // For SSL connections, build TNS-style connection string with SSL parameters
        return if (sslConfig?.enabled == true) {
            buildTnsConnectString(baseString)
        } else {
            baseString
        }
    }

    private fun buildTnsConnectString(baseString: String): String {
        // Build Oracle TNS-style connection string for SSL
        // Format: (DESCRIPTION=(ADDRESS=(PROTOCOL=TCPS)(HOST=host)(PORT=port))(CONNECT_DATA=(SERVICE_NAME=service)))
        val addressProtocol = "TCPS"
        val connectData = when {
            serviceName != null -> "(SERVICE_NAME=$serviceName)"
            sid != null -> "(SID=$sid)"
            else -> throw SQLException("Either serviceName or sid must be specified")
        }

        val sslParams = buildString {
            // Add SSL_SERVER_DN_MATCH if hostname verification is required
            if (sslConfig?.mode == "verify-full") {
                append("(SSL_SERVER_DN_MATCH=ON)")
            }
            // Add wallet location if CA cert path is provided (Oracle Wallet)
            if (sslConfig?.caCertPath != null) {
                append("(WALLET_LOCATION=(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=${sslConfig.caCertPath}))))")
            }
        }

        return "(DESCRIPTION=(ADDRESS=(PROTOCOL=$addressProtocol)(HOST=$host)(PORT=$port))$sslParams(CONNECT_DATA=$connectData))"
    }

    override fun createNewConnection(): Connection {
        return OracleConnection(connectString, user, password, sslConfig)
    }
}
