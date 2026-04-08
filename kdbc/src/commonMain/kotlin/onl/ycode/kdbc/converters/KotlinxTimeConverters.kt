// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.kdbc.converters
import onl.ycode.kdbc.SQLException

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Instant as KtInstant

/**
 * Registers conversions between kotlinx-datetime / kotlin.time types and Kotlin primitives (Long millis).
 *
 * Loaded lazily — on JVM, skipped if kotlinx-datetime is not in the classpath.
 * On native, always available.
 */
internal object KotlinxTimeConverters {
    fun register(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        // Long/Double/Float/String → kotlinx types (via milliseconds)
        registerTimeTarget(LocalDate::class, registry) {
            KtInstant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date
        }
        registerTimeTarget(LocalDateTime::class, registry) {
            KtInstant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        }
        registerTimeTarget(LocalTime::class, registry) {
            KtInstant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).time
        }
        registerTimeTarget(KtInstant::class, registry) {
            KtInstant.fromEpochMilliseconds(it)
        }

        // kotlinx types → Long/Double/Float/String (to milliseconds)
        registerTimeSource(LocalDate::class, registry) {
            LocalDateTime(it as LocalDate, LocalTime(0, 0))
                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }
        registerTimeSource(LocalDateTime::class, registry) {
            (it as LocalDateTime).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }
        registerTimeSource(LocalTime::class, registry) {
            val date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
            LocalDateTime(date, it as LocalTime)
                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        }
        registerTimeSource(KtInstant::class, registry) {
            (it as KtInstant).toEpochMilliseconds()
        }

        // Cross-conversions between kotlinx types
        val allTypes = listOf<Pair<KClass<*>, (Long) -> Any>>(
            LocalDate::class to { KtInstant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date },
            LocalDateTime::class to { KtInstant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()) },
            LocalTime::class to { KtInstant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).time },
            KtInstant::class to { KtInstant.fromEpochMilliseconds(it) },
        )
        val toMillis = mapOf<KClass<*>, (Any) -> Long>(
            LocalDate::class to { LocalDateTime(it as LocalDate, LocalTime(0, 0)).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() },
            LocalDateTime::class to { (it as LocalDateTime).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() },
            LocalTime::class to { val d = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date; LocalDateTime(d, it as LocalTime).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds() },
            KtInstant::class to { (it as KtInstant).toEpochMilliseconds() },
        )
        for ((targetClass, fromMillis) in allTypes) {
            val group = registry.getOrPut(targetClass) { mutableMapOf() }
            for ((sourceClass, sourceToMillis) in toMillis) {
                if (sourceClass != targetClass)
                    group[sourceClass] = { fromMillis(sourceToMillis(it)) }
            }
        }
    }

    private fun <T : Any> registerTimeTarget(
        targetClass: KClass<T>,
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
        fromMillis: (Long) -> T
    ) {
        val group = registry.getOrPut(targetClass) { mutableMapOf() }
        group[Long::class] = { fromMillis(it as Long) }
        group[Double::class] = { fromMillis(((it as Double) * 1000.0).toLong()) }
        group[Float::class] = { fromMillis(((it as Float) * 1000.0).toLong()) }
        group[String::class] = { fromMillis(parseTemporalString(it as String)) }
    }

    private fun registerTimeSource(
        sourceClass: KClass<*>,
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
        toMillis: (Any) -> Long
    ) {
        registry[Long::class]?.let { it[sourceClass] = { v -> toMillis(v) } }
        registry[Double::class]?.let { it[sourceClass] = { v -> toMillis(v) / 1000.0 } }
        registry[Float::class]?.let { it[sourceClass] = { v -> toMillis(v) / 1000f } }
        registry[String::class]?.let { it[sourceClass] = { v ->
            KtInstant.fromEpochMilliseconds(toMillis(v)).toString()
        }}
    }

    private fun parseTemporalString(s: String): Long = try {
        KtInstant.parse(s).toEpochMilliseconds()
    } catch (_: Exception) {
        try {
            LocalDateTime.parse(s).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        } catch (_: Exception) {
            try {
                LocalDateTime(LocalDate.parse(s), LocalTime(0, 0))
                    .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
            } catch (e: Exception) {
                throw SQLException("Unable to parse temporal string: $s", e)
            }
        }
    }
}
