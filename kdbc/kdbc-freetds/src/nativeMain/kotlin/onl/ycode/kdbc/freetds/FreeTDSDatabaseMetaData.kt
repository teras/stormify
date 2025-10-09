package onl.ycode.kdbc.freetds

import kotlinx.cinterop.*
import onl.ycode.kdbc.*
import freetds.*

/**
 * FreeTDS DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class FreeTDSDatabaseMetaData(
    private val dbContext: CPointer<DBPROCESS>
) : DatabaseMetaData {
    override val databaseProductName: String
        get() = "Microsoft SQL Server"

    override val databaseProductVersion: String
        get() {
            // Try to get version from server
            val versionStr = dbversion()
            return versionStr?.toKString() ?: "Unknown"
        }

    override val databaseMajorVersion: Int
        get() {
            // Parse major version from version string
            val version = databaseProductVersion
            return version.split(".").firstOrNull()?.toIntOrNull() ?: 0
        }

    override val databaseMinorVersion: Int
        get() {
            // Parse minor version from version string
            val version = databaseProductVersion
            val parts = version.split(".")
            return if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
        }
}
