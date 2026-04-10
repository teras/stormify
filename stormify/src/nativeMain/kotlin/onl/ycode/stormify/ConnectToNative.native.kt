@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.reflect.KClass

actual typealias NativeBigInteger = com.ionspin.kotlin.bignum.integer.BigInteger

@OptIn(ExperimentalForeignApi::class)
internal actual fun systemMillis() = memScoped {
    val time = alloc<timeval>()
    gettimeofday(time.ptr, null)
    time.tv_sec * 1000 + time.tv_usec / 1000
}

internal actual fun getNativeAllPrimitives(): Collection<KClass<*>> = listOf(
    com.ionspin.kotlin.bignum.decimal.BigDecimal::class,
    com.ionspin.kotlin.bignum.integer.BigInteger::class,
    kotlinx.datetime.LocalDate::class,
    kotlinx.datetime.LocalDateTime::class,
    kotlinx.datetime.LocalTime::class,
    kotlin.time.Instant::class,
    // On native we promote binary/char array types to scalars so that the TableInfo-based
    // column dispatch routes BLOB/CLOB columns to the typed NativeResultSet.getObject path.
    // JVM relies on jdbc.getObject(i) to hand back `byte[]` through the Any::class path;
    // the native C layer has no equivalent natural-type lookup, so we need the explicit hint.
    ByteArray::class,
    CharArray::class
)

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

internal actual fun enumEntries(klass: KClass<*>): Array<out Enum<*>>? = null // native: use explicit enumValues