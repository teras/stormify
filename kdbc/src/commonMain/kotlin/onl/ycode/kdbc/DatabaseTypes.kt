package onl.ycode.kdbc

import kotlin.reflect.KClass

/**
 * Kotlin Database Connectivity (KDBC) type definitions.
 *
 * These interfaces provide a unified, multiplatform-compatible API for database operations:
 * - JVM: Wraps JDBC types (java.sql.*)
 * - Native: Implements native database drivers (Oracle ODPI, PostgreSQL libpq, MariaDB/MySQL, MSSQL via FreeTDS db-lib)
 */

interface Statement : AutoCloseable {
    fun setObject(parameterIndex: Int, value: Any?)
    fun executeUpdate(): Int
    fun executeQuery(): ResultSet
    fun getGeneratedKeys(): ResultSet
    fun addBatch()
    fun executeBatch(): IntArray
}

interface CallableStatement : Statement {
    fun registerOutParameter(parameterIndex: Int, type: KClass<*>)
    fun getObject(parameterIndex: Int, type: KClass<*>): Any?
    fun execute(): Boolean
}

interface DataSource {
    fun getConnection(): Connection
}

interface Connection : AutoCloseable {
    val metaData: DatabaseMetaData
    fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement
    fun prepareCall(sql: String): CallableStatement
    fun commit()
    fun rollback(savepoint: Savepoint? = null)
    fun setSavepoint(name: String): Savepoint
    fun releaseSavepoint(savepoint: Savepoint)
    fun setAutoCommit(autoCommit: Boolean)

    /**
     * Best-effort asynchronous cancellation of any statement currently executing on this
     * connection. Designed to be called from a thread OTHER than the one blocked inside
     * a kdbc call — that is the point: higher-level code (e.g. a Kotlin coroutine
     * cancellation handler) can interrupt a running query without waiting for it.
     *
     * When the cancel takes effect the blocking call on the other thread returns with an
     * error (typically mapped to an exception). Cancel is a request, not a guarantee.
     *
     * Thread safety: safe to call concurrently with a blocking kdbc call on the same
     * connection. NOT safe to call concurrently with [close] — callers must serialize
     * cancellation with connection teardown.
     *
     * Implementations: JVM delegates to `java.sql.Statement.cancel` on the active statement
     * (or is a no-op if no active statement is tracked); Native delegates to the kdbc C
     * layer's `kdbc_cancel` which dispatches to the driver-specific primitive (PQcancel,
     * sqlite3_interrupt, mariadb_cancel, dpiConn_breakExecution). Default: no-op.
     */
    fun cancel(): Unit = Unit
}

interface DatabaseMetaData {
    val databaseProductName: String
    val databaseProductVersion: String
    val databaseMajorVersion: Int
    val databaseMinorVersion: Int
}

interface Savepoint {
    val savepointName: String
}

interface ResultSet : AutoCloseable {
    fun next(): Boolean
    fun getObject(columnIndex: Int, type: KClass<*>): Any?
    fun getMetaData(): ResultSetMetaData
}

interface ResultSetMetaData {
    val columnCount: Int
    fun getColumnName(column: Int): String
    fun getColumnLabel(column: Int): String = getColumnName(column)
}
