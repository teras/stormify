package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import mariadb.*
import onl.ycode.kdbc.SQLException

/**
 * Base class for MariaDB statement implementations, providing common functionality
 * for PreparedStatement and CallableStatement.
 *
 * Consolidates:
 * - Statement initialization and preparation
 * - Parameter binding setup
 * - Parameter setting logic
 * - Execute methods (executeUpdate, executeQuery, execute)
 * - Resource cleanup
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
abstract class MariadbStatementBase(
    protected val mysql: CPointer<MYSQL>,
    protected val sql: String,
    private val statementType: String
) : AutoCloseable {
    protected val stmt: CPointer<MYSQL_STMT>
    protected val paramCount: Int
    protected val bindParams: CArrayPointer<MYSQL_BIND>?
    protected val paramData = mutableListOf<MariadbParameterHelper.ParameterData>()

    init {
        stmt = mariadb_stmt_init_wrapper(mysql) ?: throw SQLException("Failed to initialize $statementType")

        val prepResult = mariadb_stmt_prepare_wrapper(stmt, sql, sql.length.toULong())
        if (prepResult != 0) {
            val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
            mariadb_stmt_close_wrapper(stmt)
            throw SQLException("Failed to prepare $statementType: $error")
        }

        paramCount = mariadb_stmt_param_count_wrapper(stmt).toInt()

        bindParams = if (paramCount > 0) {
            nativeHeap.allocArray<MYSQL_BIND>(paramCount).apply {
                for (i in 0 until paramCount) {
                    this[i].buffer_type = MYSQL_TYPE_NULL.toUInt()
                    this[i].buffer = null
                    this[i].buffer_length = 0u
                    this[i].is_null = null
                    this[i].length = null
                    this[i].is_unsigned = 0
                }
            }
        } else null

        repeat(paramCount) { paramData.add(MariadbParameterHelper.ParameterData()) }
    }

    /**
     * Sets a parameter value at the specified index.
     * Values are stored for later allocation during execution.
     */
    open fun setObject(parameterIndex: Int, value: Any?) {
        if (parameterIndex < 1 || parameterIndex > paramCount) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        val data = paramData[index]

        try {
            // Store the value (no allocation happens here)
            MariadbParameterHelper.bindParameter(value, data)
        } catch (e: Exception) {
            data.clear()
            throw e
        }
    }

    /**
     * Binds parameters and executes the statement.
     * Parameters are allocated in memScoped for automatic cleanup.
     * @return true if successful
     */
    protected fun bindAndExecute(): Boolean {
        if (paramCount > 0 && bindParams != null) {
            memScoped {
                // Allocate and populate parameters in memScoped
                paramData.forEachIndexed { index, data ->
                    val bind = bindParams[index]

                    // Use MariaDB-specific allocation helper (extension on MemScope)
                    val allocated = MariadbParameterHelper.run { allocateParameter(data) }

                    when (data.type) {
                        onl.ycode.kdbc.ParameterType.NULL -> {
                            bind.buffer_type = MYSQL_TYPE_NULL.toUInt()
                            val isNull = alloc<ByteVar>().apply { value = 1 }
                            bind.is_null = isNull.ptr
                        }
                        onl.ycode.kdbc.ParameterType.BYTE -> {
                            bind.buffer_type = MYSQL_TYPE_TINY.toUInt()
                            bind.buffer = allocated.buffer
                            bind.is_unsigned = 0
                        }
                        onl.ycode.kdbc.ParameterType.SHORT -> {
                            bind.buffer_type = MYSQL_TYPE_SHORT.toUInt()
                            bind.buffer = allocated.buffer
                            bind.is_unsigned = 0
                        }
                        onl.ycode.kdbc.ParameterType.INT -> {
                            bind.buffer_type = MYSQL_TYPE_LONG.toUInt()
                            bind.buffer = allocated.buffer
                            bind.is_unsigned = 0
                        }
                        onl.ycode.kdbc.ParameterType.LONG -> {
                            bind.buffer_type = MYSQL_TYPE_LONGLONG.toUInt()
                            bind.buffer = allocated.buffer
                            bind.is_unsigned = 0
                        }
                        onl.ycode.kdbc.ParameterType.FLOAT -> {
                            bind.buffer_type = MYSQL_TYPE_FLOAT.toUInt()
                            bind.buffer = allocated.buffer
                        }
                        onl.ycode.kdbc.ParameterType.DOUBLE -> {
                            bind.buffer_type = MYSQL_TYPE_DOUBLE.toUInt()
                            bind.buffer = allocated.buffer
                        }
                        onl.ycode.kdbc.ParameterType.BOOLEAN -> {
                            bind.buffer_type = MYSQL_TYPE_TINY.toUInt()
                            bind.buffer = allocated.buffer
                            bind.is_unsigned = 0
                        }
                        onl.ycode.kdbc.ParameterType.STRING -> {
                            bind.buffer_type = MYSQL_TYPE_STRING.toUInt()
                            bind.buffer = allocated.buffer
                            bind.buffer_length = allocated.size.toULong()
                            val lengthVar = alloc<ULongVar>().apply { value = allocated.size.toULong() }
                            bind.length = lengthVar.ptr
                        }
                        onl.ycode.kdbc.ParameterType.BYTE_ARRAY -> {
                            bind.buffer_type = MYSQL_TYPE_BLOB.toUInt()
                            bind.buffer = allocated.buffer
                            bind.buffer_length = allocated.size.toULong()
                            val lengthVar = alloc<ULongVar>().apply { value = allocated.size.toULong() }
                            bind.length = lengthVar.ptr
                        }
                        onl.ycode.kdbc.ParameterType.COMPLEX -> {
                            // Should not happen - bindParameter handles complex types
                            throw SQLException("Unexpected COMPLEX type in bind - this is a bug")
                        }
                    }
                }

                val bindResult = mariadb_stmt_bind_param_wrapper(stmt, bindParams)
                if (bindResult.toInt() != 0) {
                    val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                    throw SQLException("Failed to bind parameters: $error")
                }

                val execResult = mariadb_stmt_execute_wrapper(stmt)
                if (execResult != 0) {
                    val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                    throw SQLException("Failed to execute $statementType: $error")
                }
            } // memScoped auto-frees all allocations here
        } else {
            val execResult = mariadb_stmt_execute_wrapper(stmt)
            if (execResult != 0) {
                val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute $statementType: $error")
            }
        }

        return true
    }

    /**
     * Executes an UPDATE/INSERT/DELETE statement.
     */
    protected fun doExecuteUpdate(): Int {
        bindAndExecute()
        return mariadb_stmt_affected_rows_wrapper(stmt).toInt()
    }

    /**
     * Executes a SELECT statement and returns a result set.
     */
    protected fun doExecuteQuery(): MariadbStmtResultSet {
        bindAndExecute()
        return MariadbStmtResultSet(stmt)
    }

    /**
     * Executes a statement and returns true if it produces a result set.
     */
    protected fun doExecute(): Boolean {
        bindAndExecute()
        val metadata = mariadb_stmt_result_metadata_wrapper(stmt)
        return metadata != null
    }

    override fun close() {
        // Clean up - paramData uses ParameterStorage (no heap allocations to free)
        bindParams?.let { nativeHeap.free(it) }
        mariadb_stmt_close_wrapper(stmt)
    }
}
