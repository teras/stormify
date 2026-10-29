// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.ZoneId

/**
 * Installs the JVM part of [TypeProbe] and returns [dataSource] wrapped so that every
 * typed `getObject(column, Class)` the driver refuses is recorded, as a pool wrapper
 * would see it before kdbc falls back to an untyped read.
 */
internal fun installJvmTypeProbe(dataSource: javax.sql.DataSource): javax.sql.DataSource {
    val rejections = mutableListOf<String>()
    TypeProbe.enabled = true
    TypeProbe.startRecording = { synchronized(rejections) { rejections.clear() } }
    TypeProbe.recordedRejections = { synchronized(rejections) { rejections.toList() } }
    TypeProbe.platformTypes += JVM_TYPES
    return recordingRejections(dataSource) { synchronized(rejections) { rejections += it } }
}

/**
 * [dataSource] wrapped so that every typed `getObject(column, Class)` the driver refuses
 * is reported to [onRejection], as a pool wrapper would see it before kdbc reads again.
 */
internal fun recordingRejections(dataSource: javax.sql.DataSource, onRejection: (String) -> Unit): javax.sql.DataSource =
    proxy(dataSource, javax.sql.DataSource::class.java) { method, args, result ->
        if (method.name == "getObject" && args?.size == 2 && args[1] is Class<*> && result is Throwable)
            onRejection("${(args[1] as Class<*>).simpleName}.class (${result::class.simpleName}: ${result.message?.lineSequence()?.firstOrNull()})")
    }

/**
 * A proxy of [target] that reports every call to [observe] (the result, or the thrown
 * exception) and wraps the JDBC objects it returns in the same way.
 */
private fun <T> proxy(target: T, type: Class<T>, observe: (java.lang.reflect.Method, Array<Any?>?, Any?) -> Unit): T {
    @Suppress("UNCHECKED_CAST")
    return Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
        val result = try {
            method.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            observe(method, args, e.targetException)
            throw e.targetException
        }
        observe(method, args, result)
        val returned = method.returnType
        if (result != null && returned.isInterface && returned.name.startsWith("java.sql."))
            proxy(result, returned as Class<Any>, observe)
        else result
    } as T
}

private fun java.time.LocalDateTime.probe() = probeDateTime(year, monthValue, dayOfMonth, hour, minute, second, nano)

private val JVM_TYPES = listOf(
    ProbeType("java.BigInteger", setOf(ProbeKind.INTEGRAL, ProbeKind.BIGINT),
        read = { s, q -> s.readOne<java.math.BigInteger>(q) }, canon = { probeNumber(it.toString()) }),
    ProbeType("java.BigDecimal", setOf(ProbeKind.INTEGRAL, ProbeKind.BIGINT, ProbeKind.FRACTIONAL),
        read = { s, q -> s.readOne<java.math.BigDecimal>(q) }, canon = { probeNumber((it as java.math.BigDecimal).toPlainString()) }),
    ProbeType("java.LocalDate", PROBE_DATE_KINDS, unchecked = setOf(ProbeKind.DATETIME),
        read = { s, q -> s.readOne<java.time.LocalDate>(q) }, canon = { it.toString() }),
    ProbeType("java.LocalDateTime", PROBE_DATETIME_KINDS,
        read = { s, q -> s.readOne<java.time.LocalDateTime>(q) }, canon = { (it as java.time.LocalDateTime).probe() }),
    ProbeType("java.LocalTime", setOf(ProbeKind.TIME), unchecked = setOf(ProbeKind.DATETIME),
        read = { s, q -> s.readOne<java.time.LocalTime>(q) }, canon = { (it as java.time.LocalTime).withNano(0).toString() }),
    // The absolute types are read from a column without a zone through the JVM's zone,
    // which is also how they are written, so they come back as the stored wall time.
    ProbeType("java.Instant", PROBE_DATETIME_KINDS, read = { s, q -> s.readOne<java.time.Instant>(q) },
        canon = { java.time.LocalDateTime.ofInstant(it as java.time.Instant, ZoneId.systemDefault()).probe() }),
    ProbeType("java.OffsetDateTime", PROBE_DATETIME_KINDS, read = { s, q -> s.readOne<java.time.OffsetDateTime>(q) },
        canon = { (it as java.time.OffsetDateTime).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().probe() }),
    ProbeType("java.ZonedDateTime", PROBE_DATETIME_KINDS, read = { s, q -> s.readOne<java.time.ZonedDateTime>(q) },
        canon = { (it as java.time.ZonedDateTime).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().probe() }),
    ProbeType("java.OffsetTime", emptySet(), unchecked = setOf(ProbeKind.TIME),
        read = { s, q -> s.readOne<java.time.OffsetTime>(q) }, canon = { it.toString() }),
    ProbeType("java.util.Date", PROBE_DATETIME_KINDS, read = { s, q -> s.readOne<java.util.Date>(q) },
        canon = { java.time.LocalDateTime.ofInstant((it as java.util.Date).toInstant(), ZoneId.systemDefault()).probe() }),
    ProbeType("java.sql.Date", PROBE_DATE_KINDS, unchecked = setOf(ProbeKind.DATETIME),
        read = { s, q -> s.readOne<java.sql.Date>(q) }, canon = { (it as java.sql.Date).toLocalDate().toString() }),
    ProbeType("java.sql.Timestamp", PROBE_DATETIME_KINDS,
        read = { s, q -> s.readOne<java.sql.Timestamp>(q) }, canon = { (it as java.sql.Timestamp).toLocalDateTime().probe() }),
    ProbeType("java.sql.Time", setOf(ProbeKind.TIME), unchecked = setOf(ProbeKind.DATETIME),
        read = { s, q -> s.readOne<java.sql.Time>(q) }, canon = { (it as java.sql.Time).toLocalTime().withNano(0).toString() }),
    ProbeType("java.UUID", setOf(ProbeKind.UUID), read = { s, q -> s.readOne<java.util.UUID>(q) }, canon = { it.toString() }),
)
