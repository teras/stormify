package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant as KtInstant
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
            val fields = mysql_fetch_fields(metadata) ?: throw SQLException("Failed to fetch fields")
            for (i in 0 until columnCount) {
                val field = fields[i]
                val data = ColumnData(field.type)
                columnData.add(data)
            }

            mariadb_stmt_store_result_wrapper(stmt)
        }
    }

    override fun next(): Boolean {
        if (columnCount == 0) {
            hasRow = false
            return false
        }

        memScoped {
            // Allocate temporary buffers for this fetch only
            val tempByteBuffers = mutableListOf<ByteVar>()
            val tempShortBuffers = mutableListOf<ShortVar>()
            val tempIntBuffers = mutableListOf<IntVar>()
            val tempLongBuffers = mutableListOf<LongVar>()
            val tempFloatBuffers = mutableListOf<FloatVar>()
            val tempDoubleBuffers = mutableListOf<DoubleVar>()
            val tempStringBuffers = mutableListOf<CArrayPointer<ByteVar>>()
            val tempIsNullBuffers = mutableListOf<ByteVar>()
            val tempLengthBuffers = mutableListOf<ULongVar>()

            // Bind temporary buffers based on column types
            val fields = mysql_fetch_fields(metadata ?: throw SQLException("Metadata is null")) ?: throw SQLException("Failed to fetch fields")
            for (i in 0 until columnCount) {
                val field = fields[i]
                val bind = bindResults[i]
                bind.buffer_type = field.type
                bind.is_unsigned = ((field.flags and 32u).toByte())

                val isNull = alloc<ByteVar>()
                val length = alloc<ULongVar>()
                tempIsNullBuffers.add(isNull)
                tempLengthBuffers.add(length)
                bind.is_null = isNull.ptr
                bind.length = length.ptr

                when (columnData[i].mysqlType) {
                    MYSQL_TYPE_TINY -> {
                        val buf = alloc<ByteVar>()
                        tempByteBuffers.add(buf)
                        bind.buffer = buf.ptr
                        bind.buffer_length = 1u
                    }
                    MYSQL_TYPE_SHORT -> {
                        val buf = alloc<ShortVar>()
                        tempShortBuffers.add(buf)
                        bind.buffer = buf.ptr
                        bind.buffer_length = 2u
                    }
                    MYSQL_TYPE_LONG -> {
                        val buf = alloc<IntVar>()
                        tempIntBuffers.add(buf)
                        bind.buffer = buf.ptr
                        bind.buffer_length = 4u
                    }
                    MYSQL_TYPE_LONGLONG -> {
                        val buf = alloc<LongVar>()
                        tempLongBuffers.add(buf)
                        bind.buffer = buf.ptr
                        bind.buffer_length = 8u
                    }
                    MYSQL_TYPE_FLOAT -> {
                        val buf = alloc<FloatVar>()
                        tempFloatBuffers.add(buf)
                        bind.buffer = buf.ptr
                        bind.buffer_length = 4u
                    }
                    MYSQL_TYPE_DOUBLE -> {
                        val buf = alloc<DoubleVar>()
                        tempDoubleBuffers.add(buf)
                        bind.buffer = buf.ptr
                        bind.buffer_length = 8u
                    }
                    else -> {
                        val buf = allocArray<ByteVar>(4096)
                        tempStringBuffers.add(buf)
                        bind.buffer = buf
                        bind.buffer_length = 4096u
                    }
                }
            }

            // Bind all buffers
            val bindResult = mariadb_stmt_bind_result_wrapper(stmt, bindResults)
            if (bindResult.toInt() != 0) {
                val error = mariadb_stmt_error_wrapper(stmt)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to bind result: $error")
            }

            // Fetch the row
            val fetchResult = mariadb_stmt_fetch_wrapper(stmt)
            hasRow = fetchResult == 0

            if (hasRow) {
                // COPY values from temporary buffers to ColumnData
                var byteIdx = 0
                var shortIdx = 0
                var intIdx = 0
                var longIdx = 0
                var floatIdx = 0
                var doubleIdx = 0
                var stringIdx = 0

                for (i in 0 until columnCount) {
                    val data = columnData[i]
                    data.isNullValue = tempIsNullBuffers[i].value.toInt() == 1

                    if (!data.isNullValue) {
                        when (data.mysqlType) {
                            MYSQL_TYPE_TINY -> {
                                data.byteValue = tempByteBuffers[byteIdx].value
                                byteIdx++
                            }
                            MYSQL_TYPE_SHORT -> {
                                data.shortValue = tempShortBuffers[shortIdx].value
                                shortIdx++
                            }
                            MYSQL_TYPE_LONG -> {
                                data.intValue = tempIntBuffers[intIdx].value
                                intIdx++
                            }
                            MYSQL_TYPE_LONGLONG -> {
                                data.longValue = tempLongBuffers[longIdx].value
                                longIdx++
                            }
                            MYSQL_TYPE_FLOAT -> {
                                data.floatValue = tempFloatBuffers[floatIdx].value
                                floatIdx++
                            }
                            MYSQL_TYPE_DOUBLE -> {
                                data.doubleValue = tempDoubleBuffers[doubleIdx].value
                                doubleIdx++
                            }
                            else -> {
                                val len = tempLengthBuffers[i].value.toInt()
                                val stringBuf = tempStringBuffers[stringIdx]
                                data.stringValue = if (len > 0) stringBuf.toKString().take(len) else ""
                                data.byteArrayValue = if (len > 0) ByteArray(len) { idx -> stringBuf[idx] } else ByteArray(0)
                                stringIdx++
                            }
                        }
                    }
                }
            }

            // All temporary buffers auto-freed here!
        }

        return hasRow
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!hasRow) throw SQLException("No current row")
        if (columnIndex < 1 || columnIndex > columnCount) throw SQLException("Invalid column index: $columnIndex")

        val index = columnIndex - 1
        val data = columnData[index]

        if (data.isNullValue) return null

        return when (type) {
            Byte::class -> when (data.mysqlType) {
                MYSQL_TYPE_TINY -> data.byteValue
                else -> data.getString()?.toByteOrNull()
            }
            Short::class -> when (data.mysqlType) {
                MYSQL_TYPE_SHORT -> data.shortValue
                MYSQL_TYPE_TINY -> data.byteValue.toShort()
                else -> data.getString()?.toShortOrNull()
            }
            Int::class -> when (data.mysqlType) {
                MYSQL_TYPE_LONG -> data.intValue
                MYSQL_TYPE_SHORT -> data.shortValue.toInt()
                MYSQL_TYPE_TINY -> data.byteValue.toInt()
                else -> data.getString()?.toIntOrNull()
            }
            Long::class -> when (data.mysqlType) {
                MYSQL_TYPE_LONGLONG -> data.longValue
                MYSQL_TYPE_LONG -> data.intValue.toLong()
                else -> data.getString()?.toLongOrNull()
            }
            Float::class -> when (data.mysqlType) {
                MYSQL_TYPE_FLOAT -> data.floatValue
                else -> data.getString()?.toFloatOrNull()
            }
            Double::class -> when (data.mysqlType) {
                MYSQL_TYPE_DOUBLE -> data.doubleValue
                MYSQL_TYPE_FLOAT -> data.floatValue.toDouble()
                else -> data.getString()?.toDoubleOrNull()
            }
            Boolean::class -> when (data.mysqlType) {
                MYSQL_TYPE_TINY -> data.byteValue != 0.toByte()
                else -> data.getString()?.toIntOrNull() != 0
            }
            String::class -> data.getString()
            ByteArray::class -> data.getByteArray()
            BDN::class -> data.getString()?.let { BDN.parseString(it) }
            BIN::class -> data.getString()?.let { BIN.parseString(it) }
            LocalDateTime::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                KtInstant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
            }
            LocalDate::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                KtInstant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
            }
            LocalTime::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                KtInstant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).time
            }
            KtInstant::class -> {
                val millis = when (data.mysqlType) {
                    MYSQL_TYPE_LONGLONG -> data.longValue
                    else -> data.getString()?.toLongOrNull() ?: return null
                }
                KtInstant.fromEpochMilliseconds(millis)
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
        // Column data has no heap allocations, just clear values
        columnData.forEach { it.clear() }
        nativeHeap.free(bindResults)
        metadata?.let { mysql_free_result(it) }
    }

    /**
     * Column data holder that stores values instead of buffers.
     * Buffers are allocated temporarily during fetch and values are copied immediately.
     */
    private class ColumnData(val mysqlType: enum_field_types) {
        // Stored values (no heap allocations!)
        var byteValue: Byte = 0
        var shortValue: Short = 0
        var intValue: Int = 0
        var longValue: Long = 0
        var floatValue: Float = 0f
        var doubleValue: Double = 0.0
        var stringValue: String? = null
        var byteArrayValue: ByteArray? = null
        var isNullValue: Boolean = false

        fun getString(): String? = if (isNullValue) null else stringValue

        fun getByteArray(): ByteArray? = if (isNullValue) null else byteArrayValue

        fun clear() {
            byteValue = 0
            shortValue = 0
            intValue = 0
            longValue = 0
            floatValue = 0f
            doubleValue = 0.0
            stringValue = null
            byteArrayValue = null
            isNullValue = false
        }
    }
}
