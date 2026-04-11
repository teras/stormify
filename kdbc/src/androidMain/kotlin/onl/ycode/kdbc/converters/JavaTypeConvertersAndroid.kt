// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc.converters

import kotlin.reflect.KClass

/**
 * Android path. Android's `java.sql.*` types don't expose the JDK 8 bridge
 * methods (`toLocalDate()`, `Timestamp.valueOf(LocalDateTime)`, …), so we
 * construct values field-wise via the legacy deprecated accessors which have
 * been present on `java.util.Date` / `java.sql.*` since JDK 1.0 and are
 * available on every Android API level.
 */
@Suppress("DEPRECATION")
internal actual fun registerSqlTimeDirectConverters(
    registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>
) {
    bidi<java.sql.Date, java.time.LocalDate>(
        registry,
        { java.time.LocalDate.of(it.year + 1900, it.month + 1, it.date) },
        { java.sql.Date(it.year - 1900, it.monthValue - 1, it.dayOfMonth) }
    )
    bidi<java.sql.Timestamp, java.time.LocalDateTime>(
        registry,
        {
            java.time.LocalDateTime.of(
                it.year + 1900, it.month + 1, it.date,
                it.hours, it.minutes, it.seconds, it.nanos
            )
        },
        {
            java.sql.Timestamp(
                it.year - 1900, it.monthValue - 1, it.dayOfMonth,
                it.hour, it.minute, it.second, it.nano
            )
        }
    )
    bidi<java.sql.Time, java.time.LocalTime>(
        registry,
        { java.time.LocalTime.of(it.hours, it.minutes, it.seconds) },
        { java.sql.Time(it.hour, it.minute, it.second) }
    )
}

private inline fun <reified S : Any, reified T : Any> bidi(
    registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
    crossinline s2t: (S) -> T,
    crossinline t2s: (T) -> S
) {
    registry.getOrPut(T::class) { mutableMapOf() }[S::class] = { s2t(it as S) }
    registry.getOrPut(S::class) { mutableMapOf() }[T::class] = { t2s(it as T) }
}
