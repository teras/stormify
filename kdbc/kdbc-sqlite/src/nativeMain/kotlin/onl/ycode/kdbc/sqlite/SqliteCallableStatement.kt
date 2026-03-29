package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

class SqliteCallableStatement : CallableStatement {
    override fun setObject(parameterIndex: Int, value: Any?) {
        TODO("SQLite doesn't support callable statements")
    }

    override fun executeUpdate(): Int {
        TODO("SQLite doesn't support callable statements")
    }

    override fun executeQuery(): ResultSet {
        TODO("SQLite doesn't support callable statements")
    }

    override fun getGeneratedKeys(): ResultSet {
        TODO("SQLite doesn't support callable statements")
    }

    override fun registerOutParameter(parameterIndex: Int, type: KClass<*>) {
        TODO("SQLite doesn't support callable statements")
    }

    override fun getObject(parameterIndex: Int, type: KClass<*>): Any? {
        TODO("SQLite doesn't support callable statements")
    }

    override fun execute(): Boolean {
        TODO("SQLite doesn't support callable statements")
    }

    override fun addBatch() {
        TODO("SQLite doesn't support callable statements")
    }

    override fun executeBatch(): IntArray {
        TODO("SQLite doesn't support callable statements")
    }

    override fun close() {
        // No-op
    }
}
