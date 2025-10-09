package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.*
import kotlin.reflect.KClass

/**
 * Oracle ResultSet implementation using ODPI-C.
 * ODPI-C handles all column definition automatically.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleResultSet(
    private val stmt: CPointer<dpiStmt>,
    private val context: CPointer<dpiContext>
) : ResultSet {

    private var hasRow = false
    private val columnCount: UInt
    private val queryInfo = mutableListOf<dpiQueryInfo>()
    private var currentRow: COpaquePointer? = null

    init {
        memScoped {
            val countPtr = alloc<UIntVar>()
            dpiStmt_getNumQueryColumns(stmt, countPtr.ptr)
            columnCount = countPtr.value
        }

        // Get query info for all columns
        memScoped {
            for (i in 1u..columnCount) {
                val info = alloc<dpiQueryInfo>()
                dpiStmt_getQueryInfo(stmt, i, info.ptr)
                queryInfo.add(info)
            }
        }
    }

    override fun next(): Boolean {
        memScoped {
            val foundPtr = alloc<IntVar>()
            val bufferRowIndexPtr = alloc<UIntVar>()
            val rowDataPtr = alloc<CPointerVar<dpiData>>()

            val result = dpiStmt_fetch(stmt, foundPtr.ptr, bufferRowIndexPtr.ptr)

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to fetch row")
            }

            hasRow = foundPtr.value != 0

            if (hasRow) {
                // Get row data
                dpiStmt_getQueryValue(stmt, 1u, null, rowDataPtr.ptr)
                currentRow = rowDataPtr.value
            }

            return hasRow
        }
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!hasRow) {
            throw SQLException("No current row")
        }

        if (columnIndex < 1 || columnIndex > columnCount.toInt()) {
            throw SQLException("Invalid column index: $columnIndex")
        }

        memScoped {
            val nativeTypePtr = alloc<UIntVar>()
            val dataPtr = alloc<CPointerVar<dpiData>>()

            val result = dpiStmt_getQueryValue(stmt, columnIndex.toUInt(), nativeTypePtr.ptr, dataPtr.ptr)

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to get column value")
            }

            val data = dataPtr.value ?: return null
            val nativeType = nativeTypePtr.value

            return OracleParameterHelper.getDpiDataValue(data, type, nativeType)
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return OracleResultSetMetaData(stmt, columnCount.toInt(), queryInfo)
    }

    override fun close() {
        // Statement will be closed by PreparedStatement
        // Nothing to clean up here
    }
}
