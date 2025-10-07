package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import libpq.*
import kotlin.reflect.KClass

/**
 * PostgreSQL CallableStatement implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresCallableStatement(
    private val conn: CPointer<PGconn>,
    private val sql: String
) : CallableStatement {
    private val outParameters = mutableMapOf<Int, Any?>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        // For callable statements, we'll use simple query execution
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
        // Store for later retrieval - PostgreSQL will handle type conversion internally
        outParameters[parameterIndex] = null
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        return outParameters[parameterIndex]
    }

    override fun execute(): Boolean {
        val result = PQexec(conn, sql)
        try {
            if (result == null) {
                val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute callable statement: $error")
            }

            val status = PQresultStatus(result)
            return status == PGRES_TUPLES_OK
        } finally {
            if (result != null) {
                PQclear(result)
            }
        }
    }

    override fun close() {
        // No-op
    }
}
