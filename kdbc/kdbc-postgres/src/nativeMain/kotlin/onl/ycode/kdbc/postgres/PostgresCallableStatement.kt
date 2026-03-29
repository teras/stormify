package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import kotlinx.datetime.*
import onl.ycode.kdbc.*
import libpq.*
import kotlin.reflect.KClass
import kotlin.time.Instant as KtInstant
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

/**
 * PostgreSQL CallableStatement implementation.
 *
 * PostgreSQL stored procedures with OUT parameters return values as a result set.
 * When you CALL a procedure with OUT parameters, PostgreSQL returns PGRES_TUPLES_OK
 * with one row containing the OUT parameter values.
 *
 * ## Thread Safety
 * This class is **NOT thread-safe**. Each thread must create its own CallableStatement instance.
 * Do not share a single CallableStatement instance across multiple threads.
 *
 * Following JDBC standards, statements should not be shared between threads. If multiple
 * threads need to execute stored procedures, each thread should obtain its own CallableStatement
 * from the Connection (which itself should come from a connection pool for thread safety).
 *
 * **Safe usage:**
 * ```kotlin
 * // Thread 1
 * connection.prepareCall(sql).use { stmt ->
 *     stmt.registerOutParameter(1, Int::class)
 *     stmt.execute()
 *     val result = stmt.getObject(1, Int::class)
 * }
 *
 * // Thread 2 - creates its own statement
 * connection.prepareCall(sql).use { stmt ->
 *     stmt.registerOutParameter(1, String::class)
 *     stmt.execute()
 *     val result = stmt.getObject(1, String::class)
 * }
 * ```
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class PostgresCallableStatement(
    private val conn: CPointer<PGconn>,
    private val sql: String
) : CallableStatement {
    private val outParameters = mutableMapOf<Int, KClass<*>>()
    private var outParameterResult: CPointer<PGresult>? = null
    private val currentParams = mutableMapOf<Int, Any?>()
    private val batches = mutableListOf<Map<Int, Any?>>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        currentParams[parameterIndex] = value
        // For callable statements, we'll use simple query execution
    }

    override fun addBatch() {
        batches.add(currentParams.toMap())
        currentParams.clear()
    }

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

    override fun executeUpdate(): Int {
        val result = PQexec(conn, sql)
        try {
            if (result == null) {
                val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute callable statement: $error")
            }

            val status = PQresultStatus(result)
            if (status != PGRES_COMMAND_OK) {
                val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute callable statement: $error")
            }

            return PQcmdTuples(result)?.toKString()?.toIntOrNull() ?: 0
        } finally {
            if (result != null) {
                PQclear(result)
            }
        }
    }

    override fun executeQuery(): ResultSet {
        val result = PQexec(conn, sql)
        if (result == null) {
            val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute callable statement: $error")
        }

        val status = PQresultStatus(result)
        if (status != PGRES_TUPLES_OK) {
            val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
            PQclear(result)
            throw SQLException("Failed to execute callable statement: $error")
        }

        return PostgresResultSet(result)
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        // Store the expected type for this OUT parameter
        outParameters[parameterIndex] = type
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        // PostgreSQL returns OUT parameters as a result set with PGRES_TUPLES_OK
        // The OUT parameters are in columns 0, 1, 2, etc. (0-indexed)
        val result = outParameterResult
            ?: throw SQLException("No result available - execute the statement first")

        if (!outParameters.containsKey(parameterIndex)) {
            throw SQLException("Parameter $parameterIndex not registered as OUT parameter")
        }

        // Convert to 0-based column index
        val columnIndex = parameterIndex - 1

        // Check if we have rows
        val nRows = PQntuples(result)
        if (nRows == 0) {
            return null
        }

        // Check if column exists
        val nFields = PQnfields(result)
        if (columnIndex >= nFields) {
            throw SQLException("OUT parameter index $parameterIndex exceeds available columns ($nFields)")
        }

        // Check if value is NULL
        if (PQgetisnull(result, 0, columnIndex) != 0) {
            return null
        }

        // Get the value and its length
        val valuePtr = PQgetvalue(result, 0, columnIndex) ?: return null
        val valueLength = PQgetlength(result, 0, columnIndex)

        // PostgreSQL CallableStatements can return data in binary or text format
        // Try binary format first, fall back to text if needed
        return PostgresTypeHelper.readBinaryValue(valuePtr, valueLength, type)
            ?: PostgresTypeHelper.readTextValue(valuePtr.toKString(), type)
    }

    override fun execute(): Boolean {
        // Clear previous result if any
        outParameterResult?.let { PQclear(it) }
        outParameterResult = null

        val result = PQexec(conn, sql)
        if (result == null) {
            val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute callable statement: $error")
        }

        val status = PQresultStatus(result)

        // PostgreSQL returns PGRES_TUPLES_OK for procedures with OUT parameters
        if (status == PGRES_TUPLES_OK) {
            // Store the result for later retrieval of OUT parameters
            outParameterResult = result
            return true
        } else if (status == PGRES_COMMAND_OK) {
            // No result set (no OUT parameters)
            PQclear(result)
            return false
        } else {
            val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
            PQclear(result)
            throw SQLException("Failed to execute callable statement: $error")
        }
    }

    override fun close() {
        // Clean up the stored OUT parameter result
        outParameterResult?.let { PQclear(it) }
        outParameterResult = null
    }
}
