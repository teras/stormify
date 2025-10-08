package onl.ycode.kdbc.oracle

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.Driver
import onl.ycode.kdbc.DriverManager

/**
 * Oracle KDBC Driver implementation.
 */
class OracleDriver : Driver {
    override fun acceptsURL(url: String): Boolean {
        return url.startsWith("jdbc:oracle:") ||
               url.startsWith("oracle:")
    }

    override fun connect(url: String, properties: Map<String, String>): Connection? {
        if (!acceptsURL(url)) return null

        // Parse Oracle JDBC URL format:
        // jdbc:oracle:thin:@//host:port/service_name
        // jdbc:oracle:thin:@host:port:SID
        // oracle://host:port/service_name

        val cleanUrl = when {
            url.startsWith("jdbc:oracle:thin:@//") -> url.removePrefix("jdbc:oracle:thin:@//")
            url.startsWith("jdbc:oracle:thin:@") -> url.removePrefix("jdbc:oracle:thin:@")
            url.startsWith("oracle://") -> url.removePrefix("oracle://")
            else -> return null
        }

        // Extract connection details
        val user = properties["user"] ?: return null
        val password = properties["password"] ?: return null

        return OracleConnection(cleanUrl, user, password)
    }

    override val driverName: String = "Oracle KDBC Driver"
    override val driverVersion: String = "1.0.0"

    companion object {
        init {
            // Auto-register the driver
            DriverManager.registerDriver(OracleDriver())
        }
    }
}
