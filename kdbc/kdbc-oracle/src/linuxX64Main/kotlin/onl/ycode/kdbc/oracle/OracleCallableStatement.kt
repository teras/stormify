package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.*
import kotlin.reflect.KClass

/**
 * Oracle CallableStatement implementation for stored procedures.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleCallableStatement(
    private val serviceContext: OCISvcCtxPtr,
    private val errorHandle: OCIErrorPtr,
    private val sql: String
) : CallableStatement {

    private val preparedStatement = OraclePreparedStatement(serviceContext, errorHandle, sql)
    private val outParameters = mutableMapOf<Int, OutParamData>()

    override fun setObject(parameterIndex: Int, value: Any?) {
        preparedStatement.setObject(parameterIndex, value)
    }

    override fun executeUpdate(): Int {
        return preparedStatement.executeUpdate()
    }

    override fun executeQuery(): ResultSet {
        return preparedStatement.executeQuery()
    }

    override fun getGeneratedKeys(): ResultSet {
        return preparedStatement.getGeneratedKeys()
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        outParameters[parameterIndex] = OutParamData(type)
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        val outParam = outParameters[parameterIndex]
            ?: throw SQLException("Parameter $parameterIndex not registered as OUT parameter")

        // TODO: Implement actual OUT parameter retrieval from OCI
        // This requires binding with OCI_DATA_AT_EXEC or similar
        throw SQLException("OUT parameter retrieval not yet fully implemented")
    }

    override fun execute(): Boolean {
        val result = executeUpdate()
        return result > 0
    }

    override fun close() {
        preparedStatement.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class OutParamData(
    val type: KClass<*>,
    var value: Any? = null
)
