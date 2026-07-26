// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-platform agreement when reading a column as a type other than its own.
 *
 * The type matrix round-trips values Stormify itself wrote, so it only ever sees
 * canonical representations. This test covers the other case: rows that already
 * exist in the database — written by a legacy schema, a migration, or another
 * application — read into a declared Kotlin type. That is where each JDBC driver
 * and each native driver applies its own coercion rules, and where they can
 * disagree without anyone failing.
 *
 * Values are inserted as SQL literals rather than bound parameters so the stored
 * representation is exactly the one under test. Every assertion states the single
 * answer required on all 9 databases, on JVM, native and Android alike.
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

    /** A text column holding a number, read as each numeric type. */
    @Test
    fun textHoldingNumberReadsAsNumeric() = withDb("READ-AGREE-TEXT-NUM") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO read_agree (id, t) VALUES (1, '42')")
        s.executeUpdate("INSERT INTO read_agree (id, t) VALUES (2, '12.5')")
        s.executeUpdate("INSERT INTO read_agree (id, t) VALUES (3, '-7')")

        assertEquals(42, s.readOne<Int>("SELECT t FROM read_agree WHERE id = 1"), "'42' as Int")
        assertEquals(42L, s.readOne<Long>("SELECT t FROM read_agree WHERE id = 1"), "'42' as Long")
        assertEquals(42.0, s.readOne<Double>("SELECT t FROM read_agree WHERE id = 1"), "'42' as Double")
        assertEquals("42", s.readOne<String>("SELECT t FROM read_agree WHERE id = 1"), "'42' as String")

        assertEquals(12.5, s.readOne<Double>("SELECT t FROM read_agree WHERE id = 2"), "'12.5' as Double")
        assertEquals(-7, s.readOne<Int>("SELECT t FROM read_agree WHERE id = 3"), "'-7' as Int")
    }

    /** A numeric column read as text and as boolean. */
    @Test
    fun numericReadsAsTextAndBoolean() = withDb("READ-AGREE-NUM") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO read_agree (id, n) VALUES (1, 42)")
        s.executeUpdate("INSERT INTO read_agree (id, n) VALUES (2, 0)")
        s.executeUpdate("INSERT INTO read_agree (id, n) VALUES (3, 1)")

        assertEquals("42", s.readOne<String>("SELECT n FROM read_agree WHERE id = 1"), "42 as String")
        assertEquals(true, s.readOne<Boolean>("SELECT n FROM read_agree WHERE id = 3"), "1 as Boolean")
        assertEquals(false, s.readOne<Boolean>("SELECT n FROM read_agree WHERE id = 2"), "0 as Boolean")
        // JDBC treats any non-zero numeric as true.
        assertEquals(true, s.readOne<Boolean>("SELECT n FROM read_agree WHERE id = 1"), "42 as Boolean")
    }

    /**
     * A text column holding a number, read as boolean. The numeric getter and the
     * token rule give different answers here unless both platforms agree on which
     * one wins, so this is the case most likely to drift.
     */
    @Test
    fun textHoldingNumberReadsAsBoolean() = withDb("READ-AGREE-TEXT-BOOL") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO read_agree (id, t) VALUES (1, '1')")
        s.executeUpdate("INSERT INTO read_agree (id, t) VALUES (2, '0')")
        s.executeUpdate("INSERT INTO read_agree (id, t) VALUES (3, '42')")

        assertEquals(true, s.readOne<Boolean>("SELECT t FROM read_agree WHERE id = 1"), "'1' as Boolean")
        assertEquals(false, s.readOne<Boolean>("SELECT t FROM read_agree WHERE id = 2"), "'0' as Boolean")
        // Consistent with the numeric rule: a non-zero value is true.
        assertEquals(true, s.readOne<Boolean>("SELECT t FROM read_agree WHERE id = 3"), "'42' as Boolean")
    }

    /**
     * A decimal column read as an integral type. Whether the fraction is truncated
     * or rounded is exactly the kind of rule each driver picks for itself, so it is
     * pinned here: JDBC `getInt`/`getLong` truncate toward zero.
     */
    @Test
    fun decimalReadsAsIntegralAndBoolean() = withDb("READ-AGREE-DECIMAL") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO read_agree (id, d) VALUES (1, 12.5)")
        s.executeUpdate("INSERT INTO read_agree (id, d) VALUES (2, 0)")
        s.executeUpdate("INSERT INTO read_agree (id, d) VALUES (3, -3.75)")
        s.executeUpdate("INSERT INTO read_agree (id, d) VALUES (4, 12.4)")

        assertEquals(12.5, s.readOne<Double>("SELECT d FROM read_agree WHERE id = 1"), "12.5 as Double")
        assertEquals(12, s.readOne<Int>("SELECT d FROM read_agree WHERE id = 1"), "12.5 as Int")
        assertEquals(12L, s.readOne<Long>("SELECT d FROM read_agree WHERE id = 1"), "12.5 as Long")
        // Truncation, not rounding — 12.4 and 12.5 must land on the same integer.
        assertEquals(12, s.readOne<Int>("SELECT d FROM read_agree WHERE id = 4"), "12.4 as Int")
        // Toward zero, not floor.
        assertEquals(-3, s.readOne<Int>("SELECT d FROM read_agree WHERE id = 3"), "-3.75 as Int")

        assertEquals(true, s.readOne<Boolean>("SELECT d FROM read_agree WHERE id = 1"), "12.5 as Boolean")
        assertEquals(false, s.readOne<Boolean>("SELECT d FROM read_agree WHERE id = 2"), "0 as Boolean")
    }

    /** An integer column read as the wider numeric types. */
    @Test
    fun integerReadsAsWiderNumerics() = withDb("READ-AGREE-WIDEN") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO read_agree (id, n) VALUES (1, 42)")
        s.executeUpdate("INSERT INTO read_agree (id, n) VALUES (2, -42)")

        assertEquals(42.0, s.readOne<Double>("SELECT n FROM read_agree WHERE id = 1"), "42 as Double")
        assertEquals(42L, s.readOne<Long>("SELECT n FROM read_agree WHERE id = 1"), "42 as Long")
        assertEquals(-42.0, s.readOne<Double>("SELECT n FROM read_agree WHERE id = 2"), "-42 as Double")
        assertEquals("-42", s.readOne<String>("SELECT n FROM read_agree WHERE id = 2"), "-42 as String")
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
