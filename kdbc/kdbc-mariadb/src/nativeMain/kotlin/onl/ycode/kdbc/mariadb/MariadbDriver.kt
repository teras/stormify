package onl.ycode.kdbc.mariadb

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.Driver
import onl.ycode.kdbc.DriverManager

/**
 * MariaDB/MySQL KDBC Driver implementation.
 * Supports both MariaDB and MySQL databases using the MariaDB Connector/C library.
 */
class MariadbDriver : Driver {
    override fun acceptsURL(url: String): Boolean {
        return url.startsWith("jdbc:mysql:") ||
               url.startsWith("jdbc:mariadb:") ||
               url.startsWith("mysql:") ||
               url.startsWith("mariadb:")
    }

    override fun connect(url: String, properties: Map<String, String>): Connection? {
        if (!acceptsURL(url)) return null

        val connectionString = when {
            url.startsWith("jdbc:mysql://") -> url.removePrefix("jdbc:mysql://")
            url.startsWith("jdbc:mariadb://") -> url.removePrefix("jdbc:mariadb://")
            url.startsWith("mysql://") -> url.removePrefix("mysql://")
            url.startsWith("mariadb://") -> url.removePrefix("mariadb://")
            else -> return null
        }

        return MariadbConnection(connectionString, properties)
    }

    override val driverName: String = "MariaDB KDBC Driver"
    override val driverVersion: String = "1.0.0"

    companion object {
        init {
            // Auto-register the driver
            DriverManager.registerDriver(MariadbDriver())
        }
    }
}
