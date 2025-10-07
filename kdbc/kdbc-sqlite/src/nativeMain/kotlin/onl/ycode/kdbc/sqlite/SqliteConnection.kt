package onl.ycode.kdbc.sqlite

import kotlinx.cinterop.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import onl.ycode.kdbc.*
import sqlite3.*
import kotlin.reflect.KClass
import kotlin.time.Instant

typealias BDN = com.ionspin.kotlin.bignum.decimal.BigDecimal
typealias BIN = com.ionspin.kotlin.bignum.integer.BigInteger

/**
 * SQLite Connection implementation.
 */
@OptIn(ExperimentalForeignApi::class, kotlin.time.ExperimentalTime::class)
class SqliteConnection(private val url: String) : Connection {
    private val dbPointer: CPointer<cnames.structs.sqlite3>
    private var autoCommit = true

    init {
        memScoped {
            val dbPtr = alloc<CPointerVar<cnames.structs.sqlite3>>()
            val result = sqlite3_open(url, dbPtr.ptr)
            if (result != SQLITE_OK) {
                throw SQLException("Failed to open database: $url, error code: $result")
            }
            dbPointer = dbPtr.value ?: throw SQLException("Database pointer is null")
        }
    }

    override val metaData: DatabaseMetaData
        get() = SqliteDatabaseMetaData(dbPointer)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return SqlitePreparedStatement(dbPointer, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        throw SQLException("SQLite doesn't support callable statements")
    }

    override fun commit() {
        prepareStatement("COMMIT").use { it.executeUpdate() }
        if (!autoCommit) {
            prepareStatement("BEGIN").use { it.executeUpdate() }
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            prepareStatement("ROLLBACK TO SAVEPOINT ${savepoint.savepointName}").use { it.executeUpdate() }
        } else {
            prepareStatement("ROLLBACK").use { it.executeUpdate() }
            if (!autoCommit) {
                prepareStatement("BEGIN").use { it.executeUpdate() }
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        prepareStatement("SAVEPOINT $name").use { it.executeUpdate() }
        return SqliteSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        prepareStatement("RELEASE SAVEPOINT ${savepoint.savepointName}").use { it.executeUpdate() }
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (this.autoCommit != autoCommit) {
            if (autoCommit) {
                prepareStatement("COMMIT").use { it.executeUpdate() }
            } else {
                prepareStatement("BEGIN").use { it.executeUpdate() }
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        sqlite3_close(dbPointer)
    }
}
