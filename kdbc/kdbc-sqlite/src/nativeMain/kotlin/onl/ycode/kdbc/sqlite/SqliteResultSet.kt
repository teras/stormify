package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class SqliteResultSet(
    private val stmtPointer: CPointer<cnames.structs.sqlite3_stmt>,
    private val dbPointer: CPointer<cnames.structs.sqlite3>
) : ResultSet {
    private var hasRow = false
    private var isFirst = true

    override fun next(): Boolean {
        val result = sqlite3_step(stmtPointer)
        hasRow = when (result) {
            SQLITE_ROW -> true
            SQLITE_DONE -> false
            else -> {
                val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to fetch next row: $errorMsg")
            }
        }
        isFirst = false
        return hasRow
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!hasRow) {
            throw SQLException("No current row")
        }

        // SQLite uses 0-based indexing, JDBC uses 1-based
        val index = columnIndex - 1

        val columnType = sqlite3_column_type(stmtPointer, index)

        if (columnType == SQLITE_NULL) {
            return null
        }

        return when (type) {
            Int::class -> sqlite3_column_int(stmtPointer, index)
            Long::class -> sqlite3_column_int64(stmtPointer, index)
            Double::class -> sqlite3_column_double(stmtPointer, index)
            Float::class -> sqlite3_column_double(stmtPointer, index).toFloat()
            Byte::class -> sqlite3_column_int(stmtPointer, index).toByte()
            Short::class -> sqlite3_column_int(stmtPointer, index).toShort()
            String::class -> sqlite3_column_text(stmtPointer, index)?.reinterpret<ByteVar>()?.toKString()
            Boolean::class -> sqlite3_column_int(stmtPointer, index) != 0
            ByteArray::class -> {
                val size = sqlite3_column_bytes(stmtPointer, index)
                val blob = sqlite3_column_blob(stmtPointer, index)
                if (blob != null && size > 0) {
                    ByteArray(size) { i ->
                        blob.reinterpret<ByteVar>()[i]
                    }
                } else {
                    ByteArray(0)
                }
            }
            // Big numbers - stored as string
            BDN::class -> {
                val text = sqlite3_column_text(stmtPointer, index)?.reinterpret<ByteVar>()?.toKString()
                if (text != null) BDN.parseString(text) else null
            }
            BIN::class -> {
                val text = sqlite3_column_text(stmtPointer, index)?.reinterpret<ByteVar>()?.toKString()
                if (text != null) BIN.parseString(text) else null
            }
            // Time-related types - stored as epoch milliseconds
            LocalDateTime::class -> {
                val millis = sqlite3_column_int64(stmtPointer, index)
                kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
            }
            LocalDate::class -> {
                val millis = sqlite3_column_int64(stmtPointer, index)
                kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
            LocalTime::class -> {
                val millis = sqlite3_column_int64(stmtPointer, index)
                kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).time
            }
            else -> {
                // Default: try to return as string
                sqlite3_column_text(stmtPointer, index)?.reinterpret<ByteVar>()?.toKString()
            }
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return SqliteResultSetMetaData(stmtPointer)
    }

    override fun close() {
        // Statement finalization is handled by PreparedStatement
    }
}
