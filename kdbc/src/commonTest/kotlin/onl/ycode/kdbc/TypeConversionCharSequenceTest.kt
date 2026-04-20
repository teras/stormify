// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.kdbc

import kotlin.test.Test
import kotlin.test.assertEquals

class TypeConversionCharSequenceTest {

    @Test
    fun stringBuilderToString() {
        val sb = StringBuilder("hello")
        assertEquals("hello", TypeConversion.castScalar(String::class, sb))
    }

    @Test
    fun stringBuilderToInt() {
        val sb = StringBuilder("42")
        assertEquals(42, TypeConversion.castScalar(Int::class, sb))
    }

    @Test
    fun stringBuilderToBoolean() {
        val sb = StringBuilder("true")
        assertEquals(true, TypeConversion.castScalar(Boolean::class, sb))
    }
}
