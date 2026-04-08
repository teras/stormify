// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc.converters
import onl.ycode.kdbc.SQLException

import com.ionspin.kotlin.bignum.decimal.BigDecimal as BDN
import com.ionspin.kotlin.bignum.integer.BigInteger as BIN
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.reflect.KClass

/**
 * Registers conversions between ionspin bignum types and java.math types on JVM.
 * Uses binary path (significand + toByteArray) for maximum performance — no toString().
 *
 * Loaded lazily — skipped if ionspin is not in the classpath.
 */
internal object IonspinJvmConverters {
    fun register(registry: MutableMap<KClass<*>, MutableMap<KClass<*>, (Any) -> Any>>) {
        val toBDN = registry[BDN::class] ?: return
        val toBIN = registry[BIN::class] ?: return

        // java.math.BigDecimal → ionspin BigDecimal
        toBDN[BigDecimal::class] = { BDN.parseString(it.toString()) }
        toBDN[BigInteger::class] = { BDN.parseString(it.toString()) }

        // java.math.BigInteger → ionspin BigInteger
        toBIN[BigDecimal::class] = { BIN.parseString(it.toString()) }
        toBIN[BigInteger::class] = { BIN.parseString(it.toString()) }

        // java.math.BigDecimal target group
        val toBigDecimal = registry[BigDecimal::class] ?: return
        val toBigInteger = registry[BigInteger::class] ?: return

        // ionspin BigDecimal → java.math.BigDecimal (binary: significand + precision + exponent)
        toBigDecimal[BDN::class] = { v ->
            val bd = v as BDN
            val sig = bd.significand
            val signum = sig.signum()
            if (signum == 0) BigDecimal.ZERO
            else {
                val jSig = BigInteger(signum, sig.toByteArray())
                val scale = bd.precision.toInt() - 1 - bd.exponent.toInt()
                BigDecimal(jSig, scale)
            }
        }

        // ionspin BigInteger → java.math.BigDecimal (binary: signum + toByteArray → BigInteger → BigDecimal)
        // Uses BigDecimal instead of BigInteger because some JDBC drivers (MySQL) truncate BigInteger to Long.
        toBigDecimal[BIN::class] = { v ->
            val bi = v as BIN
            val signum = bi.signum()
            if (signum == 0) BigDecimal.ZERO
            else BigDecimal(BigInteger(signum, bi.toByteArray()))
        }

        // ionspin BigDecimal → java.math.BigInteger
        toBigInteger[BDN::class] = { (it as BDN).toBigInteger().let { bi ->
            val signum = bi.signum()
            if (signum == 0) BigInteger.ZERO else BigInteger(signum, bi.toByteArray())
        }}

        // ionspin BigInteger → java.math.BigInteger
        toBigInteger[BIN::class] = { v ->
            val bi = v as BIN
            val signum = bi.signum()
            if (signum == 0) BigInteger.ZERO else BigInteger(signum, bi.toByteArray())
        }

        // String ↔ java.math (these may already exist but ensure they cover ionspin sources)
        registry[String::class]?.let { group ->
            group[BDN::class] = { (it as BDN).toStringExpanded() }
            group[BIN::class] = { it.toString() }
        }
    }
}
