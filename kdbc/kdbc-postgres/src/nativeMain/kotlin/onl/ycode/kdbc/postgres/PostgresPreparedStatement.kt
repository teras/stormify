package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import onl.ycode.kdbc.*
import libpq.*
import kotlin.time.Instant

typealias BDN = com.ionspin.kotlin.bignum.decimal.BigDecimal
typealias BIN = com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * PostgreSQL PreparedStatement implementation using binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class PostgresPreparedStatement(
    private val conn: CPointer<PGconn>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val paramData = mutableListOf<PostgresTypeHelper.ParamData>()
    private val stmtName = "stmt_${kotlin.random.Random.nextLong()}"
    private var lastInsertId: Long? = null

    init {
        // Parse SQL to count parameters
        var count = 0
        var i = 0
        while (i < sql.length) {
            if (sql[i] == '?') {
                count++
            }
            i++
        }

        // Convert JDBC-style ? to PostgreSQL-style $1, $2, etc.
        var pgSql = sql
        for (j in count downTo 1) {
            pgSql = pgSql.replaceFirst("?", "$$j")
        }

        // Prepare statement
        val result = PQprepare(conn, stmtName, pgSql, 0, null)
        try {
            if (result == null || PQresultStatus(result) != PGRES_COMMAND_OK) {
                val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to prepare statement: $error")
            }
        } finally {
            if (result != null) {
                PQclear(result)
            }
        }

        // Initialize parameter list
        repeat(count) {
            paramData.add(PostgresTypeHelper.ParamData())
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        if (parameterIndex < 1 || parameterIndex > paramData.size) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        val data = paramData[index]

        try {
            PostgresTypeHelper.bindParameter(value, data)
        } catch (e: Exception) {
            // If binding fails, clean up allocated memory to prevent leaks
            data.clear()
            throw e
        }
    }

    override fun executeUpdate(): Int {
        val result = executePrepared()
        try {
            val status = PQresultStatus(result)
            if (status != PGRES_COMMAND_OK && status != PGRES_TUPLES_OK) {
                val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute update: $error")
            }

            // Handle generated keys
            if (returnGeneratedKeys && status == PGRES_TUPLES_OK) {
                val nTuples = PQntuples(result)
                if (nTuples > 0) {
                    val value = PQgetvalue(result, 0, 0)
                    lastInsertId = value?.toKString()?.toLongOrNull()
                }
            }

            val affectedRows = PQcmdTuples(result)?.toKString()?.toIntOrNull() ?: 0
            return affectedRows
        } finally {
            PQclear(result)
        }
    }

    override fun executeQuery(): ResultSet {
        val result = executePrepared()
        val status = PQresultStatus(result)
        if (status != PGRES_TUPLES_OK) {
            val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
            PQclear(result)
            throw SQLException("Failed to execute query: $error")
        }

        return PostgresResultSet(result)
    }

    override fun getGeneratedKeys(): ResultSet {
        val insertId = lastInsertId
        if (!returnGeneratedKeys || insertId == null)
            return EmptyResultSet()
        return GeneratedKeysResultSet(insertId)
    }

    override fun close() {
        // Deallocate prepared statement
        val deallocSql = "DEALLOCATE $stmtName"
        val result = PQexec(conn, deallocSql)
        if (result != null) {
            PQclear(result)
        }
    }

    private fun executePrepared(): CPointer<PGresult> {
        memScoped {
            val paramCount = paramData.size
            val paramValuesArray = allocArray<CPointerVar<ByteVar>>(paramCount)
            val paramLengthsArray = allocArray<IntVar>(paramCount)
            val paramFormatsArray = allocArray<IntVar>(paramCount)

            paramData.forEachIndexed { index, data ->
                // Use PostgreSQL-specific allocation helper (extension on MemScope)
                val allocated = PostgresTypeHelper.run { allocateParameter(data) }

                // Set pointer and length from allocated result
                paramValuesArray[index] = allocated.buffer?.reinterpret()
                paramLengthsArray[index] = allocated.size

                // Set format based on type (binary for most, text for strings)
                paramFormatsArray[index] = when (data.type) {
                    onl.ycode.kdbc.ParameterType.STRING -> 0 // text format
                    else -> 1 // binary format
                }
            }

            val result = PQexecPrepared(
                conn,
                stmtName,
                paramCount,
                paramValuesArray,
                paramLengthsArray,
                paramFormatsArray,
                1 // request binary result format
            ) ?: throw SQLException("Failed to execute prepared statement")

            return result
        }
    }
}
