package onl.ycode.kdbc.mariadb

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.PoolableDataSource
import onl.ycode.kdbc.PoolConfig
import onl.ycode.kdbc.SslConfig

/**
 * MariaDB/MySQL DataSource implementation with built-in connection pooling and SSL/TLS support.
 *
 * Example usage:
 * ```kotlin
 * // Basic connection (no SSL)
 * val dataSource = MariadbDataSource("localhost", 3306, "mydb", "user", "pass")
 *
 * // With SSL/TLS enabled (require SSL, verify CA certificate)
 * val dataSource = MariadbDataSource(
 *     host = "db.example.com",
 *     port = 3306,
 *     database = "production",
 *     user = "app_user",
 *     password = "${YOUR_PASSWORD}",
 *     sslConfig = SslConfig.verifyCA("/path/to/ca-cert.pem")
 * )
 *
 * // With client certificate authentication
 * val dataSource = MariadbDataSource(
 *     host = "db.example.com",
 *     port = 3306,
 *     database = "production",
 *     user = "app_user",
 *     password = "${YOUR_PASSWORD}",
 *     sslConfig = SslConfig.withClientCert(
 *         clientCertPath = "/path/to/client-cert.pem",
 *         clientKeyPath = "/path/to/client-key.pem",
 *         caCertPath = "/path/to/ca-cert.pem"
 *     )
 * )
 *
 * // With custom pool and SSL configuration
 * val dataSource = MariadbDataSource(
 *     host = "db.example.com",
 *     port = 3306,
 *     database = "production",
 *     user = "app_user",
 *     password = "${YOUR_PASSWORD}",
 *     poolConfig = PoolConfig(
 *         minConnections = 5,
 *         maxConnections = 20,
 *         validationQuery = "SELECT 1"
 *     ),
 *     sslConfig = SslConfig.REQUIRED
 * )
 * ```
 */
class MariadbDataSource(
    private val host: String,
    private val port: Int = 3306,
    private val database: String,
    private val user: String,
    private val password: String,
    private val sslConfig: SslConfig? = null,
    poolConfig: PoolConfig = PoolConfig()
) : PoolableDataSource(poolConfig) {

    private val connectionString = "$host:$port/$database"
    private val properties: Map<String, String> = buildProperties()

    private fun buildProperties(): Map<String, String> {
        val props = mutableMapOf(
            "user" to user,
            "password" to password
        )

        sslConfig?.let { ssl ->
            if (ssl.enabled != false) {
                props["ssl_enabled"] = "true"

                ssl.clientKeyPath?.let { props["ssl_key"] = it }
                ssl.clientCertPath?.let { props["ssl_cert"] = it }
                ssl.caCertPath?.let { props["ssl_ca"] = it }
                ssl.caPath?.let { props["ssl_capath"] = it }
                ssl.cipherSuites?.let { props["ssl_cipher"] = it }
            }
        }

        return props
    }

    override fun createNewConnection(): Connection {
        return MariadbConnection(connectionString, properties)
    }
}
