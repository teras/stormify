package test

import onl.ycode.stormify.identityKey
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** `java.math.BigDecimal` keeps its scale in `toString()`; its identity key must not. */
class JavaIdentityKeyTest {
    @Test
    fun javaDecimalsOfDifferentScaleShareOneKey() {
        assertEquals("1e2", identityKey(BigDecimal("100")))
        assertEquals("1e2", identityKey(BigDecimal("100.00")))
        assertEquals("1e2", identityKey(BigDecimal("1E+2")))
        assertEquals(identityKey(BigDecimal("0.001")), identityKey(BigDecimal("1E-3")))
    }
}
