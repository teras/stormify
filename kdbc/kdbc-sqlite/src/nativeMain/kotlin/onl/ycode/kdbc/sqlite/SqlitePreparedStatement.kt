package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class SqlitePreparedStatement(
    private val dbPointer: CPointer<cnames.structs.sqlite3>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val stmtPointer: CPointer<cnames.structs.sqlite3_stmt>
    private var lastInsertRowId: Long = 0

    init {
        memScoped {
            val stmtPtr = alloc<CPointerVar<cnames.structs.sqlite3_stmt>>()
            val result = sqlite3_prepare_v2(dbPointer, sql, -1, stmtPtr.ptr, null)
            if (result != SQLITE_OK) {
                val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to prepare statement: $errorMsg")
            }
            stmtPointer = stmtPtr.value ?: throw SQLException("Statement pointer is null")
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        val result = when (value) {
            null -> sqlite3_bind_null(stmtPointer, parameterIndex)
            is Int -> sqlite3_bind_int(stmtPointer, parameterIndex, value)
            is Long -> sqlite3_bind_int64(stmtPointer, parameterIndex, value)
            is Double -> sqlite3_bind_double(stmtPointer, parameterIndex, value)
            is Float -> sqlite3_bind_double(stmtPointer, parameterIndex, value.toDouble())
            is String -> sqlite3_bind_text(stmtPointer, parameterIndex, value, -1, SQLITE_TRANSIENT)
            is Boolean -> sqlite3_bind_int(stmtPointer, parameterIndex, if (value) 1 else 0)
            is Byte -> sqlite3_bind_int(stmtPointer, parameterIndex, value.toInt())
            is Short -> sqlite3_bind_int(stmtPointer, parameterIndex, value.toInt())
            is ByteArray -> memScoped {
                val pinnedArray = value.pin()
                sqlite3_bind_blob(stmtPointer, parameterIndex, pinnedArray.addressOf(0), value.size, SQLITE_TRANSIENT)
                    .also { pinnedArray.unpin() }
            }
            // Big numbers - store as string for precision
            is BDN -> {
                // Try to convert to Double if it fits without precision loss
                val doubleValue = value.doubleValue(false)
                if (doubleValue.isFinite() && BDN.parseString(doubleValue.toString()) == value) {
                    sqlite3_bind_double(stmtPointer, parameterIndex, doubleValue)
                } else {
                    // Fall back to text for precision
                    sqlite3_bind_text(stmtPointer, parameterIndex, value.toString(), -1, SQLITE_TRANSIENT)
                }
            }
            is BIN -> {
                // Try to convert to Long if it fits
                val longValue = value.longValue(false)
                if (BIN.parseString(longValue.toString()) == value) {
                    sqlite3_bind_int64(stmtPointer, parameterIndex, longValue)
                } else {
                    // Fall back to text for large numbers
                    sqlite3_bind_text(stmtPointer, parameterIndex, value.toString(), -1, SQLITE_TRANSIENT)
                }
            }
            // Time-related types - store as epoch milliseconds
            is LocalDateTime -> sqlite3_bind_int64(stmtPointer, parameterIndex, value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds())
            is LocalDate -> sqlite3_bind_int64(stmtPointer, parameterIndex,
                LocalDateTime(value, LocalTime(0, 0)).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds())
            is LocalTime -> {
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                sqlite3_bind_int64(stmtPointer, parameterIndex,
                    LocalDateTime(date, value).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds())
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }

        if (result != SQLITE_OK) {
            val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to bind parameter $parameterIndex: $errorMsg")
        }
    }

    override fun executeUpdate(): Int {
        val result = sqlite3_step(stmtPointer)

        return when (result) {
            SQLITE_DONE -> {
                if (returnGeneratedKeys) {
                    lastInsertRowId = sqlite3_last_insert_rowid(dbPointer)
                }
                sqlite3_changes(dbPointer)
            }
            SQLITE_ROW -> {
                throw SQLException("executeUpdate() returned a result set")
            }
            else -> {
                val errorMsg = sqlite3_errmsg(dbPointer)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute update: $errorMsg")
            }
        }
    }

    override fun executeQuery(): ResultSet {
        return SqliteResultSet(stmtPointer, dbPointer)
    }

    override fun getGeneratedKeys(): ResultSet {
        if (!returnGeneratedKeys || lastInsertRowId == 0L) {
            return EmptyResultSet()
        }
        return GeneratedKeysResultSet(lastInsertRowId)
    }

    override fun close() {
        sqlite3_finalize(stmtPointer)
    }
}
