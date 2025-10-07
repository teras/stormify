package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass

@OptIn(ExperimentalForeignApi::class)
class SqliteDatabaseMetaData(private val dbPointer: CPointer<cnames.structs.sqlite3>) : DatabaseMetaData {
    override val databaseProductName: String = "SQLite"

    override val databaseProductVersion: String
        get() = sqlite3_libversion()?.toKString() ?: "Unknown"

    override val databaseMajorVersion: Int
        get() = sqlite3_libversion_number() / 1000000

    override val databaseMinorVersion: Int
        get() = (sqlite3_libversion_number() % 1000000) / 1000
}
