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

/**
 * Cached reflective converters for Kotlin Multiplatform types → standard Java types.
 *
 * JDBC drivers only understand java.math, java.sql, and java.time types. Kotlin
 * multiplatform libraries (ionspin bignum, kotlinx-datetime) are invisible to them.
 * We resolve the conversion method once per type and cache it for subsequent calls.
 */
private val kmpConverters = HashMap<String, ((Any) -> Any)?>()
private val UNRESOLVED: (Any) -> Any = { it } // sentinel for "no converter"

private fun resolveConverter(qualifiedName: String, value: Any): ((Any) -> Any)? {
    val cached = kmpConverters[qualifiedName]
    if (cached != null) return if (cached === UNRESOLVED) null else cached
    val converter: ((Any) -> Any)? = try {
        val cls = value::class.java
        when (qualifiedName) {
            "com.ionspin.kotlin.bignum.decimal.BigDecimal" -> {
                val biClass = Class.forName("com.ionspin.kotlin.bignum.integer.BigInteger")
                val mSig = cls.getMethod("getSignificand")
                val mExp = cls.getMethod("getExponent")
                val mPrec = cls.getMethod("getPrecision")
                val mSignum = biClass.getMethod("signum")
                val mBytes = biClass.getMethod("toByteArray")
                val fn: (Any) -> Any = { v ->
                    val sig = mSig.invoke(v)
                    val signum = mSignum.invoke(sig) as Int
                    if (signum == 0) java.math.BigDecimal.ZERO
                    else {
                        val jSig = java.math.BigInteger(signum, mBytes.invoke(sig) as ByteArray)
                        val scale = (mPrec.invoke(v) as Long).toInt() - 1 - (mExp.invoke(v) as Long).toInt()
                        java.math.BigDecimal(jSig, scale)
                    }
                }
                fn
            }
            "com.ionspin.kotlin.bignum.integer.BigInteger" -> {
                val mSignum = cls.getMethod("signum")
                val mBytes = cls.getMethod("toByteArray")
                val fn: (Any) -> Any = { v ->
                    val signum = mSignum.invoke(v) as Int
                    if (signum == 0) java.math.BigDecimal.ZERO
                    else java.math.BigDecimal(java.math.BigInteger(signum, mBytes.invoke(v) as ByteArray))
                }
                fn
            }
            "kotlinx.datetime.LocalDate" -> {
                val getYear = cls.getMethod("getYear")
                val getMonth = cls.getMethod("getMonthNumber")
                val getDay = cls.getMethod("getDayOfMonth")
                val fn: (Any) -> Any = { v ->
                    java.sql.Date.valueOf(java.time.LocalDate.of(
                        getYear.invoke(v) as Int, getMonth.invoke(v) as Int, getDay.invoke(v) as Int
                    ))
                }
                fn
            }
            "kotlinx.datetime.LocalDateTime" -> {
                val getYear = cls.getMethod("getYear")
                val getMonth = cls.getMethod("getMonthNumber")
                val getDay = cls.getMethod("getDayOfMonth")
                val getHour = cls.getMethod("getHour")
                val getMin = cls.getMethod("getMinute")
                val getSec = cls.getMethod("getSecond")
                val getNano = cls.getMethod("getNanosecond")
                val fn: (Any) -> Any = { v ->
                    java.sql.Timestamp.valueOf(java.time.LocalDateTime.of(
                        getYear.invoke(v) as Int, getMonth.invoke(v) as Int, getDay.invoke(v) as Int,
                        getHour.invoke(v) as Int, getMin.invoke(v) as Int, getSec.invoke(v) as Int,
                        getNano.invoke(v) as Int
                    ))
                }
                fn
            }
            "kotlinx.datetime.LocalTime" -> {
                val getHour = cls.getMethod("getHour")
                val getMin = cls.getMethod("getMinute")
                val getSec = cls.getMethod("getSecond")
                val getNano = cls.getMethod("getNanosecond")
                val fn: (Any) -> Any = { v ->
                    java.sql.Time.valueOf(java.time.LocalTime.of(
                        getHour.invoke(v) as Int, getMin.invoke(v) as Int, getSec.invoke(v) as Int,
                        getNano.invoke(v) as Int
                    ))
                }
                fn
            }
            "kotlin.time.Instant" -> {
                val getEpochSec = cls.getMethod("getEpochSeconds")
                val getNanoAdj = cls.getMethod("getNanosecondsOfSecond")
                val fn: (Any) -> Any = { v ->
                    val secs = getEpochSec.invoke(v) as Long
                    val nanos = getNanoAdj.invoke(v) as Int
                    java.sql.Timestamp.from(java.time.Instant.ofEpochSecond(secs, nanos.toLong()))
                }
                fn
            }
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
    kmpConverters[qualifiedName] = converter ?: UNRESOLVED
    return converter
}

private fun toJdbcValue(value: Any?): Any? {
    if (value == null) return null
    val qn = value::class.qualifiedName ?: return value
    val converter = resolveConverter(qn, value) ?: return value
    return try { converter(value) } catch (_: Throwable) { value }
}

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
        ensurePrepared().setObject(parameterIndex, toJdbcValue(value))

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

private fun toSqlType(type: KClass<*>): Int = when (type) {
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

// Standard JDBC CallableStatement wrapper — used by MySQL, MariaDB, Oracle, SQL Server.
private class JdbcCallableStatement(private val jdbc: java.sql.CallableStatement) : CallableStatement {
    override fun setObject(parameterIndex: Int, value: Any?) = jdbc.setObject(parameterIndex, toJdbcValue(value))
    override fun executeUpdate(): Int = jdbc.executeUpdate()
    override fun executeQuery(): ResultSet = JdbcResultSet(jdbc.executeQuery())
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(jdbc.generatedKeys)
    override fun addBatch() = jdbc.addBatch()
    override fun executeBatch(): IntArray = jdbc.executeBatch()
    override fun close() = jdbc.close()
    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) =
        jdbc.registerOutParameter(parameterIndex, toSqlType(type))
    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? = jdbc.getObject(parameterIndex, type.java)
    override fun execute(): Boolean = jdbc.execute()
}

/**
 * PostgreSQL CallableStatement emulation via PreparedStatement + ResultSet.
 *
 * PostgreSQL does not support OUT/INOUT parameters through JDBC's CallableStatement API.
 * Instead, `CALL proc(?, ?, ?)` returns OUT/INOUT values as columns in a ResultSet.
 * This class hides that difference behind the standard [CallableStatement] interface
 * so callers (Stormify) don't need dialect-specific branching.
 */
private class JdbcPgCallableStatement(
    private val jdbc: java.sql.Connection,
    private val sql: String
) : CallableStatement {
    private val stmt: java.sql.PreparedStatement = jdbc.prepareStatement(sql)
    private val outParams = mutableMapOf<Int, KClass<*>>() // 1-based index → type
    private var resultRow: java.sql.ResultSet? = null

    override fun setObject(parameterIndex: Int, value: Any?) =
        stmt.setObject(parameterIndex, toJdbcValue(value))

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        outParams[parameterIndex] = type
        // For pure OUT params, bind NULL so all placeholders have values
        stmt.setNull(parameterIndex, toSqlType(type))
    }

    override fun execute(): Boolean {
        val hasResult = stmt.execute()
        if (hasResult) resultRow = stmt.resultSet
        return hasResult
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        // PG returns OUT/INOUT values as ResultSet columns, numbered sequentially
        // (only OUT/INOUT params appear, IN params are excluded from the result).
        val rs = resultRow ?: throw SQLException("No result set from procedure call")
        if (!rs.isBeforeFirst && !rs.isFirst) rs.next()
        else if (rs.isBeforeFirst) rs.next()
        // Map the absolute parameter index to the ResultSet column index:
        // OUT/INOUT params are returned in declaration order as columns 1, 2, ...
        val outIndices = outParams.keys.sorted()
        val colIndex = outIndices.indexOf(parameterIndex) + 1
        if (colIndex == 0) throw SQLException("Parameter $parameterIndex is not an OUT/INOUT parameter")
        return if (type == Any::class) rs.getObject(colIndex)
        else rs.getObject(colIndex, type.java)
    }

    override fun executeUpdate(): Int = stmt.executeUpdate()
    override fun executeQuery(): ResultSet = JdbcResultSet(stmt.executeQuery())
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(stmt.generatedKeys)
    override fun addBatch() = stmt.addBatch()
    override fun executeBatch(): IntArray = stmt.executeBatch()
    override fun close() {
        resultRow?.close()
        stmt.close()
    }
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
    private val isPostgres by lazy {
        jdbc.metaData.databaseProductName.lowercase().contains("postgresql")
    }

    override val metaData: DatabaseMetaData
        get() = JdbcDatabaseMetaData(jdbc.metaData)


    override fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement =
        JdbcStatement(jdbc, sql, returnGeneratedKeys, columnNames)

    override fun prepareCall(sql: String): CallableStatement =
        if (isPostgres) JdbcPgCallableStatement(jdbc, sql)
        else JdbcCallableStatement(jdbc.prepareCall("{$sql}"))
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
// Map KMP types to Java equivalents for JDBC ResultSet.getObject()
private val kmpToJavaType = mapOf(
    "com.ionspin.kotlin.bignum.decimal.BigDecimal" to java.math.BigDecimal::class.java,
    "com.ionspin.kotlin.bignum.integer.BigInteger" to java.math.BigInteger::class.java,
    "kotlinx.datetime.LocalDate" to java.sql.Date::class.java,
    "kotlinx.datetime.LocalDateTime" to java.sql.Timestamp::class.java,
    "kotlinx.datetime.LocalTime" to java.sql.Time::class.java,
    "kotlin.time.Instant" to java.sql.Timestamp::class.java,
)

private class JdbcResultSet(private val jdbc: java.sql.ResultSet) : ResultSet {
    override fun next(): Boolean = jdbc.next()
    override fun getObject(columnIndex: Int, type: KClass<*>): Any? = try {
        val javaType = kmpToJavaType[type.qualifiedName] ?: type.java
        if (type == Any::class) jdbc.getObject(columnIndex)
        else jdbc.getObject(columnIndex, javaType)
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
