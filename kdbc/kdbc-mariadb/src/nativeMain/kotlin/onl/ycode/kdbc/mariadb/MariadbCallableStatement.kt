package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import mariadb.*
import onl.ycode.kdbc.CallableStatement
import onl.ycode.kdbc.EmptyResultSet
import onl.ycode.kdbc.ResultSet
import onl.ycode.kdbc.SQLException
import kotlin.reflect.KClass

/**
 * MariaDB/MySQL CallableStatement implementation with proper OUT parameter support.
 *
 * Uses mysql_stmt_next_result() to iterate through multiple result sets and
 * SERVER_PS_OUT_PARAMS flag to identify the result set containing OUT parameter values.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
class MariadbCallableStatement(
    private val mysqlConn: CPointer<MYSQL>,
    sql: String
) : MariadbStatementBase(mysqlConn, sql, "callable statement"), CallableStatement {
    companion object {
        // MySQL server status flag for OUT parameters result set
        private const val SERVER_PS_OUT_PARAMS = 0x0008u
    }

    private val outParameters = mutableMapOf<Int, KClass<*>>()
    private val outParameterValues = mutableMapOf<Int, Any?>()
    private var hasExecuted = false
    private var primaryResultSet: MariadbStmtResultSet? = null

    override fun executeUpdate(): Int {
        execute()
        return mariadb_stmt_affected_rows_wrapper(stmt).toInt()
    }

    override fun executeQuery(): ResultSet {
        execute()
        return primaryResultSet ?: EmptyResultSet()
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun addBatch() = super.addBatch()

    override fun executeBatch(): IntArray {
        val results = IntArray(batches.size)
        for ((i, params) in batches.withIndex()) {
            for ((index, value) in params) {
                setObject(index, value)
            }
            results[i] = executeUpdate()
        }
        batches.clear()
        return results
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        outParameters[parameterIndex] = type
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        if (!hasExecuted) {
            throw SQLException("Statement must be executed before retrieving OUT parameters")
        }

        if (!outParameters.containsKey(parameterIndex)) {
            throw SQLException("Parameter $parameterIndex is not registered as OUT parameter")
        }

        return outParameterValues[parameterIndex]
    }

    override fun execute(): Boolean {
        hasExecuted = false
        outParameterValues.clear()
        primaryResultSet?.close()
        primaryResultSet = null

        // Execute the stored procedure
        bindAndExecute()

        var hasPrimaryResultSet = false
        var isFirstResult = true

        // Process the first result set
        var metadata = mariadb_stmt_result_metadata_wrapper(stmt)

        while (true) {
            if (metadata != null) {
                // Check if this is the OUT parameters result set
                val serverStatus = mariadb_get_server_status_wrapper(mysqlConn)
                val isOutParamsResultSet = (serverStatus and SERVER_PS_OUT_PARAMS) != 0u

                if (isOutParamsResultSet) {
                    // This result set contains OUT parameter values
                    processOutParameterResultSet()
                    mysql_free_result(metadata)
                } else {
                    // This is a regular result set (from SELECT statements in the procedure)
                    if (isFirstResult) {
                        // Store the first result set as the primary one
                        primaryResultSet = MariadbStmtResultSet(stmt)
                        hasPrimaryResultSet = true
                        isFirstResult = false
                        // Don't free metadata - MariadbStmtResultSet will do it
                    } else {
                        // Consume and skip additional result sets
                        while (mariadb_stmt_fetch_wrapper(stmt) == 0) {
                            // Skip rows
                        }
                        mysql_free_result(metadata)
                    }
                }
            }

            // Move to next result set
            val nextResult = mariadb_stmt_next_result_wrapper(stmt)
            if (nextResult > 0) {
                // Error occurred
                val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                throw SQLException("Error processing result sets: $error")
            } else if (nextResult < 0) {
                // No more results
                break
            }

            // Get metadata for the next result set (nextResult == 0 means there's another result)
            metadata = mariadb_stmt_result_metadata_wrapper(stmt)
        }

        hasExecuted = true
        return hasPrimaryResultSet
    }

    override fun close() {
        primaryResultSet?.close()
        super.close()
    }

    /**
     * Processes the OUT parameters result set.
     * The result set contains one row with columns corresponding to OUT/INOUT parameters.
     */
    private fun processOutParameterResultSet() {
        if (outParameters.isEmpty()) {
            // No OUT parameters registered, skip processing
            return
        }

        memScoped {
            val metadata = mariadb_stmt_result_metadata_wrapper(stmt) ?: return@memScoped
            val numColumns = mysql_num_fields(metadata).toInt()

            if (numColumns == 0) {
                mysql_free_result(metadata)
                return@memScoped
            }

            // Allocate bind structures
            val bindResults = allocArray<MYSQL_BIND>(numColumns)
            val buffers = mutableListOf<CPointer<ByteVar>>()
            val isNullFlags = mutableListOf<CPointer<ByteVar>>()
            val lengths = mutableListOf<CPointer<ULongVar>>()

            // Setup bindings for all columns
            for (i in 0 until numColumns) {
                val bind = bindResults[i]
                bind.buffer_type = MYSQL_TYPE_STRING.toUInt()

                // Allocate buffer for string representation (handles all types)
                val buffer = allocArray<ByteVar>(4096)
                val isNull = alloc<ByteVar>()
                val length = alloc<ULongVar>()

                buffers.add(buffer)
                isNullFlags.add(isNull.ptr)
                lengths.add(length.ptr)

                bind.buffer = buffer
                bind.buffer_length = 4096u
                bind.is_null = isNull.ptr
                bind.length = length.ptr
            }

            // Bind result buffers
            if (mariadb_stmt_bind_result_wrapper(stmt, bindResults).toInt() != 0) {
                mysql_free_result(metadata)
                throw SQLException("Failed to bind OUT parameter result set")
            }

            // Store result (required for fetching)
            mariadb_stmt_store_result_wrapper(stmt)

            // Fetch the row containing OUT parameter values
            if (mariadb_stmt_fetch_wrapper(stmt) == 0) {
                // Successfully fetched the row
                // Map columns to parameter indices
                // OUT parameters are returned in the order they appear in the procedure signature
                val outParamIndices = outParameters.keys.sorted()

                for ((columnIndex, paramIndex) in outParamIndices.withIndex()) {
                    if (columnIndex >= numColumns) break

                    val isNull = isNullFlags[columnIndex].pointed.value.toInt() == 1
                    val outType = outParameters[paramIndex] ?: continue

                    if (isNull) {
                        outParameterValues[paramIndex] = null
                    } else {
                        val len = lengths[columnIndex].pointed.value.toInt()
                        val valueStr = buffers[columnIndex].toKString().take(len)

                        // Convert string to the requested type
                        val value = MariadbParameterHelper.parseValue(valueStr, outType)
                        outParameterValues[paramIndex] = value
                    }
                }
            }

            mysql_free_result(metadata)
        }
    }
}
