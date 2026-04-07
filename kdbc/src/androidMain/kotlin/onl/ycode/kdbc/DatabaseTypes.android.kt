package onl.ycode.kdbc

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlin.reflect.KClass

/**
 * Android DataSource implementation that wraps SQLiteDatabase.
 * 
 * Usage:
 * ```kotlin
 * val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
 * val stormify = Stormify(AndroidDataSource(db))
 * ```
 */
class AndroidDataSource(private val db: SQLiteDatabase) : DataSource {
    override fun getConnection(): Connection = AndroidConnection(db)
}

private class AndroidConnection(private val db: SQLiteDatabase) : Connection {
    override val metaData: DatabaseMetaData = AndroidDatabaseMetaData()
    
    override fun initStatement(sql: String, returnGeneratedKeys: Boolean, columnNames: Array<String>?): Statement {
        return AndroidPreparedStatement(db, sql, returnGeneratedKeys)
    }
    
    override fun prepareCall(sql: String): CallableStatement {
        throw UnsupportedOperationException("Android SQLite does not support stored procedures")
    }
    
    override fun commit() {
        if (db.inTransaction()) {
            db.setTransactionSuccessful()
        }
    }
    
    override fun rollback(savepoint: Savepoint?) {
        if (db.inTransaction()) {
            db.endTransaction()
            db.beginTransaction()
        }
    }
    
    override fun setSavepoint(name: String): Savepoint = AndroidSavepoint(name)
    
    override fun releaseSavepoint(savepoint: Savepoint) {}
    
    override fun setAutoCommit(autoCommit: Boolean) {
        if (!autoCommit && !db.inTransaction()) {
            db.beginTransaction()
        } else if (autoCommit && db.inTransaction()) {
            db.endTransaction()
        }
    }
    
    override fun close() {}
}

private class AndroidPreparedStatement(
    private val db: SQLiteDatabase,
    private val sql: String,
    private val returnGeneratedKeys: Boolean
) : Statement {
    private val bindings = mutableMapOf<Int, Any?>()
    private val batches = mutableListOf<Map<Int, Any?>>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        bindings[parameterIndex] = value
    }

    override fun addBatch() {
        batches.add(bindings.toMap())
        bindings.clear()
    }

    override fun executeBatch(): IntArray {
        val results = IntArray(batches.size)
        for ((i, params) in batches.withIndex()) {
            bindings.clear()
            bindings.putAll(params)
            results[i] = executeUpdate()
        }
        batches.clear()
        return results
    }
    
    override fun executeUpdate(): Int {
        val boundSql = bindParameters()
        db.execSQL(boundSql)
        return 1
    }
    
    override fun executeQuery(): ResultSet {
        val boundSql = bindParameters()
        val cursor = db.rawQuery(boundSql, null)
        return AndroidResultSet(cursor)
    }
    
    override fun getGeneratedKeys(): ResultSet {
        val lastId = android.database.DatabaseUtils.longForQuery(db, "SELECT last_insert_rowid()", null)
        return SingleValueResultSet(lastId)
    }
    
    private fun bindParameters(): String {
        var result = sql
        bindings.entries.sortedByDescending { it.key }.forEach { (_, value) ->
            val replacement = when (value) {
                null -> "NULL"
                is String -> "'${value.replace("'", "''")}'"
                is Number -> value.toString()
                is Boolean -> if (value) "1" else "0"
                else -> "'${value.toString().replace("'", "''")}'"
            }
            result = result.replaceFirst("?", replacement)
        }
        return result
    }
    
    override fun close() {
        bindings.clear()
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
            Boolean::class -> cursor.getInt(idx) != 0
            ByteArray::class -> cursor.getBlob(idx)
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
    
    override fun getColumnName(column: Int): String {
        return cursor.getColumnName(column - 1)
    }
}

private class AndroidDatabaseMetaData : DatabaseMetaData {
    override val databaseProductName: String = "SQLite"
    override val databaseProductVersion: String = android.os.Build.VERSION.RELEASE
    override val databaseMajorVersion: Int = android.os.Build.VERSION.SDK_INT
    override val databaseMinorVersion: Int = 0
}

private class AndroidSavepoint(override val savepointName: String) : Savepoint

private class SingleValueResultSet(private val value: Any?) : ResultSet {
    private var consumed = false
    
    override fun next(): Boolean {
        if (!consumed) {
            consumed = true
            return true
        }
        return false
    }
    
    override fun getObject(columnIndex: Int, type: KClass<*>): Any? = value
    
    override fun getMetaData(): ResultSetMetaData {
        return object : ResultSetMetaData {
            override val columnCount: Int = 1
            override fun getColumnName(column: Int): String = "GENERATED_KEY"
        }
    }
    
    override fun close() {}
}
