package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import kotlinx.datetime.*
import oci.*
import onl.ycode.kdbc.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

/**
 * Oracle PreparedStatement implementation using OCI.
 *
 * Type binding strategy (uses native binary types where practical):
 * - Int, Long, Boolean: SQLT_INT (native 4/8-byte integer)
 * - Double: SQLT_BDOUBLE (native 8-byte binary double)
 * - Float: SQLT_BFLOAT (native 4-byte binary float)
 * - ByteArray: SQLT_BIN (binary RAW)
 * - String: SQLT_STR (null-terminated string)
 * - LocalDate: SQLT_DAT (7-byte Oracle DATE format)
 * - LocalDateTime: SQLT_STR (ISO 8601 string - SQLT_TIMESTAMP requires descriptor management)
 * - BigDecimal: SQLT_BDOUBLE if no precision loss, else SQLT_STR
 * - BigInteger: SQLT_INT (as Long) if fits, else SQLT_STR
 */
@OptIn(ExperimentalForeignApi::class)
class OraclePreparedStatement(
    private val serviceContext: OCISvcCtxPtr,
    private val errorHandle: OCIErrorPtr,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {

    private val stmtHandle: OCIStmtPtr
    private val bindHandles = mutableListOf<OCIBindPtr?>()
    private val paramData = mutableListOf<ParamData>()

    init {
        memScoped {
            val stmtPtr = alloc<CPointerVar<out CPointed>>()

            // Prepare statement
            val result = oci_stmt_prepare2(
                serviceContext.reinterpret(),
                stmtPtr.ptr.reinterpret(),
                errorHandle.reinterpret(),
                sql.cstr.ptr.reinterpret(),
                sql.length.toUInt(),
                null,
                0u,
                OCI_NTV_SYNTAX.toUInt(),
                OCI_DEFAULT.toUInt()
            )

            if (result != OCI_SUCCESS.toInt()) {
                throw SQLException("Failed to prepare statement: ${getOciError(errorHandle)}")
            }

            stmtHandle = stmtPtr.value ?: throw SQLException("Statement handle is null")
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        // Ensure we have enough parameter slots
        while (paramData.size < parameterIndex) {
            paramData.add(ParamData())
        }

        val index = parameterIndex - 1
        val data = paramData[index]
        data.clear()

        when (value) {
            null -> {
                data.isNull = true
                bindParameter(parameterIndex, null, 0, SQLT_STR)
            }
            is String -> {
                val bytes = value.encodeToByteArray()
                data.byteArray = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
                bytes.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                data.byteArray!![bytes.size] = 0  // Null terminator
                data.length = bytes.size
                bindParameter(parameterIndex, data.byteArray, bytes.size + 1, SQLT_STR)
            }
            is Int -> {
                data.intValue = nativeHeap.alloc<IntVar>().apply { this.value = value }
                bindParameter(parameterIndex, data.intValue?.ptr, sizeOf<IntVar>().toInt(), SQLT_INT)
            }
            is Long -> {
                data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = value }
                bindParameter(parameterIndex, data.longValue?.ptr, sizeOf<LongVar>().toInt(), SQLT_INT)
            }
            is Double -> {
                data.doubleValue = nativeHeap.alloc<DoubleVar>().apply { this.value = value }
                bindParameter(parameterIndex, data.doubleValue?.ptr, sizeOf<DoubleVar>().toInt(), SQLT_BDOUBLE)
            }
            is Float -> {
                data.floatValue = nativeHeap.alloc<FloatVar>().apply { this.value = value }
                bindParameter(parameterIndex, data.floatValue?.ptr, sizeOf<FloatVar>().toInt(), SQLT_BFLOAT)
            }
            is Boolean -> {
                val intVal = if (value) 1 else 0
                data.intValue = nativeHeap.alloc<IntVar>().apply { this.value = intVal }
                bindParameter(parameterIndex, data.intValue?.ptr, sizeOf<IntVar>().toInt(), SQLT_INT)
            }
            is ByteArray -> {
                data.byteArray = nativeHeap.allocArray<ByteVar>(value.size)
                value.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                data.length = value.size
                bindParameter(parameterIndex, data.byteArray, value.size, SQLT_BIN)
            }
            is LocalDateTime -> {
                // Use ISO 8601 string format (SQLT_TIMESTAMP descriptors are complex to manage)
                val str = value.toString().replace('T', ' ')  // Oracle prefers space separator
                val bytes = str.encodeToByteArray()
                data.byteArray = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
                bytes.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                data.byteArray!![bytes.size] = 0  // Null terminator
                data.length = bytes.size
                bindParameter(parameterIndex, data.byteArray, bytes.size + 1, SQLT_STR)
            }
            is LocalDate -> {
                // Use Oracle DATE format: 7 bytes (century, year, month, day, hour, min, sec)
                data.byteArray = nativeHeap.allocArray<ByteVar>(7)
                val year = value.year
                val buffer = data.byteArray!!
                buffer[0] = ((year / 100) + 100).toByte()  // Century
                buffer[1] = ((year % 100) + 100).toByte()  // Year
                buffer[2] = value.monthNumber.toByte()
                buffer[3] = value.dayOfMonth.toByte()
                buffer[4] = 1  // Hour (1-based, so 1 = midnight)
                buffer[5] = 1  // Minute
                buffer[6] = 1  // Second
                data.length = 7
                bindParameter(parameterIndex, data.byteArray, 7, SQLT_DAT)
            }
            is BDN -> {
                // Try to convert to Double if it fits without precision loss
                val doubleValue = value.doubleValue(false)
                if (doubleValue.isFinite() && BDN.parseString(doubleValue.toString()) == value) {
                    data.doubleValue = nativeHeap.alloc<DoubleVar>().apply { this.value = doubleValue }
                    bindParameter(parameterIndex, data.doubleValue?.ptr, sizeOf<DoubleVar>().toInt(), SQLT_BDOUBLE)
                } else {
                    // Fall back to string for precision
                    val str = value.toString()
                    val bytes = str.encodeToByteArray()
                    data.byteArray = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
                    bytes.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                    data.byteArray!![bytes.size] = 0
                    data.length = bytes.size
                    bindParameter(parameterIndex, data.byteArray, bytes.size + 1, SQLT_STR)
                }
            }
            is BIN -> {
                // Try to convert to Long if it fits
                val longValue = value.longValue(false)
                if (BIN.parseString(longValue.toString()) == value) {
                    data.longValue = nativeHeap.alloc<LongVar>().apply { this.value = longValue }
                    bindParameter(parameterIndex, data.longValue?.ptr, sizeOf<LongVar>().toInt(), SQLT_INT)
                } else {
                    // Fall back to string for large numbers
                    val str = value.toString()
                    val bytes = str.encodeToByteArray()
                    data.byteArray = nativeHeap.allocArray<ByteVar>(bytes.size + 1)
                    bytes.forEachIndexed { i, byte -> data.byteArray!![i] = byte }
                    data.byteArray!![bytes.size] = 0
                    data.length = bytes.size
                    bindParameter(parameterIndex, data.byteArray, bytes.size + 1, SQLT_STR)
                }
            }
            else -> throw SQLException("Unsupported parameter type: ${value::class}")
        }
    }

    private fun bindParameter(position: Int, valuep: COpaquePointer?, valueSize: Int, dataType: UShort) {
        memScoped {
            val bindPtr = alloc<CPointerVar<out CPointed>>()

            val result = oci_bind_by_pos(
                stmtHandle.reinterpret(),
                bindPtr.ptr.reinterpret(),
                errorHandle.reinterpret(),
                position.toUInt(),
                valuep,
                valueSize,
                dataType,
                null, // indicator
                null, // actual length
                null, // return code
                0u,   // max array length
                null, // current element
                OCI_DEFAULT.toUInt()
            )

            if (result != OCI_SUCCESS.toInt()) {
                throw SQLException("Failed to bind parameter $position: ${getOciError(errorHandle)}")
            }

            // Ensure we have enough bind handle slots
            while (bindHandles.size < position) {
                bindHandles.add(null)
            }
            bindHandles[position - 1] = bindPtr.value
        }
    }

    override fun executeUpdate(): Int {
        val result = oci_stmt_execute(
            serviceContext.reinterpret(),
            stmtHandle.reinterpret(),
            errorHandle.reinterpret(),
            1u, // iters
            0u, // rowoff
            null, // snap_in
            null, // snap_out
            OCI_DEFAULT.toUInt()
        )

        if (result != OCI_SUCCESS.toInt() && result != OCI_SUCCESS_WITH_INFO.toInt()) {
            throw SQLException("Failed to execute update: ${getOciError(errorHandle)}")
        }

        // Get row count
        memScoped {
            val rowCount = alloc<UIntVar>()
            oci_attr_get(
                stmtHandle,
                OCI_HTYPE_STMT.toUInt(),
                rowCount.ptr,
                null,
                OCI_ATTR_ROW_COUNT.toUInt(),
                errorHandle.reinterpret()
            )
            return rowCount.value.toInt()
        }
    }

    override fun executeQuery(): ResultSet {
        val result = oci_stmt_execute(
            serviceContext.reinterpret(),
            stmtHandle.reinterpret(),
            errorHandle.reinterpret(),
            0u, // Don't fetch rows yet
            0u,
            null,
            null,
            OCI_DEFAULT.toUInt()
        )

        if (result != OCI_SUCCESS.toInt() && result != OCI_SUCCESS_WITH_INFO.toInt()) {
            throw SQLException("Failed to execute query: ${getOciError(errorHandle)}")
        }

        return OracleResultSet(stmtHandle, errorHandle)
    }

    override fun getGeneratedKeys(): ResultSet {
        // Oracle uses RETURNING clause for generated keys
        // This is a placeholder
        return EmptyResultSet()
    }

    override fun close() {
        // Free parameter data
        paramData.forEach { it.clear() }

        // Release statement
        oci_stmt_release(
            stmtHandle.reinterpret(),
            errorHandle.reinterpret(),
            null,
            0u,
            OCI_DEFAULT.toUInt()
        )
    }
}

/**
 * Data holder for bind parameters.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ParamData {
    var intValue: IntVar? = null
    var longValue: LongVar? = null
    var doubleValue: DoubleVar? = null
    var floatValue: FloatVar? = null
    var byteArray: CPointer<ByteVar>? = null
    var length: Int = 0
    var isNull: Boolean = false

    fun clear() {
        intValue?.let { nativeHeap.free(it) }
        longValue?.let { nativeHeap.free(it) }
        doubleValue?.let { nativeHeap.free(it) }
        floatValue?.let { nativeHeap.free(it) }
        byteArray?.let { nativeHeap.free(it) }

        intValue = null
        longValue = null
        doubleValue = null
        floatValue = null
        byteArray = null
        length = 0
        isNull = false
    }
}
