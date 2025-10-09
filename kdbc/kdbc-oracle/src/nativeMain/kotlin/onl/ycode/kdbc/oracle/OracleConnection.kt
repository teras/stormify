package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.*

/**
 * Oracle Database Connection implementation using OCI (Oracle Call Interface).
 */
@OptIn(ExperimentalForeignApi::class)
class OracleConnection(
    private val connectString: String,
    private val username: String,
    private val password: String,
    private val sslConfig: SslConfig? = null
) : Connection {

    private val envHandle: OCIEnvPtr
    private val errorHandle: OCIErrorPtr
    private val serviceContext: OCISvcCtxPtr
    private var autoCommit = true

    init {
        memScoped {
            // Allocate environment handle
            val envPtr = alloc<CPointerVar<out CPointed>>()
            val errPtr = alloc<CPointerVar<out CPointed>>()

            // Create OCI environment (OCI_THREADED for thread safety)
            val envResult = oci_env_create(
                envPtr.ptr.reinterpret(),
                (OCI_DEFAULT or OCI_THREADED),
                errPtr.ptr.reinterpret()
            )

            if (envResult != OCI_SUCCESS) {
                throw SQLException("Failed to create OCI environment")
            }

            envHandle = envPtr.value ?: throw SQLException("Environment handle is null")

            // Allocate error handle
            val errHandlePtr = alloc<CPointerVar<out CPointed>>()
            oci_handle_alloc(
                envHandle,
                errHandlePtr.ptr.reinterpret(),
                OCI_HTYPE_ERROR,
                0u,
                null
            )
            errorHandle = errHandlePtr.value ?: throw SQLException("Error handle is null")

            // Logon to Oracle database
            val svcPtr = alloc<CPointerVar<out CPointed>>()
            val logonResult = oci_logon2(
                envHandle.reinterpret(),
                errorHandle.reinterpret(),
                svcPtr.ptr.reinterpret(),
                username.cstr.ptr.reinterpret(),
                username.length.toUInt(),
                password.cstr.ptr.reinterpret(),
                password.length.toUInt(),
                connectString.cstr.ptr.reinterpret(),
                connectString.length.toUInt(),
                OCI_DEFAULT
            )

            if (logonResult != OCI_SUCCESS) {
                val errorMsg = getOciError(errorHandle)
                // Free both handles to prevent memory leak
                oci_handle_free(errorHandle, OCI_HTYPE_ERROR)
                oci_handle_free(envHandle, OCI_HTYPE_ENV)
                throw SQLException("Failed to connect to Oracle: $errorMsg")
            }

            serviceContext = svcPtr.value ?: throw SQLException("Service context is null")
        }
    }

    override val metaData: DatabaseMetaData
        get() = OracleDatabaseMetaData(serviceContext, errorHandle)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return OraclePreparedStatement(this, serviceContext, errorHandle, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        return OracleCallableStatement(this, serviceContext, errorHandle, sql)
    }

    override fun commit() {
        val result = oci_trans_commit(serviceContext.reinterpret(), errorHandle.reinterpret(), OCI_DEFAULT)
        if (result != OCI_SUCCESS) {
            throw SQLException("Failed to commit transaction: ${getOciError(errorHandle)}")
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            // Rollback to named savepoint using SQL
            val sql = "ROLLBACK TO SAVEPOINT ${savepoint.savepointName}"
            prepareStatement(sql).use { it.executeUpdate() }
        } else {
            // Full transaction rollback using OCI
            val result = oci_trans_rollback(serviceContext.reinterpret(), errorHandle.reinterpret(), OCI_DEFAULT)
            if (result != OCI_SUCCESS) {
                throw SQLException("Failed to rollback transaction: ${getOciError(errorHandle)}")
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
        // Oracle doesn't have a RELEASE SAVEPOINT command like MySQL
        // Savepoints are automatically released when the transaction commits or rolls back
        // We can optionally do nothing here, or remove the savepoint explicitly
        // For now, do nothing as Oracle auto-manages savepoint lifecycle
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        this.autoCommit = autoCommit
        // In OCI, autocommit is controlled by execution flags
        // We'll handle this in statement execution
    }

    override fun close() {
        // Logoff
        oci_logoff(serviceContext.reinterpret(), errorHandle.reinterpret())

        // Free handles
        oci_handle_free(errorHandle, OCI_HTYPE_ERROR)
        oci_handle_free(envHandle, OCI_HTYPE_ENV)
    }

    internal fun isAutoCommit(): Boolean = autoCommit
}

/**
 * Helper function to get OCI error message.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun getOciError(errorHandle: OCIErrorPtr): String {
    memScoped {
        val errcode = alloc<IntVar>()
        val buffer = allocArray<ByteVar>(512)

        oci_error_get(
            errorHandle.reinterpret(),
            1u,
            null,
            errcode.ptr,
            buffer.reinterpret(),
            512u
        )

        return buffer.toKString()
    }
}
