package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import kotlinx.datetime.*
import kotlin.reflect.KClass
import kotlin.time.Instant
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.ParameterStorage
import onl.ycode.kdbc.ParameterType

/**
 * Helper object for PostgreSQL type conversion, providing unified type reading
 * and parameter binding logic.
 *
 * PostgreSQL can return data in two formats:
 * - Binary format: Numeric types as raw bytes with network byte order (big endian)
 * - Text format: All types as strings that need parsing
 *
 * This helper supports both formats and handles byte-order conversion for binary data.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
object PostgresTypeHelper {

    /**
     * PostgreSQL parameter data - extends unified ParameterStorage.
     * No additional fields needed.
     */
    class ParamData : ParameterStorage()

    /**
     * Byte order conversion utilities for PostgreSQL binary protocol.
     * PostgreSQL uses network byte order (big endian), so we need to reverse bytes
     * on little-endian systems.
     */
    fun Short.reverseBytes(): Short {
        return ((this.toInt() and 0xFF) shl 8 or ((this.toInt() shr 8) and 0xFF)).toShort()
    }

    fun Int.reverseBytes(): Int {
        return ((this and 0xFF) shl 24) or
               ((this and 0xFF00) shl 8) or
               ((this shr 8) and 0xFF00) or
               ((this shr 24) and 0xFF)
    }

    fun Long.reverseBytes(): Long {
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
     * Reads a typed value from a PostgreSQL binary result.
     *
     * @param valuePtr Pointer to the binary data
     * @param valueLength Length of the data in bytes
     * @param type The Kotlin class to convert to
     * @return The converted value, or null if the data cannot be converted
     */
    fun readBinaryValue(valuePtr: CPointer<ByteVar>, valueLength: Int, type: KClass<*>): Any? {
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
            kotlin.time.Instant::class -> {
                if (valueLength >= 8) {
                    val millis = valuePtr.reinterpret<LongVar>().pointed.value.reverseBytes()
                    Instant.fromEpochMilliseconds(millis)
                } else null
            }
            else -> null
        }
    }

    /**
     * Reads a typed value from a PostgreSQL text result.
     * This is used when PostgreSQL returns data in text format instead of binary.
     *
     * @param valueStr The string value to parse
     * @param type The Kotlin class to convert to
     * @return The converted value, or null if the data cannot be converted
     */
    fun readTextValue(valueStr: String, type: KClass<*>): Any? {
        return when (type) {
            String::class -> valueStr
            Int::class -> valueStr.toIntOrNull()
            Long::class -> valueStr.toLongOrNull()
            Double::class -> valueStr.toDoubleOrNull()
            Float::class -> valueStr.toFloatOrNull()
            Short::class -> valueStr.toShortOrNull()
            Byte::class -> valueStr.toByteOrNull()
            Boolean::class -> valueStr.toBooleanStrictOrNull()
                ?: (valueStr == "t" || valueStr == "true" || valueStr == "1")
            ByteArray::class -> valueStr.encodeToByteArray()
            kotlinx.datetime.LocalDate::class -> try {
                kotlinx.datetime.LocalDate.parse(valueStr)
            } catch (e: Exception) { null }
            kotlinx.datetime.LocalDateTime::class -> try {
                kotlinx.datetime.LocalDateTime.parse(valueStr.replace(' ', 'T'))
            } catch (e: Exception) { null }
            kotlinx.datetime.LocalTime::class -> try {
                kotlinx.datetime.LocalTime.parse(valueStr)
            } catch (e: Exception) { null }
            kotlin.time.Instant::class -> try {
                Instant.parse(valueStr)
            } catch (e: Exception) { null }
            BDN::class -> try {
                BDN.parseString(valueStr)
            } catch (e: Exception) { null }
            BIN::class -> try {
                BIN.parseString(valueStr)
            } catch (e: Exception) { null }
            else -> valueStr
        }
    }

    /**
     * Stores a parameter value in ParamData for later allocation during execution.
     * Uses unified ParameterStorage, applies PostgreSQL-specific byte-order conversion.
     */
    fun bindParameter(value: Any?, data: ParamData) {
        // Try unified storage first
        if (data.store(value)) {
            // PostgreSQL needs byte-order conversion (network byte order = big endian)
            when (value) {
                is Boolean -> data.byteValue = if (value) 1 else 0
                is Short -> data.shortValue = value.reverseBytes()
                is Int -> data.intValue = value.reverseBytes()
                is Long -> data.longValue = value.reverseBytes()
                is Float -> data.intValue = value.toBits().reverseBytes()
                is Double -> data.longValue = value.toBits().reverseBytes()
            }
            return
        }

        // Handle PostgreSQL-specific complex types (with byte reversal)
        when (value) {
            is BDN -> {
                // Use PostgreSQL NUMERIC binary format for full precision
                data.type = ParameterType.BYTE_ARRAY
                data.byteArrayValue = encodePostgresNumeric(value)
            }
            is BIN -> {
                // Try to convert to Long if it fits in Long range
                if (value >= BIN.fromLong(Long.MIN_VALUE) && value <= BIN.fromLong(Long.MAX_VALUE)) {
                    data.type = ParameterType.LONG
                    data.longValue = value.longValue(false).reverseBytes()
                } else {
                    // Fall back to text for large numbers outside Long range
                    data.type = ParameterType.STRING
                    data.stringValue = value.toString()
                }
            }
            is kotlinx.datetime.LocalDateTime -> {
                data.type = ParameterType.LONG
                data.longValue = value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().reverseBytes()
            }
            is kotlinx.datetime.LocalDate -> {
                data.type = ParameterType.LONG
                data.longValue = kotlinx.datetime.LocalDateTime(value, kotlinx.datetime.LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().reverseBytes()
            }
            is kotlinx.datetime.LocalTime -> {
                data.type = ParameterType.LONG
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                data.longValue = kotlinx.datetime.LocalDateTime(date, value)
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().reverseBytes()
            }
            is Instant -> {
                data.type = ParameterType.LONG
                data.longValue = value.toEpochMilliseconds().reverseBytes()
            }
            else -> throw onl.ycode.kdbc.SQLException("Unsupported parameter type: ${value!!::class}")
        }
    }

    /**
     * Encodes a BigDecimal to PostgreSQL NUMERIC binary format.
     *
     * PostgreSQL NUMERIC binary format:
     * - 4x int16 (8 bytes header): ndigits, weight, sign, dscale
     * - ndigits x int16: digits in base-10000 (NBASE = 10000)
     * - sign: 0x0000 = positive, 0x4000 = negative, 0xC000 = NaN
     * - weight: position of first digit (number of base-10000 digits before decimal point - 1)
     * - dscale: number of decimal digits after decimal point
     * - All int16 values in network byte order (big endian)
     */
    private fun encodePostgresNumeric(value: BDN): ByteArray {
        // Handle zero
        if (value == BDN.ZERO) {
            val result = ByteArray(8)
            // ndigits=0, weight=0, sign=0x0000, dscale=0
            return result
        }

        val isNegative = value < BDN.ZERO
        val absValue = if (isNegative) value.negate() else value

        // Get string representation
        val str = absValue.toStringExpanded()
        val parts = str.split('.')
        val integerPart = parts[0].trimStart('0')
        val fractionalPart = if (parts.size > 1) parts[1] else ""

        // Calculate dscale (decimal digits after decimal point)
        val dscale = fractionalPart.length

        // Combine all significant digits
        val allDigits = (if (integerPart.isEmpty()) "0" else integerPart) + fractionalPart

        // Convert to base-10000 digits
        val base10000Digits = mutableListOf<Short>()
        var i = allDigits.length

        // Process from right to left in groups of 4
        while (i > 0) {
            val start = maxOf(0, i - 4)
            val digitGroup = allDigits.substring(start, i)
            base10000Digits.add(0, digitGroup.toShort())
            i = start
        }

        // Calculate weight (position of first base-10000 digit group)
        // weight = (number of decimal digits before decimal point - 1) / 4
        val digitsBeforeDecimal = if (integerPart.isEmpty()) 0 else integerPart.length
        val weight = if (digitsBeforeDecimal == 0) {
            // For numbers < 1, weight is negative
            -(((fractionalPart.indexOfFirst { it != '0' } + 1 + 3) / 4))
        } else {
            (digitsBeforeDecimal - 1) / 4
        }

        val ndigits = base10000Digits.size.toShort()
        val sign: Short = if (isNegative) 0x4000 else 0x0000

        // Build result: header (8 bytes) + digits (ndigits * 2 bytes)
        val result = ByteArray(8 + ndigits * 2)
        var offset = 0

        // Write header (all int16 in big endian)
        result[offset++] = (ndigits.toInt() shr 8).toByte()
        result[offset++] = ndigits.toByte()

        result[offset++] = (weight shr 8).toByte()
        result[offset++] = weight.toByte()

        result[offset++] = (sign.toInt() shr 8).toByte()
        result[offset++] = sign.toByte()

        result[offset++] = (dscale shr 8).toByte()
        result[offset++] = dscale.toByte()

        // Write digits
        for (digit in base10000Digits) {
            result[offset++] = (digit.toInt() shr 8).toByte()
            result[offset++] = digit.toByte()
        }

        return result
    }
}
