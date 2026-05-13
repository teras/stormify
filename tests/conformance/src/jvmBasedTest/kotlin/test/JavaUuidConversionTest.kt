// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.TypeConversion
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round-trip tests for [java.util.UUID] under both canonical-text and
 * 16-byte representations.
 */
open class JavaUuidConversionTest {

    private val u = UUID.fromString("12345678-1234-1234-1234-123456789012")

    @Test fun uuidIsKnownScalar() {
        assertTrue(TypeConversion.isKnownScalar(UUID::class))
    }

    @Test fun uuidStringBidi() {
        val s = TypeConversion.castScalar(String::class, u)
        assertEquals("12345678-1234-1234-1234-123456789012", s)
        val back = TypeConversion.castScalar(UUID::class, s!!)
        assertEquals(u, back)
    }

    @Test fun uuidByteArrayBidi() {
        val bytes = TypeConversion.castScalar(ByteArray::class, u)
        assertNotNull(bytes)
        assertEquals(16, bytes.size)
        // Most-significant bits first (RFC 4122 wire order).
        val expectedHead = byteArrayOf(
            0x12, 0x34, 0x56, 0x78, 0x12, 0x34, 0x12, 0x34,
            0x12, 0x34, 0x12, 0x34, 0x56, 0x78, 0x90.toByte(), 0x12,
        )
        assertTrue(bytes.contentEquals(expectedHead))
        val back = TypeConversion.castScalar(UUID::class, bytes)
        assertEquals(u, back)
    }

    @Test fun uuidByteArrayWrongLengthFails() {
        assertFailsWith<onl.ycode.kdbc.SQLException> {
            TypeConversion.castScalar(UUID::class, ByteArray(15))
        }
    }

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    @Test fun uuidKotlinInterop() {
        val kUuid = kotlin.uuid.Uuid.parse("12345678-1234-1234-1234-123456789012")
        val j = TypeConversion.castScalar(UUID::class, kUuid)
        assertEquals(u, j)
        val back = TypeConversion.castScalar(kotlin.uuid.Uuid::class, j!!)
        assertEquals(kUuid, back)
    }
}
