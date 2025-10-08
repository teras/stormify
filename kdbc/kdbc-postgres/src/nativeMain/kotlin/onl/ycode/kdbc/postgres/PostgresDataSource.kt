package onl.ycode.kdbc.postgres

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.PoolableDataSource
import onl.ycode.kdbc.PoolConfig
import onl.ycode.kdbc.SslConfig

/**
 * PostgreSQL DataSource implementation with built-in connection pooling and SSL/TLS support.
 *
 * Example usage:
 * ```kotlin
 * // Basic connection (no SSL)
 * val dataSource = PostgresDataSource("localhost", 5432, "mydb", "user", "pass")
 *
 * // With SSL/TLS enabled (require SSL, verify CA certificate)
 * val dataSource = PostgresDataSource(
 *     host = "db.example.com",
 *     port = 5432,
 *     database = "production",
 *     user = "app_user",
 *     password = "secure_pass",
 *     sslConfig = SslConfig.verifyCA("/path/to/ca-cert.pem")
 * )
 *
 * // With full SSL verification (certificate + hostname)
 * val dataSource = PostgresDataSource(
 *     host = "db.example.com",
 *     port = 5432,
 *     database = "production",
 *     user = "app_user",
 *     password = "secure_pass",
 *     sslConfig = SslConfig.verifyFull("/path/to/ca-cert.pem")
 * )
 *
 * // With client certificate authentication
 * val dataSource = PostgresDataSource(
 *     host = "db.example.com",
 *     port = 5432,
 *     database = "production",
 *     user = "app_user",
 *     password = "secure_pass",
 *     sslConfig = SslConfig.withClientCert(
 *         clientCertPath = "/path/to/client-cert.pem",
 *         clientKeyPath = "/path/to/client-key.pem",
 *         caCertPath = "/path/to/ca-cert.pem"
 *     )
 * )
 *
 * // With custom pool configuration
 * val dataSource = PostgresDataSource(
 *     host = "db.example.com",
 *     port = 5432,
 *     database = "production",
 *     user = "app_user",
 *     password = "secure_pass",
 *     poolConfig = PoolConfig(
 *         minConnections = 5,
 *         maxConnections = 20,
 *         validationQuery = "SELECT 1"
 *     ),
 *     sslConfig = SslConfig.REQUIRED
 * )
 * ```
 */
class PostgresDataSource(
    private val host: String,
    private val port: Int = 5432,
    private val database: String,
    private val user: String,
    private val password: String,
    private val sslConfig: SslConfig? = null,
    poolConfig: PoolConfig = PoolConfig()
) : PoolableDataSource(poolConfig) {

    private val connectionString: String = buildConnectionString()

    private fun buildConnectionString(): String {
        val baseUri = "postgresql://$user:$password@$host:$port/$database"

        val sslParams = mutableListOf<String>()

        sslConfig?.let { ssl ->
            // SSL mode
            when {
                ssl.mode != null -> sslParams.add("sslmode=${ssl.mode}")
                ssl.enabled == true -> sslParams.add("sslmode=require")
                ssl.enabled == false -> sslParams.add("sslmode=disable")
                // null (prefer) is the default, no need to add
            }

            // Certificate paths
            ssl.clientCertPath?.let { sslParams.add("sslcert=$it") }
            ssl.clientKeyPath?.let { sslParams.add("sslkey=$it") }
            ssl.caCertPath?.let { sslParams.add("sslrootcert=$it") }
            ssl.caPath?.let { sslParams.add("sslrootcert=$it") } // PostgreSQL doesn't have a separate CA dir option
            ssl.crlPath?.let { sslParams.add("sslcrl=$it") }

            // TLS versions (PostgreSQL 12+)
            ssl.minTlsVersion?.let { sslParams.add("ssl_min_protocol_version=$it") }
            ssl.maxTlsVersion?.let { sslParams.add("ssl_max_protocol_version=$it") }
        }

        return if (sslParams.isNotEmpty()) {
            "$baseUri?${sslParams.joinToString("&")}"
        } else {
            baseUri
        }
    }

    override fun createNewConnection(): Connection {
        return PostgresConnection(connectionString, emptyMap())
    }
}
