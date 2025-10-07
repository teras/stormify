package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

@OptIn(ExperimentalForeignApi::class)
class SqliteResultSetMetaData(private val stmtPointer: CPointer<cnames.structs.sqlite3_stmt>) : ResultSetMetaData {
    override val columnCount: Int
        get() = sqlite3_column_count(stmtPointer)

    override fun getColumnName(column: Int): String {
        // SQLite uses 0-based indexing, JDBC uses 1-based
        val index = column - 1
        return sqlite3_column_name(stmtPointer, index)?.toKString()
            ?: throw SQLException("Failed to get column name for index $column")
    }
}
