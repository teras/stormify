package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.TypeUtils
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Rows read by position: columns with the same label (`a.id`, `b.id`) all come back,
 * where a `Map` keeps only one of them, and reads inside a transaction see its own writes.
 */
open class ReadRowsTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun schema(s: Stormify) {
        TestDDL.dropTable("rr_b")
        TestDDL.dropTable("rr_a")
        s.executeUpdate(TestDDL.createTable("rr_a", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("rr_b",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, ${TestDDL.intColumn("a_id")}"))
        s.executeUpdate("INSERT INTO rr_a (id, name) VALUES (1, 'alpha')")
        s.executeUpdate("INSERT INTO rr_b (id, name, a_id) VALUES (10, 'beta', 1)")
    }

    private fun int(v: Any?): Int? = TypeUtils.castTo(Int::class, v)

    @Test
    fun columnsWithTheSameLabelAllComeBack() = withDb("READ-ROWS-SAME-LABEL") { s ->
        schema(s)
        val rows = s.readRows("SELECT a.id, b.id, a.name, b.name FROM rr_a a JOIN rr_b b ON b.a_id = a.id")
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(4, row.size)
        assertEquals(listOf(1, 10), listOf(int(row[0]), int(row[1])))
        assertEquals(listOf("alpha", "beta"), listOf(row[2].toString(), row[3].toString()))

        // The streaming form hands over the same rows, and NULL stays null.
        val streamed = mutableListOf<Array<Any?>>()
        val count = s.readRowsCursor("SELECT b.id, NULL FROM rr_b b WHERE b.id = ?", 10) { streamed += it }
        assertEquals(1, count)
        assertEquals(10, int(streamed.single()[0]))
        assertEquals(null, streamed.single()[1])
    }

    @Test
    fun readsInsideATransactionSeeItsWrites() = withDb("READ-ROWS-TX") { s ->
        schema(s)
        var seen: List<Int?> = emptyList()
        try {
            s.transaction {
                s.executeUpdate("INSERT INTO rr_a (id, name) VALUES (2, 'gamma')")
                // Uncommitted: visible only on the transaction's own connection.
                seen = s.readRows("SELECT id FROM rr_a ORDER BY id").map { int(it[0]) }
                throw IllegalStateException("roll back")
            }
        } catch (_: Exception) {
        }
        assertEquals(listOf(1, 2), seen)
        assertEquals(listOf(1), s.readRows("SELECT id FROM rr_a ORDER BY id").map { int(it[0]) })
    }
}
