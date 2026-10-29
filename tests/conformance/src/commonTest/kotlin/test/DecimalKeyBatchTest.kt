package test

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * A decimal key can come back from the database with a different scale on the
 * FK side and on the PK side (`100` from an integer FK column, `100.00` from a
 * `DECIMAL(12,2)` key on PostgreSQL). Batch hydration and the per-result
 * reference cache must treat both as the same key.
 */
open class DecimalKeyBatchTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    private fun Stormify.createSchema() {
        TestDDL.dropTable("dec_child")
        TestDDL.dropTable("dec_parent")
        executeUpdate(TestDDL.createTable("dec_parent",
            "id ${TestDDL.decimalType(12, 2)} PRIMARY KEY, name ${TestDDL.textType()}"))
        // No FK constraint: PostgreSQL refuses one between INT and NUMERIC.
        executeUpdate(TestDDL.createTable("dec_child",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("parent")}, other ${TestDDL.decimalType(12, 2)}"))
    }

    @Test
    fun batchHydrationMatchesKeysOfDifferentScale() = withDb("DECIMAL-PK-BATCH") { s ->
        s.createSchema()
        s.executeUpdate("INSERT INTO dec_parent (id, name) VALUES (100, 'Alpha')")
        s.executeUpdate("INSERT INTO dec_parent (id, name) VALUES (200, 'Beta')")
        s.executeUpdate("INSERT INTO dec_child (id, parent, other) VALUES (1, 100, 100)")
        s.executeUpdate("INSERT INTO dec_child (id, parent, other) VALUES (2, 200, NULL)")

        val children = s.read<DecChild>("SELECT * FROM dec_child ORDER BY id")
        val first = children[0].parent
        val second = children[1].parent
        assertNotNull(first)
        assertNotNull(second)
        // Touching one shadow reference hydrates the whole sibling group.
        assertEquals("Alpha", first.name)
        assertEquals("Beta", second.name)
        assertEquals(0, first.id!!.compareTo(BigDecimal.fromInt(100)))

        // The same row reached through an integer and through a decimal FK column
        // is one reference within a result.
        assertSame(first, children[0].other)
    }
}
