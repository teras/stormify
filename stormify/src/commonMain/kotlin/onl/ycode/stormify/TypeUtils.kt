// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import kotlin.reflect.KClass

/**
 * Utility class for converting between different types and handling exceptions.
 */
object TypeUtils {
    /**
     * Convert a value to the target class
     *
     * @param F the source class type
     * @param T the target class type
     * @param targetClass the target class
     * @param value the value to convert
     * @param stormify optional Stormify instance for entity conversion
     * @return the converted value
     */
    @Suppress("UNCHECKED_CAST")
    fun <F : Any, T : Any> castTo(targetClass: KClass<T>, value: F?, stormify: Stormify? = null): T? {
        if (value == null || targetClass.isInstance(value)) return value as T?
        val givenClass = value::class
        if (!isScalarObject(value)) {
            if (stormify == null)
                throw SQLException("Unable to convert non-scalar object to " + targetClass.fullName + "; missing database context")
            val info = stormify.resolveTableInfo(givenClass)
            val item = info.create()
            if (item is AutoTable) item.`!stormify` = stormify
            info.setField(item, info.singleKeyDbName, value, stormify)
            return item as T
        }
        val typeConv = (registry[targetClass]
            ?: throw SQLException("Target class " + targetClass.fullName + " is not convertible"))[givenClass]
            ?: throw SQLException("Unable to convert " + givenClass.fullName + " to " + targetClass.fullName)
        return try {
            typeConv(value) as T
        } catch (th: Throwable) {
            th.throwQuery("Error while trying to convert from " + value::class.fullName + " to " + targetClass.fullName)
        }
    }

    fun err(name: String, cls: String): Nothing = throw SQLException("$name cannot be null in class $cls")

    // first key: target class
    // second key: source class
    // function: converter from source class to target class
    @PublishedApi
    internal val registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>> = HashMap()

    init {
        val toBoolean = mutableMapOf<KClass<*>, (Any) -> Any>()
        registry[Boolean::class] = toBoolean
        val toString = mutableMapOf<KClass<*>, (Any) -> Any>()
        registry[String::class] = toString
        val toChar = mutableMapOf<KClass<*>, (Any) -> Any>()
        registry[Char::class] = toChar
        val toByteArr = mutableMapOf<KClass<*>, (Any) -> Any>()
        registry[ByteArray::class] = toByteArr
        val toCharArr = mutableMapOf<KClass<*>, (Any) -> Any>()
        registry[CharArray::class] = toCharArr

        toBoolean[String::class] = { (it as String).toBoolean() }
        toBoolean[Char::class] = { (it as Char) == '1' }

        toString[Boolean::class] = { it.toString() }
        toString[Char::class] = { it.toString() }
        toString[ByteArray::class] = { (it as ByteArray).decodeToString() }
        toString[CharArray::class] = { (it as CharArray).concatToString() }

        toCharArr[String::class] = { (it as String).toCharArray() }
        toCharArr[ByteArray::class] = { (it as ByteArray).decodeToString().toCharArray() }

        toByteArr[String::class] = { (it as String).encodeToByteArray() }
        toByteArr[CharArray::class] = { (it as CharArray).concatToString().encodeToByteArray() }

        toChar[String::class] = { (it as String).let { s -> if (s.isEmpty()) '\u0000' else s[0] } }
        toChar[Boolean::class] = { if ((it as Boolean)) '1' else '0' }

        val numeric = arrayOf<Pair<KClass<*>, (Any) -> Any>>(
            Byte::class to { (it as Number).toByte() },
            Short::class to { (it as Number).toShort() },
            Int::class to { (it as Number).toInt() },
            Long::class to { (it as Number).toLong() },
            Float::class to { (it as Number).toFloat() },
            Double::class to { (it as Number).toDouble() }
        )
        numeric.forEach { (target, converter) ->
            // numeric from one to another
            val fromGroup = mutableMapOf<KClass<*>, (Any) -> Any>()
            registry[target] = fromGroup
            numeric.forEach { (source, _) ->
                if (source != target)
                    fromGroup[source] = converter
            }
            // from/to boolean
            fromGroup[Boolean::class] = { converter(if ((it as Boolean)) 1 else 0) }
            toBoolean[target] = { (it as Number).toInt() != 0 }
            // from/to String
            if (target != Double::class && target != Float::class)
                fromGroup[String::class] = { converter((it as String).toLong()) }
            else
                fromGroup[String::class] = { converter((it as String).toDouble()) }
            toString[target] = { it.toString() }
        }

        registerNativeTargets(registry)
    }

    /**
     * Register a conversion function from sourceClass to targetClass.
     * This function will provide custom conversion between classes, when casting objects of
     * different types.
     *
     * @param F the source class type
     * @param T the target class type
     * @param sourceClass the source class that needs to be converted
     * @param targetClass the target class that the data should be converted to
     * @param converter the function that will convert the data
     */
    private val registryLock = kotlinx.atomicfu.locks.SynchronizedObject()

    fun <F : Any, T : Any> register(
        sourceClass: KClass<F>,
        targetClass: KClass<T>,
        converter: (Any) -> Any
    ) = kotlinx.atomicfu.locks.synchronized(registryLock) {
        registry.getOrPut(targetClass) { mutableMapOf() }[sourceClass] = converter
    }
}

internal expect fun registerNativeTargets(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>)

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