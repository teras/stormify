package onl.ycode.kdbc.oracle

import kotlinx.cinterop.*

/**
 * Type aliases for Oracle OCI opaque types.
 * OCI uses opaque handles for all data structures.
 */
@OptIn(ExperimentalForeignApi::class)
typealias OCIEnvPtr = CPointer<out CPointed>
@OptIn(ExperimentalForeignApi::class)
typealias OCIErrorPtr = CPointer<out CPointed>
@OptIn(ExperimentalForeignApi::class)
typealias OCISvcCtxPtr = CPointer<out CPointed>
@OptIn(ExperimentalForeignApi::class)
typealias OCIStmtPtr = CPointer<out CPointed>
@OptIn(ExperimentalForeignApi::class)
typealias OCIBindPtr = CPointer<out CPointed>
@OptIn(ExperimentalForeignApi::class)
typealias OCIDefinePtr = CPointer<out CPointed>
@OptIn(ExperimentalForeignApi::class)
typealias OCIParamPtr = CPointer<out CPointed>

// OCI Constants
const val OCI_SUCCESS = 0
const val OCI_SUCCESS_WITH_INFO = 1
const val OCI_NO_DATA = 100

const val OCI_DEFAULT = 0x00000000u
const val OCI_THREADED = 0x00000001u

// Handle types
const val OCI_HTYPE_ENV = 1u
const val OCI_HTYPE_ERROR = 2u
const val OCI_HTYPE_SVCCTX = 3u
const val OCI_HTYPE_STMT = 4u
const val OCI_HTYPE_BIND = 5u
const val OCI_HTYPE_DEFINE = 6u

// Data types (SQLT_*) - Using UShort directly as const values
const val SQLT_CHR: UShort = 1u      // VARCHAR2
const val SQLT_NUM: UShort = 2u      // NUMBER (internal Oracle format)
const val SQLT_INT: UShort = 3u      // INTEGER
const val SQLT_FLT: UShort = 4u      // FLOAT
const val SQLT_STR: UShort = 5u      // NULL-terminated STRING
const val SQLT_VNU: UShort = 6u      // NUMBER with preceding length byte
const val SQLT_BIN: UShort = 23u     // RAW
const val SQLT_LNG: UShort = 8u      // LONG
const val SQLT_DAT: UShort = 12u     // DATE (7-byte Oracle format)
const val SQLT_DATE: UShort = 184u   // ANSI Date
const val SQLT_TIME: UShort = 185u   // TIME
const val SQLT_TIMESTAMP: UShort = 187u // TIMESTAMP
const val SQLT_TIMESTAMP_TZ: UShort = 188u // TIMESTAMP WITH TIME ZONE
const val SQLT_BFLOAT: UShort = 21u  // Native binary float
const val SQLT_BDOUBLE: UShort = 22u // Native binary double

// Attributes
const val OCI_ATTR_ROW_COUNT = 9u
const val OCI_ATTR_PARAM_COUNT = 18u
const val OCI_ATTR_NAME = 4u
const val OCI_ATTR_DATA_SIZE = 1u
const val OCI_ATTR_DATA_TYPE = 2u
const val OCI_ATTR_PRECISION = 5u
const val OCI_ATTR_SCALE = 6u
const val OCI_ATTR_SERVER_VERSION = 18u

// Fetch orientation
const val OCI_FETCH_NEXT: UShort = 0x02u

// Descriptor types
const val OCI_DTYPE_PARAM = 53u

// Statement types
const val OCI_NTV_SYNTAX = 1u
