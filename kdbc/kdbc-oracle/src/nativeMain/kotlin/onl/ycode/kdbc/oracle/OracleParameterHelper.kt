package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import kotlinx.datetime.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.SQLException
import kotlin.reflect.KClass
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

/**
 * ODPI-C parameter binding helper.
 * ODPI-C uses dpiData structures for all value transfers, which is much simpler than raw OCI.
 */
@OptIn(ExperimentalForeignApi::class)
object OracleParameterHelper {

    /**
     * Set a value into a dpiData structure based on Kotlin type.
     */
    fun setDpiDataValue(data: CPointer<dpiData>, value: Any?, oracleType: UInt, nativeType: UInt) {
        when (value) {
            null -> {
                data.pointed.isNull = 1
            }
            is Boolean -> {
                data.pointed.isNull = 0
                data.pointed.value.asBoolean = if (value) 1 else 0
            }
            is Byte, is Short, is Int -> {
                data.pointed.isNull = 0
                data.pointed.value.asInt64 = (value as Number).toLong()
            }
            is Long -> {
                data.pointed.isNull = 0
                data.pointed.value.asInt64 = value
            }
            is Float -> {
                data.pointed.isNull = 0
                data.pointed.value.asFloat = value
            }
            is Double -> {
                data.pointed.isNull = 0
                data.pointed.value.asDouble = value
            }
            is String -> {
                data.pointed.isNull = 0
                val bytes = value.encodeToByteArray()
                bytes.usePinned { pinned ->
                    data.pointed.value.asBytes.ptr = pinned.addressOf(0)
                    data.pointed.value.asBytes.length = bytes.size.toUInt()
                }
            }
            is ByteArray -> {
                data.pointed.isNull = 0
                value.usePinned { pinned ->
                    data.pointed.value.asBytes.ptr = pinned.addressOf(0)
                    data.pointed.value.asBytes.length = value.size.toUInt()
                }
            }
            is LocalDateTime -> {
                data.pointed.isNull = 0
                setTimestamp(data.pointed.value.asTimestamp.ptr, value)
            }
            is LocalDate -> {
                data.pointed.isNull = 0
                setTimestamp(data.pointed.value.asTimestamp.ptr, LocalDateTime(value, LocalTime(0, 0)))
            }
            is LocalTime -> {
                data.pointed.isNull = 0
                setTimestamp(data.pointed.value.asTimestamp.ptr, LocalDateTime(LocalDate(1970, 1, 1), value))
            }
            is Instant -> {
                data.pointed.isNull = 0
                setTimestamp(data.pointed.value.asTimestamp.ptr, value.toLocalDateTime(TimeZone.UTC))
            }
            is BDN -> {
                data.pointed.isNull = 0
                val str = value.toStringExpanded()
                val bytes = str.encodeToByteArray()
                bytes.usePinned { pinned ->
                    data.pointed.value.asBytes.ptr = pinned.addressOf(0)
                    data.pointed.value.asBytes.length = bytes.size.toUInt()
                }
            }
            is BIN -> {
                data.pointed.isNull = 0
                val str = value.toString()
                val bytes = str.encodeToByteArray()
                bytes.usePinned { pinned ->
                    data.pointed.value.asBytes.ptr = pinned.addressOf(0)
                    data.pointed.value.asBytes.length = bytes.size.toUInt()
                }
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }
    }

    /**
     * Get a Kotlin value from a dpiData structure.
     */
    fun getDpiDataValue(data: CPointer<dpiData>, targetType: KClass<*>, nativeType: UInt): Any? {
        if (data.pointed.isNull != 0) {
            return null
        }

        return when (nativeType) {
            DPI_NATIVE_TYPE_INT64 -> {
                val longValue = data.pointed.value.asInt64
                when (targetType) {
                    Int::class -> longValue.toInt()
                    Long::class -> longValue
                    Boolean::class -> longValue != 0L
                    String::class -> longValue.toString()
                    BIN::class -> BIN.parseString(longValue.toString())
                    else -> longValue
                }
            }
            DPI_NATIVE_TYPE_FLOAT -> {
                val floatValue = data.pointed.value.asFloat
                when (targetType) {
                    Float::class -> floatValue
                    Double::class -> floatValue.toDouble()
                    String::class -> floatValue.toString()
                    else -> floatValue
                }
            }
            DPI_NATIVE_TYPE_DOUBLE -> {
                val doubleValue = data.pointed.value.asDouble
                when (targetType) {
                    Double::class -> doubleValue
                    Float::class -> doubleValue.toFloat()
                    String::class -> doubleValue.toString()
                    BDN::class -> BDN.parseString(doubleValue.toString())
                    else -> doubleValue
                }
            }
            DPI_NATIVE_TYPE_BYTES -> {
                val bytes = data.pointed.value.asBytes
                val ptr = bytes.ptr ?: throw SQLException("Unexpected null pointer in bytes data")
                val byteArray = ByteArray(bytes.length.toInt()) { i ->
                    ptr[i]
                }
                when (targetType) {
                    String::class -> byteArray.decodeToString()
                    ByteArray::class -> byteArray
                    BDN::class -> BDN.parseString(byteArray.decodeToString())
                    BIN::class -> BIN.parseString(byteArray.decodeToString())
                    else -> byteArray
                }
            }
            DPI_NATIVE_TYPE_TIMESTAMP -> {
                val ts = data.pointed.value.asTimestamp.ptr
                val dateTime = getTimestamp(ts)
                when (targetType) {
                    LocalDateTime::class -> dateTime
                    LocalDate::class -> dateTime.date
                    LocalTime::class -> dateTime.time
                    Instant::class -> dateTime.toInstant(TimeZone.UTC)
                    String::class -> dateTime.toString()
                    else -> dateTime
                }
            }
            DPI_NATIVE_TYPE_BOOLEAN -> {
                val boolValue = data.pointed.value.asBoolean != 0
                when (targetType) {
                    Boolean::class -> boolValue
                    Int::class -> if (boolValue) 1 else 0
                    String::class -> boolValue.toString()
                    else -> boolValue
                }
            }
            else -> throw SQLException("Unsupported native type: $nativeType")
        }
    }

    /**
     * Determine ODPI-C oracle and native types for a Kotlin value.
     */
    fun getTypesForValue(value: Any?): Pair<UInt, UInt> {
        return when (value) {
            null -> DPI_ORACLE_TYPE_VARCHAR to DPI_NATIVE_TYPE_BYTES
            is Boolean -> DPI_ORACLE_TYPE_NUMBER to DPI_NATIVE_TYPE_BOOLEAN
            is Byte, is Short, is Int, is Long -> DPI_ORACLE_TYPE_NUMBER to DPI_NATIVE_TYPE_INT64
            is Float -> DPI_ORACLE_TYPE_BINARY_FLOAT to DPI_NATIVE_TYPE_FLOAT
            is Double -> DPI_ORACLE_TYPE_BINARY_DOUBLE to DPI_NATIVE_TYPE_DOUBLE
            is String -> DPI_ORACLE_TYPE_VARCHAR to DPI_NATIVE_TYPE_BYTES
            is ByteArray -> DPI_ORACLE_TYPE_RAW to DPI_NATIVE_TYPE_BYTES
            is LocalDate, is LocalDateTime, is LocalTime, is Instant ->
                DPI_ORACLE_TYPE_TIMESTAMP to DPI_NATIVE_TYPE_TIMESTAMP
            is BDN, is BIN -> DPI_ORACLE_TYPE_NUMBER to DPI_NATIVE_TYPE_BYTES
            else -> throw SQLException("Unsupported parameter type: ${value?.let { it::class }}")
        }
    }

    /**
     * Determine ODPI-C oracle and native types for a Kotlin class.
     */
    fun getTypesForClass(type: KClass<*>): Pair<UInt, UInt> {
        return when (type) {
            Boolean::class -> DPI_ORACLE_TYPE_NUMBER to DPI_NATIVE_TYPE_BOOLEAN
            Int::class, Long::class -> DPI_ORACLE_TYPE_NUMBER to DPI_NATIVE_TYPE_INT64
            Float::class -> DPI_ORACLE_TYPE_BINARY_FLOAT to DPI_NATIVE_TYPE_FLOAT
            Double::class -> DPI_ORACLE_TYPE_BINARY_DOUBLE to DPI_NATIVE_TYPE_DOUBLE
            String::class -> DPI_ORACLE_TYPE_VARCHAR to DPI_NATIVE_TYPE_BYTES
            ByteArray::class -> DPI_ORACLE_TYPE_RAW to DPI_NATIVE_TYPE_BYTES
            LocalDate::class, LocalDateTime::class, LocalTime::class, Instant::class ->
                DPI_ORACLE_TYPE_TIMESTAMP to DPI_NATIVE_TYPE_TIMESTAMP
            BDN::class, BIN::class -> DPI_ORACLE_TYPE_NUMBER to DPI_NATIVE_TYPE_BYTES
            else -> throw SQLException("Unsupported type: $type")
        }
    }

    /**
     * Set timestamp value into ODPI-C timestamp structure.
     */
    private fun setTimestamp(ts: CPointer<dpiTimestamp>, dateTime: LocalDateTime) {
        ts.pointed.year = dateTime.year.toShort()
        ts.pointed.month = dateTime.monthNumber.toUByte()
        ts.pointed.day = dateTime.dayOfMonth.toUByte()
        ts.pointed.hour = dateTime.hour.toUByte()
        ts.pointed.minute = dateTime.minute.toUByte()
        ts.pointed.second = dateTime.second.toUByte()
        ts.pointed.fsecond = (dateTime.nanosecond).toUInt()
        ts.pointed.tzHourOffset = 0
        ts.pointed.tzMinuteOffset = 0
    }

    /**
     * Get timestamp value from ODPI-C timestamp structure.
     */
    private fun getTimestamp(ts: CPointer<dpiTimestamp>): LocalDateTime {
        return LocalDateTime(
            year = ts.pointed.year.toInt(),
            monthNumber = ts.pointed.month.toInt(),
            dayOfMonth = ts.pointed.day.toInt(),
            hour = ts.pointed.hour.toInt(),
            minute = ts.pointed.minute.toInt(),
            second = ts.pointed.second.toInt(),
            nanosecond = ts.pointed.fsecond.toInt()
        )
    }
}
