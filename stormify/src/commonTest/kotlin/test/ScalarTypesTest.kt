// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.time.ExperimentalTime::class)

package test

import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.ScalarTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class ScalarTypesTest {

    @Test
    fun textBucket() {
        assertEquals(Facet.Type.TEXT, ScalarTypes.categoryOf(String::class))
        assertEquals(Facet.Type.TEXT, ScalarTypes.categoryOf(Char::class))
        assertEquals(Facet.Type.TEXT, ScalarTypes.categoryOf(StringBuilder::class))
        // CharArray (CLOB) must be textual — earlier a hardcoded-list drift missed it.
        assertEquals(Facet.Type.TEXT, ScalarTypes.categoryOf(CharArray::class))
    }

    @Test
    fun numericBucket() {
        listOf(Byte::class, Short::class, Int::class, Long::class, Float::class, Double::class)
            .forEach { assertEquals(Facet.Type.NUMERIC, ScalarTypes.categoryOf(it), "$it") }
    }

    @Test
    fun kotlinxTemporalBuckets() {
        assertEquals(Facet.Type.DATE, ScalarTypes.categoryOf(kotlinx.datetime.LocalDate::class))
        assertEquals(Facet.Type.TIME, ScalarTypes.categoryOf(kotlinx.datetime.LocalTime::class))
        assertEquals(Facet.Type.TIMESTAMP, ScalarTypes.categoryOf(kotlinx.datetime.LocalDateTime::class))
        assertEquals(Facet.Type.TIMESTAMP, ScalarTypes.categoryOf(kotlin.time.Instant::class))
    }
}
