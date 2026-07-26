// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class, kotlin.uuid.ExperimentalUuidApi::class)

package onl.ycode.kdbc

import cnames.structs.kdbc_conn
import cnames.structs.kdbc_result
import cnames.structs.kdbc_stmt
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlinx.cinterop.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import onl.ycode.kdbc.cinterop.*
import kotlin.reflect.KClass
import kotlin.time.Instant as KtInstant

/*
 * Kotlin/Native wrapper classes for the unified kdbc C library.
 *
 * All classes wrap opaque C handles (kdbc_conn*, kdbc_stmt*, kdbc_result*) and
 * translate failures into SQLException using kdbc_error / kdbc_stmt_error / kdbc_global_error.
 *
 * Memory management:
 *   - C handles are closed through their kdbc_* _close functions.
 *   - Temporary strings for parameter binding are allocated inside memScoped blocks
 *     tied to the lifetime of the call (bindings are copied into the prepared statement
 *     by the driver layer, so no long-lived allocations are needed).
 */

private fun connError(conn: CPointer<kdbc_conn>?, fallback: String): String {
    val msg = conn?.let { kdbc_error(it)?.toKString() }
        ?: kdbc_global_error()?.toKString()
    return if (msg.isNullOrEmpty()) fallback else msg
}

private fun stmtError(stmt: CPointer<kdbc_stmt>?, fallback: String): String {
    val msg = stmt?.let { kdbc_stmt_error(it)?.toKString() }
    return if (msg.isNullOrEmpty()) fallback else msg
}

/**
 * Builds an [SQLException] for the current connection-level failure, populating
 * [SQLException.sqlState] and [SQLException.errorCode] from the C layer's
 * structured error fields. When [conn] is `null` the global error slot is
 * consulted (used for connect-time failures before a connection exists).
 * The empty SQLSTATE string and the zero errcode produced by drivers that do
 * not surface a value are normalised to `null`.
 */
private fun kdbcConnException(conn: CPointer<kdbc_conn>?, message: String): SQLException {
    val state = if (conn != null) kdbc_sqlstate(conn)?.toKString()
                else kdbc_global_sqlstate()?.toKString()
    val sqlState = state?.takeUnless { it.isEmpty() }
    val raw = if (conn != null) kdbc_errcode(conn) else kdbc_global_errcode()
    val errorCode: Int? = if (raw != 0) raw else null
    return SQLException(message, null, sqlState, errorCode)
}

/**
 * Builds an [SQLException] for the current statement-level failure, populating
 * [SQLException.sqlState] and [SQLException.errorCode] from the C layer's
 * structured error fields. The empty SQLSTATE string and the zero errcode
 * produced by drivers that do not surface a value are normalised to `null`.
 */
private fun kdbcStmtException(stmt: CPointer<kdbc_stmt>?, message: String): SQLException {
    val state = stmt?.let { kdbc_stmt_sqlstate(it)?.toKString() }
    val sqlState = state?.takeUnless { it.isEmpty() }
    val raw = stmt?.let { kdbc_stmt_errcode(it) } ?: 0
    val errorCode: Int? = if (raw != 0) raw else null
    return SQLException(message, null, sqlState, errorCode)
}

/**
 * Builds an [SQLException] for the current result-set-level failure, populating
 * [SQLException.sqlState] and [SQLException.errorCode] from the C layer's
 * structured error fields. The empty SQLSTATE string and the zero errcode
 * produced by drivers that do not surface a value are normalised to `null`.
 */
private fun kdbcResultException(rs: CPointer<kdbc_result>?, message: String): SQLException {
    val state = rs?.let { kdbc_result_sqlstate(it)?.toKString() }
    val sqlState = state?.takeUnless { it.isEmpty() }
    val raw = rs?.let { kdbc_result_errcode(it) } ?: 0
    val errorCode: Int? = if (raw != 0) raw else null
    return SQLException(message, null, sqlState, errorCode)
}

/**
 * Reads the result set's error message via [kdbc_result_error], falling back
 * to [fallback] when the buffer is empty.
 */
private fun resultError(rs: CPointer<kdbc_result>?, fallback: String): String {
    val msg = rs?.let { kdbc_result_error(it)?.toKString() }
    return if (msg.isNullOrEmpty()) fallback else msg
}

internal fun KdbcDriverKind.toCValue(): kdbc_driver = when (this) {
    KdbcDriverKind.SQLITE -> KDBC_SQLITE
    KdbcDriverKind.POSTGRES -> KDBC_POSTGRES
    KdbcDriverKind.MARIADB -> KDBC_MARIADB
    KdbcDriverKind.ORACLE -> KDBC_ORACLE
    KdbcDriverKind.MSSQL -> KDBC_MSSQL
}

/**
 * DataSource implementation that uses the unified kdbc C library.
 * Created indirectly through [KdbcDataSource] (the public factory).
 *
 * This DataSource does NOT pool connections — each [getConnection] call opens a
 * fresh native connection. Connection pooling is the responsibility of a higher
 * layer (see `stormify.coroutines` which provides a proper suspend-aware pool).
 */
class NativeKdbcDataSource internal constructor(
    private val kind: KdbcDriverKind,
    private val nativeUrl: String,
    private val user: String?,
    private val password: String?,
    private val initSql: String? = null,
    /** Per-connection prepared-statement cache size. 0 disables caching. */
    private val statementCacheSize: Int = 64,
) : DataSource {

    init {
        if (kdbc_driver_available(kind.toCValue()) == 0) {
            throw SQLException(
                "KDBC driver for $kind is not available on this system: " +
                        (kdbc_global_error()?.toKString() ?: "required client library missing")
            )
        }
    }

    override fun getConnection(): Connection {
        val conn: CPointer<kdbc_conn>? = kdbc_connect(
            kind.toCValue(),
            nativeUrl,
            user,
            password
        )
        if (conn == null) {
            throw kdbcConnException(null, "Failed to connect to $kind ($nativeUrl): ${connError(null, "unknown error")}")
        }
        return NativeConnection(conn, kind, statementCacheSize).runInitSql(initSql)
    }
}

/**
 * Wraps a kdbc_conn* handle.
 */
private class NativeConnection(
    private val handle: CPointer<kdbc_conn>,
    private val kind: KdbcDriverKind,
    statementCacheSize: Int = 64,
) : NativeKdbcConnection {
    private val psCache = StatementCache(statementCacheSize)
    // kotlin.concurrent.Volatile (multiplatform) ensures that [cancel], which is
    // explicitly designed to be called from a thread other than the one using the
    // connection, observes a close() that happened on the owning thread. Without
    // the write-visibility guarantee, cancel() could dispatch kdbc_cancel on an
    // already-freed handle on weak memory architectures.
    @kotlin.concurrent.Volatile
    private var closed = false
    private val supportsReleaseSavepoint: Boolean =
        kdbc_driver_supports_release_savepoint(kind.toCValue()) != 0

    override val metaData: DatabaseMetaData = NativeDatabaseMetaData(handle)

    override fun initStatement(
        sql: String,
        returnGeneratedKeys: Boolean,
        columnNames: Array<String>?
    ): Statement {
        ensureOpen()
        val stmt: CPointer<kdbc_stmt>? = if (returnGeneratedKeys || !columnNames.isNullOrEmpty()) {
            prepareReturning(sql, columnNames)
        } else {
            kdbc_prepare(handle, sql)
        }
        if (stmt == null)
            throw kdbcConnException(handle, "Failed to prepare statement: ${connError(handle, "prepare failed")}\nSQL: $sql")
        return NativeStatement(stmt, handle)
    }

    override fun acquirePreparedStatement(sql: String): Statement {
        ensureOpen()
        return psCache.acquire(this, sql)
    }

    private fun prepareReturning(sql: String, columnNames: Array<String>?): CPointer<kdbc_stmt>? {
        // When caller passes returnGeneratedKeys=true without column names, the C layer
        // uses the driver's default ("id"/ROWID/IDENTITY) based on the dialect.
        // When column names are provided, we forward them verbatim (used by PG RETURNING).
        val names = columnNames ?: emptyArray()
        return memScoped {
            if (names.isEmpty()) {
                kdbc_prepare_returning(handle, sql, null, 0)
            } else {
                val cStrings = allocArray<CPointerVar<ByteVar>>(names.size)
                for (i in names.indices) cStrings[i] = names[i].cstr.ptr
                kdbc_prepare_returning(handle, sql, cStrings, names.size)
            }
        }
    }

    override fun prepareCall(sql: String): CallableStatement {
        ensureOpen()
        val stmt = kdbc_prepare_call(handle, sql)
            ?: throw kdbcConnException(handle, "Failed to prepare call: ${connError(handle, "prepare_call failed")}\nSQL: $sql")
        return NativeCallableStatement(stmt, handle)
    }

    override fun commit() {
        ensureOpen()
        if (kdbc_commit(handle) != KDBC_OK)
            throw kdbcConnException(handle, "commit failed: ${connError(handle, "commit failed")}")
    }

    override fun rollback(savepoint: Savepoint?) {
        ensureOpen()
        val rc = if (savepoint != null) {
            kdbc_rollback_to(handle, savepoint.savepointName)
        } else {
            kdbc_rollback(handle)
        }
        if (rc != KDBC_OK)
            throw kdbcConnException(handle, "rollback failed: ${connError(handle, "rollback failed")}")
    }

    override fun setSavepoint(name: String): Savepoint {
        ensureOpen()
        if (kdbc_savepoint(handle, name) != KDBC_OK)
            throw kdbcConnException(handle, "savepoint '$name' failed: ${connError(handle, "savepoint failed")}")
        return SimpleSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        ensureOpen()
        if (!supportsReleaseSavepoint) return  // Oracle / MSSQL silently skip
        if (kdbc_release_savepoint(handle, savepoint.savepointName) != KDBC_OK)
            throw kdbcConnException(handle, "release savepoint '${savepoint.savepointName}' failed: ${connError(handle, "release failed")}")
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        ensureOpen()
        if (kdbc_set_autocommit(handle, if (autoCommit) 1 else 0) != KDBC_OK)
            throw kdbcConnException(handle, "setAutoCommit failed: ${connError(handle, "setAutoCommit failed")}")
    }

    override fun getAutoCommit(): Boolean {
        ensureOpen()
        return kdbc_get_autocommit(handle) != 0
    }

    override fun cancel() {
        // Best-effort async cancel. Safe to call from a different thread while another
        // thread is blocked inside a kdbc call on the same handle — this is the whole
        // point of the primitive. Errors are swallowed: cancel is advisory, and a failure
        // to dispatch the cancel should not throw a new exception at the cancellation
        // handler site (which is typically called from coroutine machinery).
        if (closed) return
        kdbc_cancel(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        // Release every cached prepared statement before the underlying handle goes away.
        runCatching { psCache.closeAll() }
        kdbc_close(handle)
    }

    private fun ensureOpen() {
        if (closed) throw SQLException("Connection is closed")
    }
}

/**
 * Wraps a kdbc_stmt* handle.
 */
private open class NativeStatement(
    protected val handle: CPointer<kdbc_stmt>,
    protected val conn: CPointer<kdbc_conn>
) : Statement {
    private var closed = false
    private var lastGeneratedKeys: CPointer<kdbc_result>? = null

    override fun setObject(parameterIndex: Int, value: Any?) {
        ensureOpen()
        bindValue(handle, parameterIndex, value)
    }

    override fun executeUpdate(): Int {
        ensureOpen()
        val rc = kdbc_execute_update_stmt(handle)
        if (rc == KDBC_ERROR)
            throw kdbcStmtException(handle, "executeUpdate failed: ${stmtError(handle, "executeUpdate failed")}")
        return rc
    }

    override fun executeQuery(): ResultSet {
        ensureOpen()
        val rs = kdbc_execute_query_stmt(handle)
            ?: throw kdbcStmtException(handle, "executeQuery failed: ${stmtError(handle, "executeQuery failed")}")
        return NativeResultSet(rs)
    }

    override fun getGeneratedKeys(): ResultSet {
        ensureOpen()
        val rs = kdbc_generated_keys(handle)
            ?: return EmptyResultSet()
        lastGeneratedKeys = rs
        return NativeResultSet(rs)
    }

    override fun addBatch() {
        ensureOpen()
        if (kdbc_add_batch(handle) != KDBC_OK)
            throw kdbcStmtException(handle, "addBatch failed: ${stmtError(handle, "addBatch failed")}")
    }

    override fun executeBatch(): IntArray {
        ensureOpen()
        val total = kdbc_execute_batch(handle)
        if (total == KDBC_ERROR)
            throw kdbcStmtException(handle, "executeBatch failed: ${stmtError(handle, "executeBatch failed")}")
        // The C API returns total affected rows for the whole batch; we return a single-element
        // array for JDBC-compatibility (stormify uses the sum, not per-row counts).
        return intArrayOf(total)
    }

    override fun setFetchSize(rows: Int) {
        ensureOpen()
        // Negatives clamp to 0 (eager) so an over/underflow never accidentally
        // enables streaming via the driver's positive-value gate.
        kdbc_set_fetch_size(handle, if (rows < 0) 0 else rows)
    }

    override fun reset() {
        ensureOpen()
        // kdbc_stmt_reset clears bound params and any pending batch slots while
        // preserving the underlying server-side prepare. Used by the Stormify-level
        // PreparedStatement cache to reuse this statement for the next execute.
        kdbc_stmt_reset(handle)
    }

    override fun close() {
        if (closed) return
        closed = true
        kdbc_stmt_close(handle)
    }

    protected fun ensureOpen() {
        if (closed) throw SQLException("Statement is closed")
    }
}

/**
 * Wraps a kdbc_stmt* used as a callable statement (stored procedure).
 *
 * OUT parameter retrieval currently goes through `kdbc_call_get_long` and
 * `kdbc_call_get_string`. The [getObject] method chooses between them based on
 * the requested Kotlin type.
 */
private class NativeCallableStatement(
    handle: CPointer<kdbc_stmt>,
    conn: CPointer<kdbc_conn>
) : NativeStatement(handle, conn), CallableStatement {

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        ensureOpen()
        if (kdbc_register_out(handle, parameterIndex) != KDBC_OK)
            throw kdbcStmtException(handle, "registerOutParameter failed: ${stmtError(handle, "register_out failed")}")
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        ensureOpen()
        return when (type) {
            String::class -> kdbc_call_get_string(handle, parameterIndex)?.toKString()
            Byte::class -> kdbc_call_get_long(handle, parameterIndex).toByte()
            Short::class -> kdbc_call_get_long(handle, parameterIndex).toShort()
            Int::class -> kdbc_call_get_long(handle, parameterIndex).toInt()
            Long::class -> kdbc_call_get_long(handle, parameterIndex)
            Float::class -> kdbc_call_get_long(handle, parameterIndex).toFloat()
            Double::class -> kdbc_call_get_long(handle, parameterIndex).toDouble()
            Boolean::class -> kdbc_call_get_long(handle, parameterIndex) != 0L
            BigInteger::class -> BigInteger.fromLong(kdbc_call_get_long(handle, parameterIndex))
            BigDecimal::class -> BigDecimal.fromLong(kdbc_call_get_long(handle, parameterIndex))
            else -> kdbc_call_get_long(handle, parameterIndex)
        }
    }

    override fun execute(): Boolean {
        ensureOpen()
        val rc = kdbc_call_execute(handle)
        if (rc == KDBC_ERROR)
            throw kdbcStmtException(handle, "call execute failed: ${stmtError(handle, "execute failed")}")
        return rc == 1
    }
}

/**
 * Wraps a kdbc_result* handle.
 */
private class NativeResultSet(
    private val handle: CPointer<kdbc_result>
) : ResultSet {
    private var closed = false
    private val metaData: ResultSetMetaData by lazy { NativeResultSetMetaData(handle) }

    override fun next(): Boolean {
        ensureOpen()
        val rc = kdbc_next(handle)
        if (rc == KDBC_ERROR)
            throw kdbcResultException(handle, "next() failed: ${resultError(handle, "fetch failed")}")
        return rc == 1
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        ensureOpen()
        if (kdbc_is_null(handle, columnIndex) == 1) return null
        return when (type) {
            Byte::class -> kdbc_get_long(handle, columnIndex).toByte()
            Short::class -> kdbc_get_long(handle, columnIndex).toShort()
            Int::class -> kdbc_get_long(handle, columnIndex).toInt()
            Long::class -> kdbc_get_long(handle, columnIndex)
            Float::class -> kdbc_get_double(handle, columnIndex).toFloat()
            Double::class -> kdbc_get_double(handle, columnIndex)
            Boolean::class -> readBoolean(columnIndex)
            String::class -> kdbc_get_string(handle, columnIndex)?.toKString()
            ByteArray::class -> readBlob(columnIndex)
            BigInteger::class -> readBigInteger(columnIndex)
            BigDecimal::class -> readBigDecimal(columnIndex)
            LocalDateTime::class -> readTimestamp(columnIndex)
            LocalDate::class -> readDate(columnIndex)
            LocalTime::class -> readTime(columnIndex)
            KtInstant::class -> readTimestamp(columnIndex)?.let {
                kotlinx.datetime.TimeZone.currentSystemDefault().let { tz ->
                    it.toInstant(tz)
                }
            }
            Any::class -> readAny(columnIndex)
            else -> readAny(columnIndex)
        }
    }

    private fun readAny(col: Int): Any? {
        // The C layer does not expose a "natural column type" function, so we fetch the value
        // as a string (drivers format numerics/dates consistently when asked for strings) and
        // reinterpret: prefer Long, then Double, else String. This mirrors what JDBC's
        // `getObject(i)` returns for the generic Map<String, Any> read path in Stormify.
        val s = kdbc_get_string(handle, col)?.toKString() ?: return null
        s.toLongOrNull()?.let { return it }
        s.toDoubleOrNull()?.let { return it }
        return s
    }

    // Read through the string form and apply the shared rule, rather than testing
    // kdbc_get_long directly: the two disagree on inputs each parses differently
    // (a text "0.5" reads as 0 through the integer getter), and boolean reads must
    // give the same answer here as on JVM and Android.
    private fun readBoolean(col: Int): Boolean =
        kdbc_get_string(handle, col)?.toKString()?.let { TypeConversion.asBoolean(it) } ?: false

    private fun readBlob(col: Int): ByteArray? = memScoped {
        val lenVar = alloc<ULongVar>()
        val ptr = kdbc_get_blob(handle, col, lenVar.ptr) ?: return null
        val len = lenVar.value.toInt()
        if (len == 0) return ByteArray(0)
        ByteArray(len).also { arr ->
            arr.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), ptr, len.toULong())
            }
        }
    }

    private fun readBigInteger(col: Int): BigInteger? {
        val str = kdbc_get_string(handle, col)?.toKString() ?: return null
        return try {
            BigInteger.parseString(str)
        } catch (_: Exception) {
            BigInteger.fromLong(kdbc_get_long(handle, col))
        }
    }

    private fun readBigDecimal(col: Int): BigDecimal? {
        val str = kdbc_get_string(handle, col)?.toKString() ?: return null
        return try {
            BigDecimal.parseString(str)
        } catch (_: Exception) {
            BigDecimal.fromDouble(kdbc_get_double(handle, col))
        }
    }

    private fun readTimestamp(col: Int): LocalDateTime? = memScoped {
        val y = alloc<IntVar>(); val mo = alloc<IntVar>(); val d = alloc<IntVar>()
        val h = alloc<IntVar>(); val mi = alloc<IntVar>(); val s = alloc<IntVar>(); val us = alloc<IntVar>()
        if (kdbc_get_timestamp(handle, col, y.ptr, mo.ptr, d.ptr, h.ptr, mi.ptr, s.ptr, us.ptr) != KDBC_OK) {
            // Try date-only column
            if (kdbc_get_date(handle, col, y.ptr, mo.ptr, d.ptr) == KDBC_OK) {
                return LocalDateTime(y.value, mo.value, d.value, 0, 0, 0, 0)
            }
            return null
        }
        val nanoOfSecond = us.value * 1000
        LocalDateTime(y.value, mo.value, d.value, h.value, mi.value, s.value, nanoOfSecond)
    }

    private fun readDate(col: Int): LocalDate? = memScoped {
        val y = alloc<IntVar>(); val mo = alloc<IntVar>(); val d = alloc<IntVar>()
        if (kdbc_get_date(handle, col, y.ptr, mo.ptr, d.ptr) == KDBC_OK) {
            return LocalDate(y.value, mo.value, d.value)
        }
        // Fall back to timestamp
        val hh = alloc<IntVar>(); val mi = alloc<IntVar>(); val s = alloc<IntVar>(); val us = alloc<IntVar>()
        if (kdbc_get_timestamp(handle, col, y.ptr, mo.ptr, d.ptr, hh.ptr, mi.ptr, s.ptr, us.ptr) == KDBC_OK) {
            return LocalDate(y.value, mo.value, d.value)
        }
        null
    }

    private fun readTime(col: Int): LocalTime? = memScoped {
        val h = alloc<IntVar>(); val mi = alloc<IntVar>(); val s = alloc<IntVar>(); val us = alloc<IntVar>()
        if (kdbc_get_time(handle, col, h.ptr, mi.ptr, s.ptr, us.ptr) == KDBC_OK) {
            return LocalTime(h.value, mi.value, s.value, us.value * 1000)
        }
        // Fall back to timestamp
        val y = alloc<IntVar>(); val mo = alloc<IntVar>(); val d = alloc<IntVar>()
        if (kdbc_get_timestamp(handle, col, y.ptr, mo.ptr, d.ptr, h.ptr, mi.ptr, s.ptr, us.ptr) == KDBC_OK) {
            return LocalTime(h.value, mi.value, s.value, us.value * 1000)
        }
        null
    }

    override fun getMetaData(): ResultSetMetaData = metaData

    override fun close() {
        if (closed) return
        closed = true
        kdbc_result_close(handle)
    }

    private fun ensureOpen() {
        if (closed) throw SQLException("ResultSet is closed")
    }
}

private class NativeResultSetMetaData(
    private val handle: CPointer<kdbc_result>
) : ResultSetMetaData {
    override val columnCount: Int
        get() = kdbc_col_count(handle)

    override fun getColumnName(column: Int): String =
        kdbc_col_name(handle, column)?.toKString() ?: "col$column"

    override fun getColumnLabel(column: Int): String =
        kdbc_col_label(handle, column)?.toKString() ?: getColumnName(column)
}

private class NativeDatabaseMetaData(
    private val handle: CPointer<kdbc_conn>
) : DatabaseMetaData {
    override val databaseProductName: String
        get() = kdbc_product_name(handle)?.toKString() ?: "unknown"
    override val databaseProductVersion: String
        get() = kdbc_product_version(handle)?.toKString() ?: "0"
    override val databaseMajorVersion: Int
        get() = kdbc_major_version(handle)
    override val databaseMinorVersion: Int
        get() = kdbc_minor_version(handle)
}

// ---------- Parameter binding dispatch ----------

private fun bindValue(stmt: CPointer<kdbc_stmt>, idx: Int, value: Any?) {
    val rc: Int = when (value) {
        null -> kdbc_bind_null(stmt, idx)
        is Byte -> kdbc_bind_int(stmt, idx, value.toInt())
        is Short -> kdbc_bind_int(stmt, idx, value.toInt())
        is Int -> kdbc_bind_int(stmt, idx, value)
        is Long -> kdbc_bind_long(stmt, idx, value)
        is Float -> kdbc_bind_double(stmt, idx, value.toDouble())
        is Double -> kdbc_bind_double(stmt, idx, value)
        is Boolean -> kdbc_bind_bool(stmt, idx, if (value) 1 else 0)
        is String -> kdbc_bind_string(stmt, idx, value)
        // Stormify treats any CharSequence as a scalar (StringBuilder, etc.).
        // The native driver layer only takes String — materialize first.
        is CharSequence -> kdbc_bind_string(stmt, idx, value.toString())
        is ByteArray -> bindBlob(stmt, idx, value)
        is CharArray -> kdbc_bind_string(stmt, idx, value.concatToString())
        is BigInteger -> bindBigInteger(stmt, idx, value)
        is BigDecimal -> bindBigDecimal(stmt, idx, value)
        is LocalDateTime -> kdbc_bind_timestamp(
            stmt, idx,
            value.year, value.month.ordinal + 1, value.day,
            value.hour, value.minute, value.second, value.nanosecond / 1000
        )
        is LocalDate -> kdbc_bind_date(stmt, idx, value.year, value.month.ordinal + 1, value.day)
        is LocalTime -> kdbc_bind_time(stmt, idx, value.hour, value.minute, value.second, value.nanosecond / 1000)
        is KtInstant -> {
            val ldt = value.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            kdbc_bind_timestamp(
                stmt, idx,
                ldt.year, ldt.month.ordinal + 1, ldt.day,
                ldt.hour, ldt.minute, ldt.second, ldt.nanosecond / 1000
            )
        }
        // Char is not a native-driver primitive — coerce to a one-character string.
        is Char -> kdbc_bind_string(stmt, idx, value.toString())
        is kotlin.uuid.Uuid -> kdbc_bind_string(stmt, idx, value.toString())
        // Refuse anything outside the supported set instead of silently
        // stringifying it. A silent .toString() fallback hides logic errors —
        // an unknown type at bind time always indicates a missing converter.
        else -> throw SQLException(
            "Cannot bind parameter $idx: unsupported type ${value::class.qualifiedName ?: value::class.simpleName}"
        )
    }
    if (rc != KDBC_OK)
        throw kdbcStmtException(stmt, "Failed to bind parameter $idx (type ${value?.let { it::class.simpleName } ?: "null"}): ${stmtError(stmt, "bind failed")}")
}

private fun bindBlob(stmt: CPointer<kdbc_stmt>, idx: Int, bytes: ByteArray): Int {
    if (bytes.isEmpty()) {
        // Use a 1-byte no-op pointer; some drivers reject NULL data with len=0
        return bytes.usePinned { pinned ->
            kdbc_bind_blob(stmt, idx, pinned.addressOf(0), 0u)
        }
    }
    return bytes.usePinned { pinned ->
        kdbc_bind_blob(stmt, idx, pinned.addressOf(0), bytes.size.toULong())
    }
}

private fun bindBigInteger(stmt: CPointer<kdbc_stmt>, idx: Int, value: BigInteger): Int {
    // Prefer long when it fits, string otherwise — drivers translate to NUMERIC/DECIMAL.
    return try {
        kdbc_bind_long(stmt, idx, value.longValue(exactRequired = true))
    } catch (_: Exception) {
        kdbc_bind_string(stmt, idx, value.toString())
    }
}

private fun bindBigDecimal(stmt: CPointer<kdbc_stmt>, idx: Int, value: BigDecimal): Int =
    kdbc_bind_string(stmt, idx, value.toPlainString())
