package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import kotlinx.datetime.*
import oci.*
import onl.ycode.kdbc.*
import kotlin.reflect.KClass
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN

/**
 * Oracle ResultSet implementation using OCI.
 *
 * Uses native type definitions matching the column types:
 * - SQLT_INT for integers
 * - SQLT_BDOUBLE for floats/doubles
 * - SQLT_DAT for dates
 * - SQLT_STR for strings and timestamps
 */
@OptIn(ExperimentalForeignApi::class)
class OracleResultSet(
    private val stmtHandle: OCIStmtPtr,
    private val errorHandle: OCIErrorPtr
) : ResultSet {

    private var hasRow = false
    private val columnCount: Int
    private val defineHandles = mutableListOf<OCIDefinePtr?>()
    private val columnData = mutableListOf<ColumnData>()
    private val columnTypes = mutableListOf<UShort>()

    init {
        // Get column count
        memScoped {
            val paramCount = alloc<UIntVar>()
            oci_attr_get(
                stmtHandle,
                OCI_HTYPE_STMT,
                paramCount.ptr,
                null,
                OCI_ATTR_PARAM_COUNT,
                errorHandle.reinterpret()
            )
            columnCount = paramCount.value.toInt()
        }

        // Define output columns with exception safety
        try {
            for (i in 1..columnCount) {
            memScoped {
                // Get parameter descriptor
                val paramPtr = alloc<CPointerVar<out CPointed>>()
                oci_param_get(
                    stmtHandle,
                    OCI_HTYPE_STMT,
                    errorHandle.reinterpret(),
                    paramPtr.ptr,
                    i.toUInt()
                )

                val param = paramPtr.value ?: throw SQLException("Failed to get parameter descriptor for column $i")

                // Get data type
                val dataType = alloc<UShortVar>()
                oci_attr_get(
                    param,
                    OCI_DTYPE_PARAM,
                    dataType.ptr,
                    null,
                    OCI_ATTR_DATA_TYPE,
                    errorHandle.reinterpret()
                )

                columnTypes.add(dataType.value)

                // Create column data with appropriate type
                val colData = when (dataType.value) {
                    SQLT_INT, SQLT_NUM -> {
                        // Use native integer or double based on scale
                        val scale = alloc<ShortVar>()
                        oci_attr_get(
                            param,
                            OCI_DTYPE_PARAM,
                            scale.ptr,
                            null,
                            OCI_ATTR_SCALE,
                            errorHandle.reinterpret()
                        )

                        if (scale.value == 0.toShort()) {
                            // Integer type
                            ColumnData(SQLT_INT)
                        } else {
                            // Decimal/float type
                            ColumnData(SQLT_BDOUBLE)
                        }
                    }
                    SQLT_FLT, SQLT_BFLOAT, SQLT_BDOUBLE -> ColumnData(SQLT_BDOUBLE)
                    SQLT_DAT, SQLT_DATE -> ColumnData(SQLT_DAT)
                    SQLT_TIMESTAMP, SQLT_TIMESTAMP_TZ -> ColumnData(SQLT_STR)  // Use string for timestamps
                    else -> ColumnData(SQLT_STR)  // Default to string
                }

                columnData.add(colData)

                // Define the column
                val definePtr = alloc<CPointerVar<out CPointed>>()
                val defineResult = when (colData.dataType) {
                    SQLT_INT -> {
                        oci_define_by_pos(
                            stmtHandle.reinterpret(),
                            definePtr.ptr.reinterpret(),
                            errorHandle.reinterpret(),
                            i.toUInt(),
                            colData.longBuffer?.ptr,
                            sizeOf<LongVar>().toInt(),
                            SQLT_INT,
                            colData.indicator.ptr,
                            null,
                            null,
                            OCI_DEFAULT
                        )
                    }
                    SQLT_BDOUBLE -> {
                        oci_define_by_pos(
                            stmtHandle.reinterpret(),
                            definePtr.ptr.reinterpret(),
                            errorHandle.reinterpret(),
                            i.toUInt(),
                            colData.doubleBuffer?.ptr,
                            sizeOf<DoubleVar>().toInt(),
                            SQLT_BDOUBLE,
                            colData.indicator.ptr,
                            null,
                            null,
                            OCI_DEFAULT
                        )
                    }
                    SQLT_DAT -> {
                        oci_define_by_pos(
                            stmtHandle.reinterpret(),
                            definePtr.ptr.reinterpret(),
                            errorHandle.reinterpret(),
                            i.toUInt(),
                            colData.dateBuffer,
                            7,
                            SQLT_DAT,
                            colData.indicator.ptr,
                            null,
                            null,
                            OCI_DEFAULT
                        )
                    }
                    else -> {  // SQLT_STR
                        oci_define_by_pos(
                            stmtHandle.reinterpret(),
                            definePtr.ptr.reinterpret(),
                            errorHandle.reinterpret(),
                            i.toUInt(),
                            colData.stringBuffer,
                            4000,
                            SQLT_STR,
                            colData.indicator.ptr,
                            colData.actualLength.ptr,
                            null,
                            OCI_DEFAULT
                        )
                    }
                }

                if (defineResult != OCI_SUCCESS) {
                    throw SQLException("Failed to define column $i: ${getOciError(errorHandle)}")
                }

                defineHandles.add(definePtr.value)
            }
            }
        } catch (e: Exception) {
            // Clean up any allocated column data on exception
            columnData.forEach { it.free() }
            columnData.clear()
            throw e
        }
    }

    override fun next(): Boolean {
        val result = oci_stmt_fetch2(
            stmtHandle.reinterpret(),
            errorHandle.reinterpret(),
            1u,
            OCI_FETCH_NEXT,
            0,
            OCI_DEFAULT
        )

        hasRow = when (result) {
            OCI_SUCCESS -> true
            OCI_NO_DATA -> false
            else -> {
                throw SQLException("Failed to fetch row: ${getOciError(errorHandle)}")
            }
        }

        return hasRow
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!hasRow) {
            throw SQLException("No current row")
        }

        if (columnIndex < 1 || columnIndex > columnCount) {
            throw SQLException("Invalid column index: $columnIndex")
        }

        val index = columnIndex - 1
        val colData = columnData[index]

        // Check for NULL
        if (colData.indicator.value.toInt() == -1) {
            return null
        }

        // Return value based on defined type
        return when (colData.dataType) {
            SQLT_INT -> {
                val longValue = colData.longBuffer?.value ?: 0L
                when (type) {
                    Int::class -> longValue.toInt()
                    Long::class -> longValue
                    Boolean::class -> longValue != 0L
                    String::class -> longValue.toString()
                    BIN::class -> BIN.parseString(longValue.toString())
                    else -> longValue
                }
            }
            SQLT_BDOUBLE -> {
                val doubleValue = colData.doubleBuffer?.value ?: 0.0
                when (type) {
                    Double::class -> doubleValue
                    Float::class -> doubleValue.toFloat()
                    Int::class -> doubleValue.toInt()
                    Long::class -> doubleValue.toLong()
                    String::class -> doubleValue.toString()
                    BDN::class -> BDN.parseString(doubleValue.toString())
                    else -> doubleValue
                }
            }
            SQLT_DAT -> {
                val buffer = colData.dateBuffer ?: return null
                val dateTime = OracleParameterHelper.decodeOracleDate(buffer)

                when (type) {
                    LocalDate::class -> dateTime.date
                    LocalDateTime::class -> dateTime
                    LocalTime::class -> dateTime.time
                    Instant::class -> dateTime.toInstant(TimeZone.UTC)
                    String::class -> dateTime.date.toString()
                    else -> dateTime.date
                }
            }
            else -> {  // SQLT_STR
                val stringValue = colData.stringBuffer?.toKString() ?: ""
                when (type) {
                    String::class -> stringValue
                    Int::class -> stringValue.toIntOrNull()
                    Long::class -> stringValue.toLongOrNull()
                    Double::class -> stringValue.toDoubleOrNull()
                    Float::class -> stringValue.toFloatOrNull()
                    Boolean::class -> stringValue.toIntOrNull() != 0
                    ByteArray::class -> stringValue.encodeToByteArray()
                    LocalDateTime::class -> {
                        try {
                            LocalDateTime.parse(stringValue.replace(' ', 'T'))
                        } catch (e: Exception) {
                            null
                        }
                    }
                    LocalDate::class -> {
                        try {
                            LocalDate.parse(stringValue)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    LocalTime::class -> {
                        try {
                            LocalTime.parse(stringValue)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    Instant::class -> {
                        try {
                            Instant.parse(stringValue)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    BDN::class -> {
                        try {
                            BDN.parseString(stringValue)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    BIN::class -> {
                        try {
                            BIN.parseString(stringValue)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    else -> stringValue
                }
            }
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return OracleResultSetMetaData(stmtHandle, errorHandle, columnCount)
    }

    override fun close() {
        // Column data will be freed when statement is released
        columnData.forEach { it.free() }
    }
}

/**
 * Data holder for result columns with type-specific buffers.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ColumnData(val dataType: UShort) {
    val indicator = nativeHeap.alloc<ShortVar>()
    val actualLength = nativeHeap.alloc<UShortVar>()

    // Type-specific buffers
    val stringBuffer: CPointer<ByteVar>? = if (dataType == SQLT_STR) nativeHeap.allocArray<ByteVar>(4000) else null
    val longBuffer: LongVar? = if (dataType == SQLT_INT) nativeHeap.alloc<LongVar>() else null
    val doubleBuffer: DoubleVar? = if (dataType == SQLT_BDOUBLE) nativeHeap.alloc<DoubleVar>() else null
    val dateBuffer: CPointer<ByteVar>? = if (dataType == SQLT_DAT) nativeHeap.allocArray<ByteVar>(7) else null

    fun free() {
        stringBuffer?.let { nativeHeap.free(it) }
        longBuffer?.let { nativeHeap.free(it) }
        doubleBuffer?.let { nativeHeap.free(it) }
        dateBuffer?.let { nativeHeap.free(it) }
        nativeHeap.free(indicator)
        nativeHeap.free(actualLength)
    }
}
