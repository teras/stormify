// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc.converters

import kotlin.reflect.KClass

/**
 * Desktop JVM path. Uses the JDK 8 bridge methods on `java.sql.*`
 * (`toLocalDate()`, `toLocalDateTime()`, `toLocalTime()`, and the
 * corresponding `valueOf(...)` factories) to stay on the well-trodden
 * JDK-blessed conversion path.
 */
internal actual fun registerSqlTimeDirectConverters(
    registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>
) {
    bidi<java.sql.Date, java.time.LocalDate>(
        registry,
        { it.toLocalDate() },
        { java.sql.Date.valueOf(it) }
    )
    bidi<java.sql.Timestamp, java.time.LocalDateTime>(
        registry,
        { it.toLocalDateTime() },
        { java.sql.Timestamp.valueOf(it) }
    )
    bidi<java.sql.Time, java.time.LocalTime>(
        registry,
        { it.toLocalTime() },
        { java.sql.Time.valueOf(it) }
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
