// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-platform read behaviour that the read matrix cannot express.
 *
 * [ReadMatrixTest] crosses every stored column with every declared read type against a
 * single small row, which is the right shape for coercion rules. The two cases here need
 * a row the matrix does not have: a LOB large enough that a driver hands back a locator
 * instead of the value, and a row of SQL NULLs.
 */
open class ReadAgreementTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun Stormify.createSchema() {
        TestDDL.dropTable("read_agree")
        executeUpdate(
            TestDDL.createTable(
                "read_agree",
                "${TestDDL.intPrimaryKey("id")}, t ${TestDDL.textType()}, " +
                        "${TestDDL.intColumn("n")}, d ${TestDDL.decimalType(18, 4)}"
            )
        )
    }

    /**
     * LOB columns read without a declared target type.
     *
     * The generic row-as-map read asks for no particular type, which is where a JDBC
     * driver is free to hand back a live `Clob`/`Blob` locator instead of the value.
     * Such a handle stops being readable once the row moves on and has no counterpart
     * on the native drivers, so both sides must present the content itself.
     */
    @Test
    fun lobColumnsReadUntypedYieldTheirContent() = withDb("READ-AGREE-LOB") { s ->
        TestDDL.dropTable("read_agree_lob")
        s.executeUpdate(
            TestDDL.createTable(
                "read_agree_lob",
                "${TestDDL.intPrimaryKey("id")}, c ${TestDDL.largeTextType()}, b ${TestDDL.blobType()}"
            )
        )
        try {
            val text = "x".repeat(5000)
            val bytes = ByteArray(5000) { (it % 251).toByte() }
            s.executeUpdate("INSERT INTO read_agree_lob (id, c, b) VALUES (?, ?, ?)", 1, text, bytes)

            val row = s.read<Map<String, Any>>("SELECT c, b FROM read_agree_lob WHERE id = 1").single()
            assertEquals(5000, (row["c"] as? String)?.length, "clob as String")
            assertEquals(5000, (row["b"] as? ByteArray)?.size, "blob as ByteArray")
        } finally {
            TestDDL.dropTable("read_agree_lob")
        }
    }

    /**
     * SQL NULL never becomes a zero value. Reading it into a non-nullable scalar is
     * refused rather than silently coerced, and the refusal must be the same on every
     * platform — a driver that returned 0 or false here would corrupt data quietly.
     */
    @Test
    fun nullIsRefusedForNonNullableScalars() = withDb("READ-AGREE-NULL") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO read_agree (id, t, n, d) VALUES (1, NULL, NULL, NULL)")

        assertFailsWith<SQLException>("NULL text as String") {
            s.readOne<String>("SELECT t FROM read_agree WHERE id = 1")
        }
        assertFailsWith<SQLException>("NULL int as Int") {
            s.readOne<Int>("SELECT n FROM read_agree WHERE id = 1")
        }
        assertFailsWith<SQLException>("NULL decimal as Double") {
            s.readOne<Double>("SELECT d FROM read_agree WHERE id = 1")
        }
        assertFailsWith<SQLException>("NULL int as Boolean") {
            s.readOne<Boolean>("SELECT n FROM read_agree WHERE id = 1")
        }
    }
}
