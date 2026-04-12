// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.TypeConversion
import kotlin.reflect.KClass

/**
 * Utility class for type conversion with entity-aware fallback.
 *
 * Scalar conversions are delegated to [TypeConversion] in the KDBC layer.
 * Entity conversions (foreign key → entity object) are handled here.
 */
object TypeUtils {
    /**
     * Convert a value to the target class.
     * For scalar types, delegates to [TypeConversion.castScalar].
     * For non-scalar types, resolves the entity via Stormify.
     */
    @Suppress("UNCHECKED_CAST")
    fun <F : Any, T : Any> castTo(targetClass: KClass<T>, value: F?, stormify: Stormify? = null): T? {
        if (value == null || targetClass.isInstance(value)) return value as T?
        // Enum conversion: Int/Number → enum by ordinal/DbValue, String → enum by name
        if (isEnumClass(targetClass)) {
            return when (value) {
                is Number -> enumFromInt(targetClass, value.toInt())
                is String -> {
                    val intVal = value.toIntOrNull()
                    if (intVal != null) enumFromInt(targetClass, intVal)
                    else enumFromName(targetClass, value)
                }
                else -> throw SQLException("Cannot convert ${value::class.fullName} to enum ${targetClass.fullName}")
            }
        }
        if (!isScalarObject(value)) {
            if (stormify == null)
                throw SQLException("Unable to convert non-scalar object to " + targetClass.fullName + "; missing database context")
            val info = stormify.resolveTableInfo(value::class)
            val item = info.create()
            if (item is StormifyEntity) item._stormify = stormify
            info.setField(item, info.singleKeyDbName, value, stormify)
            return item as T
        }
        return TypeConversion.castScalar(targetClass, value)
    }

    /** Throws a [SQLException] indicating that field [name] in class [cls] cannot be null. Used by generated code. */
    fun err(name: String, cls: String): Nothing = throw SQLException("$name cannot be null in class $cls")

    /**
     * Registers a custom type conversion function from [sourceClass] to [targetClass].
     * Delegates to [TypeConversion.register].
     */
    fun <F : Any, T : Any> register(
        sourceClass: KClass<F>,
        targetClass: KClass<T>,
        converter: (Any) -> Any
    ) = TypeConversion.register(sourceClass, targetClass, converter)
}

internal fun count(container: String?, searchable: Char) = container?.count { it == searchable } ?: 0

internal fun nCopies(base: String, delimiter: String, count: Int) =
    if (count <= 0) "" else List(count) { base }.joinToString(delimiter)

internal fun <T> findItemOnce(data: List<T>, key: T, spaceName: String): Int {
    var found: Int = -1
    for (i in data.indices)
        if (key == data[i])
            if (found < 0) found = i
            else throw SQLException("Multiple instances of '$key' found in $spaceName")
    if (found < 0)
        throw SQLException("Unable to find any instances of '$key' in $spaceName")
    return found
}

internal fun isScalarObject(request: Any) =
    request is Number || request is CharSequence || request is Char || request is Boolean
            || request is ByteArray || request is CharArray || request.isOtherPrimitive

internal fun isScalarClass(request: KClass<*>) = allPrimitives.contains(request.fullName)

internal fun isTextualClass(request: KClass<*>) = with(request.fullName) {
    this == "kotlin.String" || this == "kotlin.Char" || this == "kotlin.text.StringBuilder"
}

internal fun Throwable.throwQuery(reason: String): Nothing =
    if (this is SQLException) throw this else throw SQLException(reason, this)

private val allPrimitives: Set<String> = (listOf(
    Byte::class,
    Short::class,
    Int::class,
    Long::class,
    Float::class,
    Double::class,
    String::class,
    Char::class,
    StringBuilder::class,
    Boolean::class,
    Number::class,
) + getNativeAllPrimitives()).mapTo(LinkedHashSet()) { it.fullName }

internal val KClass<*>.fullName get() = qualifiedName ?: throw SQLException("Unknown class name of class $this")

internal inline fun <T : AutoCloseable, R> T.useWithException(
    message: String,
    block: (T) -> R
) = try {
    use(block)
} catch (e: Throwable) {
    e.throwQuery(message)
}

internal inline fun <R> tryQuery(
    message: String,
    block: () -> R
) = try {
    block()
} catch (e: Throwable) {
    e.throwQuery(message)
}
