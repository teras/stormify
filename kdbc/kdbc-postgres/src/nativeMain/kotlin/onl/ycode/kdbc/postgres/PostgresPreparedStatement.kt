package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import onl.ycode.kdbc.*
import libpq.*

typealias BDN = com.ionspin.kotlin.bignum.decimal.BigDecimal
typealias BIN = com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * Byte order conversion utilities for PostgreSQL binary protocol (network byte order = big endian).
 */
private fun Short.reverseBytes(): Short {
    return ((this.toInt() and 0xFF) shl 8 or ((this.toInt() shr 8) and 0xFF)).toShort()
}

private fun Int.reverseBytes(): Int {
    return ((this and 0xFF) shl 24) or
           ((this and 0xFF00) shl 8) or
           ((this shr 8) and 0xFF00) or
           ((this shr 24) and 0xFF)
}

private fun Long.reverseBytes(): Long {
    return ((this and 0xFF) shl 56) or
           ((this and 0xFF00) shl 40) or
           ((this and 0xFF0000) shl 24) or
           ((this and 0xFF000000) shl 8) or
           ((this shr 8) and 0xFF000000) or
           ((this shr 24) and 0xFF0000) or
           ((this shr 40) and 0xFF00) or
           ((this shr 56) and 0xFF)
}

/**
 * PostgreSQL PreparedStatement implementation using binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class PostgresPreparedStatement(
    private val conn: CPointer<PGconn>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {
    private val paramData = mutableListOf<ParamData>()
    private val stmtName = "stmt_${kotlin.random.Random.nextLong()}"
    private var lastInsertId: Long? = null

    init {
        // Parse SQL to count parameters
        var count = 0
        var i = 0
        while (i < sql.length) {
            if (sql[i] == '?') {
                count++
            }
            i++
        }

        // Convert JDBC-style ? to PostgreSQL-style $1, $2, etc.
        var pgSql = sql
        for (j in count downTo 1) {
            pgSql = pgSql.replaceFirst("?", "$$j")
        }

        // Prepare statement
        val result = PQprepare(conn, stmtName, pgSql, 0, null)
        try {
            if (result == null || PQresultStatus(result) != PGRES_COMMAND_OK) {
                val error = PQerrorMessage(conn)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to prepare statement: $error")
            }
        } finally {
            if (result != null) {
                PQclear(result)
            }
        }

        // Initialize parameter list
        repeat(count) {
            paramData.add(ParamData())
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        if (parameterIndex < 1 || parameterIndex > paramData.size) {
            throw SQLException("Invalid parameter index: $parameterIndex")
        }

        val index = parameterIndex - 1
        val data = paramData[index]
        data.clear()

        when (value) {
            null -> {
                data.isNull = true
            }
            is Byte -> {
                data.byteValue = nativeHeap.alloc<ByteVar>().apply { this.value = value }
            }
            is Short -> {
                data.shortValue = nativeHeap.alloc<ShortVar>().apply { this.value = value.reverseBytes() }
            }
            is Int -> {
                data.intValue = nativeHeap.alloc<IntVar>().apply { this.value = value.reverseBytes() }
            }
            is Long -> {
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = value.reverseBytes() }
            }
            is Float -> {
                val intBits = value.toBits().reverseBytes()
                data.intValue = nativeHeap.alloc<IntVar>().apply { this.value = intBits }
            }
            is Double -> {
                val longBits = value.toBits().reverseBytes()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = longBits }
            }
            is Boolean -> {
                data.byteValue = nativeHeap.alloc<ByteVar>().apply { this.value = if (value) 1 else 0 }
            }
            is String -> {
                data.stringValue = value.cstr.getPointer(MemScope())
                data.length = value.length
            }
            is ByteArray -> {
                data.byteArray = nativeHeap.allocArray<ByteVar>(value.size)
                value.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                data.length = value.size
            }
            is BDN -> {
                // Try to convert to Double if it fits without precision loss
                val doubleValue = value.doubleValue(false)
                if (doubleValue.isFinite() && BDN.parseString(doubleValue.toString()) == value) {
                    val longBits = doubleValue.toBits().reverseBytes()
                    data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = longBits }
                } else {
                    // Fall back to text for precision
                    val str = value.toString()
                    data.stringValue = str.cstr.getPointer(MemScope())
                    data.length = str.length
                }
            }
            is BIN -> {
                // Try to convert to Long if it fits
                val longValue = value.longValue(false)
                if (BIN.parseString(longValue.toString()) == value) {
                    data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = longValue.reverseBytes() }
                } else {
                    // Fall back to text for large numbers
                    val str = value.toString()
                    data.stringValue = str.cstr.getPointer(MemScope())
                    data.length = str.length
                }
            }
            is LocalDateTime -> {
                val millis = value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = millis.reverseBytes() }
            }
            is LocalDate -> {
                val millis = LocalDateTime(value, LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = millis.reverseBytes() }
            }
            is LocalTime -> {
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                val millis = LocalDateTime(date, value).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = millis.reverseBytes() }
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }
    }

    override fun executeUpdate(): Int {
        val result = executePrepared()
        try {
            val status = PQresultStatus(result)
            if (status != PGRES_COMMAND_OK && status != PGRES_TUPLES_OK) {
                val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to execute update: $error")
            }

            // Handle generated keys
            if (returnGeneratedKeys && status == PGRES_TUPLES_OK) {
                val nTuples = PQntuples(result)
                if (nTuples > 0) {
                    val value = PQgetvalue(result, 0, 0)
                    lastInsertId = value?.toKString()?.toLongOrNull()
                }
            }

            val affectedRows = PQcmdTuples(result)?.toKString()?.toIntOrNull() ?: 0
            return affectedRows
        } finally {
            PQclear(result)
        }
    }

    override fun executeQuery(): ResultSet {
        val result = executePrepared()
        val status = PQresultStatus(result)
        if (status != PGRES_TUPLES_OK) {
            val error = PQresultErrorMessage(result)?.toKString() ?: "Unknown error"
            PQclear(result)
            throw SQLException("Failed to execute query: $error")
        }

        return PostgresResultSet(result)
    }

    override fun getGeneratedKeys(): ResultSet {
        if (!returnGeneratedKeys || lastInsertId == null) {
            return EmptyResultSet()
        }
        return GeneratedKeysResultSet(lastInsertId!!)
    }

    override fun close() {
        // Clean up parameter data
        paramData.forEach { it.clear() }

        // Deallocate prepared statement
        val deallocSql = "DEALLOCATE $stmtName"
        val result = PQexec(conn, deallocSql)
        if (result != null) {
            PQclear(result)
        }
    }

    /**
     * Holds binary parameter data for PostgreSQL protocol.
     */
    internal class ParamData {
        var byteValue: ByteVar? = null
        var shortValue: ShortVar? = null
        var intValue: IntVar? = null
        var longValue: LongVar? = null
        var stringValue: CPointer<ByteVar>? = null
        var byteArray: CArrayPointer<ByteVar>? = null
        var isNull: Boolean = false
        var length: Int = 0

        fun clear() {
            byteValue?.let { nativeHeap.free(it) }
            shortValue?.let { nativeHeap.free(it) }
            intValue?.let { nativeHeap.free(it) }
            longValue?.let { nativeHeap.free(it) }
            byteArray?.let { nativeHeap.free(it) }

            byteValue = null
            shortValue = null
            intValue = null
            longValue = null
            stringValue = null
            byteArray = null
            isNull = false
            length = 0
        }
    }

    private fun executePrepared(): CPointer<PGresult> {
        memScoped {
            val paramCount = paramData.size
            val paramValuesArray = allocArray<CPointerVar<ByteVar>>(paramCount)
            val paramLengthsArray = allocArray<IntVar>(paramCount)
            val paramFormatsArray = allocArray<IntVar>(paramCount)

            paramData.forEachIndexed { index, data ->
                when {
                    data.isNull -> {
                        paramValuesArray[index] = null
                        paramLengthsArray[index] = 0
                        paramFormatsArray[index] = 1 // binary
                    }
                    data.byteValue != null -> {
                        paramValuesArray[index] = data.byteValue!!.ptr
                        paramLengthsArray[index] = 1
                        paramFormatsArray[index] = 1 // binary
                    }
                    data.shortValue != null -> {
                        paramValuesArray[index] = data.shortValue!!.ptr.reinterpret()
                        paramLengthsArray[index] = 2
                        paramFormatsArray[index] = 1 // binary
                    }
                    data.intValue != null -> {
                        paramValuesArray[index] = data.intValue!!.ptr.reinterpret()
                        paramLengthsArray[index] = 4
                        paramFormatsArray[index] = 1 // binary
                    }
                    data.longValue != null -> {
                        paramValuesArray[index] = data.longValue!!.ptr.reinterpret()
                        paramLengthsArray[index] = 8
                        paramFormatsArray[index] = 1 // binary
                    }
                    data.stringValue != null -> {
                        paramValuesArray[index] = data.stringValue
                        paramLengthsArray[index] = data.length
                        paramFormatsArray[index] = 0 // text (strings are inherently textual)
                    }
                    data.byteArray != null -> {
                        paramValuesArray[index] = data.byteArray
                        paramLengthsArray[index] = data.length
                        paramFormatsArray[index] = 1 // binary
                    }
                    else -> {
                        paramValuesArray[index] = null
                        paramLengthsArray[index] = 0
                        paramFormatsArray[index] = 1 // binary
                    }
                }
            }

            val result = PQexecPrepared(
                conn,
                stmtName,
                paramCount,
                paramValuesArray,
                paramLengthsArray,
                paramFormatsArray,
                1 // request binary result format
            ) ?: throw SQLException("Failed to execute prepared statement")

            return result
        }
    }
}
