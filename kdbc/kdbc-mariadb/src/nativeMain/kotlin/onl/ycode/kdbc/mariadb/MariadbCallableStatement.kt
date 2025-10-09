package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import mariadb.*
import onl.ycode.kdbc.CallableStatement
import onl.ycode.kdbc.EmptyResultSet
import onl.ycode.kdbc.ResultSet
import onl.ycode.kdbc.SQLException
import kotlin.reflect.KClass

/**
 * MariaDB/MySQL CallableStatement implementation using binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
class MariadbCallableStatement(
    mysql: CPointer<MYSQL>,
    sql: String
) : MariadbStatementBase(mysql, sql, "callable statement"), CallableStatement {
    private val outParameters = mutableMapOf<Int, KClass<*>>()
    private var resultSet: MariadbStmtResultSet? = null

    override fun executeUpdate(): Int {
        return doExecuteUpdate()
    }

    override fun executeQuery(): ResultSet {
        resultSet = doExecuteQuery()
        return resultSet!!
    }

    override fun getGeneratedKeys(): ResultSet {
        return EmptyResultSet()
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        outParameters[parameterIndex] = type
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        // After execution, OUT parameters would be in the result set
        // For MySQL/MariaDB stored procedures, OUT params are returned in SELECT results
        if (resultSet == null) {
            throw SQLException("No result set available - execute the statement first")
        }

        // The first row should contain the OUT parameters
        return resultSet?.getObject(parameterIndex, type)
    }

    override fun execute(): Boolean {
        val hasResultSet = doExecute()
        if (hasResultSet) {
            resultSet = MariadbStmtResultSet(stmt)
        }
        return hasResultSet
    }

    override fun close() {
        resultSet?.close()
        super.close()
    }
}
