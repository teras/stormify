package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import libpq.*

/**
 * PostgreSQL Connection implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresConnection(
    private val connectionString: String,
    private val properties: Map<String, String> = emptyMap()
) : Connection {
    private val conn: CPointer<PGconn>
    private var autoCommit = true
    private var inTransaction = false

    init {
        // Build connection string with properties
        val connStr = if (properties.isNotEmpty()) {
            val params = properties.entries.joinToString(" ") { "${it.key}=${it.value}" }
            "$connectionString $params"
        } else {
            connectionString
        }

        // Connect to database
        val pgConn = PQconnectdb(connStr)
            ?: throw SQLException("Failed to allocate connection")

        // Check connection status
        val status = PQstatus(pgConn)
        if (status != ConnStatusType.CONNECTION_OK) {
            val error = PQerrorMessage(pgConn)?.toKString() ?: "Unknown error"
            PQfinish(pgConn)
            throw SQLException("Failed to connect to database: $error")
        }

        conn = pgConn
    }

    override val metaData: DatabaseMetaData
        get() = PostgresDatabaseMetaData(conn)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return PostgresPreparedStatement(conn, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        return PostgresCallableStatement(conn, sql)
    }

    override fun commit() {
        if (!inTransaction) {
            throw SQLException("No active transaction to commit")
        }
        prepareStatement("COMMIT").use { it.executeUpdate() }
        inTransaction = false
        if (!autoCommit) {
            prepareStatement("BEGIN").use { it.executeUpdate() }
            inTransaction = true
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            prepareStatement("ROLLBACK TO SAVEPOINT ${savepoint.savepointName}").use { it.executeUpdate() }
        } else {
            if (!inTransaction) {
                throw SQLException("No active transaction to rollback")
            }
            prepareStatement("ROLLBACK").use { it.executeUpdate() }
            inTransaction = false
            if (!autoCommit) {
                prepareStatement("BEGIN").use { it.executeUpdate() }
                inTransaction = true
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        if (!inTransaction) {
            prepareStatement("BEGIN").use { it.executeUpdate() }
            inTransaction = true
        }
        prepareStatement("SAVEPOINT $name").use { it.executeUpdate() }
        return PostgresSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        prepareStatement("RELEASE SAVEPOINT ${savepoint.savepointName}").use { it.executeUpdate() }
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (this.autoCommit != autoCommit) {
            if (autoCommit) {
                if (inTransaction) {
                    prepareStatement("COMMIT").use { it.executeUpdate() }
                    inTransaction = false
                }
            } else {
                if (!inTransaction) {
                    prepareStatement("BEGIN").use { it.executeUpdate() }
                    inTransaction = true
                }
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        if (inTransaction) {
            try {
                prepareStatement("ROLLBACK").use { it.executeUpdate() }
            } catch (_: Exception) {
                // Ignore errors during close
            }
        }
        PQfinish(conn)
    }
}
