package onl.ycode.kdbc.freetds

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import freetds.*
import kotlin.reflect.KClass

/**
 * FreeTDS ResultSet implementation using db-lib API.
 */
@OptIn(ExperimentalForeignApi::class)
class FreeTDSResultSet(
    private val dbContext: CPointer<DBPROCESS>
) : ResultSet {
    private var currentRowStatus: Int = 0
    private var closed = false

    override fun next(): Boolean {
        checkClosed()
        currentRowStatus = dbnextrow(dbContext)
        return currentRowStatus == REG_ROW
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        checkClosed()
        if (currentRowStatus != REG_ROW) {
            throw SQLException("No current row")
        }

        // Get column data
        val data = dbdata(dbContext, columnIndex)
        if (data == null) {
            return null
        }

        val dataLen = dbdatlen(dbContext, columnIndex)
        if (dataLen == 0) {
            return null
        }

        // Convert to string first (FreeTDS db-lib uses text format)
        val valueStr = data.reinterpret<ByteVar>().toKString()

        // Use TypeHelper to parse the value
        return FreeTDSTypeHelper.readValue(valueStr, type)
    }

    override fun getMetaData(): ResultSetMetaData {
        return FreeTDSResultSetMetaData(dbContext)
    }

    override fun close() {
        if (!closed) {
            // Cancel remaining results
            dbcancel(dbContext)
            closed = true
        }
    }

    private fun checkClosed() {
        if (closed) {
            throw SQLException("ResultSet is closed")
        }
    }
}
