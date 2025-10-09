package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.*
import kotlin.reflect.KClass

/**
 * Oracle CallableStatement implementation using ODPI-C.
 * Handles both IN and OUT parameters cleanly with dpiVar.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleCallableStatement(
    private val connection: OracleConnection,
    private val connPtr: CPointer<dpiConn>,
    private val context: CPointer<dpiContext>,
    private val sql: String
) : CallableStatement {

    private val stmt: CPointer<dpiStmt>
    private val parameters = mutableMapOf<Int, CPointer<dpiVar>>()
    private val outParameterTypes = mutableMapOf<Int, Pair<KClass<*>, UInt>>()  // type and nativeType
    private var executed = false

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
                throw SQLException("Failed to prepare callable statement: ${connection.getLastError()}")
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
                0u,  // size
                0,   // sizeIsBytes
                0,   // isArray
                null,  // objType
                varPtr.ptr,
                null   // data pointer
            )

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to create variable for IN parameter $parameterIndex: ${connection.getLastError()}")
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
                throw SQLException("Failed to bind IN parameter $parameterIndex: ${connection.getLastError()}")
            }

            // Store the variable
            parameters[parameterIndex]?.let { dpiVar_release(it) }
            parameters[parameterIndex] = dpiVar
        }
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        val (oracleType, nativeType) = OracleParameterHelper.getTypesForClass(type)

        memScoped {
            val varPtr = alloc<CPointerVar<dpiVar>>()

            // Create variable for OUT parameter
            val result = dpiConn_newVar(
                connPtr,
                oracleType,
                nativeType,
                1u,  // maxArraySize = 1
                0u,  // size
                0,   // sizeIsBytes
                0,   // isArray
                null,  // objType
                varPtr.ptr,
                null   // data pointer
            )

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to create variable for OUT parameter $parameterIndex: ${connection.getLastError()}")
            }

            val dpiVar = varPtr.value ?: throw SQLException("Variable pointer is null")

            // Bind variable to statement
            val bindResult = dpiStmt_bindByPos(stmt, parameterIndex.toUInt(), dpiVar)
            if (bindResult != DPI_SUCCESS) {
                dpiVar_release(dpiVar)
                throw SQLException("Failed to bind OUT parameter $parameterIndex: ${connection.getLastError()}")
            }

            // Store the variable and type
            parameters[parameterIndex]?.let { dpiVar_release(it) }
            parameters[parameterIndex] = dpiVar
            outParameterTypes[parameterIndex] = type to nativeType
        }
    }

    override fun execute(): Boolean {
        val mode = if (connection.isAutoCommit()) {
            DPI_MODE_EXEC_COMMIT_ON_SUCCESS
        } else {
            DPI_MODE_EXEC_DEFAULT
        }

        memScoped {
            val numQueryColumnsPtr = alloc<UIntVar>()
            val result = dpiStmt_execute(stmt, mode, numQueryColumnsPtr.ptr)

            if (result != DPI_SUCCESS) {
                throw SQLException("Failed to execute callable statement: ${connection.getLastError()}")
            }
        }

        executed = true
        return true
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        if (!executed) {
            throw SQLException("Statement not executed - call execute() first")
        }

        val dpiVar = parameters[parameterIndex]
            ?: throw SQLException("Parameter $parameterIndex not registered")

        val (_, nativeType) = outParameterTypes[parameterIndex]
            ?: throw SQLException("Parameter $parameterIndex not registered as OUT parameter")

        memScoped {
            val dataPtr = alloc<CPointerVar<dpiData>>()
            val numElementsPtr = alloc<UIntVar>()
            dpiVar_getReturnedData(dpiVar, 0u, numElementsPtr.ptr, dataPtr.ptr)
            val data = dataPtr.value ?: return null

            return OracleParameterHelper.getDpiDataValue(data, type, nativeType)
        }
    }

    override fun executeUpdate(): Int {
        execute()

        memScoped {
            val rowCountPtr = alloc<ULongVar>()
            dpiStmt_getRowCount(stmt, rowCountPtr.ptr)
            return rowCountPtr.value.toInt()
        }
    }

    override fun executeQuery(): ResultSet {
        execute()
        return OracleResultSet(stmt, context)
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun close() {
        // Release all bound variables
        parameters.values.forEach { dpiVar ->
            dpiVar_release(dpiVar)
        }
        parameters.clear()
        outParameterTypes.clear()

        // Release statement
        dpiStmt_close(stmt, null, 0u)
        dpiStmt_release(stmt)
    }
}
