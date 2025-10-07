package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import mariadb.*
import onl.ycode.kdbc.ResultSet
import onl.ycode.kdbc.ResultSetMetaData
import onl.ycode.kdbc.SQLException
import kotlin.reflect.KClass

/**
 * MariaDB/MySQL ResultSet for prepared statements using binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
class MariadbStmtResultSet(private val stmt: CPointer<MYSQL_STMT>) : ResultSet {
    private val metadata: CPointer<MYSQL_RES>?
    private val columnCount: Int
    private val bindResults: CArrayPointer<MYSQL_BIND>
    private val columnData = mutableListOf<ColumnData>()
    private var hasRow = false

    init {
        metadata = mariadb_stmt_result_metadata_wrapper(stmt)
        columnCount = if (metadata != null) mysql_num_fields(metadata).toInt() else 0

        bindResults = nativeHeap.allocArray<MYSQL_BIND>(columnCount)

        if (metadata != null) {
            val fields = mysql_fetch_fields(metadata)
            for (i in 0 until columnCount) {
                val field = fields!![i]
                val data = ColumnData(field.type)
                columnData.add(data)

                val bind = bindResults[i]
                bind.buffer_type = field.type
                bind.buffer = data.buffer
                bind.buffer_length = data.bufferLength
                bind.is_null = data.isNull.ptr
                bind.length = data.length.ptr
                bind.is_unsigned = ((field.flags and 32u).toByte())
            }

            val bindResult = mariadb_stmt_bind_result_wrapper(stmt, bindResults)
            if (bindResult.toInt() != 0) {
                cleanup()
                val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to bind result: $error")
            }

            mariadb_stmt_store_result_wrapper(stmt)
        }
    }

    override fun next(): Boolean {
        val fetchResult = mariadb_stmt_fetch_wrapper(stmt)
        hasRow = fetchResult == 0
        return hasRow
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!hasRow) throw SQLException("No current row")
        if (columnIndex < 1 || columnIndex > columnCount) throw SQLException("Invalid column index: $columnIndex")

        val index = columnIndex - 1
        val data = columnData[index]

        if (data.isNull.value.toInt() == 1) return null

        return when (type) {
            Byte::class -> when (data.mysqlType) {
                MYSQL_TYPE_TINY -> data.byteValue.value
                else -> data.getString()?.toByteOrNull()
            }
            Short::class -> when (data.mysqlType) {
                MYSQL_TYPE_SHORT -> data.shortValue.value
                MYSQL_TYPE_TINY -> data.byteValue.value.toShort()
                else -> data.getString()?.toShortOrNull()
            }
            Int::class -> when (data.mysqlType) {
                MYSQL_TYPE_LONG -> data.intValue.value
                MYSQL_TYPE_SHORT -> data.shortValue.value.toInt()
                MYSQL_TYPE_TINY -> data.byteValue.value.toInt()
                else -> data.getString()?.toIntOrNull()
            }
            Long::class -> when (data.mysqlType) {
                MYSQL_TYPE_LONGLONG -> data.longValue.value
                MYSQL_TYPE_LONG -> data.intValue.value.toLong()
                else -> data.getString()?.toLongOrNull()
            }
            Float::class -> when (data.mysqlType) {
                MYSQL_TYPE_FLOAT -> data.floatValue.value
                else -> data.getString()?.toFloatOrNull()
            }
            Double::class -> when (data.mysqlType) {
                MYSQL_TYPE_DOUBLE -> data.doubleValue.value
                MYSQL_TYPE_FLOAT -> data.floatValue.value.toDouble()
                else -> data.getString()?.toDoubleOrNull()
            }
            Boolean::class -> when (data.mysqlType) {
                MYSQL_TYPE_TINY -> data.byteValue.value != 0.toByte()
                else -> data.getString()?.toIntOrNull() != 0
            }
            String::class -> data.getString()
            ByteArray::class -> data.getByteArray()
            BDN::class -> data.getString()?.let { BDN.parseString(it) }
            BIN::class -> data.getString()?.let { BIN.parseString(it) }
            LocalDateTime::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue.value
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
            }
            LocalDate::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue.value
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
            LocalTime::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue.value
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                kotlinx.datetime.Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).time
            }
            else -> data.getString()
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        if (metadata == null) {
            return object : ResultSetMetaData {
                override val columnCount: Int = 0
                override fun getColumnName(column: Int): String = throw SQLException("No metadata available")
            }
        }
        return MariadbResultSetMetaData(metadata, columnCount)
    }

    override fun close() {
        cleanup()
    }

    private fun cleanup() {
        columnData.forEach { it.cleanup() }
        nativeHeap.free(bindResults)
        metadata?.let { mysql_free_result(it) }
    }

    private class ColumnData(val mysqlType: enum_field_types) {
        val byteValue = nativeHeap.alloc<ByteVar>()
        val shortValue = nativeHeap.alloc<ShortVar>()
        val intValue = nativeHeap.alloc<IntVar>()
        val longValue = nativeHeap.alloc<LongVar>()
        val floatValue = nativeHeap.alloc<FloatVar>()
        val doubleValue = nativeHeap.alloc<DoubleVar>()
        val stringBuffer = nativeHeap.allocArray<ByteVar>(4096)
        val isNull = nativeHeap.alloc<ByteVar>()
        val length = nativeHeap.alloc<ULongVar>()

        val buffer: CPointer<*>?
        val bufferLength: ULong

        init {
            when (mysqlType) {
                MYSQL_TYPE_TINY -> { buffer = byteValue.ptr; bufferLength = 1u }
                MYSQL_TYPE_SHORT -> { buffer = shortValue.ptr; bufferLength = 2u }
                MYSQL_TYPE_LONG -> { buffer = intValue.ptr; bufferLength = 4u }
                MYSQL_TYPE_LONGLONG -> { buffer = longValue.ptr; bufferLength = 8u }
                MYSQL_TYPE_FLOAT -> { buffer = floatValue.ptr; bufferLength = 4u }
                MYSQL_TYPE_DOUBLE -> { buffer = doubleValue.ptr; bufferLength = 8u }
                else -> { buffer = stringBuffer; bufferLength = 4096u }
            }
        }

        fun getString(): String? {
            if (isNull.value.toInt() == 1) return null
            val len = length.value.toInt()
            return if (len > 0) stringBuffer.toKString().take(len) else ""
        }

        fun getByteArray(): ByteArray? {
            if (isNull.value.toInt() == 1) return null
            val len = length.value.toInt()
            return ByteArray(len) { i -> stringBuffer[i] }
        }

        fun cleanup() {
            nativeHeap.free(byteValue)
            nativeHeap.free(shortValue)
            nativeHeap.free(intValue)
            nativeHeap.free(longValue)
            nativeHeap.free(floatValue)
            nativeHeap.free(doubleValue)
            nativeHeap.free(stringBuffer)
            nativeHeap.free(isNull)
            nativeHeap.free(length)
        }
    }
}
