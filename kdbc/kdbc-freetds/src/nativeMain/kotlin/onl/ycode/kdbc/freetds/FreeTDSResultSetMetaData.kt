package onl.ycode.kdbc.freetds

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import freetds.*

/**
 * FreeTDS ResultSetMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class FreeTDSResultSetMetaData(
    private val dbContext: CPointer<DBPROCESS>
) : ResultSetMetaData {
    override val columnCount: Int
        get() = dbnumcols(dbContext)

    override fun getColumnName(column: Int): String {
        val name = dbcolname(dbContext, column)
        return name?.toKString() ?: "column_$column"
    }
}
