package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import mariadb.*
import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.ParameterStorage
import onl.ycode.kdbc.ParameterType

/**
 * Helper object for MariaDB parameter handling.
 * Uses unified ParameterStorage from base kdbc module for common types.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
object MariadbParameterHelper {

    /**
     * MariaDB parameter data - extends unified ParameterStorage.
     * No additional fields needed since MariaDB doesn't require special encoding.
     */
    class ParameterData : ParameterStorage()

    /**
     * Stores a parameter value in ParameterData for later allocation during execution.
     * Uses unified ParameterStorage for primitives, handles MariaDB-specific types.
     *
     * @param value The parameter value to bind (null for SQL NULL)
     * @param data The ParameterData to store the value
     */
    fun bindParameter(value: Any?, data: ParameterData) {
        // Try unified storage first (handles all primitives)
        if (data.store(value)) {
            // Boolean needs special handling for MariaDB (stored as byte)
            if (value is Boolean) {
                data.byteValue = if (value) 1 else 0
            }
            return
        }

        // Handle MariaDB-specific complex types
        when (value) {
            is BDN -> {
                // Always use string format to preserve full precision
                // MariaDB/MySQL sends DECIMAL as strings in binary protocol
                data.type = ParameterType.STRING
                data.stringValue = value.toString()
            }
            is BIN -> {
                // Try to convert to Long if it fits in Long range
                if (value >= BIN.fromLong(Long.MIN_VALUE) && value <= BIN.fromLong(Long.MAX_VALUE)) {
                    data.type = ParameterType.LONG
                    data.longValue = value.longValue(false)
                } else {
                    // Fall back to text for large numbers outside Long range
                    data.type = ParameterType.STRING
                    data.stringValue = value.toString()
                }
            }
            is LocalDateTime -> {
                data.type = ParameterType.LONG
                data.longValue = value.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }
            is LocalDate -> {
                data.type = ParameterType.LONG
                data.longValue = LocalDateTime(value, LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }
            is LocalTime -> {
                data.type = ParameterType.LONG
                val date = kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                data.longValue = LocalDateTime(date, value)
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            }
            is kotlinx.datetime.Instant -> {
                data.type = ParameterType.LONG
                data.longValue = value.toEpochMilliseconds()
            }
            else -> throw SQLException("Unsupported parameter type: ${value!!::class}")
        }
    }
}
