package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import libpq.*
import kotlin.reflect.KClass

/**
 * PostgreSQL Connection implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresConnection(
    private val connectionString: String,
    private val properties: Map<String, String> = emptyMap()
) : Connection {
    private val conn: CPointer<PGconn>
    private var autoCommit = true
    private var inTransaction = false

    init {
        // Build connection string with properties
        val connStr = if (properties.isNotEmpty()) {
            val params = properties.entries.joinToString(" ") { "${it.key}=${it.value}" }
            "$connectionString $params"
        } else {
            connectionString
        }

        // Connect to database
        val pgConn = PQconnectdb(connStr)
            ?: throw SQLException("Failed to allocate connection")

        // Check connection status
        val status = PQstatus(pgConn)
        if (status != ConnStatusType.CONNECTION_OK) {
            val error = PQerrorMessage(pgConn)?.toKString() ?: "Unknown error"
            PQfinish(pgConn)
            throw SQLException("Failed to connect to database: $error")
        }

        conn = pgConn
    }

    override val metaData: DatabaseMetaData
        get() = PostgresDatabaseMetaData(conn)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return PostgresPreparedStatement(conn, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        return PostgresCallableStatement(conn, sql)
    }

    override fun commit() {
        if (!inTransaction) {
            throw SQLException("No active transaction to commit")
        }
        executeSimpleSQL("COMMIT")
        inTransaction = false
        if (!autoCommit) {
            executeSimpleSQL("BEGIN")
            inTransaction = true
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            executeSimpleSQL("ROLLBACK TO SAVEPOINT ${savepoint.savepointName}")
        } else {
            if (!inTransaction) {
                throw SQLException("No active transaction to rollback")
            }
            executeSimpleSQL("ROLLBACK")
            inTransaction = false
            if (!autoCommit) {
                executeSimpleSQL("BEGIN")
                inTransaction = true
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        if (!inTransaction) {
            executeSimpleSQL("BEGIN")
            inTransaction = true
        }
        executeSimpleSQL("SAVEPOINT $name")
        return PostgresSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        executeSimpleSQL("RELEASE SAVEPOINT ${savepoint.savepointName}")
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (this.autoCommit != autoCommit) {
            if (autoCommit) {
                if (inTransaction) {
                    executeSimpleSQL("COMMIT")
                    inTransaction = false
                }
            } else {
                if (!inTransaction) {
                    executeSimpleSQL("BEGIN")
                    inTransaction = true
                }
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        if (inTransaction) {
            try {
                executeSimpleSQL("ROLLBACK")
            } catch (_: Exception) {
                // Ignore errors during close
            }
        }
        PQfinish(conn)
    }

    internal fun executeSimpleSQL(sql: String) {
        val result = PQexec(conn, sql)
        try {
            if (result == null) {
                val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute SQL '$sql': $error")
            }

            val status = PQresultStatus(result)
            if (status != PGRES_COMMAND_OK && status != PGRES_TUPLES_OK) {
                val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute SQL '$sql': $error")
            }
        } finally {
            if (result != null) {
                PQclear(result)
            }
        }
    }
}

/**
 * PostgreSQL DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresDatabaseMetaData(private val conn: CPointer<PGconn>) : DatabaseMetaData {
    override val databaseProductName: String = "PostgreSQL"

    override val databaseProductVersion: String
        get() {
            val result = PQexec(conn, "SELECT version()")
            try {
                if (result != null && PQresultStatus(result) == PGRES_TUPLES_OK) {
                    val value = PQgetvalue(result, 0, 0)
                    return value?.toKString() ?: "Unknown"
                }
                return "Unknown"
            } finally {
                if (result != null) {
                    PQclear(result)
                }
            }
        }

    override val databaseMajorVersion: Int
        get() = PQserverVersion(conn) / 10000

    override val databaseMinorVersion: Int
        get() = (PQserverVersion(conn) % 10000) / 100
}

/**
 * PostgreSQL PreparedStatement implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresPreparedStatement(
    private val conn: CPointer<PGconn>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val paramValues = mutableListOf<String?>()
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
            paramValues.add(null)
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        if (parameterIndex < 1 || parameterIndex > paramValues.size) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        paramValues[index] = when (value) {
            null -> null
            is ByteArray -> {
                // Convert ByteArray to hex string for PostgreSQL bytea
                value.joinToString("") {
                    val hex = it.toUByte().toString(16)
                    if (hex.length == 1) "0$hex" else hex
                }
            }
            else -> value.toString()
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
        if (!returnGeneratedKeys || lastInsertId == null) {
            return EmptyPostgresResultSet()
        }
        return GeneratedKeysResultSet(lastInsertId!!)
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
            val paramCount = paramValues.size
            val paramValuesArray = allocArray<CPointerVar<ByteVar>>(paramCount)

            paramValues.forEachIndexed { index, value ->
                paramValuesArray[index] = value?.cstr?.ptr
            }

            val result = PQexecPrepared(
                conn,
                stmtName,
                paramCount,
                paramValuesArray,
                null,
                null,
                0
            ) ?: throw SQLException("Failed to execute prepared statement")

            return result
        }
    }
}

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
        return EmptyPostgresResultSet()
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

/**
 * PostgreSQL ResultSet implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresResultSet(private val result: CPointer<PGresult>) : ResultSet {
    private var currentRow = -1
    private val rowCount = PQntuples(result)
    private val columnCount = PQnfields(result)

    override fun next(): Boolean {
        currentRow++
        return currentRow < rowCount
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (currentRow < 0 || currentRow >= rowCount) {
            throw SQLException("No current row")
        }

        if (columnIndex < 1 || columnIndex > columnCount) {
            throw SQLException("Invalid column index: $columnIndex")
        }

        val colIndex = columnIndex - 1

        if (PQgetisnull(result, currentRow, colIndex) == 1) {
            return null
        }

        val value = PQgetvalue(result, currentRow, colIndex)?.toKString()
            ?: return null

        return when (type) {
            Int::class -> value.toIntOrNull()
            Long::class -> value.toLongOrNull()
            Double::class -> value.toDoubleOrNull()
            Float::class -> value.toFloatOrNull()
            String::class -> value
            Boolean::class -> value.lowercase() == "t" || value == "1" || value.lowercase() == "true"
            ByteArray::class -> {
                // Parse hex string back to ByteArray
                if (value.startsWith("\\x")) {
                    val hex = value.substring(2)
                    ByteArray(hex.length / 2) { i ->
                        hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                    }
                } else {
                    value.encodeToByteArray()
                }
            }
            else -> value
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return PostgresResultSetMetaData(result, columnCount)
    }

    override fun close() {
        PQclear(result)
    }
}

/**
 * PostgreSQL ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresResultSetMetaData(
    private val result: CPointer<PGresult>,
    override val columnCount: Int
) : ResultSetMetaData {

    override fun getColumnName(column: Int): String {
        if (column < 1 || column > columnCount) {
            throw SQLException("Invalid column index: $column")
        }

        val colIndex = column - 1
        return PQfname(result, colIndex)?.toKString() ?: "COLUMN_$column"
    }
}

/**
 * PostgreSQL Savepoint implementation.
 */
class PostgresSavepoint(override val savepointName: String) : Savepoint

/**
 * Empty ResultSet implementation for cases where no results are available.
 */
class EmptyPostgresResultSet : ResultSet {
    override fun next(): Boolean = false

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        throw SQLException("No rows available")
    }

    override fun getMetaData(): ResultSetMetaData {
        return object : ResultSetMetaData {
            override val columnCount: Int = 0
            override fun getColumnName(column: Int): String {
                throw SQLException("No columns available")
            }
        }
    }

    override fun close() {
        // No-op
    }
}

/**
 * ResultSet implementation for generated keys (auto-increment IDs).
 */
class GeneratedKeysResultSet(private val generatedKey: Long) : ResultSet {
    private var consumed = false

    override fun next(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!consumed) {
            throw SQLException("Call next() before accessing data")
        }
        if (columnIndex != 1) {
            throw SQLException("Generated keys ResultSet only has one column")
        }

        return when (type) {
            Long::class -> generatedKey
            Int::class -> generatedKey.toInt()
            String::class -> generatedKey.toString()
            else -> generatedKey
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return object : ResultSetMetaData {
            override val columnCount: Int = 1
            override fun getColumnName(column: Int): String {
                if (column != 1) throw SQLException("Only one column available")
                return "GENERATED_KEY"
            }
        }
    }

    override fun close() {
        // No-op
    }
}
