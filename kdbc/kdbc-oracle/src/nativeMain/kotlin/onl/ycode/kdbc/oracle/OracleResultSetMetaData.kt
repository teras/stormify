package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.ResultSetMetaData
import onl.ycode.kdbc.SQLException

/**
 * Oracle ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleResultSetMetaData(
    private val stmtHandle: OCIStmtPtr,
    private val errorHandle: OCIErrorPtr,
    override val columnCount: Int
) : ResultSetMetaData {

    override fun getColumnName(column: Int): String {
        if (column < 1 || column > columnCount) {
            throw SQLException("Invalid column index: $column")
        }

        memScoped {
            // Get parameter descriptor
            val paramPtr = alloc<CPointerVar<out CPointed>>()
            oci_param_get(
                stmtHandle,
                OCI_HTYPE_STMT.toUInt(),
                errorHandle.reinterpret(),
                paramPtr.ptr,
                column.toUInt()
            )

            val param = paramPtr.value ?: throw SQLException("Failed to get parameter")

            // Get column name
            val namePtr = alloc<CPointerVar<ByteVar>>()
            val nameLen = alloc<UIntVar>()

            oci_attr_get(
                param,
                OCI_DTYPE_PARAM.toUInt(),
                namePtr.ptr,
                nameLen.ptr,
                OCI_ATTR_NAME.toUInt(),
                errorHandle.reinterpret()
            )

            return namePtr.value?.toKString() ?: "Column_$column"
        }
    }
}
