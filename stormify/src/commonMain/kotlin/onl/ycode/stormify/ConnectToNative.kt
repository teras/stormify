@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.stormify

import kotlin.reflect.KClass

/** Platform-specific big integer type. Maps to `java.math.BigInteger` on JVM and ionspin `BigInteger` on Native. */
expect class NativeBigInteger

/** Whether this value is a platform-specific primitive type (e.g. `java.math.BigDecimal` on JVM). */
expect val Any.isOtherPrimitive: Boolean

internal expect fun getNativeAllPrimitives(): Collection<KClass<*>>
internal expect fun transformResultValue(value: Any?): Any?

internal expect class WeakRef<T : Any>(referent: T) {
    fun get(): T?
}

internal expect fun <T : Any> tryReflection(type: KClass<T>): EntityMeta<T>?

/**
 * Convert an integer value from the database to an enum constant of the given class.
 * For enums implementing [DbValue], matches by [DbValue.dbValue].
 * For plain enums, uses ordinal.
 * Returns null if no matching entry is found.
 */
internal expect fun <T : Any> enumFromInt(enumClass: KClass<T>, value: Int): T?

/**
 * Convert an enum constant to its integer representation for the database.
 * For enums implementing [DbValue], returns [DbValue.dbValue].
 * For plain enums, returns [Enum.ordinal].
 */
internal expect fun enumToInt(value: Enum<*>): Int

/**
 * Convert a string name from the database to an enum constant of the given class.
 * Uses [Enum.name] matching.
 * Returns null if no matching entry is found.
 */
internal expect fun <T : Any> enumFromName(enumClass: KClass<T>, name: String): T?

/**
 * Returns true if the given class is an enum class.
 */
internal expect fun isEnumClass(klass: KClass<*>): Boolean

/**
 * Returns all enum entries for the given class, or null if not an enum.
 */
internal expect fun enumEntries(klass: KClass<*>): Array<out Enum<*>>?
