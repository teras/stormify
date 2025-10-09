package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.*

/**
 * Oracle Database Connection implementation using ODPI-C.
 *
 * ODPI-C provides a cleaner, higher-level API over raw OCI with:
 * - Simplified connection management
 * - Automatic resource cleanup
 * - Better error handling
 * - Version compatibility across Oracle Client versions
 */
@OptIn(ExperimentalForeignApi::class)
class OracleConnection(
    private val connectString: String,
    private val username: String,
    private val password: String,
    private val sslConfig: SslConfig? = null
) : Connection {

    private val context: CPointer<dpiContext>
    private val connection: CPointer<dpiConn>
    private var autoCommit = true

    companion object {
        // Global context initialization (thread-safe, done once)
        private val globalContext: CPointer<dpiContext> by lazy {
            memScoped {
                val ctxPtr = alloc<CPointerVar<dpiContext>>()
                val errorInfo = alloc<dpiErrorInfo>()

                // Create ODPI-C context with default parameters
                val result = dpiContext_createWithParams(
                    DPI_MAJOR_VERSION.toUInt(),
                    DPI_MINOR_VERSION.toUInt(),
                    null,  // Use default params
                    ctxPtr.ptr,
                    errorInfo.ptr
                )

                if (result != DPI_SUCCESS) {
                    throw SQLException("Failed to create ODPI-C context: ${getErrorMessage(errorInfo)}")
                }

                ctxPtr.value ?: throw SQLException("Context pointer is null")
            }
        }
    }

    init {
        context = globalContext

        memScoped {
            val connPtr = alloc<CPointerVar<dpiConn>>()
            val errorInfo = alloc<dpiErrorInfo>()

            // Initialize connection creation parameters
            val createParams = alloc<dpiConnCreateParams>()
            dpiContext_initConnCreateParams(context, createParams.ptr)

            // Configure SSL if provided
            if (sslConfig?.enabled == true) {
                // ODPI-C handles SSL through Oracle Wallet or connection string
                // The connection string should be pre-configured in TNS format
                // (already handled in OracleDataSource)
            }

            // Create connection
            val result = dpiConn_create(
                context,
                username,
                username.length.toUInt(),
                password,
                password.length.toUInt(),
                connectString,
                connectString.length.toUInt(),
                null,  // Use default common params
                createParams.ptr,
                connPtr.ptr
            )

            if (result != DPI_SUCCESS) {
                dpiContext_getError(context, errorInfo.ptr)
                throw SQLException("Failed to connect to Oracle: ${getErrorMessage(errorInfo)}")
            }

            connection = connPtr.value ?: throw SQLException("Connection pointer is null")
        }
    }

    override val metaData: DatabaseMetaData
        get() = OracleDatabaseMetaData(connection, context)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return OraclePreparedStatement(this, connection, context, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        return OracleCallableStatement(this, connection, context, sql)
    }

    override fun commit() {
        val result = dpiConn_commit(connection)
        if (result != DPI_SUCCESS) {
            throw SQLException("Failed to commit transaction: ${getLastError()}")
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            // Rollback to named savepoint using SQL
            val sql = "ROLLBACK TO SAVEPOINT ${savepoint.savepointName}"
            prepareStatement(sql).use { it.executeUpdate() }
        } else {
            // Full transaction rollback
            val result = dpiConn_rollback(connection)
            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to rollback transaction: ${getLastError()}")
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        // Create savepoint using SQL
        val sql = "SAVEPOINT $name"
        prepareStatement(sql).use { it.executeUpdate() }
        return SimpleSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        // Oracle doesn't have a RELEASE SAVEPOINT command
        // Savepoints are automatically released when the transaction commits or rolls back
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        this.autoCommit = autoCommit
        // ODPI-C doesn't have explicit autocommit setting
        // We handle it by calling commit() after each statement if autoCommit is true
    }

    override fun close() {
        dpiConn_close(connection, DPI_MODE_CONN_CLOSE_DEFAULT, null, 0u)
        // Context is shared and managed globally, don't destroy it here
    }

    internal fun isAutoCommit(): Boolean = autoCommit

    internal fun getConnection(): CPointer<dpiConn> = connection

    /**
     * Get the last error message from this connection.
     */
    internal fun getLastError(): String {
        return memScoped {
            val errorInfo = alloc<dpiErrorInfo>()
            dpiContext_getError(context, errorInfo.ptr)
            getErrorMessage(errorInfo)
        }
    }
}

/**
 * Helper function to extract error message from dpiErrorInfo.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getErrorMessage(errorInfo: dpiErrorInfo): String {
    return buildString {
        append("ORA-${errorInfo.code}: ")
        append(errorInfo.message?.toKString() ?: "Unknown error")
        if (errorInfo.offset > 0u) {
            append(" (at offset ${errorInfo.offset})")
        }
    }
}

/**
 * Helper to get error message from a dpiErrorInfo pointer.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getErrorMessage(errorInfoPtr: CPointer<dpiErrorInfo>): String {
    return getErrorMessage(errorInfoPtr.pointed)
}
