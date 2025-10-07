package onl.ycode.kdbc.postgres

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.Driver
import onl.ycode.kdbc.DriverManager

/**
 * PostgreSQL KDBC Driver implementation.
 */
class PostgresDriver : Driver {
    override fun acceptsURL(url: String): Boolean {
        return url.startsWith("jdbc:postgresql:") ||
               url.startsWith("postgresql:") ||
               url.startsWith("postgres:")
    }

    override fun connect(url: String, properties: Map<String, String>): Connection? {
        if (!acceptsURL(url)) return null

        val connectionString = when {
            url.startsWith("jdbc:postgresql://") -> url.removePrefix("jdbc:")
            url.startsWith("postgres://") -> "postgresql://" + url.removePrefix("postgres://")
            url.startsWith("postgresql://") -> url
            else -> return null
        }

        return PostgresConnection(connectionString, properties)
    }

    override val driverName: String = "PostgreSQL KDBC Driver"
    override val driverVersion: String = "1.0.0"

    companion object {
        init {
            // Auto-register the driver
            DriverManager.registerDriver(PostgresDriver())
        }
    }
}
