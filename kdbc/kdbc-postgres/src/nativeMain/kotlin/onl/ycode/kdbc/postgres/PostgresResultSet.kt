package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import onl.ycode.kdbc.*
import libpq.*
import kotlin.reflect.KClass
import kotlin.time.Instant
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

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
 * PostgreSQL ResultSet implementation.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class PostgresResultSet(private val result: CPointer<PGresult>) : ResultSet {
    private var currentRow = -1
    private val rowCount = PQntuples(result)
    private val columnCount = PQnfields(result)

    override fun next(): Boolean {
        currentRow++
        return currentRow < rowCount
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (currentRow < 0 || currentRow >= rowCount) {
            throw SQLException("No current row")
        }

        if (columnIndex < 1 || columnIndex > columnCount) {
            throw SQLException("Invalid column index: $columnIndex")
        }

        val colIndex = columnIndex - 1

        if (PQgetisnull(result, currentRow, colIndex) == 1) {
            return null
        }

        val valuePtr = PQgetvalue(result, currentRow, colIndex) ?: return null
        val valueLength = PQgetlength(result, currentRow, colIndex)

        // Binary format only
        return when (type) {
            Byte::class -> {
                if (valueLength >= 1) valuePtr.pointed.value else null
            }
            Short::class -> {
                if (valueLength >= 2) {
                    valuePtr.reinterpret<ShortVar>().pointed.value.reverseBytes()
                } else null
            }
            Int::class -> {
                if (valueLength >= 4) {
                    valuePtr.reinterpret<IntVar>().pointed.value.reverseBytes()
                } else null
            }
            Long::class -> {
                if (valueLength >= 8) {
                    valuePtr.reinterpret<LongVar>().pointed.value.reverseBytes()
                } else null
            }
            Float::class -> {
                if (valueLength >= 4) {
                    val intBits = valuePtr.reinterpret<IntVar>().pointed.value.reverseBytes()
                    Float.fromBits(intBits)
                } else null
            }
            Double::class -> {
                if (valueLength >= 8) {
                    val longBits = valuePtr.reinterpret<LongVar>().pointed.value.reverseBytes()
                    Double.fromBits(longBits)
                } else null
            }
            Boolean::class -> {
                if (valueLength >= 1) valuePtr.pointed.value != 0.toByte() else null
            }
            String::class -> {
                valuePtr.toKString()
            }
            ByteArray::class -> {
                ByteArray(valueLength) { i -> valuePtr[i] }
            }
            BDN::class -> {
                val str = valuePtr.toKString()
                BDN.parseString(str)
            }
            BIN::class -> {
                val str = valuePtr.toKString()
                BIN.parseString(str)
            }
            kotlinx.datetime.LocalDateTime::class -> {
                if (valueLength >= 8) {
                    val millis = valuePtr.reinterpret<LongVar>().pointed.value.reverseBytes()
                    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())
                } else null
            }
            kotlinx.datetime.LocalDate::class -> {
                if (valueLength >= 8) {
                    val millis = valuePtr.reinterpret<LongVar>().pointed.value.reverseBytes()
                    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).date
                } else null
            }
            kotlinx.datetime.LocalTime::class -> {
                if (valueLength >= 8) {
                    val millis = valuePtr.reinterpret<LongVar>().pointed.value.reverseBytes()
                    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault()).time
                } else null
            }
            else -> null
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return PostgresResultSetMetaData(result, columnCount)
    }

    override fun close() {
        PQclear(result)
    }
}
