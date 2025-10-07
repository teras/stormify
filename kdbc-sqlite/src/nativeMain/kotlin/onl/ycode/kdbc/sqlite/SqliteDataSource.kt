package onl.ycode.kdbc.sqlite

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource

/**
 * SQLite DataSource implementation.
 */
class SqliteDataSource(private val url: String) : DataSource {
    fun getConnection(): Connection {
        return SqliteConnection(url)
    }
}
