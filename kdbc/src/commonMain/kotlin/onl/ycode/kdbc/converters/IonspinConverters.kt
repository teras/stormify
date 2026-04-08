// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc.converters
import onl.ycode.kdbc.SQLException

import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import kotlin.reflect.KClass

/**
 * Registers conversions between ionspin bignum types and Kotlin primitives.
 *
 * This class is loaded lazily — on JVM, if ionspin is not in the classpath,
 * the class fails to load with [NoClassDefFoundError] and registration is skipped.
 * On native, ionspin is always available.
 */
internal object IonspinConverters {
    private val int = listOf(Byte::class, Short::class, Int::class, Long::class)
    private val dec = listOf(Float::class, Double::class)

    fun register(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        // ionspin → primitives
        int.forEach { n ->
            val tGroup = registry[n] ?: return@forEach
            tGroup[BDN::class] = { (it as BDN).longValue().let { l -> tGroup[Long::class]?.invoke(l) ?: l } }
            tGroup[BIN::class] = { (it as BIN).longValue().let { l -> tGroup[Long::class]?.invoke(l) ?: l } }
        }
        dec.forEach { n ->
            val tGroup = registry[n] ?: return@forEach
            tGroup[BDN::class] = { (it as BDN).doubleValue().let { d -> tGroup[Double::class]?.invoke(d) ?: d } }
            tGroup[BIN::class] = { (it as BIN).doubleValue().let { d -> tGroup[Double::class]?.invoke(d) ?: d } }
        }

        // ionspin BigDecimal
        val toBDN = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BDN::class] = it }
        int.forEach { t -> toBDN[t] = { BDN.fromLong((it as Number).toLong()) } }
        dec.forEach { t -> toBDN[t] = { BDN.fromDouble((it as Number).toDouble()) } }
        toBDN[BIN::class] = { BDN.fromBigInteger(it as BIN) }
        toBDN[Boolean::class] = { if (it as Boolean) BDN.ONE else BDN.ZERO }

        // ionspin BigInteger
        val toBIN = mutableMapOf<KClass<*>, (Any) -> Any>().also { registry[BIN::class] = it }
        int.forEach { t -> toBIN[t] = { BIN.fromLong((it as Number).toLong()) } }
        dec.forEach { t -> toBIN[t] = { BIN.tryFromDouble((it as Number).toDouble()) } }
        toBIN[BDN::class] = { (it as BDN).toBigInteger() }
        toBIN[Boolean::class] = { if (it as Boolean) BIN.ONE else BIN.ZERO }

        // String ↔ ionspin
        registry[String::class]?.let { group ->
            group[BIN::class] = { it.toString() }
            group[BDN::class] = { it.toString() }
        }
        toBDN[String::class] = { BDN.parseString(it as String) }
        toBIN[String::class] = { BIN.parseString(it as String) }
    }
}
