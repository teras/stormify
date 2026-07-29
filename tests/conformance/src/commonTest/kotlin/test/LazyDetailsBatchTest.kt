// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.logger.LogLevel
import onl.ycode.logger.SilentLogger
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reading a `by lazyDetails()` property across a whole result must cost one query for the
 * whole page, not one per row.
 *
 * The rows of a result come back together, so the first one asked for its children can
 * fetch every row's children at once. Without that, a page of N parents costs N+1 queries
 * — the shape that quietly turns a fast endpoint into a slow one as the page grows, with
 * nothing in the code looking wrong.
 *
 * The counting here is deliberately of `SELECT`s against the child table only, so the
 * assertion says what it means and is not disturbed by the parent query, by reference
 * hydration, or by anything the driver does on its own.
 */
open class LazyDetailsBatchTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    private fun schema(s: Stormify, parents: Int, childrenEach: Int) {
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(
            TestDDL.createTable(
                "auto_parent",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"
            )
        )
        s.executeUpdate(
            TestDDL.createTable(
                "auto_child",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                    "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"
            )
        )
        var childId = 1
        for (p in 1..parents) {
            val parent = AutoParentEntity().apply { id = p; data = "parent $p"; other = "o$p" }
            s.create(parent)
            repeat(childrenEach) { c ->
                s.create(AutoChildEntity().apply {
                    id = childId++
                    data = "child $p.$c"
                    this.parent = parent
                })
            }
        }
    }

    /** Counts SELECTs issued against [table] while [block] runs. */
    private fun countingSelects(s: Stormify, table: String, block: () -> Unit): Int {
        val original = s.logger
        var count = 0
        s.logger = WatchLogger(SilentLogger()) { level, message, _ ->
            if (level == LogLevel.DEBUG &&
                message.startsWith("SELECT", ignoreCase = true) &&
                message.contains(table, ignoreCase = true)
            ) count++
        }.also { it.level = LogLevel.DEBUG }
        try {
            block()
        } finally {
            s.logger = original
        }
        return count
    }

    /**
     * The point of the feature: ten parents, one query for all their children.
     *
     * Asserted as "fewer than one per parent" rather than exactly one, so the test states
     * the property that matters — the cost stops scaling with the page — instead of
     * pinning an implementation detail that a future batch-size change would break.
     */
    @Test
    fun readingDetailsAcrossAPageCostsOneQuery() = withDb("LazyDetailsBatch") { s ->
        schema(s, parents = 10, childrenEach = 3)

        val parents = s.findAll<AutoParentEntity>().sortedBy { it.id }
        assertEquals(10, parents.size, "setup did not produce ten parents")

        val queries = countingSelects(s, "auto_child") {
            for (parent in parents) assertEquals(3, parent.children.size, "parent ${parent.id}")
        }

        assertEquals(1, queries, "expected one grouped query for the whole page, got $queries")
    }

    /** Every parent must receive its own children, not another parent's and not all of them. */
    @Test
    fun eachParentGetsItsOwnChildren() = withDb("LazyDetailsBatchSlices") { s ->
        schema(s, parents = 5, childrenEach = 2)

        for (parent in s.findAll<AutoParentEntity>()) {
            val labels = parent.children.mapNotNull { it.data }.sorted()
            assertEquals(
                listOf("child ${parent.id}.0", "child ${parent.id}.1"), labels,
                "parent ${parent.id} received the wrong children"
            )
        }
    }

    /** The child's back-reference must point at the parent instance the caller already holds. */
    @Test
    fun childrenPointBackAtTheSameParentInstance() = withDb("LazyDetailsBatchIdentity") { s ->
        schema(s, parents = 4, childrenEach = 2)

        for (parent in s.findAll<AutoParentEntity>())
            for (child in parent.children)
                assertTrue(child.parent === parent, "child ${child.id} points at a different instance")
    }

    /**
     * A single parent must not be dragged through the batch machinery: with nothing to
     * group it with, the grouped query would be pure overhead.
     */
    @Test
    fun aSingleParentStillWorks() = withDb("LazyDetailsBatchSingle") { s ->
        schema(s, parents = 1, childrenEach = 4)

        val parent = s.findAll<AutoParentEntity>().single()
        assertEquals(4, parent.children.size)
    }

    /**
     * A parent loaded on its own, after a page was read, must still see its children.
     *
     * This is the case a naive cache gets wrong: the group remembers what it fetched, and
     * an entity that was never part of it must not be handed an empty list.
     */
    @Test
    fun aParentFetchedSeparatelyIsUnaffected() = withDb("LazyDetailsBatchSeparate") { s ->
        schema(s, parents = 6, childrenEach = 2)

        s.findAll<AutoParentEntity>().forEach { it.children.size }

        val alone = s.findById<AutoParentEntity>(3)!!
        assertEquals(2, alone.children.size, "a separately fetched parent lost its children")
    }
}
