package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import onl.ycode.stormify.canonicalDecimal
import onl.ycode.stormify.identityKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdentityKeyTest {
    @Test
    fun equalDecimalsShareOneKey() {
        val hundred = listOf("100", "100.00", "1E+2", "1e2", "1.0E+2", "0100", "+100.0").map(::canonicalDecimal)
        assertEquals(setOf("1e2"), hundred.toSet())
        assertEquals(canonicalDecimal("0.001"), canonicalDecimal("1E-3"))
        assertEquals("-125e-1", canonicalDecimal("-12.50"))
        assertEquals(setOf("0"), listOf("0", "0.00", "-0.0", "0E+3").map(::canonicalDecimal).toSet())
    }

    @Test
    fun differentDecimalsKeepDifferentKeys() {
        assertNotEquals(canonicalDecimal("100"), canonicalDecimal("1000"))
        assertNotEquals(canonicalDecimal("100"), canonicalDecimal("-100"))
        assertNotEquals(canonicalDecimal("1.5"), canonicalDecimal("15"))
        assertEquals("not a number", canonicalDecimal("not a number"))
    }

    @Test
    fun identityKeyCanonicalizesDecimalsOnly() {
        // The exact canonical form: a plain toString() of any decimal would not give "1e2".
        assertEquals("1e2", identityKey(BigDecimal.parseString("100")))
        assertEquals("1e2", identityKey(BigDecimal.parseString("100.00")))
        assertEquals(identityKey(BigDecimal.parseString("0.10")), identityKey(BigDecimal.parseString("0.1")))
        // Anything that is not a decimal keys by its text, even when it looks like one.
        assertEquals("100.00", identityKey("100.00"))
        assertEquals("100", identityKey(100))
        assertEquals("null", identityKey(null))
    }
}
