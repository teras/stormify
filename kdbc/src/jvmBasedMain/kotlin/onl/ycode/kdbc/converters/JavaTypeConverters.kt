// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.kdbc.converters
import onl.ycode.kdbc.SQLException

import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass
import kotlinx.datetime.toInstant
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toJavaLocalTime
import kotlinx.datetime.toKotlinLocalDate
import kotlinx.datetime.toKotlinLocalDateTime
import kotlinx.datetime.toKotlinLocalTime
import kotlinx.datetime.toLocalDateTime

/**
 * Platform hook for registering direct `java.sql.* ↔ java.time.*` converters.
 *
 * JVM desktop and Android diverge here: desktop can rely on the JDK 8 bridge
 * methods (`Date.toLocalDate()`, `Timestamp.valueOf(LocalDateTime)`, …), while
 * Android's `java.sql.*` types don't carry those bridges and must fall back to
 * field-wise construction via the legacy deprecated accessors.
 */
internal expect fun registerSqlTimeDirectConverters(
    registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>
)

/**
 * Registers conversions for Java-specific types: java.math, java.sql, java.time, and vendor types.
 * Also registers cross-conversions between java types and kotlinx-datetime types (if available).
 */
internal object JavaTypeConverters {
    private val int = listOf(Byte::class, Short::class, Int::class, Long::class)
    private val dec = listOf(Float::class, Double::class)

    fun register(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        registerJavaMath(registry)
        registerJavaTime(registry)
        registerVendorTypes(registry)
    }

    private fun registerJavaMath(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        val toBigDecimal = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BigDecimal::class] = it }
        int.forEach { t -> toBigDecimal[t] = { BigDecimal((it as Number).toLong()) } }
        dec.forEach { t -> toBigDecimal[t] = { BigDecimal((it as Number).toDouble()) } }
        toBigDecimal[BigInteger::class] = { (it as BigInteger).toBigDecimal() }
        toBigDecimal[Boolean::class] = { if (it as Boolean) BigDecimal.ONE else BigDecimal.ZERO }

        val toBigInteger = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BigInteger::class] = it }
        int.forEach { t -> toBigInteger[t] = { BigInteger.valueOf((it as Number).toLong()) } }
        dec.forEach { t -> toBigInteger[t] = { BigDecimal((it as Number).toDouble()).toBigInteger() } }
        toBigInteger[BigDecimal::class] = { (it as BigDecimal).toBigInteger() }
        toBigInteger[Boolean::class] = { if (it as Boolean) BigInteger.ONE else BigInteger.ZERO }

        registry[String::class]?.let { group ->
            group[BigDecimal::class] = { it.toString() }
            group[BigInteger::class] = { it.toString() }
        }
        toBigDecimal[String::class] = { BigDecimal(it as String) }
        toBigInteger[String::class] = { BigInteger(it as String) }

        registry[Number::class]?.let { toNumber ->
            toNumber[String::class] = { s ->
                val str = s as String
                str.toLongOrNull() ?: str.toDoubleOrNull()
                    ?: runCatching { BigInteger(str) }.getOrNull()
                    ?: runCatching { BigDecimal(str) }.getOrNull()
                    ?: throw SQLException("Cannot parse '$str' as Number")
            }
        }
    }

    private fun registerJavaTime(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        val supportsKotlinxTime = try {
            kotlinx.datetime.LocalDate::class.simpleName
            true
        } catch (_: Throwable) {
            false
        }

        registerTimeRelated(java.util.Date::class, false, supportsKotlinxTime, { java.util.Date(it) }, registry)
        registerTimeRelated(java.sql.Date::class, false, supportsKotlinxTime, { java.sql.Date(it) }, registry)
        registerTimeRelated(java.sql.Timestamp::class, false, supportsKotlinxTime, { java.sql.Timestamp(it) }, registry)
        registerTimeRelated(java.sql.Time::class, false, supportsKotlinxTime, { java.sql.Time(it) }, registry)
        registerTimeRelated(
            java.time.LocalDateTime::class, false, supportsKotlinxTime,
            { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime() }, registry
        )
        registerTimeRelated(
            java.time.LocalDate::class, false, supportsKotlinxTime,
            { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }, registry
        )
        registerTimeRelated(
            java.time.LocalTime::class, false, supportsKotlinxTime,
            { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime() }, registry
        )
        // Core types: Long, Double, Float, String — add java.sql/time sources to existing groups
        registerTimeRelated(Long::class, true, supportsKotlinxTime, { it }, registry)
        registerTimeRelated(Double::class, true, supportsKotlinxTime, { it / 1000.0 }, registry)
        registerTimeRelated(Float::class, true, supportsKotlinxTime, { it / 1000f }, registry)
        registerTimeRelated(
            String::class, true, supportsKotlinxTime,
            { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_INSTANT) }, registry
        )
        if (supportsKotlinxTime) {
            registerKotlinxTimeTargets(registry)
        }

        // Direct pair-wise converters for pure-decomposed pairs. Registered AFTER
        // the epoch-pivot block above, so the registry naturally prefers the direct
        // path wherever one exists. Bug-fixes disguised as structural changes:
        // no timezone traversal, no noon hack, no non-determinism.
        registerJavaTimeDirectConverters(registry, supportsKotlinxTime)
    }

    /**
     * Registers a direct (pair-wise) converter. Later registrations override earlier
     * ones at the same (target, source) key.
     */
    private fun direct(
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
        target: KClass<*>,
        source: KClass<*>,
        fn: (Any) -> Any
    ) {
        registry.getOrPut(target) { mutableMapOf() }[source] = fn
    }

    /**
     * Registers a bidirectional direct pair via two `direct` calls.
     * Use for symmetric JDK-provided conversions (`toLocalDate` / `Date.valueOf`, etc.).
     */
    private inline fun <reified S : Any, reified T : Any> bidi(
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
        crossinline s2t: (S) -> T,
        crossinline t2s: (T) -> S
    ) {
        direct(registry, T::class, S::class) { s2t(it as S) }
        direct(registry, S::class, T::class) { t2s(it as T) }
    }

    private fun registerJavaTimeDirectConverters(
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>,
        supportsKotlinxTime: Boolean
    ) {
        // java.time pure-decomposed pairs
        bidi<java.time.LocalDate, java.time.LocalDateTime>(
            registry,
            { it.atStartOfDay() },
            { it.toLocalDate() }
        )
        direct(registry, java.time.LocalTime::class, java.time.LocalDateTime::class) {
            (it as java.time.LocalDateTime).toLocalTime()
        }

        // java.sql ↔ java.time direct pairs are registered per-platform:
        // JVM desktop can use the JDK 8 bridge methods (toLocalDate(),
        // Timestamp.valueOf(LocalDateTime), …), while Android's java.sql.*
        // types lack those bridges and must use field-wise construction.
        registerSqlTimeDirectConverters(registry)

        // java.time → String via ISO toString() (preserves the exact decomposed
        // value; the old path round-tripped through a noon-anchored Instant and
        // yielded "2026-03-20T12:00:00Z" for a plain LocalDate).
        listOf(
            java.time.LocalDate::class,
            java.time.LocalDateTime::class,
            java.time.LocalTime::class
        ).forEach { t -> direct(registry, String::class, t) { it.toString() } }

        // Cross-type: kotlinx.datetime ↔ java.time via the official interop
        // extensions. These don't touch a timezone — they just rewrap the wire
        // representation — so they're structurally DST-immune.
        if (supportsKotlinxTime) {
            bidi<kotlinx.datetime.LocalDate, java.time.LocalDate>(
                registry,
                { it.toJavaLocalDate() },
                { it.toKotlinLocalDate() }
            )
            bidi<kotlinx.datetime.LocalDateTime, java.time.LocalDateTime>(
                registry,
                { it.toJavaLocalDateTime() },
                { it.toKotlinLocalDateTime() }
            )
            bidi<kotlinx.datetime.LocalTime, java.time.LocalTime>(
                registry,
                { it.toJavaLocalTime() },
                { it.toKotlinLocalTime() }
            )
        }
    }

    private fun registerKotlinxTimeTargets(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        // kotlinx types are already registered by KotlinxTimeConverters.
        // Here we add java.sql/java.time sources to kotlinx target groups.
        val javaTimeToMillis: List<Pair<KClass<*>, (Any) -> Long>> = listOf(
            java.util.Date::class to { (it as java.util.Date).time },
            java.sql.Date::class to { (it as java.sql.Date).time },
            java.sql.Timestamp::class to { (it as java.sql.Timestamp).time },
            java.sql.Time::class to { (it as java.sql.Time).time },
            java.time.LocalDateTime::class to { (it as java.time.LocalDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            java.time.LocalDate::class to { (it as java.time.LocalDate).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            // Deterministic anchor: 1970-01-01 UTC. Previously used LocalDate.now() +
            // ZonedDateTime.now().offset, which made the result depend on the current
            // DST era — same LocalTime produced different Longs before/after a DST
            // transition. The direct LocalTime ↔ java.sql.Time converters registered
            // below short-circuit this path for that specific pair.
            java.time.LocalTime::class to { (it as java.time.LocalTime).atDate(java.time.LocalDate.EPOCH).toInstant(ZoneOffset.UTC).toEpochMilli() },
        )
        val kotlinxFromMillis: List<Pair<KClass<*>, (Long) -> Any>> = listOf(
            kotlinx.datetime.LocalDate::class to { m: Long ->
                kotlin.time.Instant.fromEpochMilliseconds(m).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
            },
            kotlinx.datetime.LocalDateTime::class to { m: Long ->
                kotlin.time.Instant.fromEpochMilliseconds(m).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            },
            kotlinx.datetime.LocalTime::class to { m: Long ->
                kotlin.time.Instant.fromEpochMilliseconds(m).toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).time
            },
            kotlin.time.Instant::class to { m: Long -> kotlin.time.Instant.fromEpochMilliseconds(m) },
        )
        for ((ktTarget, fromMillis) in kotlinxFromMillis) {
            val group = registry.getOrPut(ktTarget) { mutableMapOf() }
            for ((javaSource, toMillis) in javaTimeToMillis) {
                group[javaSource] = { fromMillis(toMillis(it)) }
            }
        }
        // Also: java.sql/java.time targets ← kotlinx sources (already registered by registerTimeRelated
        // via the supportsKotlinxTime branches)
    }

    private fun <T : Any> registerTimeRelated(
        destClass: KClass<T>,
        isCore: Boolean,
        supportsKotlinxTime: Boolean,
        toNative: (Long) -> T,
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>
    ) {
        val converters: MutableMap<KClass<*>, (Any) -> Any> =
            if (isCore) registry[destClass]
                ?: throw IllegalArgumentException("Unable to access converter for ${destClass.qualifiedName}")
            else registry.getOrPut(destClass) { HashMap() }

        if (destClass != java.util.Date::class)
            converters[java.util.Date::class] = { toNative((it as java.util.Date).time) }
        if (destClass != java.sql.Date::class)
            converters[java.sql.Date::class] = { toNative((it as java.sql.Date).time) }
        if (destClass != java.sql.Timestamp::class)
            converters[java.sql.Timestamp::class] = { toNative((it as java.sql.Timestamp).time) }
        if (destClass != java.sql.Time::class)
            converters[java.sql.Time::class] = { toNative((it as java.sql.Time).time) }
        if (destClass != java.time.LocalDateTime::class) converters[java.time.LocalDateTime::class] = {
            toNative((it as java.time.LocalDateTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        if (destClass != java.time.LocalDate::class) converters[java.time.LocalDate::class] = {
            toNative((it as java.time.LocalDate).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        }
        // Deterministic anchor: 1970-01-01 UTC (see commentary at the matching
        // line inside registerKotlinxTimeTargets for the rationale).
        if (destClass != java.time.LocalTime::class) converters[java.time.LocalTime::class] = {
            toNative(
                (it as java.time.LocalTime).atDate(java.time.LocalDate.EPOCH).toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            )
        }
        if (supportsKotlinxTime) {
            if (destClass != kotlinx.datetime.LocalDate::class) converters[kotlinx.datetime.LocalDate::class] = {
                toNative(
                    kotlinx.datetime.LocalDateTime(it as kotlinx.datetime.LocalDate, kotlinx.datetime.LocalTime(12, 0))
                        .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
                )
            }
            if (destClass != kotlinx.datetime.LocalDateTime::class) converters[kotlinx.datetime.LocalDateTime::class] = {
                toNative((it as kotlinx.datetime.LocalDateTime).toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds())
            }
            if (destClass != kotlinx.datetime.LocalTime::class) converters[kotlinx.datetime.LocalTime::class] = {
                val date = kotlin.time.Clock.System.now().toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date
                toNative(
                    kotlinx.datetime.LocalDateTime(date, it as kotlinx.datetime.LocalTime)
                        .toInstant(kotlinx.datetime.TimeZone.currentSystemDefault()).toEpochMilliseconds()
                )
            }
            if (destClass != kotlin.time.Instant::class) converters[kotlin.time.Instant::class] = {
                toNative((it as kotlin.time.Instant).toEpochMilliseconds())
            }
        }

        if (!isCore) {
            converters[Long::class] = { toNative(it as Long) }
            converters[Double::class] = { toNative(((it as Double) * 1000.0).toLong()) }
            converters[Float::class] = { toNative(((it as Float) * 1000.0).toLong()) }
            converters[String::class] = { toNative(parseTemporalString(it as String)) }
        }
    }

    private fun registerVendorTypes(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        tryRegisterVendorConversion("oracle.sql.TIMESTAMP", java.sql.Timestamp::class, "timestampValue", registry)
        tryRegisterVendorConversion("oracle.sql.DATE", java.sql.Date::class, "dateValue", registry)
        tryRegisterVendorConversion("oracle.sql.NUMBER", BigDecimal::class, "bigDecimalValue", registry)
        tryRegisterVendorConversion("oracle.sql.CLOB", String::class, "stringValue", registry)
    }

    private fun tryRegisterVendorConversion(
        vendorClassName: String,
        standardType: KClass<*>,
        methodName: String,
        registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>
    ) {
        try {
            val vendorClass = Class.forName(vendorClassName).kotlin
            val method = Class.forName(vendorClassName).getMethod(methodName)
            val toStandard: (Any) -> Any = { method.invoke(it)!! }
            registry.getOrPut(standardType) { mutableMapOf() }[vendorClass] = toStandard
            for ((targetClass, converters) in registry) {
                val standardToTarget = converters[standardType]
                if (standardToTarget != null && targetClass != standardType)
                    converters.putIfAbsent(vendorClass) { standardToTarget(toStandard(it)) }
            }
        } catch (_: ClassNotFoundException) { }
        catch (_: NoSuchMethodException) { }
    }

    private fun parseTemporalString(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                java.time.LocalDate.parse(s).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (e: Exception) {
                throw SQLException("Unable to parse temporal string: $s", e)
            }
        }
    }
}
