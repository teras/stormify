package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.*
import kotlin.reflect.KClass

/**
 * Oracle CallableStatement implementation for stored procedures with OUT parameter support.
 *
 * Oracle OUT parameters require:
 * 1. Pre-allocating output buffers before execution
 * 2. Binding buffers using oci_bind_by_pos() with OCI_DEFAULT
 * 3. Executing the statement (buffers are populated by OCI)
 * 4. Reading values from the buffers after execution
 *
 * ## Thread Safety
 * This class is **NOT thread-safe**. Each thread must create its own CallableStatement instance.
 * Following JDBC standards, statements should not be shared between threads.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleCallableStatement(
    connection: OracleConnection,
    serviceContext: OCISvcCtxPtr,
    errorHandle: OCIErrorPtr,
    sql: String
) : OracleStatementBase(connection, serviceContext, errorHandle, sql, "callable statement"), CallableStatement {

    private val inParameters = mutableMapOf<Int, OracleParameterHelper.InParameterData>()
    private val outParameters = mutableMapOf<Int, OracleParameterHelper.OutParameterData>()
    private val outParameterTypes = mutableMapOf<Int, KClass<*>>()
    private val outParameterValues = mutableMapOf<Int, Any?>()  // Copied results after execution
    private var executed = false

    override fun setObject(parameterIndex: Int, value: Any?) {
        // Store IN parameter value (no heap allocation yet)
        if (inParameters[parameterIndex] == null) {
            inParameters[parameterIndex] = OracleParameterHelper.InParameterData()
        }

        val data = inParameters[parameterIndex]!!
        data.clear()

        // Store parameter value using unified helper
        OracleParameterHelper.bindInParameter(value, data)
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        val data = OracleParameterHelper.OutParameterData()

        // Allocate OUT parameter buffer (heap allocation needed before execute)
        OracleParameterHelper.allocateOutParameter(type, data)

        outParameters[parameterIndex] = data
        outParameterTypes[parameterIndex] = type
    }

    override fun execute(): Boolean {
        // Clear previous OUT parameter values
        outParameterValues.clear()

        // Bind all IN parameters
        inParameters.forEach { (index, data) ->
            bindInParameter(index, data)
        }

        // Bind all OUT parameters
        outParameters.forEach { (index, data) ->
            bindOutParameter(index, data)
        }

        // Execute the statement
        val result = executeStatement(1u)

        if (result != OCI_SUCCESS && result != OCI_SUCCESS_WITH_INFO) {
            throw SQLException("Failed to execute callable statement: ${getOciError(errorHandle)}")
        }

        // IMMEDIATELY copy OUT parameter values from buffers to Kotlin objects
        // This allows us to free the buffers right away
        outParameters.forEach { (index, data) ->
            val type = outParameterTypes[index]
                ?: throw SQLException("No type registered for OUT parameter $index")
            val value = OracleParameterHelper.readOutValue(type, data)
            outParameterValues[index] = value
        }

        // Free OUT parameter buffers immediately after copying
        outParameters.values.forEach { it.clear() }
        outParameters.clear()

        executed = true
        return true
    }

    private fun bindInParameter(parameterIndex: Int, data: OracleParameterHelper.InParameterData) {
        memScoped {
            val bindPtr = alloc<CPointerVar<out CPointed>>()
            val indicator = alloc<ShortVar>()

            // Use unified allocation helper
            val allocated = allocateParameter(data)
            indicator.value = if (data.type == ParameterType.NULL) -1 else 0

            val result = oci_bind_by_pos(
                stmtHandle.reinterpret(),
                bindPtr.ptr.reinterpret(),
                errorHandle.reinterpret(),
                parameterIndex.toUInt(),
                allocated.buffer,
                allocated.size,
                data.oracleDataType,
                indicator.ptr,
                null, // actual length
                null, // return code
                0u,   // max array length
                null, // current element
                OCI_DEFAULT.toUInt()
            )

            if (result != OCI_SUCCESS) {
                throw SQLException("Failed to bind IN parameter $parameterIndex: ${getOciError(errorHandle)}")
            }
        } // memScoped auto-frees all allocations
    }

    private fun bindOutParameter(parameterIndex: Int, data: OracleParameterHelper.OutParameterData) {
        memScoped {
            val bindPtr = alloc<CPointerVar<out CPointed>>()

            // OUT parameters use heap-allocated buffers (already allocated in registerOutParameter)
            val (valuep, dataType) = when {
                data.stringBuffer != null -> data.stringBuffer to data.dataType
                data.intBuffer != null -> data.intBuffer!!.ptr to data.dataType
                data.longBuffer != null -> data.longBuffer!!.ptr to data.dataType
                data.doubleBuffer != null -> data.doubleBuffer!!.ptr to data.dataType
                data.floatBuffer != null -> data.floatBuffer!!.ptr to data.dataType
                data.byteArrayBuffer != null -> data.byteArrayBuffer to data.dataType
                data.dateBuffer != null -> data.dateBuffer to data.dataType
                else -> throw SQLException("No buffer allocated for OUT parameter $parameterIndex")
            }

            val result = oci_bind_by_pos(
                stmtHandle.reinterpret(),
                bindPtr.ptr.reinterpret(),
                errorHandle.reinterpret(),
                parameterIndex.toUInt(),
                valuep,
                data.bufferSize,
                dataType,
                data.indicator!!.ptr,
                data.actualLength?.ptr,
                null, // return code
                0u,   // max array length
                null, // current element
                OCI_DEFAULT.toUInt()
            )

            if (result != OCI_SUCCESS) {
                throw SQLException("Failed to bind OUT parameter $parameterIndex: ${getOciError(errorHandle)}")
            }
        }
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        if (!executed) {
            throw SQLException("Statement not executed - call execute() first")
        }

        if (!outParameterValues.containsKey(parameterIndex)) {
            throw SQLException("Parameter $parameterIndex not registered as OUT parameter")
        }

        // Return the copied value (buffers were freed right after execute())
        return outParameterValues[parameterIndex]
    }

    override fun executeUpdate(): Int {
        execute()
        return getRowCount()
    }

    override fun executeQuery(): ResultSet {
        execute()
        return OracleResultSet(stmtHandle, errorHandle)
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun close() {
        // IN parameters use ParameterStorage (no heap allocations to free)
        inParameters.clear()

        // OUT parameter buffers are already freed in execute()
        // Just clear the copied values
        outParameterValues.clear()
        outParameterTypes.clear()

        super.close()
    }
}
