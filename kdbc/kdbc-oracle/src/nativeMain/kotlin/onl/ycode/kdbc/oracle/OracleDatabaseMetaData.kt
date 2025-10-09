package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import oci.*
import onl.ycode.kdbc.DatabaseMetaData
import onl.ycode.kdbc.SQLException

/**
 * Oracle DatabaseMetaData implementation.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleDatabaseMetaData(
    private val serviceContext: OCISvcCtxPtr,
    private val errorHandle: OCIErrorPtr
) : DatabaseMetaData {

    override val databaseProductName: String
        get() = "Oracle Database"

    override val databaseProductVersion: String
        get() {
            // Query Oracle version
            memScoped {
                val buffer = allocArray<ByteVar>(256)
                oci_attr_get(
                    serviceContext,
                    OCI_HTYPE_SVCCTX,
                    buffer,
                    null,
                    OCI_ATTR_SERVER_VERSION,
                    errorHandle.reinterpret()
                )
                return buffer.toKString()
            }
        }

    override val databaseMajorVersion: Int
        get() {
            // Parse version string to get major version
            val version = databaseProductVersion
            val match = Regex("""(\d+)\.(\d+)""").find(version)
            return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

    override val databaseMinorVersion: Int
        get() {
            // Parse version string to get minor version
            val version = databaseProductVersion
            val match = Regex("""(\d+)\.(\d+)""").find(version)
            return match?.groupValues?.get(2)?.toIntOrNull() ?: 0
        }
}
