// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package test

import onl.ycode.kdbc.TypeConversion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

open class KotlinUuidConversionTest {

    private val u = Uuid.parse("12345678-1234-1234-1234-123456789012")

    @Test fun uuidIsKnownScalar() {
        assertTrue(TypeConversion.isKnownScalar(Uuid::class))
    }

    @Test fun uuidStringBidi() {
        val s = TypeConversion.castScalar(String::class, u)
        assertEquals("12345678-1234-1234-1234-123456789012", s)
        val back = TypeConversion.castScalar(Uuid::class, s!!)
        assertEquals(u, back)
    }

    @Test fun uuidByteArrayBidi() {
        val bytes = TypeConversion.castScalar(ByteArray::class, u)
        assertNotNull(bytes)
        assertEquals(16, bytes.size)
        val back = TypeConversion.castScalar(Uuid::class, bytes)
        assertEquals(u, back)
    }
}
