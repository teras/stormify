package onl.ycode.stormify

import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.posix.llround
import platform.posix.llroundf
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
        { KInstant.fromEpochMilliseconds(it).toString() }, registry
    )
    registerTimeRelated(
        KLocalDate::class, false, {
            KInstant.fromEpochMilliseconds(it).toLocalDateTime(KTimeZone.currentSystemDefault()).date
        }, registry
    )
    registerTimeRelated(
        KLocalDateTime::class, false, {
            KInstant.fromEpochMilliseconds(it).toLocalDateTime(KTimeZone.currentSystemDefault())
        }, registry
    )
    registerTimeRelated(
        KLocalTime::class, false, {
            KInstant.fromEpochMilliseconds(it).toLocalDateTime(KTimeZone.currentSystemDefault()).time
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
        else registry.getOrPut(destClass) { HashMap() }

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

    if (!isCore) {
        converters[Long::class] = { toNative((it as Long)) }
        converters[Double::class] = { toNative(llround((it as Double) * 1000.0)) }
        converters[Float::class] = { toNative(llroundf((it as Float) * 1000.0f)) }
        converters[String::class] = { toNative(KInstant.parse(it as String).toEpochMilliseconds()) }
    }
}
