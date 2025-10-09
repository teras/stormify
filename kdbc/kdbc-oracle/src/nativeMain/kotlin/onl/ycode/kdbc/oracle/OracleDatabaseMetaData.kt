package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*
import odpi.*
import cnames.structs.*
import onl.ycode.kdbc.DatabaseMetaData

/**
 * Oracle DatabaseMetaData implementation using ODPI-C.
 */
@OptIn(ExperimentalForeignApi::class)
class OracleDatabaseMetaData(
    private val conn: CPointer<dpiConn>,
    private val context: CPointer<dpiContext>
) : DatabaseMetaData {

    override val databaseProductName: String
        get() = "Oracle Database"

    override val databaseProductVersion: String
        get() {
            memScoped {
                val versionInfo = alloc<dpiVersionInfo>()
                val releaseStringPtr = alloc<CPointerVar<ByteVar>>()
                val releaseStringLengthPtr = alloc<UIntVar>()

                dpiConn_getServerVersion(
                    conn,
                    releaseStringPtr.ptr,
                    releaseStringLengthPtr.ptr,
                    versionInfo.ptr
                )

                return releaseStringPtr.value?.toKString() ?: "Unknown"
            }
        }

    override val databaseMajorVersion: Int
        get() {
            memScoped {
                val versionInfo = alloc<dpiVersionInfo>()
                val releaseStringPtr = alloc<CPointerVar<ByteVar>>()
                val releaseStringLengthPtr = alloc<UIntVar>()

                dpiConn_getServerVersion(
                    conn,
                    releaseStringPtr.ptr,
                    releaseStringLengthPtr.ptr,
                    versionInfo.ptr
                )

                return versionInfo.versionNum.toInt()
            }
        }

    override val databaseMinorVersion: Int
        get() {
            memScoped {
                val versionInfo = alloc<dpiVersionInfo>()
                val releaseStringPtr = alloc<CPointerVar<ByteVar>>()
                val releaseStringLengthPtr = alloc<UIntVar>()

                dpiConn_getServerVersion(
                    conn,
                    releaseStringPtr.ptr,
                    releaseStringLengthPtr.ptr,
                    versionInfo.ptr
                )

                return versionInfo.releaseNum.toInt()
            }
        }
}
