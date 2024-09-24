package onl.ycode.stormify

import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.reflect.KClass

private val int = listOf(Byte::class, Short::class, Int::class, Long::class)
private val dec = listOf(Float::class, Double::class)
private fun missingGroup(c: KClass<*>): Nothing =
    throw IllegalArgumentException("Unable to find conversions to target type ${c.fullName}")
typealias BDN = com.ionspin.kotlin.bignum.decimal.BigDecimal
typealias BIN = com.ionspin.kotlin.bignum.integer.BigInteger
typealias KLocalDate = kotlinx.datetime.LocalDate
typealias KLocalDateTime = kotlinx.datetime.LocalDateTime
typealias KLocalTime = kotlinx.datetime.LocalTime
typealias KInstant = kotlinx.datetime.Instant
typealias KTimeZone = kotlinx.datetime.TimeZone
typealias KClockSystem = kotlinx.datetime.Clock.System

internal actual fun registerNativeTargets(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
    int.forEach { n ->
        val tGroup = registry[n] ?: missingGroup(n)
        tGroup[BigDecimal::class] = { val l = (it as Number).toLong();tGroup[Long::class]?.let { it(l) } ?: l }
        tGroup[BigInteger::class] = { val l = (it as Number).toLong();tGroup[Long::class]?.let { it(l) } ?: l }
    }
    dec.forEach { n ->
        val tGroup = registry[n] ?: missingGroup(n)
        tGroup[BigDecimal::class] = { val l = (it as Number).toDouble();tGroup[Double::class]?.let { it(l) } ?: l }
        tGroup[BigInteger::class] = { val l = (it as Number).toDouble();tGroup[Double::class]?.let { it(l) } ?: l }
    }

    val toBigDecimal = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BigDecimal::class] = it }
    int.forEach { t -> toBigDecimal[t] = { BigDecimal((it as Number).toLong()) } }
    dec.forEach { t -> toBigDecimal[t] = { BigDecimal((it as Number).toDouble()) } }
    toBigDecimal[BigInteger::class] = { (it as BigInteger).toBigDecimal() }

    val toBigInteger = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BigInteger::class] = it }
    int.forEach { t -> toBigInteger[t] = { BigInteger.valueOf((it as Number).toLong()) } }
    dec.forEach { t -> toBigInteger[t] = { BigDecimal((it as Number).toDouble()).toBigInteger() } }
    toBigInteger[BigDecimal::class] = { (it as BigDecimal).toBigInteger() }

    // Add date-related
    val supportsKotlinxTime = try {
        kotlinx.datetime.LocalDate::class.simpleName
        true
    } catch (e: Throwable) {
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
    registerTimeRelated(Long::class, true, supportsKotlinxTime, { it }, registry)
    registerTimeRelated(Double::class, true, supportsKotlinxTime, { it / 1000.0 }, registry)
    registerTimeRelated(Float::class, true, supportsKotlinxTime, { it / 1000f }, registry)
    registerTimeRelated(
        String::class, true, supportsKotlinxTime,
        { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_INSTANT) }, registry
    )
    if (supportsKotlinxTime) {
        registerTimeRelated(
            KLocalDate::class, false, supportsKotlinxTime, {
                KInstant.fromEpochMilliseconds(it).toLocalDateTime(KTimeZone.currentSystemDefault()).date
            }, registry
        )
        registerTimeRelated(
            KLocalDateTime::class, false, supportsKotlinxTime, {
                KInstant.fromEpochMilliseconds(it).toLocalDateTime(KTimeZone.currentSystemDefault())
            }, registry
        )
        registerTimeRelated(
            KLocalTime::class, false, supportsKotlinxTime, {
                KInstant.fromEpochMilliseconds(it).toLocalDateTime(KTimeZone.currentSystemDefault()).time
            }, registry
        )
    }

    // String related
    registry[String::class]?.let { group ->
        group[BigDecimal::class] = { it.toString() }
        group[BigInteger::class] = { it.toString() }
    }
    toBigDecimal[String::class] = { BigDecimal(it as String) }
    toBigInteger[String::class] = { BigInteger(it as String) }

    runCatching { registerIonspinBigNumbers(registry) }

}

fun registerIonspinBigNumbers(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
    int.forEach { n ->
        val tGroup = registry[n] ?: missingGroup(n)
        tGroup[BDN::class] = { val l = (it as BDN).longValue();tGroup[Long::class]?.let { it(l) } ?: l }
        tGroup[BIN::class] = { val l = (it as BIN).longValue();tGroup[Long::class]?.let { it(l) } ?: l }
    }
    dec.forEach { n ->
        val tGroup = registry[n] ?: missingGroup(n)
        tGroup[BDN::class] = { val l = (it as BDN).doubleValue();tGroup[Double::class]?.let { it(l) } ?: l }
        tGroup[BIN::class] = { val l = (it as BIN).doubleValue();tGroup[Double::class]?.let { it(l) } ?: l }
    }

    val toBDN = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BDN::class] = it }
    int.forEach { t -> toBDN[t] = { BDN.fromLong((it as Number).toLong()) } }
    dec.forEach { t -> toBDN[t] = { BDN.fromDouble((it as Number).toDouble()) } }
    toBDN[BIN::class] = { BDN.fromBigInteger(it as BIN) }
    toBDN[BigDecimal::class] = { BDN.parseString(it.toString()) }
    toBDN[BigInteger::class] = { BDN.parseString(it.toString()) }

    val toBIN = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BIN::class] = it }
    int.forEach { t -> toBIN[t] = { BIN.fromLong((it as Number).toLong()) } }
    dec.forEach { t -> toBIN[t] = { BIN.tryFromDouble((it as Number).toDouble()) } }
    toBIN[BDN::class] = { (it as BDN).toBigInteger() }
    toBIN[BigDecimal::class] = { BIN.parseString(it.toString()) }
    toBIN[BigInteger::class] = { BIN.parseString(it.toString()) }

    // String related
    registry[String::class]?.let { group ->
        group[BIN::class] = { it.toString() }
        group[BDN::class] = { it.toString() }
    }
    toBDN[String::class] = { BDN.parseString(it as String) }
    toBIN[String::class] = { BIN.parseString(it as String) }
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
            ?: throw IllegalArgumentException("Unable to access converter for ${destClass.fullName}")
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
        toNative((it as java.time.LocalDate).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }
    if (destClass != java.time.LocalTime::class) converters[java.time.LocalTime::class] = {
        toNative(
            (it as java.time.LocalTime).atDate(java.time.LocalDate.now()).toInstant(ZonedDateTime.now().offset)
                .toEpochMilli()
        )
    }
    if (supportsKotlinxTime) {
        if (destClass != KLocalDate::class) converters[KLocalDate::class] = {
            toNative(
                KLocalDateTime(it as KLocalDate, KLocalTime(0, 0))
                    .toInstant(KTimeZone.currentSystemDefault()).toEpochMilliseconds()
            )
        }
        if (destClass != KLocalDateTime::class) converters[KLocalDateTime::class] = {
            toNative((it as KLocalDateTime).toInstant(KTimeZone.currentSystemDefault()).toEpochMilliseconds())
        }
        if (destClass != KLocalTime::class) converters[KLocalTime::class] = {
            val date = KClockSystem.now().toLocalDateTime(KTimeZone.currentSystemDefault()).date
            toNative(
                KLocalDateTime(date, it as KLocalTime)
                    .toInstant(KTimeZone.currentSystemDefault()).toEpochMilliseconds()
            )
        }
    }

    if (!isCore) {
        converters[Long::class] = { toNative((it as Long?)!!) }
        converters[Double::class] = { toNative(Math.round((it as Double) * 1000.0)) }
        converters[Float::class] = { toNative(Math.round((it as Float) * 1000.0)) }
        converters[String::class] = { toNative(Instant.parse(it as String).toEpochMilli()) }
    }
}