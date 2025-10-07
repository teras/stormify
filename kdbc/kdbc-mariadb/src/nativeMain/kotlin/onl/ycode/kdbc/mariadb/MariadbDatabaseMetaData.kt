package onl.ycode.kdbc.mariadb

import kotlinx.cinterop.*
import mariadb.*
import onl.ycode.kdbc.DatabaseMetaData

/**
 * MariaDB/MySQL DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class MariadbDatabaseMetaData(private val mysql: CPointer<MYSQL>) : DatabaseMetaData {
    override val databaseProductName: String = "MySQL/MariaDB"

    override val databaseProductVersion: String
        get() = mysql_get_server_info(mysql)?.toKString() ?: "Unknown"

    override val databaseMajorVersion: Int
        get() {
            val version = mysql_get_server_version(mysql)
            return (version / 10000u).toInt()
        }

    override val databaseMinorVersion: Int
        get() {
            val version = mysql_get_server_version(mysql)
            return ((version % 10000u) / 100u).toInt()
        }
}
