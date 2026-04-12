// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Verifies that client-generated string primary keys round-trip correctly
 * through bind, store, and WHERE-clause predicate on **every** supported
 * database. This exercises the driver's VARCHAR bind and fetch paths for
 * the "id" column (which historically has been assumed numeric on several
 * drivers), independent of any server-side key generation mechanism.
 *
 * A separate test exercises *server-generated* string keys (SYS_GUID /
 * gen_random_uuid / NEWID) at the kdbc C level, where it is naturally
 * dialect-partitioned. This test runs everywhere — no skips.
 */
open class StringPkTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    /** Hex UUID-like identifier generated client-side (no platform Uuid dependency). */
    private fun randomId(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(32)
        repeat(32) { sb.append(hex[Random.nextInt(16)]) }
        return sb.toString()
    }

    @Test
    fun testStringPkRoundTrip() {
        withDb("STRING-PK-ROUNDTRIP") { s ->
            TestDDL.dropTable("kdbc_str_pk")
            s.executeUpdate(TestDDL.createTable(
                "kdbc_str_pk",
                "id ${TestDDL.stringPkType()} PRIMARY KEY, val ${TestDDL.textType()}"
            ))
            try {
                val id1 = randomId()
                val id2 = randomId()
                s.executeUpdate("INSERT INTO kdbc_str_pk (id, val) VALUES (?, ?)", id1, "alpha")
                s.executeUpdate("INSERT INTO kdbc_str_pk (id, val) VALUES (?, ?)", id2, "beta")

                // Round-trip: WHERE-clause bind against the string PK must find the row.
                val v1 = s.readOne<String>("SELECT val FROM kdbc_str_pk WHERE id = ?", id1)
                val v2 = s.readOne<String>("SELECT val FROM kdbc_str_pk WHERE id = ?", id2)
                assertNotNull(v1)
                assertNotNull(v2)
                assertEquals("alpha", v1)
                assertEquals("beta", v2)

                // Fetch the PK back as a string to verify the get-string path on the
                // VARCHAR column (distinct from bind).
                val fetched = s.readOne<String>(
                    "SELECT id FROM kdbc_str_pk WHERE val = ?", "alpha"
                )
                assertEquals(id1, fetched)
            } finally {
                TestDDL.dropTable("kdbc_str_pk")
            }
        }
    }
}
