package onl.ycode.kdbc

import kotlin.reflect.KClass

/**
 * Kotlin Database Connectivity (KDBC) type definitions.
 *
 * These interfaces provide a unified, multiplatform-compatible API for database operations:
 * - JVM: Wraps JDBC types (java.sql.*)
 * - Native: Implements native database drivers (Oracle ODPI, PostgreSQL libpq, MariaDB/MySQL, FreeTDS)
 */

interface PreparedStatement : AutoCloseable {
    fun setObject(parameterIndex: Int, value: Any?)
    fun executeUpdate(): Int
    fun executeQuery(): ResultSet
    fun getGeneratedKeys(): ResultSet
    fun addBatch()
    fun executeBatch(): IntArray
}

interface CallableStatement : PreparedStatement {
    fun registerOutParameter(parameterIndex: Int, type: KClass<*>)
    fun getObject(parameterIndex: Int, type: KClass<*>): Any?
    fun execute(): Boolean
}

interface DataSource {
    fun getConnection(): Connection
}

interface Connection : AutoCloseable {
    val metaData: DatabaseMetaData

    fun prepareStatement(sql: String, returnGeneratedKeys: Boolean = false): PreparedStatement
    fun prepareCall(sql: String): CallableStatement
    fun commit()
    fun rollback(savepoint: Savepoint? = null)
    fun setSavepoint(name: String): Savepoint
    fun releaseSavepoint(savepoint: Savepoint)
    fun setAutoCommit(autoCommit: Boolean)
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
}
