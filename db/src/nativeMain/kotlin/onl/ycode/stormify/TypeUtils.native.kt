@file:OptIn(kotlin.time.ExperimentalTime::class)

package onl.ycode.stormify

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.posix.llround
import platform.posix.llroundf
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Instant

private val int = listOf(Byte::class, Short::class, Int::class, Long::class)
private val dec = listOf(Float::class, Double::class)
private fun missingGroup(c: KClass<*>): Nothing =
    throw IllegalArgumentException("Unable to find conversions to target type ${c.fullName}")

typealias BDN = com.ionspin.kotlin.bignum.decimal.BigDecimal
typealias BIN = com.ionspin.kotlin.bignum.integer.BigInteger

internal actual fun registerNativeTargets(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
    int.forEach { n ->
        val tGroup = registry[n] ?: missingGroup(n)
        tGroup[BDN::class] = { val l = (it as BDN).longValue(); tGroup[Long::class]?.let { conv -> conv(l) } ?: l }
        tGroup[BIN::class] = { val l = (it as BIN).longValue(); tGroup[Long::class]?.let { conv -> conv(l) } ?: l }
    }
    dec.forEach { n ->
        val tGroup = registry[n] ?: missingGroup(n)
        tGroup[BDN::class] = { val l = (it as BDN).doubleValue(); tGroup[Double::class]?.let { conv -> conv(l) } ?: l }
        tGroup[BIN::class] = { val l = (it as BIN).doubleValue(); tGroup[Double::class]?.let { conv -> conv(l) } ?: l }
    }

    val toBDN = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BDN::class] = it }
    int.forEach { t -> toBDN[t] = { BDN.fromLong((it as Number).toLong()) } }
    dec.forEach { t -> toBDN[t] = { BDN.fromDouble((it as Number).toDouble()) } }
    toBDN[BIN::class] = { BDN.fromBigInteger(it as BIN) }

    val toBIN = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BIN::class] = it }
    int.forEach { t -> toBIN[t] = { BIN.fromLong((it as Number).toLong()) } }
    dec.forEach { t -> toBIN[t] = { BIN.tryFromDouble((it as Number).toDouble()) } }
    toBIN[BDN::class] = { (it as BDN).toBigInteger() }

    // String related
    registry[String::class]?.let { group ->
        group[BIN::class] = { it.toString() }
        group[BDN::class] = { it.toString() }
    }
    toBDN[String::class] = { BDN.parseString(it as String) }
    toBIN[String::class] = { BIN.parseString(it as String) }

    registerTimeRelated(Long::class, true, { it }, registry)
    registerTimeRelated(Double::class, true, { it / 1000.0 }, registry)
    registerTimeRelated(Float::class, true, { it / 1000f }, registry)
    registerTimeRelated(
        String::class, true,
        { Instant.fromEpochMilliseconds(it).toString() }, registry
    )
    registerTimeRelated(
        LocalDate::class, false, {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).date
        }, registry
    )
    registerTimeRelated(
        LocalDateTime::class, false, {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault())
        }, registry
    )
    registerTimeRelated(
        LocalTime::class, false, {
            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).time
        }, registry
    )
}

private fun <T : Any> registerTimeRelated(
    destClass: KClass<T>,
    isCore: Boolean,
    toNative: (Long) -> T,
    registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>
) {
    val converters: MutableMap<KClass<*>, (Any) -> Any> =
        if (isCore) registry[destClass]
            ?: throw IllegalArgumentException("Unable to access converter for ${destClass.fullName}")
        else registry.getOrPut(destClass) { mutableMapOf() }

    if (destClass != LocalDate::class) converters[LocalDate::class] = {
        toNative(
            LocalDateTime(it as LocalDate, LocalTime(0, 0))
                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        )
    }
    if (destClass != LocalDateTime::class) converters[LocalDateTime::class] = {
        toNative((it as LocalDateTime).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds())
    }
    if (destClass != LocalTime::class) converters[LocalTime::class] = {
        val date = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        toNative(
            LocalDateTime(date, it as LocalTime)
                .toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        )
    }

    if (!isCore) {
        converters[Long::class] = { toNative((it as Long)) }
        converters[Double::class] = { toNative(llround((it as Double) * 1000.0)) }
        converters[Float::class] = { toNative(llroundf((it as Float) * 1000.0f)) }
        converters[String::class] = { toNative(Instant.parse(it as String).toEpochMilliseconds()) }
    }
}
