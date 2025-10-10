package onl.ycode.kdbc

import java.sql.Types
import kotlin.reflect.KClass

/**
 * JDBC wrappers for JVM platform.
 *
 * These classes wrap java.sql.* types to implement the KDBC interfaces, bridging:
 * - KClass<*> ↔ Class<T>
 * - Boolean flags ↔ Int constants
 * - Nullable parameters ↔ Overloaded methods
 */

// Wrapper class for PreparedStatement
private class JdbcPreparedStatement(private val jdbc: java.sql.PreparedStatement) : PreparedStatement {
    override fun setObject(parameterIndex: Int, value: Any?) = jdbc.setObject(parameterIndex, value)
    override fun executeUpdate(): Int = jdbc.executeUpdate()
    override fun executeQuery(): ResultSet = JdbcResultSet(jdbc.executeQuery())
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(jdbc.generatedKeys)
    override fun close() = jdbc.close()
}

// Wrapper class for CallableStatement
private class JdbcCallableStatement(private val jdbc: java.sql.CallableStatement) : CallableStatement {
    override fun setObject(parameterIndex: Int, value: Any?) = jdbc.setObject(parameterIndex, value)
    override fun executeUpdate(): Int = jdbc.executeUpdate()
    override fun executeQuery(): ResultSet = JdbcResultSet(jdbc.executeQuery())
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(jdbc.generatedKeys)
    override fun close() = jdbc.close()

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        val sqlType = when (type) {
            Byte::class -> Types.TINYINT
            Short::class -> Types.SMALLINT
            Int::class -> Types.INTEGER
            Long::class -> Types.BIGINT
            Float::class -> Types.FLOAT
            Double::class -> Types.DOUBLE
            String::class, StringBuilder::class -> Types.VARCHAR
            Char::class -> Types.CHAR
            Boolean::class -> Types.BOOLEAN
            ByteArray::class -> Types.BLOB
            CharArray::class -> Types.CLOB
            Number::class, java.math.BigInteger::class -> Types.NUMERIC
            java.math.BigDecimal::class -> Types.DECIMAL
            java.sql.Date::class, java.time.LocalDate::class -> Types.DATE
            java.sql.Time::class, java.time.LocalTime::class -> Types.TIME
            java.sql.Timestamp::class, java.util.Date::class, java.time.LocalDateTime::class -> Types.TIMESTAMP
            else -> when (type.qualifiedName) {
                "com.ionspin.kotlin.bignum.decimal.BigDecimal" -> Types.DECIMAL
                "com.ionspin.kotlin.bignum.integer.BigInteger" -> Types.NUMERIC
                "kotlinx.datetime.LocalDate" -> Types.DATE
                "kotlinx.datetime.LocalTime" -> Types.TIME
                "kotlinx.datetime.LocalDateTime" -> Types.TIMESTAMP
                else -> Types.OTHER
            }
        }
        jdbc.registerOutParameter(parameterIndex, sqlType)
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? = jdbc.getObject(parameterIndex, type.java)
    override fun execute(): Boolean = jdbc.execute()
}

// Wrapper class for DataSource
private class JdbcDataSource(private val jdbc: javax.sql.DataSource) : DataSource {
    override fun getConnection(): Connection = JdbcConnection(jdbc.connection)
}

/**
 * Wraps a JDBC DataSource to implement the KDBC DataSource interface.
 */
fun javax.sql.DataSource.toKdbcDataSource(): DataSource = JdbcDataSource(this)

// Wrapper class for Connection
private class JdbcConnection(private val jdbc: java.sql.Connection) : Connection {
    override val metaData: DatabaseMetaData
        get() = JdbcDatabaseMetaData(jdbc.metaData)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement =
        JdbcPreparedStatement(
            if (returnGeneratedKeys)
                jdbc.prepareStatement(sql, java.sql.PreparedStatement.RETURN_GENERATED_KEYS)
            else
                jdbc.prepareStatement(sql)
        )

    override fun prepareCall(sql: String): CallableStatement = JdbcCallableStatement(jdbc.prepareCall(sql))
    override fun commit() = jdbc.commit()
    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            jdbc.rollback((savepoint as JdbcSavepoint).jdbc)
        } else {
            jdbc.rollback()
        }
    }
    override fun setSavepoint(name: String): Savepoint = JdbcSavepoint(jdbc.setSavepoint(name))
    override fun releaseSavepoint(savepoint: Savepoint) = jdbc.releaseSavepoint((savepoint as JdbcSavepoint).jdbc)
    override fun setAutoCommit(autoCommit: Boolean) {
        jdbc.autoCommit = autoCommit
    }
    override fun close() = jdbc.close()
}

// Wrapper class for DatabaseMetaData
private class JdbcDatabaseMetaData(private val jdbc: java.sql.DatabaseMetaData) : DatabaseMetaData {
    override val databaseProductName: String get() = jdbc.databaseProductName
    override val databaseProductVersion: String get() = jdbc.databaseProductVersion
    override val databaseMajorVersion: Int get() = jdbc.databaseMajorVersion
    override val databaseMinorVersion: Int get() = jdbc.databaseMinorVersion
}

// Wrapper class for Savepoint
private class JdbcSavepoint(val jdbc: java.sql.Savepoint) : Savepoint {
    override val savepointName: String get() = jdbc.savepointName
}

// Wrapper class for ResultSet
private class JdbcResultSet(private val jdbc: java.sql.ResultSet) : ResultSet {
    override fun next(): Boolean = jdbc.next()
    override fun getObject(columnIndex: Int, type: KClass<*>): Any? = jdbc.getObject(columnIndex, type.java)
    override fun getMetaData(): ResultSetMetaData = JdbcResultSetMetaData(jdbc.metaData)
    override fun close() = jdbc.close()
}

// Wrapper class for ResultSetMetaData
private class JdbcResultSetMetaData(private val jdbc: java.sql.ResultSetMetaData) : ResultSetMetaData {
    override val columnCount: Int get() = jdbc.columnCount
    override fun getColumnName(column: Int): String = jdbc.getColumnName(column)
}
