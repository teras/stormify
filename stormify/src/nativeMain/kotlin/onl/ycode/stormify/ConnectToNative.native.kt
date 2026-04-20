@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify

import kotlin.reflect.KClass

/** Maps to ionspin [com.ionspin.kotlin.bignum.integer.BigInteger] on Native. */
actual typealias NativeBigInteger = com.ionspin.kotlin.bignum.integer.BigInteger

@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
internal actual class WeakRef<T : Any> actual constructor(referent: T) {
    private val ref = kotlin.native.ref.WeakReference(referent)
    actual fun get(): T? = ref.get()
}

internal actual fun transformResultValue(value: Any?): Any? = value

actual val Any.isOtherPrimitive
    get() = this is com.ionspin.kotlin.bignum.BigNumber<*> ||
            this is kotlinx.datetime.LocalDate ||
            this is kotlinx.datetime.LocalDateTime ||
            this is kotlinx.datetime.LocalTime ||
            this is kotlin.time.Instant

internal actual fun <T : Any> tryReflection(type: KClass<T>): EntityMeta<T>? = null

internal actual fun <T : Any> enumFromInt(enumClass: KClass<T>, value: Int): T? =
    EnumRegistry.fromInt(enumClass, value)

internal actual fun enumToInt(value: Enum<*>): Int =
    EnumRegistry.toInt(value) ?: value.ordinal

internal actual fun <T : Any> enumFromName(enumClass: KClass<T>, name: String): T? =
    EnumRegistry.fromName(enumClass, name)

internal actual fun isEnumClass(klass: KClass<*>): Boolean =
    EnumRegistry.isRegistered(klass)

internal actual fun enumEntries(klass: KClass<*>): Array<out Enum<*>>? =
    EnumRegistry.entriesOf(klass)
