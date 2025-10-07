package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

/**
 * SQLite Connection implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class SqliteConnection(private val url: String) : Connection {
    private val dbPointer: CPointer<cnames.structs.sqlite3>
    private var autoCommit = true

    init {
        memScoped {
            val dbPtr = alloc<CPointerVar<cnames.structs.sqlite3>>()
            val result = sqlite3_open(url, dbPtr.ptr)
            if (result != SQLITE_OK) {
                throw SQLException("Failed to open database: $url, error code: $result")
            }
            dbPointer = dbPtr.value ?: throw SQLException("Database pointer is null")
        }
    }

    override val metaData: DatabaseMetaData
        get() = SqliteDatabaseMetaData(dbPointer)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return SqlitePreparedStatement(dbPointer, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        throw SQLException("SQLite doesn't support callable statements")
    }

    override fun commit() {
        executeSimpleSQL("COMMIT")
        if (!autoCommit) {
            executeSimpleSQL("BEGIN")
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            executeSimpleSQL("ROLLBACK TO SAVEPOINT ${savepoint.savepointName}")
        } else {
            executeSimpleSQL("ROLLBACK")
            if (!autoCommit) {
                executeSimpleSQL("BEGIN")
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        executeSimpleSQL("SAVEPOINT $name")
        return SqliteSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        executeSimpleSQL("RELEASE SAVEPOINT ${savepoint.savepointName}")
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (this.autoCommit != autoCommit) {
            if (autoCommit) {
                executeSimpleSQL("COMMIT")
            } else {
                executeSimpleSQL("BEGIN")
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        sqlite3_close(dbPointer)
    }

    private fun executeSimpleSQL(sql: String) {
        memScoped {
            val errorMsg = alloc<CPointerVar<ByteVar>>()
            val result = sqlite3_exec(dbPointer, sql, null, null, errorMsg.ptr)
            if (result != SQLITE_OK) {
                val error = errorMsg.value?.toKString() ?: "Unknown error"
                sqlite3_free(errorMsg.value)
                throw SQLException("Failed to execute SQL '$sql': $error")
            }
        }
    }
}

/**
 * SQLite DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class SqliteDatabaseMetaData(private val dbPointer: CPointer<cnames.structs.sqlite3>) : DatabaseMetaData {
    override val databaseProductName: String = "SQLite"

    override val databaseProductVersion: String
        get() = sqlite3_libversion()?.toKString() ?: "Unknown"

    override val databaseMajorVersion: Int
        get() = sqlite3_libversion_number() / 1000000

    override val databaseMinorVersion: Int
        get() = (sqlite3_libversion_number() % 1000000) / 1000
}

/**
 * SQLite PreparedStatement implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class SqlitePreparedStatement(
    private val dbPointer: CPointer<cnames.structs.sqlite3>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val stmtPointer: CPointer<cnames.structs.sqlite3_stmt>
    private var lastInsertRowId: Long = 0

    init {
        memScoped {
            val stmtPtr = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
            val result = sqlite3_prepare_v2(dbPointer, sql, -1, stmtPtr.ptr, null)
            if (result != SQLITE_OK) {
                val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to prepare statement: $errorMsg")
            }
            stmtPointer = stmtPtr.value ?: throw SQLException("Statement pointer is null")
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        val result = when (value) {
            null -> sqlite3_bind_null(stmtPointer, parameterIndex)
            is Int -> sqlite3_bind_int(stmtPointer, parameterIndex, value)
            is Long -> sqlite3_bind_int64(stmtPointer, parameterIndex, value)
            is Double -> sqlite3_bind_double(stmtPointer, parameterIndex, value)
            is Float -> sqlite3_bind_double(stmtPointer, parameterIndex, value.toDouble())
            is String -> sqlite3_bind_text(stmtPointer, parameterIndex, value, -1, SQLITE_TRANSIENT)
            is Boolean -> sqlite3_bind_int(stmtPointer, parameterIndex, if (value) 1 else 0)
            is ByteArray -> memScoped {
                val pinnedArray = value.pin()
                sqlite3_bind_blob(stmtPointer, parameterIndex, pinnedArray.addressOf(0), value.size, SQLITE_TRANSIENT)
                    .also { pinnedArray.unpin() }
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }

        if (result != SQLITE_OK) {
            val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to bind parameter $parameterIndex: $errorMsg")
        }
    }

    override fun executeUpdate(): Int {
        val result = sqlite3_step(stmtPointer)

        return when (result) {
            SQLITE_DONE -> {
                if (returnGeneratedKeys) {
                    lastInsertRowId = sqlite3_last_insert_rowid(dbPointer)
                }
                sqlite3_changes(dbPointer)
            }
            SQLITE_ROW -> {
                throw SQLException("executeUpdate() returned a result set")
            }
            else -> {
                val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute update: $errorMsg")
            }
        }
    }

    override fun executeQuery(): ResultSet {
        return SqliteResultSet(stmtPointer, dbPointer)
    }

    override fun getGeneratedKeys(): ResultSet {
        if (!returnGeneratedKeys || lastInsertRowId == 0L) {
            return EmptySqliteResultSet()
        }
        return GeneratedKeysResultSet(lastInsertRowId)
    }

    override fun close() {
        sqlite3_finalize(stmtPointer)
    }
}

/**
 * SQLite CallableStatement implementation (stub - SQLite doesn't support stored procedures).
 */
class SqliteCallableStatement : CallableStatement {
    override fun setObject(parameterIndex: Int, value: Any?) {
        TODO("SQLite doesn't support callable statements")
    }

    override fun executeUpdate(): Int {
        TODO("SQLite doesn't support callable statements")
    }

    override fun executeQuery(): ResultSet {
        TODO("SQLite doesn't support callable statements")
    }

    override fun getGeneratedKeys(): ResultSet {
        TODO("SQLite doesn't support callable statements")
    }

    override fun registerOutParameter(parameterIndex: Int, sqlType: Int) {
        TODO("SQLite doesn't support callable statements")
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        TODO("SQLite doesn't support callable statements")
    }

    override fun execute(): Boolean {
        TODO("SQLite doesn't support callable statements")
    }

    override fun close() {
        // No-op
    }
}

/**
 * SQLite ResultSet implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class SqliteResultSet(
    private val stmtPointer: CPointer<cnames.structs.sqlite3_stmt>,
    private val dbPointer: CPointer<cnames.structs.sqlite3>
) : ResultSet {
    private var hasRow = false
    private var isFirst = true

    override fun next(): Boolean {
        val result = sqlite3_step(stmtPointer)
        hasRow = when (result) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> {
                val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to fetch next row: $errorMsg")
            }
        }
        isFirst = false
        return hasRow
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!hasRow) {
            throw SQLException("No current row")
        }

        // SQLite uses 0-based indexing, JDBC uses 1-based
        val index = columnIndex - 1

        val columnType = sqlite3_column_type(stmtPointer, index)

        if (columnType == SQLITE_NULL) {
            return null
        }

        return when (type) {
            Int::class -> sqlite3_column_int(stmtPointer, index)
            Long::class -> sqlite3_column_int64(stmtPointer, index)
            Double::class -> sqlite3_column_double(stmtPointer, index)
            Float::class -> sqlite3_column_double(stmtPointer, index).toFloat()
            String::class -> sqlite3_column_text(stmtPointer, index)?.reinterpret<ByteVar>()?.toKString()
            Boolean::class -> sqlite3_column_int(stmtPointer, index) != 0
            ByteArray::class -> {
                val size = sqlite3_column_bytes(stmtPointer, index)
                val blob = sqlite3_column_blob(stmtPointer, index)
                if (blob != null && size > 0) {
                    ByteArray(size) { i ->
                        blob.reinterpret<ByteVar>()[i]
                    }
                } else {
                    ByteArray(0)
                }
            }
            else -> {
                // Default: try to return as string
                sqlite3_column_text(stmtPointer, index)?.reinterpret<ByteVar>()?.toKString()
            }
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return SqliteResultSetMetaData(stmtPointer)
    }

    override fun close() {
        // Statement finalization is handled by PreparedStatement
    }
}

/**
 * SQLite ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class SqliteResultSetMetaData(private val stmtPointer: CPointer<cnames.structs.sqlite3_stmt>) : ResultSetMetaData {
    override val columnCount: Int
        get() = sqlite3_column_count(stmtPointer)

    override fun getColumnName(column: Int): String {
        // SQLite uses 0-based indexing, JDBC uses 1-based
        val index = column - 1
        return sqlite3_column_name(stmtPointer, index)?.toKString()
            ?: throw SQLException("Failed to get column name for index $column")
    }
}

/**
 * SQLite Savepoint implementation.
 */
class SqliteSavepoint(override val savepointName: String) : Savepoint

/**
 * Empty ResultSet implementation for cases where no results are available.
 */
class EmptySqliteResultSet : ResultSet {
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
