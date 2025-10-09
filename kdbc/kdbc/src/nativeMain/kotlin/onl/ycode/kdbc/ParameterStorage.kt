package onl.ycode.kdbc

import kotlinx.cinterop.*

/**
 * Unified parameter type enum used across all native drivers.
 * Represents the stored type, not the database wire format.
 */
enum class ParameterType {
    NULL,
    BYTE,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    BOOLEAN,
    STRING,
    BYTE_ARRAY,
    // Complex types - drivers handle specific types
    COMPLEX
}

/**
 * Result of parameter allocation containing buffer pointer and size.
 */
@OptIn(ExperimentalForeignApi::class)
data class AllocatedParameter(
    val buffer: COpaquePointer?,
    val size: Int
)

/**
 * Unified parameter value storage for native drivers.
 * Stores actual values instead of pointers - allocation happens during execution.
 *
 * This class provides a common structure for storing parameter values across
 * PostgreSQL, MariaDB, and Oracle drivers, eliminating code duplication while
 * allowing driver-specific serialization.
 *
 * Memory management:
 * - All fields are Kotlin values (no native heap allocations)
 * - Drivers allocate temporary buffers in memScoped during execution
 * - Automatic cleanup when memScoped exits
 *
 * Note: For complex types (dates, BigDecimal, etc.), drivers should extend this
 * class and add type-specific fields as needed.
 */
open class ParameterStorage {
    var type: ParameterType = ParameterType.NULL

    // Primitive values
    var byteValue: Byte = 0
    var shortValue: Short = 0
    var intValue: Int = 0
    var longValue: Long = 0
    var floatValue: Float = 0f
    var doubleValue: Double = 0.0
    var booleanValue: Boolean = false

    // Complex values
    var stringValue: String? = null
    var byteArrayValue: ByteArray? = null

    // For driver-specific complex types (dates, BigDecimal, etc.)
    var complexValue: Any? = null

    /**
     * Clears all stored values and resets to NULL type.
     */
    open fun clear() {
        type = ParameterType.NULL
        byteValue = 0
        shortValue = 0
        intValue = 0
        longValue = 0
        floatValue = 0f
        doubleValue = 0.0
        booleanValue = false
        stringValue = null
        byteArrayValue = null
        complexValue = null
    }

    /**
     * Stores a primitive value in this ParameterStorage based on its Kotlin type.
     * For complex types, drivers should override this method or use complexValue field.
     *
     * @param value The value to store (null for SQL NULL)
     * @return true if the value was stored, false if driver should handle it
     */
    open fun store(value: Any?): Boolean {
        clear()

        return when (value) {
            null -> {
                type = ParameterType.NULL
                true
            }
            is Byte -> {
                type = ParameterType.BYTE
                byteValue = value
                true
            }
            is Short -> {
                type = ParameterType.SHORT
                shortValue = value
                true
            }
            is Int -> {
                type = ParameterType.INT
                intValue = value
                true
            }
            is Long -> {
                type = ParameterType.LONG
                longValue = value
                true
            }
            is Float -> {
                type = ParameterType.FLOAT
                floatValue = value
                true
            }
            is Double -> {
                type = ParameterType.DOUBLE
                doubleValue = value
                true
            }
            is Boolean -> {
                type = ParameterType.BOOLEAN
                booleanValue = value
                true
            }
            is String -> {
                type = ParameterType.STRING
                stringValue = value
                true
            }
            is ByteArray -> {
                type = ParameterType.BYTE_ARRAY
                byteArrayValue = value
                true
            }
            else -> {
                // Driver should handle complex types
                type = ParameterType.COMPLEX
                complexValue = value
                false
            }
        }
    }
}

/**
 * Unified helper to allocate parameter buffers in memScoped.
 * Eliminates code duplication across all drivers (PostgreSQL, MariaDB, Oracle).
 *
 * Must be called within a memScoped block.
 *
 * @param data The ParameterStorage containing the value to allocate
 * @return AllocatedParameter with buffer pointer and size
 */
@OptIn(ExperimentalForeignApi::class)
inline fun <reified T : ParameterStorage> MemScope.allocateParameter(data: T): AllocatedParameter {
    return when (data.type) {
        ParameterType.NULL -> AllocatedParameter(null, 0)
        ParameterType.BYTE -> {
            val byteVar = alloc<ByteVar>().apply { value = data.byteValue }
            AllocatedParameter(byteVar.ptr, 1)
        }
        ParameterType.SHORT -> {
            val shortVar = alloc<ShortVar>().apply { value = data.shortValue }
            AllocatedParameter(shortVar.ptr, sizeOf<ShortVar>().toInt())
        }
        ParameterType.INT -> {
            val intVar = alloc<IntVar>().apply { value = data.intValue }
            AllocatedParameter(intVar.ptr, sizeOf<IntVar>().toInt())
        }
        ParameterType.LONG -> {
            val longVar = alloc<LongVar>().apply { value = data.longValue }
            AllocatedParameter(longVar.ptr, sizeOf<LongVar>().toInt())
        }
        ParameterType.FLOAT -> {
            val floatVar = alloc<FloatVar>().apply { value = data.floatValue }
            AllocatedParameter(floatVar.ptr, sizeOf<FloatVar>().toInt())
        }
        ParameterType.DOUBLE -> {
            val doubleVar = alloc<DoubleVar>().apply { value = data.doubleValue }
            AllocatedParameter(doubleVar.ptr, sizeOf<DoubleVar>().toInt())
        }
        ParameterType.BOOLEAN -> {
            val byteVar = alloc<ByteVar>().apply { value = if (data.booleanValue) 1 else 0 }
            AllocatedParameter(byteVar.ptr, 1)
        }
        ParameterType.STRING -> {
            val str = data.stringValue!!
            val bytes = str.encodeToByteArray()
            val buffer = allocArray<ByteVar>(bytes.size + 1)
            bytes.forEachIndexed { i, byte -> buffer[i] = byte }
            buffer[bytes.size] = 0  // null terminator
            AllocatedParameter(buffer, bytes.size + 1)
        }
        ParameterType.BYTE_ARRAY -> {
            val bytes = data.byteArrayValue!!
            val buffer = allocArray<ByteVar>(bytes.size)
            bytes.forEachIndexed { i, byte -> buffer[i] = byte }
            AllocatedParameter(buffer, bytes.size)
        }
        ParameterType.COMPLEX -> {
            throw SQLException("COMPLEX type must be handled by driver-specific code")
        }
    }
}
