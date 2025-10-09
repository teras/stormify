package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.SQLException

/**
 * Base class for Oracle statement implementations, providing common functionality
 * for PreparedStatement and CallableStatement.
 *
 * Consolidates:
 * - Statement preparation
 * - Auto-commit mode handling
 * - Statement execution with proper mode flags
 * - Row count retrieval
 * - Resource cleanup
 */
@OptIn(ExperimentalForeignApi::class)
abstract class OracleStatementBase(
    protected val connection: OracleConnection,
    protected val serviceContext: OCISvcCtxPtr,
    protected val errorHandle: OCIErrorPtr,
    protected val sql: String,
    private val statementType: String
) : AutoCloseable {
    protected val stmtHandle: OCIStmtPtr

    init {
        memScoped {
            val stmtPtr = alloc<CPointerVar<out CPointed>>()

            // Prepare statement
            val result = oci_stmt_prepare2(
                serviceContext.reinterpret(),
                stmtPtr.ptr.reinterpret(),
                errorHandle.reinterpret(),
                sql.cstr.ptr.reinterpret(),
                sql.length.toUInt(),
                null,
                0u,
                OCI_NTV_SYNTAX.toUInt(),
                OCI_DEFAULT.toUInt()
            )

            if (result != OCI_SUCCESS) {
                throw SQLException("Failed to prepare $statementType: ${getOciError(errorHandle)}")
            }

            stmtHandle = stmtPtr.value ?: throw SQLException("Statement handle is null")
        }
    }

    /**
     * Returns the appropriate execution mode based on auto-commit setting.
     */
    protected fun getExecutionMode(): UInt {
        return if (connection.isAutoCommit()) {
            OCI_COMMIT_ON_SUCCESS.toUInt()
        } else {
            OCI_DEFAULT.toUInt()
        }
    }

    /**
     * Executes the statement with the given iteration count.
     * @param iters Number of iterations (1 for DML, 0 for SELECT)
     * @return OCI result code
     */
    protected fun executeStatement(iters: UInt): Int {
        val mode = getExecutionMode()

        return oci_stmt_execute(
            serviceContext.reinterpret(),
            stmtHandle.reinterpret(),
            errorHandle.reinterpret(),
            iters,
            0u, // rowoff
            null, // snap_in
            null, // snap_out
            mode
        )
    }

    /**
     * Retrieves the row count after statement execution.
     */
    protected fun getRowCount(): Int {
        memScoped {
            val rowCount = alloc<UIntVar>()
            oci_attr_get(
                stmtHandle,
                OCI_HTYPE_STMT,
                rowCount.ptr,
                null,
                OCI_ATTR_ROW_COUNT,
                errorHandle.reinterpret()
            )
            return rowCount.value.toInt()
        }
    }

    override fun close() {
        // Release statement
        oci_stmt_release(
            stmtHandle.reinterpret(),
            errorHandle.reinterpret(),
            null,
            0u,
            OCI_DEFAULT.toUInt()
        )
    }
}
