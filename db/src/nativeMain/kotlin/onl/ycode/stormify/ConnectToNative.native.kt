@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package onl.ycode.stormify

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.gettimeofday
import platform.posix.timeval
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.Worker
import kotlin.reflect.KClass

actual typealias NativeBigInteger = com.ionspin.kotlin.bignum.integer.BigInteger

@OptIn(ExperimentalForeignApi::class)
internal actual fun systemMillis() = memScoped {
    val time = alloc<timeval>()
    gettimeofday(time.ptr, null)
    time.tv_sec * 1000 + time.tv_usec / 1000
}

private fun getCoreIonspinBigNumbers() = try {
    listOf(com.ionspin.kotlin.bignum.BigNumber::class)
} catch (e: Throwable) {
    emptyList()
}

private fun getAllIonspinBigNumbers() = try {
    listOf(com.ionspin.kotlin.bignum.decimal.BigDecimal::class, com.ionspin.kotlin.bignum.integer.BigInteger::class)
} catch (e: Throwable) {
    emptyList()
}

private fun getKotlinxDatetime() = try {
    listOf(kotlinx.datetime.LocalDate::class, kotlinx.datetime.LocalDateTime::class, kotlinx.datetime.LocalTime::class)
} catch (e: Throwable) {
    emptyList()
}

internal actual fun getNativeAllPrimitives(): Collection<KClass<*>> = listOf(
    com.ionspin.kotlin.bignum.decimal.BigDecimal::class,
    com.ionspin.kotlin.bignum.integer.BigInteger::class,
    kotlinx.datetime.LocalDate::class,
    kotlinx.datetime.LocalDateTime::class,
    kotlinx.datetime.LocalTime::class
)

@ObsoleteWorkersApi
actual class ThreadLocal<T : Any> actual constructor() {
    private val values = mutableMapOf<Int, T>()

    actual fun set(value: T) {
        values[Worker.current.id] = value
    }

    actual fun get() = values[Worker.current.id]

    actual fun remove() {
        values.remove(Worker.current.id)
    }
}

actual val Any.isOtherPrimitive
    get() = this is com.ionspin.kotlin.bignum.BigNumber<*> ||
            this is kotlinx.datetime.LocalDate ||
            this is kotlinx.datetime.LocalDateTime ||
            this is kotlinx.datetime.LocalTime