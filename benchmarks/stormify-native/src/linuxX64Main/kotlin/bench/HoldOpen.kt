package bench

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.KdbcDataSource

/**
 * Sequential bench harness needs ONE persistent connection (no pool, no per-call
 * connect/release). [HoldOpenDataSource] hands out the same wrapped [Connection]
 * for every getConnection() call; the wrapper swallows close() so Stormify's
 * ConnectionMaker can do its acquire/release dance without tearing down the real
 * socket. Mirror of what HikariCP gives JPA: one warm connection, reused.
 */
class HoldOpenDataSource(url: String, user: String?, password: String?) : DataSource, AutoCloseable {
    private val real: DataSource = if (user == null) KdbcDataSource(url)
                                   else KdbcDataSource(url, user, password!!)
    private val held: Connection = real.getConnection()
    private val noClose: Connection = object : Connection by held {
        override fun close() { /* kept alive for the run */ }
    }
    override fun getConnection(): Connection = noClose
    override fun close() { held.close() }
}
