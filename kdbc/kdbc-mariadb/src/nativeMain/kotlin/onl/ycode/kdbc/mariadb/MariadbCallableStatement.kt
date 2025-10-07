package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import mariadb.*
import onl.ycode.kdbc.CallableStatement
import onl.ycode.kdbc.EmptyResultSet
import onl.ycode.kdbc.ResultSet
import onl.ycode.kdbc.SQLException
import kotlin.reflect.KClass

/**
 * MariaDB/MySQL CallableStatement implementation using binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
class MariadbCallableStatement(
    private val mysql: CPointer<MYSQL>,
    private val sql: String
) : CallableStatement {
    private val stmt: CPointer<MYSQL_STMT>
    private val paramCount: Int
    private val bindParams: CArrayPointer<MYSQL_BIND>?
    private val paramData = mutableListOf<MariadbPreparedStatement.ParamData>()
    private val outParameters = mutableMapOf<Int, KClass<*>>()
    private var resultSet: MariadbStmtResultSet? = null

    init {
        stmt = mariadb_stmt_init_wrapper(mysql) ?: throw SQLException("Failed to initialize callable statement")

        val prepResult = mariadb_stmt_prepare_wrapper(stmt, sql, sql.length.toULong())
        if (prepResult != 0) {
            val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
            mariadb_stmt_close_wrapper(stmt)
            throw SQLException("Failed to prepare callable statement: $error")
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

        repeat(paramCount) { paramData.add(MariadbPreparedStatement.ParamData()) }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        if (parameterIndex < 1 || parameterIndex > paramCount) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        val bind = bindParams!![index]
        val data = paramData[index]
        data.clear()

        // Same logic as MariadbPreparedStatement.setObject - delegate or inline
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
            is BDN, is BIN -> {
                val str = value.toString()
                bind.buffer_type = MYSQL_TYPE_STRING.toUInt()
                data.stringValue = str.cstr.getPointer(MemScope())
                bind.buffer = data.stringValue
                bind.buffer_length = str.length.toULong()
                data.length = nativeHeap.alloc<ULongVar>().apply { this.value = str.length.toULong() }
                bind.length = data.length?.ptr
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
            throw SQLException("Failed to execute callable statement: $error")
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
            throw SQLException("Failed to execute callable statement: $error")
        }

        resultSet = MariadbStmtResultSet(stmt)
        return resultSet!!
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        outParameters[parameterIndex] = type
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        // After execution, OUT parameters would be in the result set
        // For MySQL/MariaDB stored procedures, OUT params are returned in SELECT results
        if (resultSet == null) {
            throw SQLException("No result set available - execute the statement first")
        }

        // The first row should contain the OUT parameters
        return resultSet?.getObject(parameterIndex, type)
    }

    override fun execute(): Boolean {
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
            throw SQLException("Failed to execute callable statement: $error")
        }

        // Check if there's a result set
        val metadata = mariadb_stmt_result_metadata_wrapper(stmt)
        val hasResultSet = metadata != null
        if (hasResultSet) {
            resultSet = MariadbStmtResultSet(stmt)
        }
        return hasResultSet
    }

    override fun close() {
        paramData.forEach { it.clear() }
        bindParams?.let { nativeHeap.free(it) }
        resultSet?.close()
        mariadb_stmt_close_wrapper(stmt)
    }
}

