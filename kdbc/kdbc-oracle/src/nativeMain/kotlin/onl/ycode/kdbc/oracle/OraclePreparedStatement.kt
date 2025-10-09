package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.*

/**
 * Oracle PreparedStatement implementation using ODPI-C.
 * Much simpler than raw OCI - uses dpiVar for binding and dpiData for values.
 */
@OptIn(ExperimentalForeignApi::class)
class OraclePreparedStatement(
    private val connection: OracleConnection,
    private val connPtr: CPointer<dpiConn>,
    private val context: CPointer<dpiContext>,
    private val sql: String,
    private val returnGeneratedKeys: Boolean = false
) : PreparedStatement {

    private val stmt: CPointer<dpiStmt>
    private val parameters = mutableMapOf<Int, Pair<CPointer<dpiVar>, Any?>>()

    init {
        memScoped {
            val stmtPtr = alloc<CPointerVar<dpiStmt>>()
            val result = dpiConn_prepareStmt(
                connPtr,
                0,  // scrollable = false
                sql,
                sql.length.toUInt(),
                null,  // tag
                0u,    // tagLength
                stmtPtr.ptr
            )

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to prepare statement: ${connection.getLastError()}")
            }

            stmt = stmtPtr.value ?: throw SQLException("Statement pointer is null")
        }
    }

    override fun setObject(parameterIndex: Int, value: Any?) {
        val (oracleType, nativeType) = OracleParameterHelper.getTypesForValue(value)

        memScoped {
            val varPtr = alloc<CPointerVar<dpiVar>>()

            // Create variable for binding
            val result = dpiConn_newVar(
                connPtr,
                oracleType,
                nativeType,
                1u,  // maxArraySize = 1
                0u,  // size (0 for non-string types)
                0,   // sizeIsBytes
                0,   // isArray
                null,  // objType
                varPtr.ptr,
                null   // data pointer (we'll get it separately)
            )

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to create variable for parameter $parameterIndex: ${connection.getLastError()}")
            }

            val dpiVar = varPtr.value ?: throw SQLException("Variable pointer is null")

            // Get data pointer and set value
            val dataPtr = alloc<CPointerVar<dpiData>>()
            val numElementsPtr = alloc<UIntVar>()
            dpiVar_getReturnedData(dpiVar, 0u, numElementsPtr.ptr, dataPtr.ptr)
            val data = dataPtr.value ?: throw SQLException("Data pointer is null")

            OracleParameterHelper.setDpiDataValue(data, value, oracleType, nativeType)

            // Bind variable to statement
            val bindResult = dpiStmt_bindByPos(stmt, parameterIndex.toUInt(), dpiVar)
            if (bindResult != DPI_SUCCESS) {
                dpiVar_release(dpiVar)
                throw SQLException("Failed to bind parameter $parameterIndex: ${connection.getLastError()}")
            }

            // Store the variable (will be released in close())
            parameters[parameterIndex] = dpiVar to value
        }
    }

    override fun executeUpdate(): Int {
        val mode = if (connection.isAutoCommit()) {
            DPI_MODE_EXEC_COMMIT_ON_SUCCESS
        } else {
            DPI_MODE_EXEC_DEFAULT
        }

        memScoped {
            val numQueryColumnsPtr = alloc<UIntVar>()
            val result = dpiStmt_execute(stmt, mode, numQueryColumnsPtr.ptr)

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to execute update: ${connection.getLastError()}")
            }

            // Get row count
            val rowCountPtr = alloc<ULongVar>()
            dpiStmt_getRowCount(stmt, rowCountPtr.ptr)
            return rowCountPtr.value.toInt()
        }
    }

    override fun executeQuery(): ResultSet {
        val mode = if (connection.isAutoCommit()) {
            DPI_MODE_EXEC_COMMIT_ON_SUCCESS
        } else {
            DPI_MODE_EXEC_DEFAULT
        }

        memScoped {
            val numQueryColumnsPtr = alloc<UIntVar>()
            val result = dpiStmt_execute(stmt, mode, numQueryColumnsPtr.ptr)

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to execute query: ${connection.getLastError()}")
            }
        }

        return OracleResultSet(stmt, context)
    }

    override fun getGeneratedKeys(): ResultSet {
        // Oracle uses sequences for primary keys
        // Stormify handles this via sequence.CURRVAL queries
        return EmptyResultSet()
    }

    override fun close() {
        // Release all bound variables
        parameters.values.forEach { (dpiVar, _) ->
            dpiVar_release(dpiVar)
        }
        parameters.clear()

        // Release statement
        dpiStmt_close(stmt, null, 0u)
        dpiStmt_release(stmt)
    }
}
