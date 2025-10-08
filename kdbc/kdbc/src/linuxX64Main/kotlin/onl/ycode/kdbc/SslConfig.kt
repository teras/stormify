package onl.ycode.kdbc

/**
 * SSL/TLS configuration for database connections.
 *
 * This class provides a database-agnostic way to configure SSL/TLS settings.
 * Different databases will interpret these settings according to their specific
 * SSL implementation.
 */
data class SslConfig(
    /**
     * Enable or disable SSL/TLS for the connection.
     * - true: SSL is required
     * - false: SSL is disabled
     * - null: SSL is preferred but not required (default)
     */
    val enabled: Boolean? = null,

    /**
     * SSL mode - database-specific SSL behavior.
     *
     * PostgreSQL modes:
     * - "disable": No SSL
     * - "allow": Try non-SSL first, then SSL
     * - "prefer": Try SSL first, then non-SSL (default)
     * - "require": SSL required, no certificate verification
     * - "verify-ca": SSL required, verify server certificate
     * - "verify-full": SSL required, verify server certificate and hostname
     *
     * MySQL/MariaDB modes:
     * - "DISABLED": No SSL
     * - "PREFERRED": Try SSL, fall back to non-SSL (default)
     * - "REQUIRED": SSL required
     * - "VERIFY_CA": SSL required, verify server certificate
     * - "VERIFY_IDENTITY": SSL required, verify server certificate and hostname
     */
    val mode: String? = null,

    /**
     * Path to the client certificate file (PEM format).
     * Used for client certificate authentication.
     */
    val clientCertPath: String? = null,

    /**
     * Path to the client private key file (PEM format).
     * Used for client certificate authentication.
     */
    val clientKeyPath: String? = null,

    /**
     * Path to the CA (Certificate Authority) certificate file (PEM format).
     * Used to verify the server's certificate.
     */
    val caCertPath: String? = null,

    /**
     * Path to a directory containing CA certificates (PEM format).
     * Alternative to caCertPath for multiple CA certificates.
     */
    val caPath: String? = null,

    /**
     * List of allowed SSL/TLS cipher suites.
     * Format is database-specific (e.g., OpenSSL cipher list format).
     */
    val cipherSuites: String? = null,

    /**
     * Path to Certificate Revocation List file (PEM format).
     * Used to check if server certificates have been revoked.
     */
    val crlPath: String? = null,

    /**
     * Minimum TLS version to accept.
     * Examples: "TLSv1.2", "TLSv1.3"
     */
    val minTlsVersion: String? = null,

    /**
     * Maximum TLS version to accept.
     * Examples: "TLSv1.2", "TLSv1.3"
     */
    val maxTlsVersion: String? = null
) {
    companion object {
        /**
         * No SSL/TLS encryption.
         */
        val DISABLED = SslConfig(enabled = false)

        /**
         * SSL/TLS preferred but not required (tries SSL first, falls back to non-SSL).
         */
        val PREFERRED = SslConfig(enabled = null)

        /**
         * SSL/TLS required, but server certificate is not verified.
         * Warning: Vulnerable to man-in-the-middle attacks.
         */
        val REQUIRED = SslConfig(enabled = true)

        /**
         * SSL/TLS required with server certificate verification.
         * Requires caCertPath to be set.
         */
        fun verifyCA(caCertPath: String) = SslConfig(
            enabled = true,
            mode = "verify-ca",
            caCertPath = caCertPath
        )

        /**
         * SSL/TLS required with full server certificate and hostname verification.
         * Requires caCertPath to be set.
         * Most secure option.
         */
        fun verifyFull(caCertPath: String) = SslConfig(
            enabled = true,
            mode = "verify-full",
            caCertPath = caCertPath
        )

        /**
         * SSL/TLS with client certificate authentication.
         */
        fun withClientCert(
            clientCertPath: String,
            clientKeyPath: String,
            caCertPath: String? = null
        ) = SslConfig(
            enabled = true,
            clientCertPath = clientCertPath,
            clientKeyPath = clientKeyPath,
            caCertPath = caCertPath
        )
    }
}
