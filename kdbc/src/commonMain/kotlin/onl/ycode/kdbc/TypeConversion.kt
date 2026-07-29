// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import onl.ycode.kdbc.converters.IonspinConverters
import onl.ycode.kdbc.converters.KotlinUuidConverters
import onl.ycode.kdbc.converters.KotlinxTimeConverters
import kotlin.jvm.JvmSynthetic
import kotlin.reflect.KClass

/**
 * Type conversion registry for scalar values (numbers, strings, dates, booleans, etc.).
 *
 * Provides bidirectional conversion between all scalar types the database layer may encounter:
 * Kotlin primitives, java.math/sql/time types (JVM), ionspin bignum (optional on JVM, always on native),
 * and kotlinx-datetime (optional on JVM, always on native).
 *
 * Entity-level conversions (e.g. foreign key → entity object) are handled at the ORM layer (Stormify),
 * not here.
 */
object TypeConversion {

    // first key: target class, second key: source class
    @JvmSynthetic
    internal val registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>> = HashMap()

    /** True if kdbc has a scalar converter registered for [type]. */
    fun isKnownScalar(type: KClass<*>): Boolean = registry.containsKey(type)

    /**
     * Convert a scalar value to the target class.
     *
     * @return the converted value, or null if value is null
     * @throws SQLException if conversion is not possible
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> castScalar(targetClass: KClass<T>, value: Any?): T? {
        if (value == null || targetClass.isInstance(value)) return value as T?
        val normalized: Any = if (value is CharSequence && value !is String) value.toString() else value
        if (targetClass.isInstance(normalized)) return normalized as T
        val givenClass = normalized::class
        val converters = registry[targetClass]
            ?: throw SQLException("Target class ${targetClass.qualifiedName} is not convertible")
        val typeConv = converters[givenClass]
            ?: (if (normalized is Number) converters[Number::class] else null)
            ?: throw SQLException("Unable to convert ${givenClass.qualifiedName} to ${targetClass.qualifiedName}")
        return try {
            typeConv(normalized) as T
        } catch (e: Throwable) {
            if (e is SQLException) throw e
            throw SQLException("Error converting ${givenClass.qualifiedName} to ${targetClass.qualifiedName}", e)
        }
    }

    /**
     * Register a custom conversion function.
     *
     * Registration mutates a global, non-synchronized registry — call this at
     * startup, before any concurrent query traffic. The built-in converters are
     * registered during object initialization, which is already thread-safe.
     */
    fun register(
        sourceClass: KClass<*>,
        targetClass: KClass<*>,
        converter: (Any) -> Any
    ) {
        registry.getOrPut(targetClass) { mutableMapOf() }[sourceClass] = converter
    }

    /**
     * Tokens a text column may use to encode a true flag: the set every JDBC driver
     * agrees on, so a text flag reads the same on JVM and native. A `CHAR(1)` holding
     * `'Y'` is the standard Oracle idiom, since Oracle has no BOOLEAN column type.
     */
    private val trueTokens = arrayOf("true", "t", "yes", "y", "1")

    /** True when [text] is one of the recognised true tokens, ignoring case. */
    internal fun isTrueToken(text: String): Boolean =
        trueTokens.any { it.equals(text, ignoreCase = true) }

    /**
     * Interprets [text] as a boolean, so that a flag means the same thing whether it
     * was stored in a numeric or a text column: a numeric payload follows the numeric
     * rule and is true when non-zero, anything else falls back to [isTrueToken].
     *
     * This is the single rule every platform reads booleans by — JDBC drivers
     * disagree with each other here, so the answer cannot be delegated to them.
     */
    internal fun asBoolean(text: String): Boolean {
        val t = text.trim()
        t.toLongOrNull()?.let { return it != 0L }
        t.toDoubleOrNull()?.let { return it != 0.0 }
        return isTrueToken(t)
    }

    init {
        val toBoolean = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[Boolean::class] = it }
        val toString = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[String::class] = it }
        val toChar = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[Char::class] = it }
        val toByteArr = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[ByteArray::class] = it }
        val toCharArr = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[CharArray::class] = it }

        toBoolean[String::class] = { asBoolean(it as String) }
        toBoolean[Char::class] = { asBoolean((it as Char).toString()) }
        toBoolean[Number::class] = { (it as Number).toInt() != 0 }

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
            val fromGroup = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[target] = it }
            numeric.forEach { (source, _) ->
                if (source != target)
                    fromGroup[source] = converter
            }
            fromGroup[Number::class] = converter
            fromGroup[Boolean::class] = { converter(if ((it as Boolean)) 1 else 0) }
            toBoolean[target] = { (it as Number).toInt() != 0 }
            if (target != Double::class && target != Float::class)
            // A decimal column read into an integral field arrives as text on the
            // drivers that stringify numerics; truncate toward zero, as both the
            // native drivers and JDBC getInt do.
                fromGroup[String::class] = {
                    val t = (it as String).trim()
                    converter(t.toLongOrNull() ?: t.toDouble().toLong())
                }
            else
                fromGroup[String::class] = { converter((it as String).toDouble()) }
            toString[target] = { it.toString() }
        }

        val toNumber = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[Number::class] = it }
        toNumber[String::class] = { s ->
            val str = s as String
            str.toLongOrNull() ?: str.toDoubleOrNull()
                ?: throw SQLException("Cannot parse '$str' as Number")
        }

        try { IonspinConverters.register(registry) } catch (_: Throwable) {}
        try { KotlinUuidConverters.register(registry) } catch (_: Throwable) {}
        // Platform converters (JVM: java.math / java.sql / java.time) run BEFORE
        // KotlinxTimeConverters so that kotlinx direct pair-wise converters registered
        // at the tail of KotlinxTimeConverters.register() are the last writers to the
        // registry and naturally win. See the ordering commentary in
        // KotlinxTimeConverters.register() for the rationale.
        registerPlatformConverters(registry)
        try { KotlinxTimeConverters.register(registry) } catch (_: Throwable) {}

        // Every number has a text form, whatever class the driver hands back. The
        // numeric group above covers the Kotlin primitives by name; this covers
        // java.math.BigDecimal and anything else that arrives as a plain Number.
        toString[Number::class] = { it.toString() }
        // A CharArray is the character view of a value, so whatever has a text form
        // has one too. Mirroring the String sources — after every platform converter
        // has registered — keeps the two in step instead of maintaining a second list
        // that silently falls behind on one driver and not another.
        toString.forEach { (source, toText) ->
            if (source !in toCharArr) toCharArr[source] = { (toText(it) as String).toCharArray() }
        }
    }
}

internal expect fun registerPlatformConverters(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>)
