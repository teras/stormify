package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import onl.ycode.kdbc.ResultSetMetaData
import onl.ycode.kdbc.SQLException
import libpq.*

/**
 * PostgreSQL ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresResultSetMetaData(
    private val result: CPointer<PGresult>,
    override val columnCount: Int
) : ResultSetMetaData {

    override fun getColumnName(column: Int): String {
        if (column < 1 || column > columnCount) {
            throw SQLException("Invalid column index: $column")
        }

        val colIndex = column - 1
        return PQfname(result, colIndex)?.toKString() ?: "COLUMN_$column"
    }
}
