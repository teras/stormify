package onl.ycode.kdbc.freetds

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import freetds.*

/**
 * FreeTDS PreparedStatement implementation using db-lib API.
 *
 * Note: FreeTDS db-lib doesn't have native prepared statement support like libpq or MariaDB.
 * Parameters are handled by building parameterized SQL with placeholders and binding values.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class)
class FreeTDSPreparedStatement(
    private val dbContext: CPointer<DBPROCESS>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val paramData = mutableListOf<FreeTDSTypeHelper.ParamData>()
    private var lastInsertId: Long? = null
    private var closed = false

    init {
        // Count parameters (? placeholders)
        var count = 0
        var i = 0
        while (i < sql.length) {
            if (sql[i] == '?') {
                count++
            }
            i++
        }

        // Initialize parameter list
        repeat(count) {
            paramData.add(FreeTDSTypeHelper.ParamData())
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        checkClosed()
        if (parameterIndex < 1 || parameterIndex > paramData.size) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        val data = paramData[index]

        try {
            FreeTDSTypeHelper.bindParameter(value, data)
        } catch (e: Exception) {
            data.clear()
            throw e
        }
    }

    override fun executeUpdate(): Int {
        checkClosed()
        val finalSql = buildFinalSql()

        memScoped {
            // Clear previous command buffer
            dbfreebuf(dbContext)

            // Set command
            if (dbcmd(dbContext, finalSql.cstr.ptr) == FAIL) {
                throw SQLException("Failed to set command")
            }

            // Execute
            if (dbsqlexec(dbContext) == FAIL) {
                throw SQLException("Failed to execute statement")
            }
        }

        // Process results
        var affectedRows = 0
        while (true) {
            val result = dbresults(dbContext)
            when (result) {
                SUCCEED -> {
                    // Check if this is a result set or just a row count
                    val numCols = dbnumcols(dbContext)
                    if (numCols == 0) {
                        // No columns - this is an UPDATE/INSERT/DELETE result
                        affectedRows += dbcount(dbContext).toInt()
                    }

                    // For INSERT with RETURNING clause (SQL Server uses OUTPUT)
                    if (returnGeneratedKeys && numCols > 0) {
                        // Read the first row to get the generated key
                        if (dbnextrow(dbContext) == REG_ROW) {
                            val data = dbdata(dbContext, 1)
                            val dataLen = dbdatlen(dbContext, 1)
                            if (data != null && dataLen > 0) {
                                val value = data.reinterpret<ByteVar>().toKString()
                                lastInsertId = value.toLongOrNull()
                            }
                        }
                    }
                }
                NO_MORE_RESULTS -> break
                FAIL -> throw SQLException("Error processing results")
                else -> break
            }
        }

        return affectedRows
    }

    override fun executeQuery(): ResultSet {
        checkClosed()
        val finalSql = buildFinalSql()

        memScoped {
            // Clear previous command buffer
            dbfreebuf(dbContext)

            // Set command
            if (dbcmd(dbContext, finalSql.cstr.ptr) == FAIL) {
                throw SQLException("Failed to set command")
            }

            // Execute
            if (dbsqlexec(dbContext) == FAIL) {
                throw SQLException("Failed to execute query")
            }
        }

        // Get first result set
        val result = dbresults(dbContext)
        if (result != SUCCEED) {
            throw SQLException("Failed to get query results")
        }

        return FreeTDSResultSet(dbContext)
    }

    override fun getGeneratedKeys(): ResultSet {
        val insertId = lastInsertId
        if (!returnGeneratedKeys || insertId == null) {
            return EmptyResultSet()
        }
        return GeneratedKeysResultSet(insertId)
    }

    override fun close() {
        if (!closed) {
            // Free command buffer
            dbfreebuf(dbContext)
            closed = true
        }
    }

    private fun checkClosed() {
        if (closed) {
            throw SQLException("Statement is closed")
        }
    }

    /**
     * Builds the final SQL by replacing ? placeholders with actual values.
     * This is necessary because FreeTDS db-lib doesn't have native prepared statements.
     */
    private fun buildFinalSql(): String {
        var result = sql
        var paramIndex = 0

        while (paramIndex < paramData.size && result.contains('?')) {
            val data = paramData[paramIndex]
            val valueStr = formatParameter(data)
            result = result.replaceFirst("?", valueStr)
            paramIndex++
        }

        return result
    }

    /**
     * Formats a parameter value for SQL insertion.
     */
    private fun formatParameter(data: FreeTDSTypeHelper.ParamData): String {
        return when (data.type) {
            onl.ycode.kdbc.ParameterType.NULL -> "NULL"
            onl.ycode.kdbc.ParameterType.BYTE,
            onl.ycode.kdbc.ParameterType.SHORT,
            onl.ycode.kdbc.ParameterType.INT,
            onl.ycode.kdbc.ParameterType.LONG,
            onl.ycode.kdbc.ParameterType.FLOAT,
            onl.ycode.kdbc.ParameterType.DOUBLE,
            onl.ycode.kdbc.ParameterType.BOOLEAN -> data.value.toString()
            onl.ycode.kdbc.ParameterType.STRING -> {
                // Escape single quotes
                val str = data.value.safeCast<String>()
                "'${str.replace("'", "''")}'"
            }
            onl.ycode.kdbc.ParameterType.BYTE_ARRAY -> {
                // Convert to hex string for binary data
                val bytes = data.value.safeCast<ByteArray>()
                "0x${bytes.joinToString("") { byte -> byte.toString(16).padStart(2, '0') }}"
            }
            onl.ycode.kdbc.ParameterType.COMPLEX -> throw SQLException("COMPLEX type not supported")
        }
    }
}
