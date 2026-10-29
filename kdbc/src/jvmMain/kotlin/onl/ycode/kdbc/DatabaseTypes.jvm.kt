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
 * Java class to request from the driver, per declared type.
 *
 * Everything [kmpToJdbcTarget] remaps on the way in has to be remapped on the way
 * out too, so that table is the base. On top of it sit the absolute-instant
 * `java.time` types: asking a driver for those directly is not portable (ojdbc
 * rejects `Instant.class`, and the untyped fallback yields an unconvertible
 * `oracle.sql.TIMESTAMP`), and a driver-native read interprets the stored wall
 * time as UTC while the bind wrote it through the JVM default zone — an
 * asymmetric shift. `java.sql.Timestamp` is accepted by every driver and is
 * zone-symmetric with [toJdbcValue]; [TypeConversion] finishes the conversion.
 *
 * `java.util.Date` is not a JDBC type either: pgjdbc rejects it, but the MariaDB
 * driver answers with a `java.sql.Date` and drops the time of day. A
 * `java.sql.Timestamp` is a `java.util.Date` and keeps it.
 *
 * `CharArray` is here for a blunter reason: `char[]` is not a JDBC type, so asking
 * for it throws on every driver and costs a caught exception per cell before the
 * fallback arrives at the text anyway.
 */
private val jdbcReadTarget: Map<String, KClass<*>> = kmpToJdbcTarget + mapOf(
    "java.time.Instant" to java.sql.Timestamp::class,
    "java.time.OffsetDateTime" to java.sql.Timestamp::class,
    "java.time.ZonedDateTime" to java.sql.Timestamp::class,
    "java.util.Date" to java.sql.Timestamp::class,
    "kotlin.CharArray" to String::class,
)

/**
 * Types the driver is never asked for, because it answers without failing and the
 * answers disagree.
 *
 * A driver that rejects a requested class throws, and the call sites recover by
 * re-reading untyped. Silent disagreement has no such safety net, so it has to be
 * pre-empted here. Measured on a text column holding a boolean token: the MariaDB
 * driver reads `'false'` as true, xerial SQLite reads `'true'` as false, and
 * mssql-jdbc rejects `'t'`. Reading untyped and applying
 * [TypeConversion.isTrueToken] gives one answer on every database, and the same
 * answer as the native drivers.
 */
private val jdbcUntrustedReads = setOf("kotlin.Boolean")

/**
 * The Java class to request from the driver when the caller asks for [type].
 *
 * Falls back to `javaObjectType`: a [KClass] may carry the primitive Java class
 * (e.g. `boolean.class` when it originates from `typeOf<T>().classifier`), which
 * JDBC drivers reject.
 */
private fun jdbcReadType(type: KClass<*>): Class<*> =
    jdbcReadTarget[type.qualifiedName]?.java ?: type.javaObjectType

/**
 * The Java class to request from the driver for [type] on a column of JDBC type [sqlType].
 *
 * A driver is only sure to produce the column's own class: pgjdbc refuses a `Long` from
 * an `int4` or a `String` from a number, ojdbc a `BigInteger` or a `Character`, every
 * driver a `UUID` or a `Character` from a `VARCHAR`. A refusal is caught and read again
 * untyped, but the driver, or the pool wrapping it, may log it first, and it costs a
 * thrown exception per cell. So for a column of numbers, text or bytes the column's own
 * class is asked for, and [TypeConversion] makes it the declared type. Temporal types
 * are asked for as declared ([jdbcReadTarget]), which keeps the precision and the zone
 * handling of the driver, unless the column holds another kind of temporal value (a
 * date read from a timestamp), which is asked for as the column's own.
 */
private fun jdbcReadType(type: KClass<*>, sqlType: Int): Class<*> {
    val kind = temporalKind[type.qualifiedName]
    if (kind != null) {
        val columnKind = when (sqlType) {
            java.sql.Types.DATE -> TemporalKind.DATE
            java.sql.Types.TIME -> TemporalKind.TIME
            java.sql.Types.TIMESTAMP -> TemporalKind.DATETIME
            // A temporal value kept as text is read as the text, and parsed
            else -> return if (columnClass(sqlType) == String::class.java) String::class.java else jdbcReadType(type)
        }
        return if (columnKind == kind) jdbcReadType(type) else columnKind.sqlClass
    }
    return columnClass(sqlType) ?: jdbcReadType(type)
}

private enum class TemporalKind(val sqlClass: Class<*>) {
    DATE(java.sql.Date::class.java), TIME(java.sql.Time::class.java), DATETIME(java.sql.Timestamp::class.java)
}

/** The kind of temporal value each temporal type holds. */
private val temporalKind: Map<String, TemporalKind> = mapOf(
    "java.sql.Date" to TemporalKind.DATE,
    "java.time.LocalDate" to TemporalKind.DATE,
    "kotlinx.datetime.LocalDate" to TemporalKind.DATE,
    "java.sql.Time" to TemporalKind.TIME,
    "java.time.LocalTime" to TemporalKind.TIME,
    "java.time.OffsetTime" to TemporalKind.TIME,
    "kotlinx.datetime.LocalTime" to TemporalKind.TIME,
    "java.sql.Timestamp" to TemporalKind.DATETIME,
    "java.util.Date" to TemporalKind.DATETIME,
    "java.time.LocalDateTime" to TemporalKind.DATETIME,
    "java.time.Instant" to TemporalKind.DATETIME,
    "java.time.OffsetDateTime" to TemporalKind.DATETIME,
    "java.time.ZonedDateTime" to TemporalKind.DATETIME,
    "kotlinx.datetime.LocalDateTime" to TemporalKind.DATETIME,
    "kotlin.time.Instant" to TemporalKind.DATETIME,
)

/** The class every driver produces for a column of numbers, text or bytes, or null for any other column. */
private fun columnClass(sqlType: Int): Class<*>? = when (sqlType) {
    java.sql.Types.TINYINT, java.sql.Types.SMALLINT, java.sql.Types.INTEGER -> Int::class.javaObjectType
    java.sql.Types.BIGINT -> Long::class.javaObjectType
    java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> java.math.BigDecimal::class.java
    java.sql.Types.REAL -> Float::class.javaObjectType
    java.sql.Types.FLOAT, java.sql.Types.DOUBLE -> Double::class.javaObjectType
    java.sql.Types.CHAR, java.sql.Types.VARCHAR, java.sql.Types.LONGVARCHAR, java.sql.Types.NCHAR,
    java.sql.Types.NVARCHAR, java.sql.Types.LONGNVARCHAR, java.sql.Types.CLOB, java.sql.Types.NCLOB -> String::class.java
    java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB -> ByteArray::class.java
    else -> null
}

private val temporalColumns = setOf(java.sql.Types.DATE, java.sql.Types.TIME, java.sql.Types.TIMESTAMP)

private val binaryColumns = setOf(
    java.sql.Types.BINARY, java.sql.Types.VARBINARY, java.sql.Types.LONGVARBINARY, java.sql.Types.BLOB
)

/**
 * True when [type] is text, read with `getString`, which every driver answers for every
 * column but bytes, with its own rendering of the value.
 */
private fun readsAsText(type: KClass<*>, sqlType: Int): Boolean =
    (type == String::class || type == Char::class || type == CharArray::class) && sqlType !in binaryColumns

/**
 * The value read as [type]: converted by [TypeConversion] when the driver gave the column's
 * own class. A `java.util.Date` is handed over as one, not as the `java.sql.Date` or
 * `java.sql.Time` the driver may give, whose `toInstant()` throws.
 */
private fun fitDeclared(type: KClass<*>, value: Any?): Any? = when {
    value == null -> null
    type == java.util.Date::class && (value is java.sql.Date || value is java.sql.Time) -> java.util.Date((value as java.util.Date).time)
    type.isInstance(value) || !TypeConversion.isKnownScalar(type) -> materializeLob(value)
    else -> TypeConversion.castScalar(type, materializeLob(value))
}

/** True when [type] must be read untyped and coerced by [TypeConversion]. */
private fun readsUntyped(type: KClass<*>): Boolean =
    type == Any::class || type.qualifiedName in jdbcUntrustedReads

/**
 * Materialize a driver LOB handle into the value it stands for.
 *
 * A CLOB or BLOB column read without an explicit target type hands back a live
 * [java.sql.Clob]/[java.sql.Blob] bound to the result set, which stops being
 * readable once the row moves on. The native drivers return the content itself, so
 * reading it here is what makes the two sides agree.
 */
private fun materializeLob(value: Any?): Any? = when (value) {
    is java.sql.Clob -> value.characterStream.use { it.readText() }
    is java.sql.Blob -> value.binaryStream.use { it.readBytes() }
    else -> value
}

/**
 * Read column/parameter [index] untyped via [get] and coerce it to [type].
 *
 * A type kdbc has no converter for is handed back as read, as the native drivers do,
 * and left to the caller's own conversion.
 */
private fun coerceUntyped(type: KClass<*>, index: Int, get: (Int) -> Any?): Any? {
    val raw = materializeLob(get(index))
    return if (type == Any::class || !TypeConversion.isKnownScalar(type)) raw
    else TypeConversion.castScalar(type, raw)
}

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

/**
 * The statement a connection is running, which [Connection.cancel] stops from another
 * thread. A query stays running while its rows are read, until its result set or
 * statement is closed; an update ends when it returns.
 */
private class RunningStatement {
    @Volatile private var current: java.sql.Statement? = null

    inline fun <R> update(statement: java.sql.Statement, block: () -> R): R {
        current = statement
        try {
            return block()
        } finally {
            done(statement)
        }
    }

    inline fun <R> query(statement: java.sql.Statement, block: () -> R): R {
        current = statement
        return try {
            block()
        } catch (e: Throwable) {
            done(statement)
            throw e
        }
    }

    fun done(statement: java.sql.Statement?) {
        if (statement != null && current === statement) current = null
    }

    fun cancel() {
        current?.let { runCatching { it.cancel() } }
    }
}

// Wrapper class for PreparedStatement with lazy preparation.
// If setObject() is never called (no params), executeUpdate/executeQuery
// use direct execution via more efficient jdbc.createStatement()
private class JdbcStatement(
    private val jdbc: java.sql.Connection,
    private val sql: String,
    private val returnGeneratedKeys: Boolean,
    private val columnNames: Array<String>?,
    private val temporalUntyped: Lazy<Boolean>,
    private val running: RunningStatement,
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

    override fun executeUpdate(): Int {
        val ps = preparedStatement
        if (ps != null) return running.update(ps) { ps.executeUpdate() }
        val s = ensureDirect()
        return running.update(s) {
            if (returnGeneratedKeys) s.executeUpdate(sql, RETURN_GENERATED_KEYS)
            else if (!columnNames.isNullOrEmpty()) s.executeUpdate(sql, columnNames)
            else s.executeUpdate(sql)
        }
    }

    override fun executeQuery(): ResultSet {
        val ps = preparedStatement
        val s = ps ?: ensureDirect()
        val rs = running.query(s) { ps?.executeQuery() ?: s.executeQuery(sql) }
        return JdbcResultSet(rs, temporalUntyped) { running.done(s) }
    }

    override fun getGeneratedKeys(): ResultSet =
        JdbcResultSet(
            (preparedStatement ?: directStatement)?.generatedKeys
                ?: throw SQLException("Generated keys not available"),
            temporalUntyped
        )

    override fun addBatch() = ensurePrepared().addBatch()
    override fun executeBatch(): IntArray = ensurePrepared().let { ps -> running.update(ps) { ps.executeBatch() } }

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
        running.done(preparedStatement)
        running.done(directStatement)
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
private class JdbcCallableStatement(
    private val jdbc: java.sql.CallableStatement,
    private val running: RunningStatement,
) : CallableStatement {
    /** SQL type of every parameter registered as OUT, so an INOUT can be sent a typed NULL. */
    private val outSqlTypes = HashMap<Int, Int>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        // An untyped NULL on an INOUT parameter conflicts with its registered OUT
        // type on Oracle ("Parameter Type Conflict"): send NULL of that type.
        val outType = if (value == null) outSqlTypes[parameterIndex] else null
        if (outType != null) jdbc.setNull(parameterIndex, outType)
        else jdbc.setObject(parameterIndex, toJdbcValue(value))
    }
    override fun executeUpdate(): Int = running.update(jdbc) { jdbc.executeUpdate() }
    override fun executeQuery(): ResultSet =
        JdbcResultSet(running.query(jdbc) { jdbc.executeQuery() }, lazyOf(false)) { running.done(jdbc) }
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(jdbc.generatedKeys, lazyOf(false))
    override fun addBatch() = jdbc.addBatch()
    override fun executeBatch(): IntArray = running.update(jdbc) { jdbc.executeBatch() }
    override fun close() {
        running.done(jdbc)
        jdbc.close()
    }
    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        val sqlType = toSqlType(type)
        outSqlTypes[parameterIndex] = sqlType
        jdbc.registerOutParameter(parameterIndex, sqlType)
    }
    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? = try {
        if (readsUntyped(type)) coerceUntyped(type, parameterIndex, jdbc::getObject)
        else jdbc.getObject(parameterIndex, jdbcReadType(type))
    } catch (_: Exception) {
        // Same driver-strictness fallback as JdbcResultSet.getObject: read untyped
        // and coerce.
        coerceUntyped(type, parameterIndex, jdbc::getObject)
    }
    override fun execute(): Boolean = running.update(jdbc) { jdbc.execute() }
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
    private val sql: String,
    private val running: RunningStatement,
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
        val hasResult = running.update(stmt) { stmt.execute() }
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
        return try {
            if (readsUntyped(type)) coerceUntyped(type, colIndex, rs::getObject)
            else rs.getObject(colIndex, jdbcReadType(type))
        } catch (_: Exception) {
            // Same driver-strictness fallback as JdbcResultSet.getObject: read untyped
            // and coerce.
            coerceUntyped(type, colIndex, rs::getObject)
        }
    }

    override fun executeUpdate(): Int = running.update(stmt) { stmt.executeUpdate() }
    override fun executeQuery(): ResultSet =
        JdbcResultSet(running.query(stmt) { stmt.executeQuery() }, lazyOf(false)) { running.done(stmt) }
    override fun getGeneratedKeys(): ResultSet = JdbcResultSet(stmt.generatedKeys, lazyOf(false))
    override fun addBatch() = stmt.addBatch()
    override fun executeBatch(): IntArray = running.update(stmt) { stmt.executeBatch() }
    override fun close() {
        running.done(stmt)
        resultRow?.close()
        stmt.close()
    }
}

/**
 * JDBC DataSource wrapper that implements KDBC DataSource interface.
 *
 * [initSql] runs on every freshly opened connection before it is returned to the caller —
 * like HikariCP's `connectionInitSql`. Separate more than one statement with `;`; each is run
 * in order.
 *
 * Usage:
 * ```kotlin
 * val hikariDS = HikariDataSource(config)
 * val stormify = Stormify(hikariDS)  // Uses convenience function
 * // or
 * val stormify = Stormify(JdbcDataSource(hikariDS, "PRAGMA foreign_keys = ON"))
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
    // SQLite keeps a temporal value as text or as epoch milliseconds, and its driver
    // parses the text into the temporal classes only in the one format it is configured
    // for: temporal values are read untyped there, and converted here.
    private val temporalUntyped = lazy { jdbc.metaData.databaseProductName.equals("SQLite", ignoreCase = true) }
    private val psCache = StatementCache(statementCacheSize)
    private val running = RunningStatement()

    override val metaData: DatabaseMetaData
        get() = JdbcDatabaseMetaData(jdbc.metaData)


    override fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement =
        JdbcStatement(jdbc, sql, returnGeneratedKeys, columnNames, temporalUntyped, running)

    override fun acquirePreparedStatement(sql: String): Statement = psCache.acquire(this, sql)

    override fun prepareCall(sql: String): CallableStatement =
        if (isPostgres) JdbcPgCallableStatement(jdbc, sql, running)
        else JdbcCallableStatement(jdbc.prepareCall("{$sql}"), running)
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

    override fun cancel() = running.cancel()

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
private class JdbcResultSet(
    private val jdbc: java.sql.ResultSet,
    private val temporalUntyped: Lazy<Boolean>,
    private val onClose: (() -> Unit)? = null,
) : ResultSet {
    /** The JDBC type of each column, from the metadata, read on the first typed read. */
    private var columnTypes: IntArray? = null

    private fun columnType(columnIndex: Int): Int {
        val types = columnTypes ?: jdbc.metaData.let { md -> IntArray(md.columnCount) { md.getColumnType(it + 1) } }
            .also { columnTypes = it }
        return types[columnIndex - 1]
    }

    /** Whether the column's type carries a zone (`timetz`, `time with time zone`), which pgjdbc still reports as TIME. */
    private fun hasZone(columnIndex: Int): Boolean =
        jdbc.metaData.getColumnTypeName(columnIndex).lowercase().let { "tz" in it || "zone" in it }

    override fun next(): Boolean = jdbc.next()
    override fun getObject(columnIndex: Int, type: KClass<*>): Any? = try {
        if (readsUntyped(type)) coerceUntyped(type, columnIndex, jdbc::getObject)
        else {
            val sqlType = columnType(columnIndex)
            val value = when {
                readsAsText(type, sqlType) -> jdbc.getString(columnIndex)
                temporalUntyped.value && sqlType in temporalColumns && type.qualifiedName in temporalKind ->
                    // Epoch milliseconds, as the driver writes a temporal value by default
                    when (val raw = jdbc.getObject(columnIndex)) {
                        is Long, is Int -> java.sql.Timestamp((raw as Number).toLong())
                        else -> raw
                    }
                // Most drivers refuse an OffsetTime from a TIME column without a zone
                type == java.time.OffsetTime::class && sqlType == java.sql.Types.TIME && !hasZone(columnIndex) ->
                    jdbc.getObject(columnIndex, java.sql.Time::class.java)
                else -> jdbc.getObject(columnIndex, jdbcReadType(type, sqlType))
            }
            fitDeclared(type, value)
        }
    } catch (_: Exception) {
        // Some drivers throw non-SQLException (pg's getObject(idx, UUID.class) on
        // a TEXT column raises ClassCastException); fall back to untyped and coerce.
        coerceUntyped(type, columnIndex, jdbc::getObject)
    }

    override fun getMetaData(): ResultSetMetaData = JdbcResultSetMetaData(jdbc.metaData)
    override fun close() {
        onClose?.invoke()
        jdbc.close()
    }
}

// Wrapper class for ResultSetMetaData
private class JdbcResultSetMetaData(private val jdbc: java.sql.ResultSetMetaData) : ResultSetMetaData {
    override val columnCount: Int get() = jdbc.columnCount
    override fun getColumnName(column: Int): String = jdbc.getColumnName(column)
    override fun getColumnLabel(column: Int): String = jdbc.getColumnLabel(column)
}
