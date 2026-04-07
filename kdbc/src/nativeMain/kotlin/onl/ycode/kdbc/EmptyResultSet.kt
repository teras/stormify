package onl.ycode.kdbc

import kotlin.reflect.KClass

/**
 * Shared empty ResultSet implementation for cases where no results are available.
 * Used by all database drivers.
 */
class EmptyResultSet : ResultSet {
    override fun next(): Boolean = false

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        throw SQLException("No rows available")
    }

    override fun getMetaData(): ResultSetMetaData {
        return object : ResultSetMetaData {
            override val columnCount: Int = 0
            override fun getColumnName(column: Int): String {
                throw SQLException("No columns available")
            }
        }
    }

    override fun close() {
        // No-op
    }
}
