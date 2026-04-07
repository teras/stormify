package onl.ycode.kdbc

import java.sql.PreparedStatement
import java.sql.Statement.RETURN_GENERATED_KEYS
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

// Wrapper class for PreparedStatement with lazy preparation.
// If setObject() is never called (no params), executeUpdate/executeQuery
// use direct execution via more efficient jdbc.createStatement()
private class JdbcStatement(
    private val jdbc: java.sql.Connection,
    private val sql: String,
    private val returnGeneratedKeys: Boolean,
    private val columnNames: Array<String>?,
) : Statement {
    private var preparedStatement: PreparedStatement? = null
    private var directStatement: java.sql.Statement? = null

    private fun ensurePrepared(): PreparedStatement {
        if (preparedStatement == null)
            preparedStatement = if (returnGeneratedKeys)
                jdbc.prepareStatement(sql, RETURN_GENERATED_KEYS)
            else if (!columnNames.isNullOrEmpty())
                jdbc.prepareStatement(sql, columnNames)
            else
                jdbc.prepareStatement(sql)
        return preparedStatement!!
    }

    private fun ensureDirect(): java.sql.Statement {
        if (directStatement == null)
            directStatement = jdbc.createStatement()
        return directStatement!!
    }

    override fun setObject(parameterIndex: Int, value: Any?) =
        ensurePrepared().setObject(parameterIndex, value)

    override fun executeUpdate(): Int =
        preparedStatement?.executeUpdate()
            ?: ensureDirect().let { s ->
                if (returnGeneratedKeys) s.executeUpdate(sql, RETURN_GENERATED_KEYS)
                else if (!columnNames.isNullOrEmpty()) s.executeUpdate(sql, columnNames)
                else s.executeUpdate(sql)
            }

    override fun executeQuery(): ResultSet =
        JdbcResultSet(
            preparedStatement?.executeQuery()
                ?: ensureDirect().executeQuery(sql)
        )

    override fun getGeneratedKeys(): ResultSet =
        JdbcResultSet(
            (preparedStatement ?: directStatement)?.generatedKeys
                ?: throw SQLException("Generated keys not available")
        )

    override fun addBatch() = ensurePrepared().addBatch()
    override fun executeBatch(): IntArray = ensurePrepared().executeBatch()

    override fun close() {
        preparedStatement?.close()
        directStatement?.close()
    }
}

// Wrapper class for CallableStatement
private class JdbcCallableStatement(private val jdbc: java.sql.CallableStatement) : CallableStatement {
    override fun setObject(parameterIndex: Int, value: Any?) = jdbc.setObject(parameterIndex, value)
    override fun executeUpdate(): Int = jdbc.executeUpdate()
    override fun executeQuery(): ResultSet = JdbcResultSet(jdbc.executeQuery())
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(jdbc.generatedKeys)
    override fun addBatch() = jdbc.addBatch()
    override fun executeBatch(): IntArray = jdbc.executeBatch()
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

/**
 * JDBC DataSource wrapper that implements KDBC DataSource interface.
 *
 * Usage:
 * ```kotlin
 * val hikariDS = HikariDataSource(config)
 * val stormify = Stormify(hikariDS)  // Uses convenience function
 * // or
 * val stormify = Stormify(JdbcDataSource(hikariDS))  // Direct wrapper
 * ```
 */
class JdbcDataSource(private val jdbc: javax.sql.DataSource) : DataSource {
    override fun getConnection(): Connection = JdbcConnection(jdbc.connection)
}

// Wrapper class for Connection
private class JdbcConnection(private val jdbc: java.sql.Connection) : Connection {
    override val metaData: DatabaseMetaData
        get() = JdbcDatabaseMetaData(jdbc.metaData)


    override fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement =
        JdbcStatement(jdbc, sql, returnGeneratedKeys, columnNames)

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
    override fun getObject(columnIndex: Int, type: KClass<*>): Any? = try {
        if (type == Any::class) jdbc.getObject(columnIndex)
        else jdbc.getObject(columnIndex, type.java)
    } catch (_: java.sql.SQLException) {
        jdbc.getObject(columnIndex)
    }

    override fun getMetaData(): ResultSetMetaData = JdbcResultSetMetaData(jdbc.metaData)
    override fun close() = jdbc.close()
}

// Wrapper class for ResultSetMetaData
private class JdbcResultSetMetaData(private val jdbc: java.sql.ResultSetMetaData) : ResultSetMetaData {
    override val columnCount: Int get() = jdbc.columnCount
    override fun getColumnName(column: Int): String = jdbc.getColumnName(column)
    override fun getColumnLabel(column: Int): String = jdbc.getColumnLabel(column)
}
