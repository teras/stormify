// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A row that mixes a large text column with ordinary columns.
 *
 * Drivers that fetch prepared-statement results into per-column buffers size those
 * buffers up front, so a value larger than the guess forces a resize and a refetch
 * of that one column. The rest of the row must survive that: re-registering the
 * enlarged buffers resets every bound length output, so doing it while the row is
 * still being read empties the sibling columns without any error being raised.
 *
 * Two rows of different sizes are used so the enlarged buffer is also exercised on
 * the following fetch, and the large column is placed before the plain ones so a
 * mid-row reset would be visible in them.
 */
open class LargeTextRowTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    // Comfortably past the 4 KB buffer the drivers reserve when the server reports
    // no maximum length for the column.
    private val bigA = "a".repeat(8000)
    private val bigB = "b".repeat(6000)

    @Test
    fun largeTextDoesNotClobberTheRestOfTheRow() = withDb("LARGE-TEXT-ROW") { s ->
        TestDDL.dropTable("large_text_row")
        s.executeUpdate(
            TestDDL.createTable(
                "large_text_row",
                "${TestDDL.intPrimaryKey("id")}, big ${TestDDL.largeTextType()}, " +
                        "tail ${TestDDL.textType()}, ${TestDDL.intColumn("n")}"
            )
        )
        try {
            s.executeUpdate(
                "INSERT INTO large_text_row (id, big, tail, n) VALUES (?, ?, ?, ?)",
                1, bigA, "alpha", 111
            )
            s.executeUpdate(
                "INSERT INTO large_text_row (id, big, tail, n) VALUES (?, ?, ?, ?)",
                2, bigB, "beta", 222
            )

            val rows = s.read<Map<String, Any>>("SELECT id, big, tail, n FROM large_text_row ORDER BY id")
            assertEquals(2, rows.size, "row count")

            val first = rows[0]
            assertEquals(8000, (first["big"] as? String)?.length, "row 1 large column length")
            assertEquals("alpha", first["tail"], "row 1 sibling text after the large column")
            assertEquals(111L, (first["n"] as Number).toLong(), "row 1 sibling number")

            // The second row is read through the buffer enlarged for the first one.
            val second = rows[1]
            assertEquals(6000, (second["big"] as? String)?.length, "row 2 large column length")
            assertEquals("beta", second["tail"], "row 2 sibling text after the large column")
            assertEquals(222L, (second["n"] as Number).toLong(), "row 2 sibling number")
        } finally {
            TestDDL.dropTable("large_text_row")
        }
    }

    /** The large value itself must come back intact, not merely non-empty. */
    @Test
    fun largeTextRoundTripsExactly() = withDb("LARGE-TEXT-EXACT") { s ->
        TestDDL.dropTable("large_text_row")
        s.executeUpdate(
            TestDDL.createTable(
                "large_text_row",
                "${TestDDL.intPrimaryKey("id")}, big ${TestDDL.largeTextType()}"
            )
        )
        try {
            val value = (0 until 5000).joinToString("") { ('a' + (it % 26)).toString() }
            s.executeUpdate("INSERT INTO large_text_row (id, big) VALUES (?, ?)", 1, value)
            val back = s.readOne<String>("SELECT big FROM large_text_row WHERE id = 1")
            assertNotNull(back)
            assertEquals(value.length, back.length, "length")
            assertEquals(value, back, "content")
        } finally {
            TestDDL.dropTable("large_text_row")
        }
    }
}
