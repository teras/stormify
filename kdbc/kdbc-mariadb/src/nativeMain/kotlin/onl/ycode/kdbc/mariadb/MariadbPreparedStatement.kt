package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import mariadb.*
import onl.ycode.kdbc.EmptyResultSet
import onl.ycode.kdbc.PreparedStatement
import onl.ycode.kdbc.ResultSet
import onl.ycode.kdbc.SQLException

/**
 * MariaDB/MySQL PreparedStatement implementation using native binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
class MariadbPreparedStatement(
    private val mysql: CPointer<MYSQL>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val stmt: CPointer<MYSQL_STMT>
    private val paramCount: Int
    private val bindParams: CArrayPointer<MYSQL_BIND>?
    private val paramData = mutableListOf<ParamData>()
    private var lastInsertId: ULong = 0u

    init {
        stmt = mariadb_stmt_init_wrapper(mysql) ?: throw SQLException("Failed to initialize prepared statement")

        val prepResult = mariadb_stmt_prepare_wrapper(stmt, sql, sql.length.toULong())
        if (prepResult != 0) {
            val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
            mariadb_stmt_close_wrapper(stmt)
            throw SQLException("Failed to prepare statement: $error")
        }

        paramCount = mariadb_stmt_param_count_wrapper(stmt).toInt()

        bindParams = if (paramCount > 0) {
            nativeHeap.allocArray<MYSQL_BIND>(paramCount).apply {
                for (i in 0 until paramCount) {
                    this[i].buffer_type = MYSQL_TYPE_NULL.toUInt()
                    this[i].buffer = null
                    this[i].buffer_length = 0u
                    this[i].is_null = null
                    this[i].length = null
                    this[i].is_unsigned = 0
                }
            }
        } else null

        repeat(paramCount) { paramData.add(ParamData()) }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        if (parameterIndex < 1 || parameterIndex > paramCount) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        val bind = bindParams!![index]
        val data = paramData[index]
        data.clear()

        when (value) {
            null -> {
                bind.buffer_type = MYSQL_TYPE_NULL.toUInt()
                data.isNull = nativeHeap.alloc<ByteVar>().apply { this.value = 1 }
                bind.is_null = data.isNull?.ptr
            }
            is Byte -> {
                bind.buffer_type = MYSQL_TYPE_TINY.toUInt()
                data.byteValue = nativeHeap.alloc<ByteVar>().apply { this.value = value }
                bind.buffer = data.byteValue?.ptr
                bind.is_unsigned = 0
            }
            is Short -> {
                bind.buffer_type = MYSQL_TYPE_SHORT.toUInt()
                data.shortValue = nativeHeap.alloc<ShortVar>().apply { this.value = value }
                bind.buffer = data.shortValue?.ptr
                bind.is_unsigned = 0
            }
            is Int -> {
                bind.buffer_type = MYSQL_TYPE_LONG.toUInt()
                data.intValue = nativeHeap.alloc<IntVar>().apply { this.value = value }
                bind.buffer = data.intValue?.ptr
                bind.is_unsigned = 0
            }
            is Long -> {
                bind.buffer_type = MYSQL_TYPE_LONGLONG.toUInt()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = value }
                bind.buffer = data.longValue?.ptr
                bind.is_unsigned = 0
            }
            is Float -> {
                bind.buffer_type = MYSQL_TYPE_FLOAT.toUInt()
                data.floatValue = nativeHeap.alloc<FloatVar>().apply { this.value = value }
                bind.buffer = data.floatValue?.ptr
            }
            is Double -> {
                bind.buffer_type = MYSQL_TYPE_DOUBLE.toUInt()
                data.doubleValue = nativeHeap.alloc<DoubleVar>().apply { this.value = value }
                bind.buffer = data.doubleValue?.ptr
            }
            is Boolean -> {
                bind.buffer_type = MYSQL_TYPE_TINY.toUInt()
                data.byteValue = nativeHeap.alloc<ByteVar>().apply { this.value = if (value) 1 else 0 }
                bind.buffer = data.byteValue?.ptr
                bind.is_unsigned = 0
            }
            is String -> {
                bind.buffer_type = MYSQL_TYPE_STRING.toUInt()
                data.stringValue = value.cstr.getPointer(MemScope())
                bind.buffer = data.stringValue
                bind.buffer_length = value.length.toULong()
                data.length = nativeHeap.alloc<ULongVar>().apply { this.value = value.length.toULong() }
                bind.length = data.length?.ptr
            }
            is ByteArray -> {
                bind.buffer_type = MYSQL_TYPE_BLOB.toUInt()
                data.byteArray = nativeHeap.allocArray<ByteVar>(value.size)
                value.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                bind.buffer = data.byteArray
                bind.buffer_length = value.size.toULong()
                data.length = nativeHeap.alloc<ULongVar>().apply { this.value = value.size.toULong() }
                bind.length = data.length?.ptr
            }
            is BDN -> {
                // Try to convert to Double if it fits without precision loss
                val doubleValue = value.doubleValue(false)
                if (doubleValue.isFinite() && BDN.parseString(doubleValue.toString()) == value) {
                    bind.buffer_type = MYSQL_TYPE_DOUBLE.toUInt()
                    data.doubleValue = nativeHeap.alloc<DoubleVar>().apply { this.value = doubleValue }
                    bind.buffer = data.doubleValue?.ptr
                } else {
                    // Fall back to text for precision
                    val str = value.toString()
                    bind.buffer_type = MYSQL_TYPE_STRING.toUInt()
                    data.stringValue = str.cstr.getPointer(MemScope())
                    bind.buffer = data.stringValue
                    bind.buffer_length = str.length.toULong()
                    data.length = nativeHeap.alloc<ULongVar>().apply { this.value = str.length.toULong() }
                    bind.length = data.length?.ptr
                }
            }
            is BIN -> {
                // Try to convert to Long if it fits
                val longValue = value.longValue(false)
                if (BIN.parseString(longValue.toString()) == value) {
                    bind.buffer_type = MYSQL_TYPE_LONGLONG.toUInt()
                    data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = longValue }
                    bind.buffer = data.longValue?.ptr
                    bind.is_unsigned = 0
                } else {
                    // Fall back to text for large numbers
                    val str = value.toString()
                    bind.buffer_type = MYSQL_TYPE_STRING.toUInt()
                    data.stringValue = str.cstr.getPointer(MemScope())
                    bind.buffer = data.stringValue
                    bind.buffer_length = str.length.toULong()
                    data.length = nativeHeap.alloc<ULongVar>().apply { this.value = str.length.toULong() }
                    bind.length = data.length?.ptr
                }
            }
            is LocalDateTime -> {
                val millis = value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                bind.buffer_type = MYSQL_TYPE_LONGLONG.toUInt()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = millis }
                bind.buffer = data.longValue?.ptr
                bind.is_unsigned = 0
            }
            is LocalDate -> {
                val millis = LocalDateTime(value, LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                bind.buffer_type = MYSQL_TYPE_LONGLONG.toUInt()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = millis }
                bind.buffer = data.longValue?.ptr
                bind.is_unsigned = 0
            }
            is LocalTime -> {
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                val millis = LocalDateTime(date, value).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                bind.buffer_type = MYSQL_TYPE_LONGLONG.toUInt()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = millis }
                bind.buffer = data.longValue?.ptr
                bind.is_unsigned = 0
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }
    }

    override fun executeUpdate(): Int {
        if (paramCount > 0 && bindParams != null) {
            val bindResult = mariadb_stmt_bind_param_wrapper(stmt, bindParams)
            if (bindResult.toInt() != 0) {
                val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to bind parameters: $error")
            }
        }

        val execResult = mariadb_stmt_execute_wrapper(stmt)
        if (execResult != 0) {
            val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute update: $error")
        }

        if (returnGeneratedKeys) {
            lastInsertId = mariadb_stmt_insert_id_wrapper(stmt)
        }

        return mariadb_stmt_affected_rows_wrapper(stmt).toInt()
    }

    override fun executeQuery(): ResultSet {
        if (paramCount > 0 && bindParams != null) {
            val bindResult = mariadb_stmt_bind_param_wrapper(stmt, bindParams)
            if (bindResult.toInt() != 0) {
                val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to bind parameters: $error")
            }
        }

        val execResult = mariadb_stmt_execute_wrapper(stmt)
        if (execResult != 0) {
            val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to execute query: $error")
        }

        return MariadbStmtResultSet(stmt)
    }

    override fun getGeneratedKeys(): ResultSet {
        if (!returnGeneratedKeys || lastInsertId == 0uL) {
            return EmptyResultSet()
        }
        return GeneratedKeysResultSet(lastInsertId.toLong())
    }

    override fun close() {
        paramData.forEach { it.clear() }
        bindParams?.let { nativeHeap.free(it) }
        mariadb_stmt_close_wrapper(stmt)
    }

    internal class ParamData {
        var byteValue: ByteVar? = null
        var shortValue: ShortVar? = null
        var intValue: IntVar? = null
        var longValue: LongVar? = null
        var floatValue: FloatVar? = null
        var doubleValue: DoubleVar? = null
        var stringValue: CPointer<ByteVar>? = null
        var byteArray: CArrayPointer<ByteVar>? = null
        var isNull: ByteVar? = null
        var length: ULongVar? = null

        fun clear() {
            byteValue?.let { nativeHeap.free(it) }
            shortValue?.let { nativeHeap.free(it) }
            intValue?.let { nativeHeap.free(it) }
            longValue?.let { nativeHeap.free(it) }
            floatValue?.let { nativeHeap.free(it) }
            doubleValue?.let { nativeHeap.free(it) }
            byteArray?.let { nativeHeap.free(it) }
            isNull?.let { nativeHeap.free(it) }
            length?.let { nativeHeap.free(it) }

            byteValue = null
            shortValue = null
            intValue = null
            longValue = null
            floatValue = null
            doubleValue = null
            stringValue = null
            byteArray = null
            isNull = null
            length = null
        }
    }
}
