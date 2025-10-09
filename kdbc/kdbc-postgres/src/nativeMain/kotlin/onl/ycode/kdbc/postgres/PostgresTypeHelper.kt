package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import kotlinx.datetime.*
import kotlin.reflect.KClass
import kotlin.time.Instant
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.ParameterType
import onl.ycode.kdbc.AllocatedParameter
import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.allocateParameterBuffer
import onl.ycode.kdbc.reverseBytes

/**
 * PostgreSQL-specific type helper for parameter binding and result reading.
 *
 * This driver-specific implementation eliminates the overengineered ParameterStorage
 * abstraction, providing clear, explicit PostgreSQL libpq parameter handling.
 *
 * PostgreSQL specifics:
 * - Network byte order (big endian) for all numeric types
 * - NUMERIC binary format (base-10000 encoding)
 * - Binary and text format support
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
object PostgresTypeHelper {

    /**
     * PostgreSQL-specific parameter data.
     * Implements ParameterData interface for shared allocation logic.
     */
    class ParamData : onl.ycode.kdbc.ParameterData {
        override var type: ParameterType = ParameterType.NULL
        override var value: Any? = null

        // For complex types that need pre-encoding (NUMERIC)
        var encodedData: ByteArray? = null

        fun clear() {
            type = ParameterType.NULL
            value = null
            encodedData = null
        }
    }

    /**
     * Reads a typed value from a PostgreSQL binary result.
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
     * Binds a parameter value to ParamData.
     * PostgreSQL-specific encoding with byte-order conversion (big endian).
     * Clear, explicit handling - no inherited store() calls.
     */
    fun bindParameter(value: Any?, data: ParamData) {
        data.clear()

        when (value) {
            null -> {
                data.type = ParameterType.NULL
                data.value = null
            }
            is Boolean -> {
                // PostgreSQL stores Boolean as byte (1 or 0)
                data.type = ParameterType.BYTE
                data.value = if (value) 1.toByte() else 0.toByte()
            }
            is Byte -> {
                data.type = ParameterType.BYTE
                data.value = value
            }
            is Short -> {
                // PostgreSQL needs big endian (network byte order)
                data.type = ParameterType.SHORT
                data.value = value.reverseBytes()
            }
            is Int -> {
                data.type = ParameterType.INT
                data.value = value.reverseBytes()
            }
            is Long -> {
                data.type = ParameterType.LONG
                data.value = value.reverseBytes()
            }
            is Float -> {
                // Float sent as int bits (big endian)
                data.type = ParameterType.INT
                data.value = value.toBits().reverseBytes()
            }
            is Double -> {
                // Double sent as long bits (big endian)
                data.type = ParameterType.LONG
                data.value = value.toBits().reverseBytes()
            }
            is String -> {
                data.type = ParameterType.STRING
                data.value = value
            }
            is ByteArray -> {
                data.type = ParameterType.BYTE_ARRAY
                data.value = value
            }
            is kotlinx.datetime.LocalDateTime -> {
                data.type = ParameterType.LONG
                data.value = value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().reverseBytes()
            }
            is kotlinx.datetime.LocalDate -> {
                data.type = ParameterType.LONG
                data.value = kotlinx.datetime.LocalDateTime(value, kotlinx.datetime.LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().reverseBytes()
            }
            is kotlinx.datetime.LocalTime -> {
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                data.type = ParameterType.LONG
                data.value = kotlinx.datetime.LocalDateTime(date, value)
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds().reverseBytes()
            }
            is Instant -> {
                data.type = ParameterType.LONG
                data.value = value.toEpochMilliseconds().reverseBytes()
            }
            is BDN -> {
                // Use PostgreSQL NUMERIC binary format for full precision
                data.type = ParameterType.BYTE_ARRAY
                data.encodedData = encodePostgresNumeric(value)
                data.value = data.encodedData
            }
            is BIN -> {
                // Try Long if it fits
                if (value >= BIN.fromLong(Long.MIN_VALUE) && value <= BIN.fromLong(Long.MAX_VALUE)) {
                    data.type = ParameterType.LONG
                    data.value = value.longValue(false).reverseBytes()
                } else {
                    // Fall back to text for large numbers
                    data.type = ParameterType.STRING
                    data.value = value.toString()
                }
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }
    }

    /**
     * Allocates a buffer for the parameter in memScoped.
     * Delegates to shared allocation logic.
     * Must be called within a memScoped block.
     */
    fun MemScope.allocateParameter(data: ParamData): AllocatedParameter {
        return allocateParameterBuffer(data)
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
