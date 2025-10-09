package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import libpq.*
import kotlin.reflect.KClass

/**
 * PostgreSQL ResultSet implementation using binary protocol.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class PostgresResultSet(private val result: CPointer<PGresult>) : ResultSet {
    private var currentRow = -1
    private val rowCount = PQntuples(result)
    private val columnCount = PQnfields(result)

    override fun next(): Boolean {
        currentRow++
        return currentRow < rowCount
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (currentRow < 0 || currentRow >= rowCount) {
            throw SQLException("No current row")
        }

        if (columnIndex < 1 || columnIndex > columnCount) {
            throw SQLException("Invalid column index: $columnIndex")
        }

        val colIndex = columnIndex - 1

        if (PQgetisnull(result, currentRow, colIndex) == 1) {
            return null
        }

        val valuePtr = PQgetvalue(result, currentRow, colIndex) ?: return null
        val valueLength = PQgetlength(result, currentRow, colIndex)

        // Use helper to read binary data
        return PostgresTypeHelper.readBinaryValue(valuePtr, valueLength, type)
    }

    override fun getMetaData(): ResultSetMetaData {
        return PostgresResultSetMetaData(result, columnCount)
    }

    override fun close() {
        PQclear(result)
    }
}
