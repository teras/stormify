package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import mariadb.*
import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.ParameterType
import onl.ycode.kdbc.AllocatedParameter
import onl.ycode.kdbc.allocateParameterBuffer

/**
 * MariaDB-specific parameter binding helper.
 *
 * This driver-specific implementation eliminates the overengineered ParameterStorage
 * abstraction, providing clear, explicit MariaDB/MySQL C API parameter handling.
 *
 * MariaDB specifics:
 * - DECIMAL sent as strings (MySQL binary protocol requirement)
 * - Dates as epoch milliseconds
 * - Boolean as byte (1 or 0)
 * - Simplest driver (minimal encoding needed)
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
object MariadbParameterHelper {

    /**
     * MariaDB-specific parameter data.
     * Implements ParameterData interface for shared allocation logic.
     */
    class ParameterData : onl.ycode.kdbc.ParameterData {
        override var type: ParameterType = ParameterType.NULL
        override var value: Any? = null

        fun clear() {
            type = ParameterType.NULL
            value = null
        }
    }

    /**
     * Binds a parameter value to ParameterData.
     * MariaDB-specific encoding (DECIMAL as string, dates as epoch milliseconds).
     * Clear, explicit handling - no inherited store() calls.
     */
    fun bindParameter(value: Any?, data: ParameterData) {
        data.clear()

        when (value) {
            null -> {
                data.type = ParameterType.NULL
                data.value = null
            }
            is Boolean -> {
                // MariaDB stores Boolean as byte (1 or 0)
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
            is LocalDateTime -> {
                // MariaDB encodes dates as epoch milliseconds
                data.type = ParameterType.LONG
                data.value = value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }
            is LocalDate -> {
                data.type = ParameterType.LONG
                data.value = LocalDateTime(value, LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }
            is LocalTime -> {
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                data.type = ParameterType.LONG
                data.value = LocalDateTime(date, value)
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }
            is kotlinx.datetime.Instant -> {
                data.type = ParameterType.LONG
                data.value = value.toEpochMilliseconds()
            }
            is BDN -> {
                // MariaDB/MySQL sends DECIMAL as strings (binary protocol requirement)
                data.type = ParameterType.STRING
                data.value = value.toString()
            }
            is BIN -> {
                // Try Long if it fits
                if (value >= BIN.fromLong(Long.MIN_VALUE) && value <= BIN.fromLong(Long.MAX_VALUE)) {
                    data.type = ParameterType.LONG
                    data.value = value.longValue(false)
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
    fun MemScope.allocateParameter(data: ParameterData): AllocatedParameter {
        return allocateParameterBuffer(data)
    }
}
