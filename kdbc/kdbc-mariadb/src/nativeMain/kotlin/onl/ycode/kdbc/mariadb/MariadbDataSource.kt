package onl.ycode.kdbc.mariadb

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource

/**
 * MariaDB/MySQL DataSource implementation.
 */
class MariadbDataSource(
    private val host: String,
    private val port: Int = 3306,
    private val database: String,
    private val user: String,
    private val password: String
) : DataSource {

    override fun getConnection(): Connection {
        val connectionString = "$host:$port/$database"
        val properties = mapOf(
            "user" to user,
            "password" to password
        )
        return MariadbConnection(connectionString, properties)
    }
}
