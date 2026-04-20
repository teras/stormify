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
import kotlin.time.Instant as KtInstant

private val EPOCH_DATE = LocalDate(1970, 1, 1)
private val NOON = LocalTime(12, 0)
private val ANCHOR_TZ = TimeZone.UTC

/**
 * Registers conversions between kotlinx-datetime / kotlin.time types and Kotlin primitives (Long millis).
 *
 * Loaded lazily — on JVM, skipped if kotlinx-datetime is not in the classpath.
 * On native, always available.
 */
internal object KotlinxTimeConverters {
    fun register(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        // Long/Double/Float/String → kotlinx types (via milliseconds, UTC-anchored)
        registerTimeTarget(LocalDate::class, registry) {
            KtInstant.fromEpochMilliseconds(it).toLocalDateTime(ANCHOR_TZ).date
        }
        registerTimeTarget(LocalDateTime::class, registry) {
            KtInstant.fromEpochMilliseconds(it).toLocalDateTime(ANCHOR_TZ)
        }
        registerTimeTarget(LocalTime::class, registry) {
            KtInstant.fromEpochMilliseconds(it).toLocalDateTime(ANCHOR_TZ).time
        }
        registerTimeTarget(KtInstant::class, registry) {
            KtInstant.fromEpochMilliseconds(it)
        }

        // kotlinx types → Long/Double/Float/String (to milliseconds, UTC-anchored)
        registerTimeSource(LocalDate::class, registry) {
            LocalDateTime(it as LocalDate, NOON).toInstant(ANCHOR_TZ).toEpochMilliseconds()
        }
        registerTimeSource(LocalDateTime::class, registry) {
            (it as LocalDateTime).toInstant(ANCHOR_TZ).toEpochMilliseconds()
        }
        registerTimeSource(LocalTime::class, registry) {
            LocalDateTime(EPOCH_DATE, it as LocalTime).toInstant(ANCHOR_TZ).toEpochMilliseconds()
        }
        registerTimeSource(KtInstant::class, registry) {
            (it as KtInstant).toEpochMilliseconds()
        }

        val allTypes = listOf<Pair<KClass<*>, (Long) -> Any>>(
            LocalDate::class to { KtInstant.fromEpochMilliseconds(it).toLocalDateTime(ANCHOR_TZ).date },
            LocalDateTime::class to { KtInstant.fromEpochMilliseconds(it).toLocalDateTime(ANCHOR_TZ) },
            LocalTime::class to { KtInstant.fromEpochMilliseconds(it).toLocalDateTime(ANCHOR_TZ).time },
            KtInstant::class to { KtInstant.fromEpochMilliseconds(it) },
        )
        val toMillis = mapOf<KClass<*>, (Any) -> Long>(
            LocalDate::class to { LocalDateTime(it as LocalDate, NOON).toInstant(ANCHOR_TZ).toEpochMilliseconds() },
            LocalDateTime::class to { (it as LocalDateTime).toInstant(ANCHOR_TZ).toEpochMilliseconds() },
            LocalTime::class to { LocalDateTime(EPOCH_DATE, it as LocalTime).toInstant(ANCHOR_TZ).toEpochMilliseconds() },
            KtInstant::class to { (it as KtInstant).toEpochMilliseconds() },
        )
        // Wall-clock ↔ wall-clock pairs never go through epoch: the direct
        // converters below cover the decomposed cases, and LocalDate ↔ LocalTime
        // is intentionally unsupported (meaningless). Only KtInstant pivots remain.
        val wallClocks = setOf<KClass<*>>(LocalDate::class, LocalDateTime::class, LocalTime::class)
        for ((targetClass, fromMillis) in allTypes) {
            val group = registry.getOrPut(targetClass) { mutableMapOf() }
            for ((sourceClass, sourceToMillis) in toMillis) {
                if (sourceClass == targetClass) continue
                if (targetClass in wallClocks && sourceClass in wallClocks) continue
                group[sourceClass] = { fromMillis(sourceToMillis(it)) }
            }
        }

        // Direct pair-wise converters. Registered last so they override any
        // overlapping epoch-pivot entry (see `direct` kdoc).
        direct(registry, LocalDateTime::class, LocalDate::class) { LocalDateTime(it as LocalDate, LocalTime(0, 0)) }
        direct(registry, LocalDate::class, LocalDateTime::class) { (it as LocalDateTime).date }
        direct(registry, LocalTime::class, LocalDateTime::class) { (it as LocalDateTime).time }
        direct(registry, LocalDateTime::class, LocalTime::class) { LocalDateTime(EPOCH_DATE, it as LocalTime) }
        listOf(LocalDate::class, LocalDateTime::class, LocalTime::class)
            .forEach { t -> direct(registry, String::class, t) { it.toString() } }
        direct(registry, LocalDate::class, String::class) { LocalDate.parse(it as String) }
        direct(registry, LocalDateTime::class, String::class) { LocalDateTime.parse(it as String) }
        direct(registry, LocalTime::class, String::class) { LocalTime.parse(it as String) }
    }

    /** Registers a direct (pair-wise) converter in the registry. Later registrations
     *  override earlier ones at the same (target, source) key. */
    internal fun direct(
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
        target: KClass<*>,
        source: KClass<*>,
        fn: (Any) -> Any
    ) {
        registry.getOrPut(target) { mutableMapOf() }[source] = fn
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
            LocalDateTime.parse(s).toInstant(ANCHOR_TZ).toEpochMilliseconds()
        } catch (_: Exception) {
            try {
                LocalDateTime(LocalDate.parse(s), NOON).toInstant(ANCHOR_TZ).toEpochMilliseconds()
            } catch (e: Exception) {
                throw SQLException("Unable to parse temporal string: $s", e)
            }
        }
    }
}
