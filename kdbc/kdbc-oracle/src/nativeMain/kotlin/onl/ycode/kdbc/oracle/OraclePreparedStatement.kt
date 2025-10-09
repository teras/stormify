package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.*

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
 * - LocalDateTime: SQLT_DAT (7-byte Oracle DATE format with time components)
 * - BigDecimal: SQLT_BDOUBLE if no precision loss, else SQLT_STR
 * - BigInteger: SQLT_INT (as Long) if fits, else SQLT_STR
 */
@OptIn(ExperimentalForeignApi::class)
class OraclePreparedStatement(
    connection: OracleConnection,
    serviceContext: OCISvcCtxPtr,
    errorHandle: OCIErrorPtr,
    sql: String,
    private val returnGeneratedKeys: Boolean = false
) : OracleStatementBase(connection, serviceContext, errorHandle, sql, "prepared statement"), PreparedStatement {

    private val bindHandles = mutableListOf<OCIBindPtr?>()
    private val paramData = mutableListOf<OracleParameterHelper.InParameterData>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        // Ensure we have enough parameter slots
        while (paramData.size < parameterIndex) {
            paramData.add(OracleParameterHelper.InParameterData())
        }

        val index = parameterIndex - 1
        val data = paramData[index]
        data.clear()

        try {
            // Store parameter value using unified helper (no heap allocation)
            OracleParameterHelper.bindInParameter(value, data)
        } catch (e: Exception) {
            data.clear()
            throw e
        }
    }

    override fun executeUpdate(): Int {
        // Bind parameters using memScoped
        bindAllParameters()

        val result = executeStatement(1u)

        if (result != OCI_SUCCESS && result != OCI_SUCCESS_WITH_INFO) {
            throw SQLException("Failed to execute update: ${getOciError(errorHandle)}")
        }

        return getRowCount()
    }

    override fun executeQuery(): ResultSet {
        // Bind parameters using memScoped
        bindAllParameters()

        val result = executeStatement(0u) // Don't fetch rows yet

        if (result != OCI_SUCCESS && result != OCI_SUCCESS_WITH_INFO) {
            throw SQLException("Failed to execute query: ${getOciError(errorHandle)}")
        }

        return OracleResultSet(stmtHandle, errorHandle)
    }

    /**
     * Binds all parameters using memScoped for automatic memory management.
     * Allocates buffers temporarily, binds to OCI, and lets memScoped clean up.
     */
    private fun bindAllParameters() {
        paramData.forEachIndexed { index, data ->
            val position = index + 1

            memScoped {
                val bindPtr = alloc<CPointerVar<out CPointed>>()
                val indicator = alloc<ShortVar>()

                // Use Oracle-specific allocation helper (extension on MemScope)
                val allocated = OracleParameterHelper.run { allocateInParameter(data) }
                indicator.value = if (data.type == ParameterType.NULL) -1 else 0

                val result = oci_bind_by_pos(
                    stmtHandle.reinterpret(),
                    bindPtr.ptr.reinterpret(),
                    errorHandle.reinterpret(),
                    position.toUInt(),
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
                    throw SQLException("Failed to bind parameter $position: ${getOciError(errorHandle)}")
                }

                // Store bind handle
                while (bindHandles.size < position) {
                    bindHandles.add(null)
                }
                bindHandles[position - 1] = bindPtr.value
            } // memScoped auto-frees all allocations
        }
    }

    override fun getGeneratedKeys(): ResultSet {
        // Oracle uses sequences for primary keys, not auto-increment
        // Stormify's SqlDialect.ORACLE_* is configured with GeneratedKeyRetrieval.NONE
        // This means Stormify retrieves the ID by querying the sequence directly:
        //   INSERT INTO table (id, ...) VALUES (sequence.NEXTVAL, ...)
        // Then reads the ID back from the inserted object using sequence.CURRVAL
        //
        // For RETURNING clause support, would need:
        //   1. Modify SQL to include: INSERT ... RETURNING id INTO ?
        //   2. Bind output parameter using OCI_DEFINE_BY_POS
        //   3. Extract value after execute
        // This is complex and not needed since Stormify handles it via sequences
        return EmptyResultSet()
    }

    override fun close() {
        // IN parameters use ParameterStorage (no heap allocations to free)
        paramData.clear()
        super.close()
    }
}
