package test

import onl.ycode.logger.LogLevel
import onl.ycode.logger.SilentLogger
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.DanglingReferences
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A foreign key pointing at a row that does not exist must not poison its siblings: the
 * valid references of the same result load and keep working, only the orphan fails.
 */
open class OrphanReferenceTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    private fun schema(s: Stormify) {
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(TestDDL.createTable("auto_parent",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"))
        // No FK constraint, so a child can point at a parent that does not exist.
        s.executeUpdate(TestDDL.createTable("auto_child",
            "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, ${TestDDL.intColumn("parent")}"))
        s.create(AutoParentEntity().apply { id = 1; data = "parent 1" })
        s.create(AutoParentEntity().apply { id = 2; data = "parent 2" })
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (1, 'c1', 1)")
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (2, 'c2', 99)")
        s.executeUpdate("INSERT INTO auto_child (id, data, parent) VALUES (3, 'c3', 2)")
    }

    @Test
    fun orphanFailsAloneWhenAValidSiblingTriggersTheBatch() = withDb("ORPHAN-REF-VALID-FIRST") { s ->
        schema(s)
        val (p1, orphan, p2) = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id").map { it.parent!! }
        repeat(3) { round ->
            assertEquals("parent 1", p1.data, "round $round")
            assertEquals("parent 2", p2.data, "round $round")
            assertFails("round $round") { orphan.data }
        }
    }

    @Test
    fun orphanFailsAloneWhenItTriggersTheBatch() = withDb("ORPHAN-REF-ORPHAN-FIRST") { s ->
        schema(s)
        val (p1, orphan, p2) = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id").map { it.parent!! }
        repeat(3) { round ->
            assertFails("round $round") { orphan.data }
            assertEquals("parent 1", p1.data, "round $round")
            assertEquals("parent 2", p2.data, "round $round")
        }
    }

    private fun withNullPolicy(s: Stormify, block: () -> Unit) {
        s.danglingReferences = DanglingReferences.NULL
        try { block() } finally { s.danglingReferences = DanglingReferences.THROW }
    }

    /** NULL policy: the orphan reads as null right after the read; the others stay lazy. */
    @Test
    fun nullPolicyNullsTheOrphanAndKeepsTheOthersLazy() = withDb("ORPHAN-REF-NULL") { s ->
        schema(s)
        withNullPolicy(s) {
            var parentQueries = 0
            val original = s.logger
            s.logger = WatchLogger(SilentLogger()) { level, message, _ ->
                if (level == LogLevel.DEBUG && message.startsWith("SELECT", ignoreCase = true) &&
                    message.contains("auto_parent", ignoreCase = true)) parentQueries++
            }.also { it.level = LogLevel.DEBUG }
            val children = try {
                s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id")
            } finally {
                s.logger = original
            }
            assertNull(children[1].parent, "the orphan reference")
            val p1 = assertNotNull(children[0].parent)
            val p2 = assertNotNull(children[2].parent)
            assertEquals(1, parentQueries, "one existence check, no load of the parents")
            assertEquals("parent 1", p1.data)
            assertEquals("parent 2", p2.data)
        }
    }

    /**
     * NULL policy on a plain entity, which cannot remember the key: the reference is kept
     * (it holds the key), so an update writes the key back instead of NULL.
     */
    @Test
    fun nullPolicyOnPlainEntity() = withDb("ORPHAN-REF-NULL-PLAIN") { s ->
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(TestDDL.createTable("child",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, ${TestDDL.intColumn("parent")}"))
        s.create(TestC(1, "one"))
        s.executeUpdate("INSERT INTO child (id, name, parent) VALUES (1, 'a', 1)")
        s.executeUpdate("INSERT INTO child (id, name, parent) VALUES (2, 'b', 77)")
        withNullPolicy(s) {
            val children = s.read<Child>("SELECT * FROM child ORDER BY id")
            assertEquals(1, children[0].parent?.id)
            assertEquals(77, children[1].parent?.id)
            s.update(children[1].apply { name = "b2" })
            assertEquals(77, s.readOne<Int>("SELECT parent FROM child WHERE id = 2"))
        }
    }

    /** A reference held while the policy was THROW loads with default values under NULL. */
    @Test
    fun nullPolicyLoadsAHeldOrphanEmpty() = withDb("ORPHAN-REF-NULL-HELD") { s ->
        schema(s)
        val (p1, orphan, p2) = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id").map { it.parent!! }
        withNullPolicy(s) {
            assertNull(orphan.data)          // batch load: missing row, no exception
            assertEquals("parent 1", p1.data)
            assertEquals("parent 2", p2.data)
        }
        val single = s.read<AutoChildEntity>("SELECT * FROM auto_child WHERE id = 2").single().parent!!
        withNullPolicy(s) { assertNull(single.data) }   // single load
    }

    /** The existence check runs on the transaction's connection and sees its rows. */
    @Test
    fun nullPolicyInsideATransaction() = withDb("ORPHAN-REF-NULL-TX") { s ->
        schema(s)
        withNullPolicy(s) {
            var seen: AutoParentEntity? = null
            try {
                s.transaction {
                    s.executeUpdate("INSERT INTO auto_parent (id, data) VALUES (99, 'late')")
                    seen = s.read<AutoChildEntity>("SELECT * FROM auto_child ORDER BY id")[1].parent
                    throw IllegalStateException("roll back")
                }
            } catch (_: Exception) {
            }
            assertNotNull(seen, "uncommitted parent 99 is visible to the check")
        }
    }
}
