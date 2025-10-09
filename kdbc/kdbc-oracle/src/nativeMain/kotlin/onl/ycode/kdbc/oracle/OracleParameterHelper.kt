package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import kotlinx.datetime.*
import oci.*
import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.ParameterType
import onl.ycode.kdbc.AllocatedParameter
import onl.ycode.kdbc.allocateParameterBuffer
import kotlin.reflect.KClass
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

/**
 * Oracle-specific parameter binding helper for PreparedStatement and CallableStatement.
 *
 * Handles Oracle OCI protocol with proper type encoding for Oracle-specific formats
 * (7-byte DATE, base-100 NUMBER).
 *
 * Supports 13 data types: String, Int, Long, Double, Float, Boolean, ByteArray,
 * LocalDate, LocalDateTime, LocalTime, Instant, BigDecimal, BigInteger
 */
@OptIn(ExperimentalForeignApi::class)
object OracleParameterHelper {

    /**
     * Oracle-specific IN parameter data for binding values to prepared statements.
     */
    class InParameterData : onl.ycode.kdbc.ParameterData {
        override var type: ParameterType = ParameterType.NULL
        override var value: Any? = null

        var oracleDataType: UShort = SQLT_STR

        // For complex types that need pre-encoding (BigDecimal, DATE)
        var encodedData: ByteArray? = null

        fun clear() {
            type = ParameterType.NULL
            oracleDataType = SQLT_STR
            value = null
            encodedData = null
        }
    }

    /**
     * OUT parameter data holder - uses heap allocation with copy-on-execute pattern.
     * These buffers are pre-allocated before execution and freed immediately after copying.
     */
    class OutParameterData {
        // Scalar buffers
        var intBuffer: IntVar? = null
        var longBuffer: LongVar? = null
        var doubleBuffer: DoubleVar? = null
        var floatBuffer: FloatVar? = null

        // Array buffers
        var stringBuffer: CPointer<ByteVar>? = null
        var byteArrayBuffer: CPointer<ByteVar>? = null
        var dateBuffer: CPointer<ByteVar>? = null  // 7-byte Oracle DATE

        // Metadata
        var indicator: ShortVar? = null  // NULL indicator: -1 = NULL, 0 = not NULL
        var actualLength: UShortVar? = null  // For OUT parameters
        var bufferSize: Int = 0
        var dataType: UShort = SQLT_STR

        fun clear() {
            intBuffer?.let { nativeHeap.free(it) }
            longBuffer?.let { nativeHeap.free(it) }
            doubleBuffer?.let { nativeHeap.free(it) }
            floatBuffer?.let { nativeHeap.free(it) }
            stringBuffer?.let { nativeHeap.free(it) }
            byteArrayBuffer?.let { nativeHeap.free(it) }
            dateBuffer?.let { nativeHeap.free(it) }
            indicator?.let { nativeHeap.free(it) }
            actualLength?.let { nativeHeap.free(it) }

            intBuffer = null
            longBuffer = null
            doubleBuffer = null
            floatBuffer = null
            stringBuffer = null
            byteArrayBuffer = null
            dateBuffer = null
            indicator = null
            actualLength = null
            bufferSize = 0
        }
    }

    /**
     * Binds an IN parameter value to InParameterData.
     * Handles Oracle-specific encoding for DATE (7-byte format) and NUMBER (base-100 format).
     */
    fun bindInParameter(value: Any?, data: InParameterData) {
        data.clear()

        when (value) {
            null -> {
                data.type = ParameterType.NULL
                data.oracleDataType = SQLT_STR
                data.value = null
            }
            is Boolean -> {
                // Oracle stores Boolean as INT (1 or 0)
                data.type = ParameterType.INT
                data.value = if (value) 1 else 0
                data.oracleDataType = SQLT_INT
            }
            is Byte -> {
                data.type = ParameterType.BYTE
                data.value = value
                data.oracleDataType = SQLT_INT
            }
            is Short -> {
                data.type = ParameterType.SHORT
                data.value = value
                data.oracleDataType = SQLT_INT
            }
            is Int -> {
                data.type = ParameterType.INT
                data.value = value
                data.oracleDataType = SQLT_INT
            }
            is Long -> {
                data.type = ParameterType.LONG
                data.value = value
                data.oracleDataType = SQLT_INT
            }
            is Float -> {
                data.type = ParameterType.FLOAT
                data.value = value
                data.oracleDataType = SQLT_BFLOAT
            }
            is Double -> {
                data.type = ParameterType.DOUBLE
                data.value = value
                data.oracleDataType = SQLT_BDOUBLE
            }
            is String -> {
                data.type = ParameterType.STRING
                data.value = value
                data.oracleDataType = SQLT_STR
            }
            is ByteArray -> {
                data.type = ParameterType.BYTE_ARRAY
                data.value = value
                data.oracleDataType = SQLT_BIN
            }
            is LocalDateTime -> {
                // Encode to Oracle's 7-byte DATE format
                data.type = ParameterType.BYTE_ARRAY
                data.encodedData = encodeOracleDateToByteArray(value)
                data.value = data.encodedData
                data.oracleDataType = SQLT_DAT
            }
            is LocalDate -> {
                data.type = ParameterType.BYTE_ARRAY
                data.encodedData = encodeOracleDateToByteArray(LocalDateTime(value, LocalTime(0, 0)))
                data.value = data.encodedData
                data.oracleDataType = SQLT_DAT
            }
            is LocalTime -> {
                val epochDate = LocalDate(1970, 1, 1)
                data.type = ParameterType.BYTE_ARRAY
                data.encodedData = encodeOracleDateToByteArray(LocalDateTime(epochDate, value))
                data.value = data.encodedData
                data.oracleDataType = SQLT_DAT
            }
            is Instant -> {
                val dateTime = value.toLocalDateTime(TimeZone.UTC)
                data.type = ParameterType.BYTE_ARRAY
                data.encodedData = encodeOracleDateToByteArray(dateTime)
                data.value = data.encodedData
                data.oracleDataType = SQLT_DAT
            }
            is BDN -> {
                // Use Oracle NUMBER format (SQLT_VNU) for full precision
                data.type = ParameterType.BYTE_ARRAY
                data.encodedData = encodeOracleNumber(value)
                data.value = data.encodedData
                data.oracleDataType = SQLT_VNU
            }
            is BIN -> {
                // Try Long if it fits
                if (value >= BIN.fromLong(Long.MIN_VALUE) && value <= BIN.fromLong(Long.MAX_VALUE)) {
                    data.type = ParameterType.LONG
                    data.value = value.longValue(false)
                    data.oracleDataType = SQLT_INT
                } else {
                    // Use Oracle NUMBER format (SQLT_VNU) for large integers
                    data.type = ParameterType.BYTE_ARRAY
                    data.encodedData = encodeOracleNumber(BDN.fromBigInteger(value))
                    data.value = data.encodedData
                    data.oracleDataType = SQLT_VNU
                }
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }
    }

    /**
     * Allocates a buffer for the IN parameter in memScoped.
     * Must be called within a memScoped block.
     */
    fun MemScope.allocateInParameter(data: InParameterData): AllocatedParameter {
        return allocateParameterBuffer(data)
    }

    /**
     * Helper to encode LocalDateTime to 7-byte Oracle DATE format as ByteArray.
     */
    private fun encodeOracleDateToByteArray(dateTime: LocalDateTime): ByteArray {
        val year = dateTime.year
        return byteArrayOf(
            ((year / 100) + 100).toByte(),
            ((year % 100) + 100).toByte(),
            dateTime.monthNumber.toByte(),
            dateTime.dayOfMonth.toByte(),
            (dateTime.hour + 1).toByte(),
            (dateTime.minute + 1).toByte(),
            (dateTime.second + 1).toByte()
        )
    }

    /**
     * Allocates buffer for an OUT parameter based on the expected type.
     * OUT parameters use heap allocation with copy-on-execute pattern.
     */
    fun allocateOutParameter(type: KClass<*>, data: OutParameterData): BindInfo {
        data.indicator = nativeHeap.alloc<ShortVar>()
        data.actualLength = nativeHeap.alloc<UShortVar>()

        return when (type) {
            String::class -> {
                data.stringBuffer = nativeHeap.allocArray<ByteVar>(4000)
                data.dataType = SQLT_STR
                data.bufferSize = 4000
                BindInfo(data.stringBuffer, SQLT_STR, 4000)
            }
            Int::class -> allocateIntBuffer(data)
            Long::class -> {
                data.longBuffer = nativeHeap.alloc<LongVar>()
                data.dataType = SQLT_INT
                data.bufferSize = sizeOf<LongVar>().toInt()
                BindInfo(data.longBuffer!!.ptr, SQLT_INT, sizeOf<LongVar>().toInt())
            }
            Double::class -> {
                data.doubleBuffer = nativeHeap.alloc<DoubleVar>()
                data.dataType = SQLT_BDOUBLE
                data.bufferSize = sizeOf<DoubleVar>().toInt()
                BindInfo(data.doubleBuffer!!.ptr, SQLT_BDOUBLE, sizeOf<DoubleVar>().toInt())
            }
            Float::class -> {
                data.floatBuffer = nativeHeap.alloc<FloatVar>()
                data.dataType = SQLT_BFLOAT
                data.bufferSize = sizeOf<FloatVar>().toInt()
                BindInfo(data.floatBuffer!!.ptr, SQLT_BFLOAT, sizeOf<FloatVar>().toInt())
            }
            Boolean::class -> allocateIntBuffer(data)
            ByteArray::class -> {
                data.byteArrayBuffer = nativeHeap.allocArray<ByteVar>(4000)
                data.dataType = SQLT_BIN
                data.bufferSize = 4000
                BindInfo(data.byteArrayBuffer, SQLT_BIN, 4000)
            }
            LocalDate::class, LocalDateTime::class, LocalTime::class, Instant::class -> {
                data.dateBuffer = nativeHeap.allocArray<ByteVar>(7)
                data.dataType = SQLT_DAT
                data.bufferSize = 7
                BindInfo(data.dateBuffer, SQLT_DAT, 7)
            }
            BDN::class, BIN::class -> {
                // Allocate buffer for Oracle NUMBER format (max 21 bytes)
                // Both BigDecimal and BigInteger use SQLT_VNU for full precision
                data.byteArrayBuffer = nativeHeap.allocArray<ByteVar>(21)
                data.dataType = SQLT_VNU
                data.bufferSize = 21
                BindInfo(data.byteArrayBuffer, SQLT_VNU, 21)
            }
            else -> throw SQLException("Unsupported OUT parameter type: $type")
        }
    }

    /**
     * Reads a value from an OUT parameter buffer.
     */
    fun readOutValue(type: KClass<*>, data: OutParameterData): Any? {
        // Check NULL
        if (data.indicator?.value?.toInt() == -1) {
            return null
        }

        return when (type) {
            String::class -> data.stringBuffer?.toKString()
            Int::class -> data.intBuffer?.value
            Long::class -> data.longBuffer?.value
            Double::class -> data.doubleBuffer?.value
            Float::class -> data.floatBuffer?.value
            Boolean::class -> data.intBuffer?.value != 0
            ByteArray::class -> {
                val length = data.actualLength?.value?.toInt() ?: 0
                if (length > 0 && data.byteArrayBuffer != null) {
                    ByteArray(length) { data.byteArrayBuffer!![it] }
                } else null
            }
            LocalDate::class -> data.dateBuffer?.let { decodeOracleDate(it).date }
            LocalDateTime::class -> data.dateBuffer?.let { decodeOracleDate(it) }
            LocalTime::class -> data.dateBuffer?.let { decodeOracleDate(it).time }
            Instant::class -> data.dateBuffer?.let { decodeOracleDate(it).toInstant(TimeZone.UTC) }
            BDN::class -> {
                val length = data.actualLength?.value?.toInt() ?: 0
                if (length > 0 && data.byteArrayBuffer != null) {
                    decodeOracleNumber(data.byteArrayBuffer!!, length)
                } else null
            }
            BIN::class -> {
                val length = data.actualLength?.value?.toInt() ?: 0
                if (length > 0 && data.byteArrayBuffer != null) {
                    val bigDecimal = decodeOracleNumber(data.byteArrayBuffer!!, length)
                    bigDecimal.toBigInteger()
                } else null
            }
            else -> throw SQLException("Unsupported OUT parameter type: $type")
        }
    }

    /**
     * Allocates an Int buffer for OUT parameters (used for both Int and Boolean types).
     */
    private fun allocateIntBuffer(data: OutParameterData): BindInfo {
        data.intBuffer = nativeHeap.alloc<IntVar>()
        data.dataType = SQLT_INT
        data.bufferSize = sizeOf<IntVar>().toInt()
        return BindInfo(data.intBuffer!!.ptr, SQLT_INT, sizeOf<IntVar>().toInt())
    }

    /**
     * Encodes a BigDecimal to Oracle's NUMBER format (SQLT_VNU).
     *
     * Oracle NUMBER format:
     * - Byte 0: Sign + Exponent
     *   - Positive: (exponent + 65) | 0x80, where exponent is power of 100
     *   - Negative: 62 - exponent, where exponent is power of 100
     * - Bytes 1-20: Mantissa in base-100
     *   - Positive: digit + 1 (values 1-100)
     *   - Negative: 101 - digit (values 1-100)
     * - Last byte (negative only): 102 (0x66) terminator
     * - Special: Zero = [128]
     *
     * Max size: 21 bytes (1 exponent + 20 mantissa), supporting 38 digits precision
     */
    private fun encodeOracleNumber(value: BDN): ByteArray {
        // Handle zero
        if (value == BDN.ZERO) {
            return byteArrayOf(128.toByte())
        }

        val isNegative = value < BDN.ZERO
        val absValue = if (isNegative) value.negate() else value

        // Get string representation and split into parts
        val str = absValue.toStringExpanded()
        val parts = str.split('.')
        val integerPart = parts[0]
        val fractionalPart = if (parts.size > 1) parts[1] else ""

        // Calculate exponent based on Oracle NUMBER specification
        // Exponent = number of base-100 digit pairs to the left of decimal point - 1
        val significantInteger = integerPart.trimStart('0')
        val exponent = if (significantInteger.isNotEmpty()) {
            // For numbers >= 1: exponent = (length + 1) / 2 - 1
            (significantInteger.length + 1) / 2 - 1
        } else {
            // For numbers < 1: find first non-zero in fractional part
            val firstNonZeroFrac = fractionalPart.indexOfFirst { it != '0' }
            if (firstNonZeroFrac == -1) {
                return byteArrayOf(128.toByte())  // All zeros
            }
            // Exponent is negative: -(position_of_first_nonzero + 2) / 2
            -((firstNonZeroFrac + 2) / 2)
        }

        // Combine all significant digits (no decimal point, no leading zeros)
        val allDigits = if (significantInteger.isEmpty()) {
            fractionalPart.trimStart('0')
        } else {
            significantInteger + fractionalPart
        }

        if (allDigits.isEmpty() || allDigits.all { it == '0' }) {
            return byteArrayOf(128.toByte())
        }

        // Prepare result buffer (max 21 bytes)
        val result = mutableListOf<Byte>()

        // Encode exponent byte
        val expByte = if (isNegative) {
            (62 - exponent).toByte()
        } else {
            ((exponent + 65) or 0x80).toByte()
        }
        result.add(expByte)

        // Take first 38 significant digits (Oracle max precision)
        val significantDigits = allDigits.take(38)

        // Pad to even length for base-100 encoding
        val paddedDigits = if (significantDigits.length % 2 == 1) {
            significantDigits + "0"
        } else {
            significantDigits
        }

        // Encode mantissa bytes (2 digits = 1 base-100 byte)
        for (i in paddedDigits.indices step 2) {
            val digit1 = paddedDigits[i].digitToInt()
            val digit2 = paddedDigits[i + 1].digitToInt()
            val base100Digit = digit1 * 10 + digit2

            val mantissaByte = if (isNegative) {
                (101 - base100Digit).toByte()
            } else {
                (base100Digit + 1).toByte()
            }
            result.add(mantissaByte)
        }

        // Add terminator for negative numbers
        if (isNegative) {
            result.add(102.toByte())
        }

        return result.toByteArray()
    }

    /**
     * Decodes Oracle's NUMBER format (SQLT_VNU) to BigDecimal.
     */
    private fun decodeOracleNumber(buffer: CPointer<ByteVar>, length: Int): BDN {
        if (length == 0) {
            return BDN.ZERO
        }

        val firstByte = buffer[0].toUByte().toInt()

        // Handle special case: zero
        if (firstByte == 128) {
            return BDN.ZERO
        }

        // Determine sign
        val isNegative = firstByte < 128

        // Decode exponent
        val exponent = if (isNegative) {
            (62 - firstByte) * 2 + 2
        } else {
            (firstByte - 193) * 2 + 2
        }

        // Decode mantissa
        val mantissaLength = if (isNegative && length > 1 && buffer[length - 1].toUByte().toInt() == 102) {
            length - 2  // Exclude exponent byte and terminator
        } else {
            length - 1  // Exclude only exponent byte
        }

        val digits = StringBuilder()
        for (i in 1..mantissaLength) {
            val byte = buffer[i].toUByte().toInt()
            val base100Digit = if (isNegative) {
                101 - byte
            } else {
                byte - 1
            }

            // Convert base-100 digit to 2 decimal digits
            digits.append(base100Digit.toString().padStart(2, '0'))
        }

        // Build the number string with decimal point
        val digitStr = digits.toString().trimStart('0')
        if (digitStr.isEmpty()) {
            return BDN.ZERO
        }

        // Calculate where to place decimal point based on exponent
        val decimalString = if (exponent >= digitStr.length) {
            digitStr + "0".repeat(exponent - digitStr.length)
        } else if (exponent > 0) {
            digitStr.substring(0, exponent) + "." + digitStr.substring(exponent)
        } else {
            "0." + "0".repeat(-exponent) + digitStr
        }

        val result = BDN.parseString(decimalString)
        return if (isNegative) result.negate() else result
    }

    /**
     * Decodes Oracle's 7-byte DATE format to LocalDateTime.
     * Made internal so it can be shared with OracleResultSet.
     */
    internal fun decodeOracleDate(buffer: CPointer<ByteVar>): LocalDateTime {
        val century = buffer[0].toInt() - 100
        val year = buffer[1].toInt() - 100
        val fullYear = century * 100 + year
        val month = buffer[2].toInt()
        val day = buffer[3].toInt()
        val hour = buffer[4].toInt() - 1
        val minute = buffer[5].toInt() - 1
        val second = buffer[6].toInt() - 1

        return LocalDateTime(fullYear, month, day, hour, minute, second)
    }

    /**
     * Binding information returned by allocate methods.
     */
    data class BindInfo(
        val buffer: COpaquePointer?,
        val dataType: UShort,
        val bufferSize: Int
    )
}
