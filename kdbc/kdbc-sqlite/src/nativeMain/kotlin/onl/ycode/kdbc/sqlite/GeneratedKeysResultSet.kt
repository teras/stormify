package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

class GeneratedKeysResultSet(private val generatedKey: Long) : ResultSet {
    private var consumed = false

    override fun next(): Boolean {
        if (consumed) return false
        consumed = true
        return true
    }

    override fun getObject(columnIndex: Int, type: KClass<*>): Any? {
        if (!consumed) {
            throw SQLException("Call next() before accessing data")
        }
        if (columnIndex != 1) {
            throw SQLException("Generated keys ResultSet only has one column")
        }

        return when (type) {
            Long::class -> generatedKey
            Int::class -> generatedKey.toInt()
            String::class -> generatedKey.toString()
            else -> generatedKey
        }
    }

    override fun getMetaData(): ResultSetMetaData {
        return object : ResultSetMetaData {
            override val columnCount: Int = 1
            override fun getColumnName(column: Int): String {
                if (column != 1) throw SQLException("Only one column available")
                return "GENERATED_KEY"
            }
        }
    }

    override fun close() {
        // No-op
    }
}
