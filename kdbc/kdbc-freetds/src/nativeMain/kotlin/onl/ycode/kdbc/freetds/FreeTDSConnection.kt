package onl.ycode.kdbc.freetds

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import freetds.*

/**
 * FreeTDS Connection implementation for MS SQL Server.
 */
@OptIn(ExperimentalForeignApi::class)
class FreeTDSConnection(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val user: String,
    private val password: String,
    private val instanceName: String? = null,
    private val appName: String? = null
) : Connection {
    internal val dbContext: CPointer<DBPROCESS>
    private val loginContext: CPointer<LOGINREC>
    private var autoCommit = true
    private var inTransaction = false
    private var closed = false

    init {
        // Initialize db-lib
        if (dbinit() == FAIL) {
            throw SQLException("Failed to initialize FreeTDS db-lib")
        }

        // Create login context
        val login = dblogin()
            ?: throw SQLException("Failed to create login context")
        loginContext = login

        // Set login properties using dbsetlname
        if (dbsetlname(loginContext, user, DBSETUSER) == FAIL) {
            dbloginfree(loginContext)
            throw SQLException("Failed to set username")
        }
        if (dbsetlname(loginContext, password, DBSETPWD) == FAIL) {
            dbloginfree(loginContext)
            throw SQLException("Failed to set password")
        }
        appName?.let {
            dbsetlname(loginContext, it, DBSETAPP)
        }

        // Connect to server
        val serverName = instanceName?.let { "$host\\$it" } ?: host
        val db = dbopen(loginContext, serverName)
        if (db == null) {
            dbloginfree(loginContext)
            throw SQLException("Failed to connect to server: $serverName")
        }
        dbContext = db

        // Select database
        if (dbuse(dbContext, database) == FAIL) {
            dbclose(dbContext)
            dbloginfree(loginContext)
            throw SQLException("Failed to select database: $database")
        }
    }

    override val metaData: DatabaseMetaData
        get() = FreeTDSDatabaseMetaData(dbContext)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        checkClosed()
        return FreeTDSPreparedStatement(dbContext, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        checkClosed()
        return FreeTDSCallableStatement(dbContext, sql)
    }

    override fun commit() {
        checkClosed()
        if (!inTransaction) {
            throw SQLException("No active transaction to commit")
        }
        prepareStatement("COMMIT TRANSACTION").use { it.executeUpdate() }
        inTransaction = false
        if (!autoCommit) {
            prepareStatement("BEGIN TRANSACTION").use { it.executeUpdate() }
            inTransaction = true
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        checkClosed()
        if (savepoint != null) {
            val sql = "ROLLBACK TRANSACTION ${savepoint.savepointName}"
            prepareStatement(sql).use { it.executeUpdate() }
        } else {
            if (!inTransaction) {
                throw SQLException("No active transaction to rollback")
            }
            prepareStatement("ROLLBACK TRANSACTION").use { it.executeUpdate() }
            inTransaction = false
            if (!autoCommit) {
                prepareStatement("BEGIN TRANSACTION").use { it.executeUpdate() }
                inTransaction = true
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        checkClosed()
        if (!inTransaction) {
            prepareStatement("BEGIN TRANSACTION").use { it.executeUpdate() }
            inTransaction = true
        }
        prepareStatement("SAVE TRANSACTION $name").use { it.executeUpdate() }
        return SimpleSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        // SQL Server doesn't have RELEASE SAVEPOINT command
        // Savepoints are automatically released on commit or when no longer needed
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        checkClosed()
        if (this.autoCommit != autoCommit) {
            if (autoCommit) {
                if (inTransaction) {
                    prepareStatement("COMMIT TRANSACTION").use { it.executeUpdate() }
                    inTransaction = false
                }
            } else {
                if (!inTransaction) {
                    prepareStatement("BEGIN TRANSACTION").use { it.executeUpdate() }
                    inTransaction = true
                }
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        if (!closed) {
            if (inTransaction) {
                try {
                    prepareStatement("ROLLBACK TRANSACTION").use { it.executeUpdate() }
                } catch (_: Exception) {
                    // Ignore errors during close
                }
            }
            dbclose(dbContext)
            dbloginfree(loginContext)
            dbexit()
            closed = true
        }
    }

    private fun checkClosed() {
        if (closed) {
            throw SQLException("Connection is closed")
        }
    }
}
