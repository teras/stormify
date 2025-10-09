package onl.ycode.kdbc.mariadb

import onl.ycode.kdbc.SimpleSavepoint

import kotlinx.cinterop.*
import kotlinx.datetime.*
import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import onl.ycode.kdbc.*
import mariadb.*
import kotlin.reflect.KClass

/**
 * MariaDB/MySQL Connection implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbConnection(
    private val connectionString: String,
    private val properties: Map<String, String> = emptyMap()
) : Connection {
    private val mysql: CPointer<MYSQL>
    private var autoCommit = true

    init {
        // Initialize MySQL library
        val mysqlPtr = mysql_init(null) ?: throw SQLException("Failed to initialize MySQL library")

        // Configure SSL if present in properties
        if (properties.containsKey("ssl_enabled")) {
            val sslKey = properties["ssl_key"]
            val sslCert = properties["ssl_cert"]
            val sslCa = properties["ssl_ca"]
            val sslCaPath = properties["ssl_capath"]
            val sslCipher = properties["ssl_cipher"]

            mysql_ssl_set_wrapper(
                mysqlPtr,
                sslKey,
                sslCert,
                sslCa,
                sslCaPath,
                sslCipher
            )
        }

        // Parse connection string: host:port/database
        val parts = connectionString.split("/")
        if (parts.size != 2) {
            throw SQLException("Invalid connection string format. Expected: host:port/database")
        }

        val hostPort = parts[0].split(":")
        val host = hostPort[0]
        val port = if (hostPort.size > 1) hostPort[1].toUIntOrNull() ?: 3306u else 3306u
        val database = parts[1]

        val user = properties["user"] ?: "root"
        val password = properties["password"] ?: ""

        // Determine client flags for SSL
        var clientFlags = 0uL
        if (properties.containsKey("ssl_enabled")) {
            clientFlags = clientFlags or 2048uL // CLIENT_SSL flag
        }

        // Connect to database
        val result = mysql_real_connect(
            mysqlPtr,
            host,
            user,
            password,
            database,
            port,
            null,
            clientFlags
        )

        if (result == null) {
            val error = mysql_error(mysqlPtr)?.toKString() ?: "Unknown error"
            mysql_close(mysqlPtr)
            throw SQLException("Failed to connect to database: $error")
        }

        mysql = mysqlPtr

        // Set autocommit mode
        mysql_autocommit(mysql, 1.toByte())
    }

    override val metaData: DatabaseMetaData
        get() = MariadbDatabaseMetaData(mysql)

    override fun prepareStatement(sql: String, returnGeneratedKeys: Boolean): PreparedStatement {
        return MariadbPreparedStatement(mysql, sql, returnGeneratedKeys)
    }

    override fun prepareCall(sql: String): CallableStatement {
        return MariadbCallableStatement(mysql, sql)
    }

    override fun commit() {
        val result = mysql_commit(mysql)
        if (result.toInt() != 0) {
            val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
            throw SQLException("Failed to commit transaction: $error")
        }
    }

    override fun rollback(savepoint: Savepoint?) {
        if (savepoint != null) {
            val sql = "ROLLBACK TO SAVEPOINT ${savepoint.savepointName}"
            prepareStatement(sql).use { it.executeUpdate() }
        } else {
            val result = mysql_rollback(mysql)
            if (result.toInt() != 0) {
                val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to rollback transaction: $error")
            }
        }
    }

    override fun setSavepoint(name: String): Savepoint {
        val sql = "SAVEPOINT $name"
        prepareStatement(sql).use { it.executeUpdate() }
        return SimpleSavepoint(name)
    }

    override fun releaseSavepoint(savepoint: Savepoint) {
        val sql = "RELEASE SAVEPOINT ${savepoint.savepointName}"
        prepareStatement(sql).use { it.executeUpdate() }
    }

    override fun setAutoCommit(autoCommit: Boolean) {
        if (this.autoCommit != autoCommit) {
            val result = mysql_autocommit(mysql, if (autoCommit) 1.toByte() else 0.toByte())
            if (result.toInt() != 0) {
                val error = mysql_error(mysql)?.toKString() ?: "Unknown error"
                throw SQLException("Failed to set autocommit mode: $error")
            }
            this.autoCommit = autoCommit
        }
    }

    override fun close() {
        mysql_close(mysql)
    }
}
