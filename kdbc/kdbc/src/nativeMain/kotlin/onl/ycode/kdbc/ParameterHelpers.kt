package onl.ycode.kdbc

import kotlinx.cinterop.*

/**
 * Parameter type categories used across all native drivers.
 * Represents the storage type, not the database-specific wire format.
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
    COMPLEX  // Driver-specific types
}

/**
 * Result of parameter buffer allocation in memScoped.
 */
@OptIn(ExperimentalForeignApi::class)
data class AllocatedParameter(
    val buffer: COpaquePointer?,
    val size: Int
)

/**
 * Generic parameter data interface.
 * All driver-specific parameter data classes must implement this.
 */
interface ParameterData {
    val type: ParameterType
    val value: Any?
}

/**
 * Byte order conversion utilities for network protocols.
 * Many database protocols (PostgreSQL, Oracle, etc.) use network byte order (big endian),
 * so these utilities help convert between little-endian and big-endian representations.
 *
 * Note: Kotlin/Native does not provide reverseBytes() in stdlib, so we implement it here
 * as extension functions for use across all drivers.
 */

/**
 * Reverses the byte order of a Short value.
 * Converts between little-endian and big-endian (network byte order).
 */
@OptIn(ExperimentalForeignApi::class)
fun Short.reverseBytes(): Short {
    return ((this.toInt() and 0xFF) shl 8 or ((this.toInt() shr 8) and 0xFF)).toShort()
}

/**
 * Reverses the byte order of an Int value.
 * Converts between little-endian and big-endian (network byte order).
 */
@OptIn(ExperimentalForeignApi::class)
fun Int.reverseBytes(): Int {
    return ((this and 0xFF) shl 24) or
           ((this and 0xFF00) shl 8) or
           ((this shr 8) and 0xFF00) or
           ((this shr 24) and 0xFF)
}

/**
 * Reverses the byte order of a Long value.
 * Converts between little-endian and big-endian (network byte order).
 */
@OptIn(ExperimentalForeignApi::class)
fun Long.reverseBytes(): Long {
    return ((this and 0xFF) shl 56) or
           ((this and 0xFF00) shl 40) or
           ((this and 0xFF0000) shl 24) or
           ((this and 0xFF000000) shl 8) or
           ((this shr 8) and 0xFF000000) or
           ((this shr 24) and 0xFF0000) or
           ((this shr 40) and 0xFF00) or
           ((this shr 56) and 0xFF)
}

/**
 * Allocates C memory buffers for parameter values within a memScoped block.
 * Used by all drivers for consistent memory allocation.
 *
 * Must be called within a memScoped context.
 *
 * @param data The parameter data containing type and value
 * @return AllocatedParameter with buffer pointer and size
 */
@OptIn(ExperimentalForeignApi::class)
fun MemScope.allocateParameterBuffer(data: ParameterData): AllocatedParameter {
    return when (data.type) {
        ParameterType.NULL -> AllocatedParameter(null, 0)
        ParameterType.BYTE -> alloc<ByteVar>().run { value = data.value.safeCast<Byte>(); AllocatedParameter(ptr, 1) }
        ParameterType.SHORT -> alloc<ShortVar>().run { value = data.value.safeCast<Short>(); AllocatedParameter(ptr, sizeOf<ShortVar>().toInt()) }
        ParameterType.INT -> alloc<IntVar>().run { value = data.value.safeCast<Int>(); AllocatedParameter(ptr, sizeOf<IntVar>().toInt()) }
        ParameterType.LONG -> alloc<LongVar>().run { value = data.value.safeCast<Long>(); AllocatedParameter(ptr, sizeOf<LongVar>().toInt()) }
        ParameterType.FLOAT -> alloc<FloatVar>().run { value = data.value.safeCast<Float>(); AllocatedParameter(ptr, sizeOf<FloatVar>().toInt()) }
        ParameterType.DOUBLE -> alloc<DoubleVar>().run { value = data.value.safeCast<Double>(); AllocatedParameter(ptr, sizeOf<DoubleVar>().toInt()) }
        ParameterType.STRING -> {
            val str = data.value.safeCast<String>()
            val bytes = str.encodeToByteArray()
            val buffer = allocArray<ByteVar>(bytes.size + 1)
            bytes.usePinned { pinned ->
                platform.posix.memcpy(buffer, pinned.addressOf(0), bytes.size.toULong())
            }
            buffer[bytes.size] = 0  // null terminator
            AllocatedParameter(buffer, bytes.size + 1)
        }
        ParameterType.BYTE_ARRAY -> {
            val bytes = data.value.safeCast<ByteArray>()
            val buffer = allocArray<ByteVar>(bytes.size)
            bytes.usePinned { pinned ->
                platform.posix.memcpy(buffer, pinned.addressOf(0), bytes.size.toULong())
            }
            AllocatedParameter(buffer, bytes.size)
        }
        ParameterType.BOOLEAN -> {
            // Should be converted to BYTE or INT before allocation
            throw SQLException("BOOLEAN should be converted to BYTE or INT before allocation")
        }
        ParameterType.COMPLEX -> {
            throw SQLException("COMPLEX type must be handled by driver-specific code")
        }
    }
}
