package onl.ycode.kdbc.postgres

import kotlinx.cinterop.*
import onl.ycode.kdbc.DatabaseMetaData
import libpq.*

/**
 * PostgreSQL DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class PostgresDatabaseMetaData(private val conn: CPointer<PGconn>) : DatabaseMetaData {
    override val databaseProductName: String = "PostgreSQL"

    override val databaseProductVersion: String
        get() {
            val result = PQexec(conn, "SELECT version()")
            try {
                if (result != null && PQresultStatus(result) == PGRES_TUPLES_OK) {
                    val value = PQgetvalue(result, 0, 0)
                    return value?.toKString() ?: "Unknown"
                }
                return "Unknown"
            } finally {
                if (result != null) {
                    PQclear(result)
                }
            }
        }

    override val databaseMajorVersion: Int
        get() = PQserverVersion(conn) / 10000

    override val databaseMinorVersion: Int
        get() = (PQserverVersion(conn) % 10000) / 100
}
