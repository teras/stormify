package onl.ycode.kdbc

import kotlin.reflect.KClass

/**
 * Kotlin Database Connectivity (KDBC) interfaces for native platforms.
 * Minimal API designed specifically for basic implementations and easier extensibility.
 */

interface Statement : AutoCloseable

interface PreparedStatement : Statement {
    fun setObject(parameterIndex: Int, value: Any?)
    fun executeUpdate(): Int
    fun executeQuery(): ResultSet
    fun getGeneratedKeys(): ResultSet
}

interface CallableStatement : PreparedStatement {
    fun registerOutParameter(parameterIndex: Int, sqlType: Int)
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
