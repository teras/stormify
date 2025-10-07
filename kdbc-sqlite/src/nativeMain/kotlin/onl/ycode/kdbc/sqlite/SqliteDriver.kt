package onl.ycode.kdbc.sqlite

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.Driver
import onl.ycode.kdbc.DriverManager

/**
 * SQLite KDBC Driver implementation.
 */
class SqliteDriver : Driver {
    override fun acceptsURL(url: String): Boolean {
        return url.startsWith("jdbc:sqlite:") || url.startsWith("sqlite:")
    }

    override fun connect(url: String, properties: Map<String, String>): Connection? {
        if (!acceptsURL(url)) return null

        val dbPath = when {
            url.startsWith("jdbc:sqlite:") -> url.removePrefix("jdbc:sqlite:")
            url.startsWith("sqlite:") -> url.removePrefix("sqlite:")
            else -> return null
        }

        return SqliteConnection(dbPath)
    }

    override val driverName: String = "SQLite KDBC Driver"
    override val driverVersion: String = "1.0.0"

    companion object {
        init {
            // Auto-register the driver
            DriverManager.registerDriver(SqliteDriver())
        }
    }
}
