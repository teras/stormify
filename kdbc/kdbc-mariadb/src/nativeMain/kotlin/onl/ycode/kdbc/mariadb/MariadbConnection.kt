package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import mariadb.*
import kotlin.reflect.KClass

/**
 * MariaDB/MySQL Connection implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbConnection(
    private val connectionString: String,
    private val properties: Map<String, String> = emptyMap()
) : Connection {
    private val mysql: CPointer<MYSQL>
    private var autoCommit = true

    init {
        // Initialize MySQL library
        val mysqlPtr = mysql_init(null) ?: throw SQLException("Failed to initialize MySQL library")

        // Parse connection string: host:port/database
        val parts = connectionString.split("/")
        if (parts.size != 2) {
            throw SQLException("Invalid connection string format. Expected: host:port/database")
        }

        val hostPort = parts[0].split(":")
        val host = hostPort[0]
        val port = if (hostPort.size > 1) hostPort[1].toUIntOrNull() ?: 3306u else 3306u
        val database = parts[1]

        val user = properties["user"] ?: "root"
        val password = properties["password"] ?: ""

        // Connect to database
        val result = mysql_real_connect(
            mysqlPtr,
            host,
            user,
            password,
            database,
            port,
            null,
            0u
        )

        if (result == null) {
            val error = mysql_error(mysqlPtr)?.toKString() ?: "Unknown error"
            mysql_close(mysqlPtr)
            throw SQLException("Failed to connect to database: $error")
        }

        mysql = mysqlPtr

        // Set autocommit mode
        mysql_autocommit(mysql, 1.toByte())
    }

    override val metaData: DatabaseMetaData
        get() = MariadbDatabaseMetaData(mysql)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return MariadbPreparedStatement(mysql, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        return MariadbCallableStatement(mysql, sql)
    }

    override fun commit() {
        val result = mysql_commit(mysql)
        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to commit transaction: $error")
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            val sql = "ROLLBACK TO SAVEPOINT ${savepoint.savepointName}"
            executeSimpleSQL(sql)
        } else {
            val result = mysql_rollback(mysql)
            if (result.toInt() != 0) {
                val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to rollback transaction: $error")
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        val sql = "SAVEPOINT $name"
        executeSimpleSQL(sql)
        return MariadbSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        val sql = "RELEASE SAVEPOINT ${savepoint.savepointName}"
        executeSimpleSQL(sql)
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (this.autoCommit != autoCommit) {
            val result = mysql_autocommit(mysql, if (autoCommit) 1.toByte() else 0.toByte())
            if (result.toInt() != 0) {
                val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to set autocommit mode: $error")
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        mysql_close(mysql)
    }

    private fun executeSimpleSQL(sql: String) {
        val result = mysql_query(mysql, sql)
        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute SQL '$sql': $error")
        }
    }
}

/**
 * MariaDB/MySQL DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbDatabaseMetaData(private val mysql: CPointer<MYSQL>) : DatabaseMetaData {
    override val databaseProductName: String = "MySQL/MariaDB"

    override val databaseProductVersion: String
        get() = mysql_get_server_info(mysql)?.toKString() ?: "Unknown"

    override val databaseMajorVersion: Int
        get() {
            val version = mysql_get_server_version(mysql)
            return (version / 10000u).toInt()
        }

    override val databaseMinorVersion: Int
        get() {
            val version = mysql_get_server_version(mysql)
            return ((version % 10000u) / 100u).toInt()
        }
}

/**
 * MariaDB/MySQL PreparedStatement implementation using simple query approach.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbPreparedStatement(
    private val mysql: CPointer<MYSQL>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val paramValues = mutableListOf<String?>()
    private var lastInsertId: ULong = 0u

    init {
        // Count parameters
        var count = 0
        for (ch in sql) {
            if (ch == '?') count++
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
            null -> "NULL"
            is String -> "'${escapeString(value)}'"
            is ByteArray -> {
                // Convert to hex string
                "X'" + value.joinToString("") {
                    val hex = it.toUByte().toString(16).uppercase()
                    if (hex.length == 1) "0$hex" else hex
                } + "'"
            }
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }
    }

    override fun executeUpdate(): Int {
        val preparedSql = buildPreparedSQL()
        val result = mysql_query(mysql, preparedSql)

        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute update: $error")
        }

        if (returnGeneratedKeys) {
            lastInsertId = mysql_insert_id(mysql)
        }

        return mysql_affected_rows(mysql).toInt()
    }

    override fun executeQuery(): ResultSet {
        val preparedSql = buildPreparedSQL()
        val result = mysql_query(mysql, preparedSql)

        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute query: $error")
        }

        val resultSet = mysql_store_result(mysql)
            ?: return EmptyMariadbResultSet()

        return MariadbResultSet(mysql, resultSet)
    }

    override fun getGeneratedKeys(): ResultSet {
        if (!returnGeneratedKeys || lastInsertId == 0uL) {
            return EmptyMariadbResultSet()
        }
        return GeneratedKeysResultSet(lastInsertId.toLong())
    }

    override fun close() {
        // No-op for simple implementation
    }

    private fun buildPreparedSQL(): String {
        var preparedSql = sql
        for (value in paramValues) {
            preparedSql = preparedSql.replaceFirst("?", value ?: "NULL")
        }
        return preparedSql
    }

    private fun escapeString(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}

/**
 * MariaDB/MySQL CallableStatement implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbCallableStatement(
    private val mysql: CPointer<MYSQL>,
    private val sql: String
) : CallableStatement {
    private val outParameters = mutableMapOf<Int, Any?>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        // For callable statements, we'll use simple query execution
    }

    override fun executeUpdate(): Int {
        val result = mysql_query(mysql, sql)
        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute callable statement: $error")
        }
        return mysql_affected_rows(mysql).toInt()
    }

    override fun executeQuery(): ResultSet {
        val result = mysql_query(mysql, sql)
        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute callable statement: $error")
        }

        val resultSet = mysql_store_result(mysql)
            ?: return EmptyMariadbResultSet()

        return MariadbResultSet(mysql, resultSet)
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyMariadbResultSet()
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        // Store for later retrieval - MariaDB will handle type conversion internally
        outParameters[parameterIndex] = null
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        return outParameters[parameterIndex]
    }

    override fun execute(): Boolean {
        val result = mysql_query(mysql, sql)
        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute callable statement: $error")
        }
        return mysql_field_count(mysql) > 0u
    }

    override fun close() {
        // No-op
    }
}

/**
 * MariaDB/MySQL ResultSet implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbResultSet(
    private val mysql: CPointer<MYSQL>,
    private val result: CPointer<MYSQL_RES>
) : ResultSet {
    private var currentRow: CPointer<CPointerVar<ByteVar>>? = null
    private val columnCount: Int = mysql_num_fields(result).toInt()

    override fun next(): Boolean {
        currentRow = mysql_fetch_row(result)
        return currentRow != null
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (currentRow == null) {
            throw SQLException("No current row")
        }

        if (columnIndex < 1 || columnIndex > columnCount) {
            throw SQLException("Invalid column index: $columnIndex")
        }

        val value = currentRow!![columnIndex - 1]?.toKString()

        if (value == null) {
            return null
        }

        return when (type) {
            Int::class -> value.toIntOrNull()
            Long::class -> value.toLongOrNull()
            Double::class -> value.toDoubleOrNull()
            Float::class -> value.toFloatOrNull()
            String::class -> value
            Boolean::class -> value.toIntOrNull() != 0
            ByteArray::class -> value.encodeToByteArray()
            else -> value
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return MariadbResultSetMetaData(result, columnCount)
    }

    override fun close() {
        mysql_free_result(result)
    }
}

/**
 * MariaDB/MySQL ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbResultSetMetaData(
    private val result: CPointer<MYSQL_RES>,
    override val columnCount: Int
) : ResultSetMetaData {

    override fun getColumnName(column: Int): String {
        if (column < 1 || column > columnCount) {
            throw SQLException("Invalid column index: $column")
        }

        val fields = mysql_fetch_fields(result)
            ?: throw SQLException("Failed to fetch field information")

        val field = fields[column - 1]
        return field.name?.toKString() ?: "COLUMN_$column"
    }
}

/**
 * MariaDB/MySQL Savepoint implementation.
 */
class MariadbSavepoint(override val savepointName: String) : Savepoint

/**
 * Empty ResultSet implementation for cases where no results are available.
 */
class EmptyMariadbResultSet : ResultSet {
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
