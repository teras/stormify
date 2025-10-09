package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.ResultSetMetaData
import onl.ycode.kdbc.SQLException

/**
 * Oracle ResultSetMetaData implementation using ODPI-C.
 * Uses dpiQueryInfo which is already fetched by OracleResultSet.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleResultSetMetaData(
    private val stmt: CPointer<dpiStmt>,
    override val columnCount: Int,
    private val queryInfo: List<dpiQueryInfo>
) : ResultSetMetaData {

    override fun getColumnName(column: Int): String {
        if (column < 1 || column > columnCount) {
            throw SQLException("Invalid column index: $column")
        }

        val info = queryInfo[column - 1]
        return info.name?.toKString() ?: "Column_$column"
    }
}
