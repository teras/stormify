package onl.ycode.kdbc.postgres

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource

/**
 * PostgreSQL DataSource implementation.
 */
class PostgresDataSource(
    private val host: String,
    private val port: Int = 5432,
    private val database: String,
    private val user: String,
    private val password: String
) : DataSource {

    override fun getConnection(): Connection {
        val connectionString = "postgresql://$user:$password@$host:$port/$database"
        return PostgresConnection(connectionString, emptyMap())
    }
}
