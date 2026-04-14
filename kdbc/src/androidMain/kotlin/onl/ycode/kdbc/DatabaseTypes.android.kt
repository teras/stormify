// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import kotlin.reflect.KClass

/**
 * Android KDBC implementation backed by [android.database.sqlite.SQLiteDatabase].
 *
 * Wraps a platform SQLite database into the KDBC [DataSource] interface so the
 * Stormify ORM (and any other KDBC consumer) can run unchanged on Android. The
 * implementation uses native SQLite primitives throughout: [SQLiteStatement] for
 * INSERT/UPDATE/DELETE with proper typed bindings, [SQLiteDatabase.rawQuery] for
 * SELECTs, savepoints via raw SQL for nested transactions, and the real
 * `sqlite_version()` reported through [DatabaseMetaData].
 *
 * ## Usage
 * ```kotlin
 * val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
 * val stormify = Stormify(AndroidDataSource(db))
 * ```
 *
 * The wrapped database is **not** owned by the data source — calling [Connection.close]
 * does not close the underlying [SQLiteDatabase]. Lifecycle of the database is the
 * caller's responsibility (typically tied to the Android `Application` lifetime).
 */
class AndroidDataSource(private val db: SQLiteDatabase) : DataSource {
    override fun getConnection(): Connection = AndroidConnection(db)
}

/**
 * Single-database connection. SQLite is single-writer, so each "connection" we hand
 * back is the same underlying [SQLiteDatabase] — concurrency is enforced by the
 * platform's internal lock.
 *
 * Transaction model maps to [SQLiteDatabase.beginTransaction]/`setTransactionSuccessful`/
 * `endTransaction`. Stormify drives the connection via `setAutoCommit(false)` →
 * `commit()`/`rollback()` → `setAutoCommit(true)`, so we begin on the first
 * `setAutoCommit(false)` and end on `commit()`/`rollback()`. We track [inTx] locally
 * because Android's `inTransaction()` does not distinguish between "we started it"
 * and "the caller had already begun one".
 */
private class AndroidConnection(private val db: SQLiteDatabase) : Connection {
    private var inTx: Boolean = false
    private var txSuccess: Boolean = false
    private var savepointCounter: Int = 0

    // cancel() is a no-op on Android, and SQLiteDatabase's ThreadLocal transaction
    // state MUST be unwound via endTransaction() on the begin thread — otherwise the
    // SQLiteConnectionPool leaks the connection. Issue the rollback + setAutoCommit
    // restore explicitly here.
    override fun cleanupAfterCancel() {
        runCatching { rollback() }
        runCatching { setAutoCommit(true) }
    }

    override val metaData: DatabaseMetaData by lazy { AndroidDatabaseMetaData(db) }

    override fun initStatement(
        sql: String,
        returnGeneratedKeys: Boolean,
        columnNames: Array<String>?
    ): Statement = AndroidStatement(db, sql, returnGeneratedKeys)

    override fun prepareCall(sql: String): CallableStatement {
        throw SQLException("Android SQLite does not support stored procedures (CALL): $sql")
    }

    override fun commit() {
        if (inTx) {
            try {
                db.setTransactionSuccessful()
                txSuccess = true
            } finally {
                db.endTransaction()
                inTx = false
                txSuccess = false
            }
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            // Nested savepoint rollback — the outer transaction stays open.
            db.execSQL("ROLLBACK TRANSACTION TO SAVEPOINT ${savepoint.savepointName}")
            return
        }
        if (inTx) {
            // setTransactionSuccessful() was never called → endTransaction rolls back.
            try {
                db.endTransaction()
            } finally {
                inTx = false
                txSuccess = false
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        val safe = "sp_${++savepointCounter}_${name.filter { it.isLetterOrDigit() || it == '_' }}"
        db.execSQL("SAVEPOINT $safe")
        return AndroidSavepoint(safe)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        db.execSQL("RELEASE SAVEPOINT ${savepoint.savepointName}")
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (!autoCommit && !inTx) {
            db.beginTransaction()
            inTx = true
            txSuccess = false
        } else if (autoCommit && inTx) {
            // Defensive: caller never called commit/rollback explicitly. Treat as rollback
            // (don't mark successful) to be safe — losing data is better than committing
            // an unfinished transaction.
            try {
                db.endTransaction()
            } finally {
                inTx = false
                txSuccess = false
            }
        }
    }

    override fun close() {
        // Caller owns the SQLiteDatabase. We only need to make sure no transaction
        // leaks if close() is called mid-transaction.
        if (inTx) {
            try {
                db.endTransaction()
            } finally {
                inTx = false
                txSuccess = false
            }
        }
    }
}

/**
 * Map of KMP types to their JDBC-equivalent class names. Used as a sentinel by
 * [toAndroidValue] to convert KMP-only types (kotlinx-datetime, ionspin BigDecimal/
 * BigInteger) into representations that SQLite can store. We coerce dates/timestamps
 * to ISO-8601 strings (compatible with SQLite's date/time functions) and BigDecimal/
 * BigInteger to strings for full precision retention.
 */
private val kmpTypeNames = setOf(
    "com.ionspin.kotlin.bignum.decimal.BigDecimal",
    "com.ionspin.kotlin.bignum.integer.BigInteger",
    "kotlinx.datetime.LocalDate",
    "kotlinx.datetime.LocalDateTime",
    "kotlinx.datetime.LocalTime",
    "kotlinx.datetime.Instant",
    "kotlin.time.Instant",
)

/**
 * Convert a value to a SQLite-storable form. SQLite's storage classes are NULL,
 * INTEGER, REAL, TEXT, and BLOB — everything else has to map to one of those.
 */
private fun toAndroidValue(value: Any?): Any? {
    if (value == null) return null
    return when (value) {
        is Boolean -> if (value) 1L else 0L
        is Number, is String, is ByteArray -> value
        is java.sql.Date, is java.sql.Time, is java.sql.Timestamp,
        is java.util.Date -> value.toString()
        else -> {
            val name = value::class.qualifiedName
            if (name in kmpTypeNames) value.toString() else value.toString()
        }
    }
}

private class AndroidStatement(
    private val db: SQLiteDatabase,
    private val sql: String,
    private val returnGeneratedKeys: Boolean,
) : Statement {
    // 1-based parameter index → bound value
    private val bindings = sortedMapOf<Int, Any?>()
    private val batches = mutableListOf<Map<Int, Any?>>()
    private var lastInsertRowId: Long = -1L

    override fun setObject(parameterIndex: Int, value: Any?) {
        bindings[parameterIndex] = toAndroidValue(value)
    }

    override fun addBatch() {
        batches.add(bindings.toMap())
        bindings.clear()
    }

    override fun executeBatch(): IntArray {
        val results = IntArray(batches.size)
        // Compile once, rebind per batch entry. compileStatement is the fast path
        // (single sqlite3_prepare_v2) and bindings are typed, not stringified.
        db.compileStatement(sql).use { stmt ->
            for ((i, params) in batches.withIndex()) {
                stmt.clearBindings()
                bindToStatement(stmt, params)
                results[i] = if (returnGeneratedKeys) {
                    val rowId = stmt.executeInsert()
                    lastInsertRowId = rowId
                    if (rowId >= 0) 1 else 0
                } else {
                    stmt.executeUpdateDelete()
                }
            }
        }
        batches.clear()
        return results
    }

    override fun executeUpdate(): Int {
        db.compileStatement(sql).use { stmt ->
            bindToStatement(stmt, bindings)
            return if (returnGeneratedKeys) {
                lastInsertRowId = stmt.executeInsert()
                if (lastInsertRowId >= 0) 1 else 0
            } else {
                stmt.executeUpdateDelete()
            }
        }
    }

    override fun executeQuery(): ResultSet {
        // SQLite's bind API for SELECT statements goes through SQLiteQuery, but the
        // public Android API only exposes String[] selectionArgs. SQLite is
        // dynamically typed, so coercing all args to strings is safe — it's how
        // android.database.sqlite.SQLiteQueryBuilder operates internally too.
        val args: Array<String?> = Array(bindings.size) { idx ->
            // bindings is a sortedMap on Int keys (1..N), so iterate in order.
            val raw = bindings.values.elementAtOrNull(idx)
            raw?.toString()
        }
        // SQLite ignores null in selectionArgs, but Stormify always passes typed
        // bindings — there is no `IS ?` ambiguity here. Use a placeholder when null.
        val cursor = db.rawQuery(sql, args.map { it ?: "" }.toTypedArray())
        return AndroidResultSet(cursor)
    }

    override fun getGeneratedKeys(): ResultSet {
        if (lastInsertRowId < 0) {
            throw SQLException("No generated keys available — executeInsert returned -1")
        }
        return SingleValueResultSet("ROWID", lastInsertRowId)
    }

    private fun bindToStatement(stmt: SQLiteStatement, params: Map<Int, Any?>) {
        for ((index, value) in params) {
            when (value) {
                null -> stmt.bindNull(index)
                is Long -> stmt.bindLong(index, value)
                is Int -> stmt.bindLong(index, value.toLong())
                is Short -> stmt.bindLong(index, value.toLong())
                is Byte -> stmt.bindLong(index, value.toLong())
                is Boolean -> stmt.bindLong(index, if (value) 1L else 0L)
                is Double -> stmt.bindDouble(index, value)
                is Float -> stmt.bindDouble(index, value.toDouble())
                is String -> stmt.bindString(index, value)
                is ByteArray -> stmt.bindBlob(index, value)
                else -> stmt.bindString(index, value.toString())
            }
        }
    }

    override fun close() {
        bindings.clear()
        batches.clear()
    }
}

private class AndroidResultSet(private val cursor: Cursor) : ResultSet {
    override fun next(): Boolean = cursor.moveToNext()

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        val idx = columnIndex - 1
        if (cursor.isNull(idx)) return null

        return when (type) {
            String::class -> cursor.getString(idx)
            Int::class -> cursor.getInt(idx)
            Long::class -> cursor.getLong(idx)
            Double::class -> cursor.getDouble(idx)
            Float::class -> cursor.getFloat(idx)
            Short::class -> cursor.getShort(idx)
            Byte::class -> cursor.getShort(idx).toByte()
            Boolean::class -> cursor.getInt(idx) != 0
            ByteArray::class -> cursor.getBlob(idx)
            // For Any::class (the most common path Stormify uses) return the
            // most-specific native column type so the TypeConversion registry can
            // coerce later.
            Any::class -> when (cursor.getType(idx)) {
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(idx)
                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(idx)
                else -> null
            }
            else -> cursor.getString(idx)
        }
    }

    override fun getMetaData(): ResultSetMetaData = AndroidResultSetMetaData(cursor)

    override fun close() {
        cursor.close()
    }
}

private class AndroidResultSetMetaData(private val cursor: Cursor) : ResultSetMetaData {
    override val columnCount: Int = cursor.columnCount

    override fun getColumnName(column: Int): String = cursor.getColumnName(column - 1)
}

private class AndroidDatabaseMetaData(db: SQLiteDatabase) : DatabaseMetaData {
    override val databaseProductName: String = "SQLite"
    override val databaseProductVersion: String
    override val databaseMajorVersion: Int
    override val databaseMinorVersion: Int

    init {
        // Query the actual SQLite library version once at construction. This is the
        // version of the libsqlite that Android shipped on this device — *not* the
        // OS version. Stormify only uses these to detect the dialect (matches on
        // "sqlite" in the product name) so the values are mostly informational.
        var version = "unknown"
        try {
            db.rawQuery("SELECT sqlite_version()", null).use { c ->
                if (c.moveToFirst()) version = c.getString(0)
            }
        } catch (_: Throwable) {
            // Some test doubles / corner cases may not support rawQuery — fall back.
        }
        databaseProductVersion = version
        val parts = version.split('.')
        databaseMajorVersion = parts.getOrNull(0)?.toIntOrNull() ?: 3
        databaseMinorVersion = parts.getOrNull(1)?.toIntOrNull() ?: 0
    }
}

private class AndroidSavepoint(override val savepointName: String) : Savepoint

private class SingleValueResultSet(private val columnName: String, private val value: Any?) : ResultSet {
    private var consumed = false

    override fun next(): Boolean {
        if (!consumed) {
            consumed = true
            return true
        }
        return false
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? = value

    override fun getMetaData(): ResultSetMetaData = object : ResultSetMetaData {
        override val columnCount: Int = 1
        override fun getColumnName(column: Int): String = columnName
    }

    override fun close() {}
}
