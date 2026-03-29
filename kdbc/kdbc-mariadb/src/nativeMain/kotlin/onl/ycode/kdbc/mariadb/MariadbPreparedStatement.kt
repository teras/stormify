package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import mariadb.*
import onl.ycode.kdbc.EmptyResultSet
import onl.ycode.kdbc.GeneratedKeysResultSet
import onl.ycode.kdbc.PreparedStatement
import onl.ycode.kdbc.ResultSet

/**
 * MariaDB/MySQL PreparedStatement implementation using native binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.ExperimentalStdlibApi::class, kotlin.time.ExperimentalTime::class)
class MariadbPreparedStatement(
    mysql: CPointer<MYSQL>,
    sql: String,
    private val returnGeneratedKeys: Boolean = false
) : MariadbStatementBase(mysql, sql, "prepared statement"), PreparedStatement {
    private var lastInsertId: ULong = 0u

    override fun executeUpdate(): Int {
        val affectedRows = doExecuteUpdate()

        if (returnGeneratedKeys) {
            lastInsertId = mariadb_stmt_insert_id_wrapper(stmt)
        }

        return affectedRows
    }

    override fun executeQuery(): ResultSet {
        return doExecuteQuery()
    }

    override fun getGeneratedKeys(): ResultSet {
        if (!returnGeneratedKeys || lastInsertId == 0uL) {
            return EmptyResultSet()
        }
        return GeneratedKeysResultSet(lastInsertId.toLong())
    }

    override fun addBatch() = super.addBatch()

    override fun executeBatch(): IntArray = super.executeBatch()
}
