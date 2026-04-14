package onl.ycode.kdbc

import kotlin.reflect.KClass

/**
 * Kotlin Database Connectivity (KDBC) type definitions.
 *
 * These interfaces provide a unified, multiplatform-compatible API for database operations:
 * - JVM: Wraps JDBC types (java.sql.*)
 * - Native: Implements native database drivers (Oracle ODPI, PostgreSQL libpq, MariaDB/MySQL, MSSQL via FreeTDS db-lib)
 */

/** A prepared SQL statement that supports parameter binding, execution, and batching. */
interface Statement : AutoCloseable {
    /** Binds a parameter value at the given 1-based [parameterIndex]. */
    fun setObject(parameterIndex: Int, value: Any?)

    /** Executes an INSERT, UPDATE, or DELETE statement and returns the number of affected rows. */
    fun executeUpdate(): Int

    /** Executes a SELECT statement and returns the result set. */
    fun executeQuery(): ResultSet

    /** Returns a result set containing any auto-generated keys produced by the last execution. */
    fun getGeneratedKeys(): ResultSet

    /** Adds the current set of parameters as a batch entry for later execution via [executeBatch]. */
    fun addBatch()

    /** Executes all batched statements and returns an array of update counts, one per batch entry. */
    fun executeBatch(): IntArray
}

/** A statement for calling stored procedures, with support for OUT and INOUT parameters. */
interface CallableStatement : Statement {
    /** Registers an OUT parameter at the given 1-based [parameterIndex] with the expected [type]. */
    fun registerOutParameter(parameterIndex: Int, type: KClass<*>)

    /** Retrieves the value of an OUT parameter at the given 1-based [parameterIndex], cast to [type]. */
    fun getObject(parameterIndex: Int, type: KClass<*>): Any?

    /** Executes the stored procedure. Returns `true` if the first result is a result set. */
    fun execute(): Boolean
}

/** A factory for database connections. Platform implementations wrap JDBC, native drivers, or Android SQLite. */
interface DataSource {
    /** Opens and returns a new database connection. The caller is responsible for closing it. */
    fun getConnection(): Connection
}

/** A connection to a specific database, used to execute statements and manage transactions. */
interface Connection : AutoCloseable {
    /** Metadata about the database product name, version, and capabilities. */
    val metaData: DatabaseMetaData

    /** Creates a prepared statement for the given SQL. Set [returnGeneratedKeys] to retrieve auto-generated keys after execution. */
    fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement

    /** Creates a callable statement for invoking stored procedures. */
    fun prepareCall(sql: String): CallableStatement

    /** Commits the current transaction. */
    fun commit()

    /** Rolls back the current transaction, optionally to a specific [savepoint]. */
    fun rollback(savepoint: Savepoint? = null)

    /** Creates a named savepoint within the current transaction. */
    fun setSavepoint(name: String): Savepoint

    /** Releases a savepoint, freeing database resources. */
    fun releaseSavepoint(savepoint: Savepoint)

    /** Enables or disables auto-commit mode. When disabled, changes must be explicitly committed. */
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

    /**
     * Driver-specific recovery after [cancel] has interrupted a blocking call. Invoked
     * by the coroutine cancellation path on the thread that owned the blocking call,
     * once that call has returned with an error.
     *
     * The split between [cancel] and this method exists because cancel fires on a
     * DIFFERENT thread than the blocking operation, and what each driver needs to do
     * next varies:
     *
     * - **Oracle (ODPI-C, JDBC thin)**: `cancel` issues `OCIBreak`, which leaves the
     *   connection in a state where `rollback` blocks indefinitely (ODPI-C does not
     *   expose `OCIReset`). Default no-op — connection is evicted by the pool.
     * - **libpq (PostgreSQL native)**: `PQcancel` aborts the current query; `rollback`
     *   afterwards CAN succeed but is fragile when the cancel races with the call's
     *   own error return. Default no-op; pool eviction is the safer path.
     * - **JDBC / JVM drivers**: `java.sql.Statement.cancel` stops the statement, then
     *   `rollback` on the connection is typically safe. Drivers that prove robust can
     *   override this to invoke [rollback] themselves.
     * - **Android `SQLiteDatabase`**: `cancel` is a no-op (no async-interrupt primitive
     *   on Android SQLite). The active `beginTransaction` is tracked in a
     *   `ThreadLocal<SQLiteSession>`; if we skip the rollback, the session keeps the
     *   transaction open and the `SQLiteConnectionPool` never releases the underlying
     *   connection — subsequent operations hang in `waitForConnection()`. Android
     *   therefore MUST override this to call [rollback] (and restore auto-commit).
     * - **SQLite native, MariaDB, MSSQL (FreeTDS)**: similar to libpq — cancel is
     *   supported, rollback post-cancel is typically workable but drivers differ in
     *   error-state stability. Default no-op unless a specific driver proves otherwise.
     *
     * Implementations that choose to run cleanup here should wrap each call in
     * `runCatching` — any further failure is a signal that the pool should evict
     * the connection, and swallowing it avoids masking the original cancellation.
     */
    fun cleanupAfterCancel(): Unit = Unit
}

/** Provides information about the database, such as product name and version. */
interface DatabaseMetaData {
    /** The name of the database product (e.g. "PostgreSQL", "MySQL", "SQLite"). */
    val databaseProductName: String

    /** The full version string reported by the database. */
    val databaseProductVersion: String

    /** The major version number of the database. */
    val databaseMajorVersion: Int

    /** The minor version number of the database. */
    val databaseMinorVersion: Int
}

/** A named savepoint within a transaction, used for partial rollbacks. */
interface Savepoint {
    /** The name of this savepoint. */
    val savepointName: String
}

/** A table of data resulting from a query, read row-by-row via [next]. */
interface ResultSet : AutoCloseable {
    /** Advances to the next row. Returns `true` if a row is available, `false` when exhausted. */
    fun next(): Boolean

    /** Retrieves the value at the given 1-based [columnIndex], converting it to the requested [type]. */
    fun getObject(columnIndex: Int, type: KClass<*>): Any?

    /** Returns metadata describing the columns in this result set. */
    fun getMetaData(): ResultSetMetaData
}

/** Metadata about the columns in a [ResultSet]. */
interface ResultSetMetaData {
    /** The number of columns in the result set. */
    val columnCount: Int

    /** Returns the underlying column name at the given 1-based [column] index. */
    fun getColumnName(column: Int): String

    /** Returns the column alias (label) at the given 1-based [column] index. Defaults to [getColumnName]. */
    fun getColumnLabel(column: Int): String = getColumnName(column)
}
