@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify

import kotlin.reflect.KClass

actual typealias NativeBigInteger = java.math.BigInteger
internal actual fun systemMillis() = System.currentTimeMillis()

private val supportsIonspinBigNumbers = try {
    com.ionspin.kotlin.bignum.decimal.BigDecimal::class.simpleName
    true
} catch (e: Throwable) {
    false
}

private val supportsKotlinxDatetime = try {
    kotlinx.datetime.LocalDate::class.simpleName
    true
} catch (e: Throwable) {
    false
}

internal actual fun getNativeAllPrimitives(): Collection<KClass<*>> =
    listOf(
        java.math.BigInteger::class,
        java.math.BigDecimal::class,
        java.util.Date::class,
        java.sql.Date::class,
        java.sql.Timestamp::class,
        java.sql.Time::class
    ) +
            (if (supportsIonspinBigNumbers) listOf(
                com.ionspin.kotlin.bignum.decimal.BigDecimal::class,
                com.ionspin.kotlin.bignum.integer.BigInteger::class
            ) else emptyList()) +
            (if (supportsKotlinxDatetime) listOf(
                kotlinx.datetime.LocalDate::class,
                kotlinx.datetime.LocalDateTime::class,
                kotlinx.datetime.LocalTime::class,
                kotlin.time.Instant::class
            ) else emptyList())

actual val Any.isOtherPrimitive: Boolean
    get() = this is java.util.Date ||
            this is java.time.temporal.Temporal
            || (supportsIonspinBigNumbers && this is com.ionspin.kotlin.bignum.BigNumber<*>)
            || (supportsKotlinxDatetime && this is kotlinx.datetime.LocalDateTime)