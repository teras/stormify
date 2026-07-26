@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

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
 * Map of KMP types to their JDBC-compatible Java equivalents.
 * Used by [toJdbcValue] to convert KMP values before passing to JDBC's setObject().
 */
private val kmpToJdbcTarget = mapOf<String, KClass<*>>(
    "com.ionspin.kotlin.bignum.decimal.BigDecimal" to java.math.BigDecimal::class,
    "com.ionspin.kotlin.bignum.integer.BigInteger" to java.math.BigDecimal::class, // BigDecimal, not BigInteger (MySQL truncates BigInteger)
    "kotlinx.datetime.LocalDate" to java.sql.Date::class,
    "kotlinx.datetime.LocalDateTime" to java.sql.Timestamp::class,
    "kotlinx.datetime.LocalTime" to java.sql.Time::class,
    "kotlin.time.Instant" to java.sql.Timestamp::class,
)

/**
 * Convert a value to a JDBC-compatible type before reaching `setObject`.
 *
 * Strict JDBC drivers refuse `java.util.Date` and the absolute-instant
 * `java.time.*` classes at bind time — they are not legal JDBC parameter
 * types. Normalize them to `java.sql.Timestamp` so every driver accepts them.
 *
 * KMP types (ionspin, kotlinx-datetime, kotlin.time) are routed through the
 * [TypeConversion] registry. Standard Java types and Kotlin primitives pass
 * through unchanged.
 */
private fun toJdbcValue(value: Any?): Any? {
    if (value == null) return null
    // Avoid downgrading the more specific java.sql.* subclasses, which JDBC
    // already accepts as-is.
    if (value is java.sql.Timestamp || value is java.sql.Date || value is java.sql.Time) return value
    when (value) {
        is java.util.Date -> return java.sql.Timestamp(value.time)
        is java.time.Instant -> return java.sql.Timestamp.from(value)
        is java.time.OffsetDateTime -> return java.sql.Timestamp.from(value.toInstant())
        is java.time.ZonedDateTime -> return java.sql.Timestamp.from(value.toInstant())
        // OffsetTime is left to the driver: pgjdbc preserves the offset against
        // TIMETZ; MariaDB raises SQLFeatureNotSupportedException; MySQL and MSSQL
        // silently coerce through the JVM-local zone (lossy on round-trip).
        // Char is not a JDBC parameter type — strict drivers refuse it.
        is Char -> return value.toString()
        // UUID is portable as the canonical 36-char string. PostgreSQL and SQL
        // Server happily coerce it to their native UUID/UNIQUEIDENTIFIER types
        // server-side; everywhere else it lands in CHAR/VARCHAR.
        is java.util.UUID -> return value.toString()
        is kotlin.uuid.Uuid -> return value.toString()
        // Stormify treats any CharSequence as a scalar (StringBuilder, etc.),
        // but JDBC's setObject only reliably accepts String. Materialize.
        is String -> return value
        is CharSequence -> return value.toString()
    }
    val targetClass = kmpToJdbcTarget[value::class.qualifiedName] ?: return value
    return try { TypeConversion.castScalar(targetClass, value) } catch (_: Throwable) { value }
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
    // Captured before the first statement is realised, applied to whichever
    // one ends up running. Null = "no hint set"; MIN_VALUE is a valid hint
    // (MySQL streaming switch).
    private var pendingFetchSize: Int? = null

    private fun ensurePrepared(): PreparedStatement {
        if (preparedStatement == null) {
            val ps = if (returnGeneratedKeys)
                jdbc.prepareStatement(sql, RETURN_GENERATED_KEYS)
            else if (!columnNames.isNullOrEmpty())
                jdbc.prepareStatement(sql, columnNames)
            else
                jdbc.prepareStatement(sql)
            pendingFetchSize?.let { ps.fetchSize = it }
            preparedStatement = ps
        }
        return preparedStatement!!
    }

    private fun ensureDirect(): java.sql.Statement {
        if (directStatement == null) {
            val s = jdbc.createStatement()
            pendingFetchSize?.let { s.fetchSize = it }
            directStatement = s
        }
        return directStatement!!
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        // Nulls go through plain setObject(idx, null) — no setNull branch.
        // setNull(idx, Types.NULL) breaks on Microsoft SQL Server for typed
        // columns (REAL/FLOAT/DATE/TIME/DATETIME2) because the driver maps
        // the sentinel to VARBINARY and the server rejects the implicit
        // conversion. setObject(idx, null) is portable across every other
        // tested driver (PostgreSQL, MySQL, MariaDB, Oracle, SQLite).
        //
        // Known residual corner case: Microsoft SQL Server + VARBINARY(MAX)
        // column + null value. The driver maps untyped null to NVARCHAR,
        // and the server rejects "Implicit conversion from data type
        // nvarchar to varbinary(max) is not allowed." Resolving this
        // without a round-trip to fetch parameter metadata is not possible
        // — the column type lives on the server and the JDBC bind layer
        // has no local knowledge of it. See docs/src/Raw_Queries.md for
        // user-facing workarounds.
        ensurePrepared().setObject(parameterIndex, toJdbcValue(value))
    }

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

    override fun reset() {
        preparedStatement?.let {
            it.clearParameters()
            try { it.clearBatch() } catch (_: Throwable) { /* not all drivers track an empty batch */ }
        }
    }

    override fun setFetchSize(rows: Int) {
        if (rows < 0 && rows != Integer.MIN_VALUE) return
        pendingFetchSize = rows
        preparedStatement?.fetchSize = rows
        directStatement?.fetchSize = rows
    }

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
    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? = jdbc.getObject(parameterIndex, type.javaObjectType)
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
        else rs.getObject(colIndex, type.javaObjectType)
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
 * [initSql] is a single SQL statement executed on every freshly opened connection before
 * it is returned to the caller — equivalent to HikariCP's `connectionInitSql`. If the
 * statement fails the connection is closed and the exception propagates.
 *
 * Usage:
 * ```kotlin
 * val hikariDS = HikariDataSource(config)
 * val stormify = Stormify(hikariDS)  // Uses convenience function
 * // or
 * val stormify = Stormify(JdbcDataSource(hikariDS, initSql = "PRAGMA foreign_keys = ON"))
 * ```
 */
class JdbcDataSource(
    private val jdbc: javax.sql.DataSource,
    private val initSql: String? = null,
    /** Per-connection prepared-statement cache size. 0 disables caching. */
    private val statementCacheSize: Int = 64,
) : DataSource {
    override fun getConnection(): Connection =
        JdbcConnection(jdbc.connection, statementCacheSize).runInitSql(initSql)
}

// Wrapper class for Connection
private class JdbcConnection(
    private val jdbc: java.sql.Connection,
    statementCacheSize: Int,
) : Connection {
    private val isPostgres by lazy {
        jdbc.metaData.databaseProductName.lowercase().contains("postgresql")
    }
    private val psCache = StatementCache(statementCacheSize)

    override val metaData: DatabaseMetaData
        get() = JdbcDatabaseMetaData(jdbc.metaData)


    override fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement =
        JdbcStatement(jdbc, sql, returnGeneratedKeys, columnNames)

    override fun acquirePreparedStatement(sql: String): Statement = psCache.acquire(this, sql)

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
    override fun getAutoCommit(): Boolean = jdbc.autoCommit

    override fun close() {
        // Release all cached statements before the underlying connection goes away.
        runCatching { psCache.closeAll() }
        jdbc.close()
    }
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
        // javaObjectType: a KClass may carry the primitive Java class (e.g. boolean.class
        // when it originates from typeOf<T>().classifier), which JDBC drivers reject.
        val javaType = kmpToJdbcTarget[type.qualifiedName]?.java ?: type.javaObjectType
        if (type == Any::class) jdbc.getObject(columnIndex)
        else jdbc.getObject(columnIndex, javaType)
    } catch (_: Exception) {
        // Some drivers throw non-SQLException (pg's getObject(idx, UUID.class) on
        // a TEXT column raises ClassCastException); fall back to untyped and let
        // the converter pipeline coerce.
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
