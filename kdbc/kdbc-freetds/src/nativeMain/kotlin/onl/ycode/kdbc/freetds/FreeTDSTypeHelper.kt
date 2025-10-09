package onl.ycode.kdbc.freetds

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

/**
 * FreeTDS-specific type helper for parameter binding and result reading.
 *
 * MS SQL Server / FreeTDS specifics:
 * - Little endian (native byte order) for all numeric types
 * - TDS protocol binary format support
 * - MONEY/DECIMAL types
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
object FreeTDSTypeHelper {

    /**
     * FreeTDS-specific parameter data.
     * Implements ParameterData interface for shared allocation logic.
     */
    class ParamData : onl.ycode.kdbc.ParameterData {
        override var type: ParameterType = ParameterType.NULL
        override var value: Any? = null

        fun clear() {
            type = ParameterType.NULL
            value = null
        }
    }

    /**
     * Binds a parameter value to ParamData.
     * SQL Server uses little endian (native byte order on most platforms).
     */
    fun bindParameter(value: Any?, data: ParamData) {
        data.clear()

        when (value) {
            null -> {
                data.type = ParameterType.NULL
                data.value = null
            }
            is Boolean -> {
                // SQL Server stores Boolean as bit (1 or 0)
                data.type = ParameterType.BYTE
                data.value = if (value) 1.toByte() else 0.toByte()
            }
            is Byte -> {
                data.type = ParameterType.BYTE
                data.value = value
            }
            is Short -> {
                data.type = ParameterType.SHORT
                data.value = value
            }
            is Int -> {
                data.type = ParameterType.INT
                data.value = value
            }
            is Long -> {
                data.type = ParameterType.LONG
                data.value = value
            }
            is Float -> {
                data.type = ParameterType.FLOAT
                data.value = value
            }
            is Double -> {
                data.type = ParameterType.DOUBLE
                data.value = value
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
                // Convert to SQL Server datetime format string
                data.type = ParameterType.STRING
                data.value = value.toString().replace('T', ' ')
            }
            is kotlinx.datetime.LocalDate -> {
                // Convert to SQL Server date format string
                data.type = ParameterType.STRING
                data.value = value.toString()
            }
            is kotlinx.datetime.LocalTime -> {
                // Convert to SQL Server time format string
                data.type = ParameterType.STRING
                data.value = value.toString()
            }
            is Instant -> {
                // Convert to SQL Server datetime format
                val dateTime = value.toLocalDateTime(TimeZone.currentSystemDefault())
                data.type = ParameterType.STRING
                data.value = dateTime.toString().replace('T', ' ')
            }
            is BDN -> {
                // Use string representation for DECIMAL/NUMERIC
                data.type = ParameterType.STRING
                data.value = value.toStringExpanded()
            }
            is BIN -> {
                // Try Long if it fits, otherwise string
                if (value >= BIN.fromLong(Long.MIN_VALUE) && value <= BIN.fromLong(Long.MAX_VALUE)) {
                    data.type = ParameterType.LONG
                    data.value = value.longValue(false)
                } else {
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
     * Reads a typed value from FreeTDS result (text format).
     * FreeTDS db-lib primarily uses text format for results.
     */
    fun readValue(valueStr: String?, type: KClass<*>): Any? {
        if (valueStr == null) return null

        return when (type) {
            String::class -> valueStr
            Int::class -> valueStr.toIntOrNull()
            Long::class -> valueStr.toLongOrNull()
            Double::class -> valueStr.toDoubleOrNull()
            Float::class -> valueStr.toFloatOrNull()
            Short::class -> valueStr.toShortOrNull()
            Byte::class -> valueStr.toByteOrNull()
            Boolean::class -> valueStr.toBooleanStrictOrNull()
                ?: (valueStr == "1" || valueStr.equals("true", ignoreCase = true))
            ByteArray::class -> valueStr.encodeToByteArray()
            kotlinx.datetime.LocalDate::class -> try {
                kotlinx.datetime.LocalDate.parse(valueStr.take(10))
            } catch (e: Exception) { null }
            kotlinx.datetime.LocalDateTime::class -> try {
                // SQL Server format: "YYYY-MM-DD HH:MM:SS.mmm"
                kotlinx.datetime.LocalDateTime.parse(valueStr.replace(' ', 'T').take(19))
            } catch (e: Exception) { null }
            kotlinx.datetime.LocalTime::class -> try {
                kotlinx.datetime.LocalTime.parse(valueStr.take(8))
            } catch (e: Exception) { null }
            kotlin.time.Instant::class -> try {
                val dateTime = kotlinx.datetime.LocalDateTime.parse(valueStr.replace(' ', 'T').take(19))
                dateTime.toInstant(TimeZone.currentSystemDefault())
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
}
