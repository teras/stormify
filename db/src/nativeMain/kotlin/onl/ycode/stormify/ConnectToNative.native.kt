@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

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
    kotlinx.datetime.LocalTime::class
)

actual val Any.isOtherPrimitive
    get() = this is com.ionspin.kotlin.bignum.BigNumber<*> ||
            this is kotlinx.datetime.LocalDate ||
            this is kotlinx.datetime.LocalDateTime ||
            this is kotlinx.datetime.LocalTime