package test

import onl.ycode.stormify.AutoTable
import onl.ycode.stormify.DbField
import onl.ycode.stormify.DbTable
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.db
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Batch hydration keys rows by the string form of the primary key, comparing the
 * value read from the database against the value held by the entity. The two must
 * agree for every key type.
 *
 * An absolute-instant key is the case where they can drift: the JDBC layer reads
 * these as `java.sql.Timestamp` (asking a driver for `java.time.Instant` directly
 * is not portable), whose `toString()` is `"2023-11-14 22:13:20.0"` while
 * `Instant.toString()` is `"2023-11-14T22:13:20Z"`. Without converting to the
 * declared key type before keying, no row ever matches and hydration fails with
 * "No data found", even though every row was returned.
 *
 * Two distinct parents are used so the batch actually issues an `IN (?, ?)` query
 * and has to match both rows back to their references.
 */
class InstantPkBatchTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    // Whole seconds: the point under test is key matching, not sub-second precision,
    // and the column type varies in resolution across the supported databases.
    private val t1: java.time.Instant = java.time.Instant.parse("2023-11-14T22:13:20Z")
    private val t2: java.time.Instant = java.time.Instant.parse("2024-03-02T08:45:00Z")

    private fun Stormify.createSchema() {
        TestDDL.dropTable("inst_child")
        TestDDL.dropTable("inst_parent")
        executeUpdate(
            TestDDL.createTable(
                "inst_parent",
                "at ${TestDDL.timestampType()} PRIMARY KEY, name ${TestDDL.textType()}"
            )
        )
        executeUpdate(
            TestDDL.createTable(
                "inst_child",
                "${TestDDL.intPrimaryKey("id")}, title ${TestDDL.textType()}, " +
                        "parent ${TestDDL.timestampType()}, " +
                        TestDDL.foreignKey("parent", "inst_parent", "at")
            )
        )
    }

    @Test
    fun batchHydrationMatchesInstantKeys() = withDb("INSTANT-PK-BATCH") { s ->
        s.createSchema()

        val p1 = InstantParent().apply { at = t1; name = "Alpha" }
        val p2 = InstantParent().apply { at = t2; name = "Beta" }
        s.create(p1); s.create(p2)
        s.create(InstantChild().apply { id = 1; title = "one"; parent = p1 })
        s.create(InstantChild().apply { id = 2; title = "two"; parent = p2 })

        // Reading the children yields two shadow parent references that form a
        // sibling group; touching one hydrates the whole group in a single query.
        val children = s.read<InstantChild>("SELECT * FROM inst_child ORDER BY id")
        assertEquals(2, children.size)

        val first = children[0].parent
        val second = children[1].parent
        assertNotNull(first)
        assertNotNull(second)

        // This access is what triggers batch hydration.
        assertEquals("Alpha", first.name)
        assertEquals("Beta", second.name)
        assertEquals(t1, first.at)
        assertEquals(t2, second.at)
    }
}

@DbTable(name = "inst_parent")
class InstantParent : AutoTable() {
    @DbField(primaryKey = true) var at: java.time.Instant? = null
    var name: String? by db(null)
    override fun toString() = "InstantParent(at=$at, name=$name)"
}

@DbTable(name = "inst_child")
class InstantChild : AutoTable() {
    @DbField(primaryKey = true) var id: Int? = null
    var title: String? by db(null)
    var parent: InstantParent? by db(null)
    override fun toString() = "InstantChild(id=$id, title=$title)"
}
