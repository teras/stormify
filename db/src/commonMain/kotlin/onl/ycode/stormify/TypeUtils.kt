// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify

import kotlin.reflect.KClass

/**
 * Utility class for converting between different types and handling exceptions.
 */
object TypeUtils {
    /**
     * Convert a value to the target class
     *
     * @param targetClass the target class
     * @param value       the value to convert
     * @param <F>         the source class type
     * @param <T>         the target class type
     * @return the converted value
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <F : Any, T : Any> castTo(targetClass: KClass<T>, value: F?, stormify: Stormify): T? {
        if (value == null || targetClass.isInstance(value)) return value as T?
        val givenClass = value::class
        if (!isScalarObject(value)) {
            val info = TableInfo.retrieve(givenClass)
            val item = info.create()
            if (item is AutoTable) item.`!stormify` = stormify
            info.setField(item, info.singleKeyName, value, stormify)
            return item as T
        }
        val typeConv = (registry[targetClass]
            ?: throw QueryException("Target class " + targetClass.fullName + " is not convertible"))[givenClass]
            ?: throw QueryException("Unable to convert " + givenClass.fullName + " to " + targetClass.fullName)
        return try {
            typeConv(value) as T
        } catch (th: Throwable) {
            throw QueryException(
                "Error while trying to convert from " + value::class.fullName + " to " + targetClass.fullName,
                th
            )
        }
    }

    // first key: target class
    // second key: source class
    // function: converter from source class to target class
    val registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>> = HashMap()

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
            toBoolean[target] = { (it as Number) == 0 }
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
     * @param sourceClass the source class that needs to be converted
     * @param targetClass the target class that the data should be converted to
     * @param converter   the function that will convert the data
     * @param <F>         the source class type
     * @param <T>         the target class type
     * @return the previous conversion function if it was already registered
    </T></F> */
    fun <F : Any, T : Any> register(
        sourceClass: KClass<F>,
        targetClass: KClass<T>,
        converter: (Any) -> Any
    ) {
        val group = registry[targetClass] ?: mutableMapOf()
        if (group.isEmpty())
            registry[targetClass] = group
        group[sourceClass] = converter
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
            else throw QueryException("Multiple instances of '$key' found in $spaceName")
    if (found < 0)
        throw QueryException("Unable to find any instances of '$key' in $spaceName")
    return found
}

internal fun isScalarObject(request: Any) =
    request is Number || request is CharSequence || request is Char || request is Boolean || request.isOtherPrimitive

internal fun isScalarClass(request: KClass<*>) = allPrimitives.contains(request.fullName)


internal fun Throwable.throwQuery(reason: String): Nothing =
    if (this is QueryException) throw this else throw QueryException(reason, this)

internal fun convertNativeTypeToSQLType(type: KClass<out Any>): Int {
    TODO("Not yet implemented")
}

private val allPrimitives: Set<String> = (listOf(
    Byte::class,
    Short::class,
    Int::class,
    Long::class,
    Float::class,
    Double::class,
    Char::class,
    Boolean::class,
    String::class,
    CharSequence::class,
    Number::class,
) + getNativeAllPrimitives()).mapTo(LinkedHashSet()) { it.fullName }

internal val KClass<*>.fullName get() = qualifiedName ?: throw QueryException("Unknown class name of class $this")

internal inline fun <T : AutoCloseable, R> T.useWithException(
    message: String,
    block: (T) -> R
) = try {
    use(block)
} catch (q: QueryException) {
    throw q
} catch (e: Throwable) {
    throw QueryException(message, e)
}

internal inline fun <R> tryQuery(
    message: String,
    block: () -> R
) = try {
    block()
} catch (q: QueryException) {
    throw q
} catch (e: Throwable) {
    throw QueryException(message, e)
}