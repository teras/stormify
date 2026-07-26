// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The PostgreSQL driver requests binary results for every column, so each value
 * must be decoded from its column OID rather than from its byte length — an INT4
 * and a FLOAT4 are both 4 bytes, and a VARCHAR holding "12.5" is 4 bytes of UTF-8.
 *
 * Two cases pull in opposite directions and are verified together here:
 *
 *  - a **text column** whose payload happens to be 2/4/8 bytes must be parsed as
 *    text, never reinterpreted as a big-endian number;
 *  - a **DOMAIN** reports its own OID, which matches none of the known numeric
 *    types, yet keeps the base type's binary layout and must still decode.
 *
 * Only PostgreSQL exposes this path; other dialects skip.
 */
open class PgBinaryDecodeTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun requirePostgres(s: Stormify) {
        if (s.sqlDialect != SqlDialect.POSTGRESQL)
            skipTest(SkipReason.DIALECT_QUIRK, "binary result decoding is PostgreSQL-specific")
    }

    /** Text payloads of exactly 2, 4 and 8 bytes must not be read as numbers. */
    @Test
    fun testShortTextIsNotDecodedAsNumber() {
        withDb("PG-BINARY-TEXT") { s ->
            requirePostgres(s)
            TestDDL.dropTable("kdbc_pg_bin_txt")
            s.executeUpdate(
                TestDDL.createTable(
                    "kdbc_pg_bin_txt",
                    "${TestDDL.intPrimaryKey("id")}, v ${TestDDL.textType()}"
                )
            )
            try {
                // 2, 4 and 8 byte payloads — exactly the lengths a numeric guess would claim.
                val cases = listOf("12" to 12.0, "12.5" to 12.5, "1234.125" to 1234.125)
                cases.forEachIndexed { i, (text, _) ->
                    s.executeUpdate("INSERT INTO kdbc_pg_bin_txt (id, v) VALUES (?, ?)", i, text)
                }
                cases.forEachIndexed { i, (text, expected) ->
                    val d = s.readOne<Double>("SELECT v FROM kdbc_pg_bin_txt WHERE id = ?", i)
                    assertNotNull(d, "text '$text' read back as null")
                    assertEquals(expected, d, "text '$text' (${text.length} bytes)")
                }
            } finally {
                TestDDL.dropTable("kdbc_pg_bin_txt")
            }
        }
    }

    private val domains = listOf(
        "kdbc_dom_f8" to "DOUBLE PRECISION",
        "kdbc_dom_f4" to "REAL",
        "kdbc_dom_i2" to "SMALLINT",
        "kdbc_dom_i4" to "INTEGER",
        "kdbc_dom_i8" to "BIGINT",
        "kdbc_dom_nu" to "NUMERIC(12,3)",
        "kdbc_dom_tx" to "VARCHAR(32)",
    )

    /**
     * A DOMAIN is reported under its own OID while the value on the wire uses the
     * base type's encoding, so decoding must resolve the OID first. This covers a
     * domain over every family — float, integer, numeric and text — because a rule
     * that only recognises some of them is the failure mode being guarded against.
     */
    @Test
    fun testDomainOverAnyBaseTypeDecodes() {
        withDb("PG-BINARY-DOMAIN") { s ->
            requirePostgres(s)
            TestDDL.dropTable("kdbc_pg_bin_dom")
            domains.forEach { (name, _) -> s.executeUpdate("DROP DOMAIN IF EXISTS $name") }
            domains.forEach { (name, base) -> s.executeUpdate("CREATE DOMAIN $name AS $base") }
            s.executeUpdate(
                TestDDL.createTable(
                    "kdbc_pg_bin_dom",
                    "${TestDDL.intPrimaryKey("id")}, " +
                            domains.joinToString(", ") { (name, _) -> "c_$name $name" }
                )
            )
            try {
                s.executeUpdate(
                    "INSERT INTO kdbc_pg_bin_dom (id, ${domains.joinToString(", ") { "c_${it.first}" }}) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    1, 1234.125, 2.5, 42, 70000, 9000000000L, 12.5, "31.25"
                )

                assertEquals(1234.125, s.readOne<Double>("SELECT c_kdbc_dom_f8 FROM kdbc_pg_bin_dom"), "over double precision")
                assertEquals(2.5, s.readOne<Double>("SELECT c_kdbc_dom_f4 FROM kdbc_pg_bin_dom"), "over real")
                assertEquals(42.0, s.readOne<Double>("SELECT c_kdbc_dom_i2 FROM kdbc_pg_bin_dom"), "over smallint")
                assertEquals(70000.0, s.readOne<Double>("SELECT c_kdbc_dom_i4 FROM kdbc_pg_bin_dom"), "over integer")
                assertEquals(9.0E9, s.readOne<Double>("SELECT c_kdbc_dom_i8 FROM kdbc_pg_bin_dom"), "over bigint")
                assertEquals(12.5, s.readOne<Double>("SELECT c_kdbc_dom_nu FROM kdbc_pg_bin_dom"), "over numeric")
                assertEquals(31.25, s.readOne<Double>("SELECT c_kdbc_dom_tx FROM kdbc_pg_bin_dom"), "over varchar")

                // The integer and string getters resolve through the same map.
                assertEquals(70000L, s.readOne<Long>("SELECT c_kdbc_dom_i4 FROM kdbc_pg_bin_dom"), "getLong over integer")
                assertEquals(9000000000L, s.readOne<Long>("SELECT c_kdbc_dom_i8 FROM kdbc_pg_bin_dom"), "getLong over bigint")
                assertEquals("12.500", s.readOne<String>("SELECT c_kdbc_dom_nu FROM kdbc_pg_bin_dom"), "getString over numeric")
                assertEquals("31.25", s.readOne<String>("SELECT c_kdbc_dom_tx FROM kdbc_pg_bin_dom"), "getString over varchar")
            } finally {
                TestDDL.dropTable("kdbc_pg_bin_dom")
                domains.forEach { (name, _) -> s.executeUpdate("DROP DOMAIN IF EXISTS $name") }
            }
        }
    }

    /** BOOLEAN must agree between the integer and the floating-point getter. */
    @Test
    fun testBooleanAgreesAcrossGetters() {
        withDb("PG-BINARY-BOOL") { s ->
            requirePostgres(s)
            TestDDL.dropTable("kdbc_pg_bin_bool")
            s.executeUpdate(
                TestDDL.createTable(
                    "kdbc_pg_bin_bool",
                    "${TestDDL.intPrimaryKey("id")}, b ${TestDDL.booleanType()}"
                )
            )
            try {
                s.executeUpdate("INSERT INTO kdbc_pg_bin_bool (id, b) VALUES (?, ?)", 1, true)
                s.executeUpdate("INSERT INTO kdbc_pg_bin_bool (id, b) VALUES (?, ?)", 2, false)
                assertEquals(1.0, s.readOne<Double>("SELECT b FROM kdbc_pg_bin_bool WHERE id = 1"))
                assertEquals(1L, s.readOne<Long>("SELECT b FROM kdbc_pg_bin_bool WHERE id = 1"))
                assertEquals(0.0, s.readOne<Double>("SELECT b FROM kdbc_pg_bin_bool WHERE id = 2"))
                assertEquals(0L, s.readOne<Long>("SELECT b FROM kdbc_pg_bin_bool WHERE id = 2"))
            } finally {
                TestDDL.dropTable("kdbc_pg_bin_bool")
            }
        }
    }
}
