package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import mariadb.*
import onl.ycode.kdbc.ResultSetMetaData
import onl.ycode.kdbc.SQLException

/**
 * MariaDB/MySQL ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbResultSetMetaData(
    private val result: CPointer<MYSQL_RES>,
    override val columnCount: Int
) : ResultSetMetaData {

    override fun getColumnName(column: Int): String {
        if (column < 1 || column > columnCount) {
            throw SQLException("Invalid column index: $column")
        }

        val fields = mysql_fetch_fields(result)
            ?: throw SQLException("Failed to fetch field information")

        val field = fields[column - 1]
        return field.name?.toKString() ?: "COLUMN_$column"
    }
}
