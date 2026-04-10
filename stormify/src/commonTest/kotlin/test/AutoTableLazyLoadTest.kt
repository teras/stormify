// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.Stormify
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests the lazy-load behavior of [onl.ycode.stormify.AutoTable] and the [onl.ycode.stormify.db]
 * property delegate. Covers the matrix of (stormify attached? / user touched? / hasRun?) states
 * to verify that:
 *
 * - Fresh user-constructed entities work without any Stormify instance.
 * - Entities that only have their ID set fail loudly when a field is read without a Stormify.
 * - Stubs from reference resolution lazy-load correctly from the database.
 * - Entities already populated (via findById/create) never re-trigger lazy-load.
 *
 * Important: the no-database tests in this class require [Stormify.defaultInstance] to be `null`.
 * The class name starts with 'A' so it runs before [PagedListTest] (and any other class that
 * calls `asDefault()`) under the default alphabetical test ordering. The [requireCleanState]
 * guard will fail loudly if this assumption is ever broken.
 */
class AutoTableLazyLoadTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    @BeforeTest
    fun requireCleanState() {
        check(Stormify.defaultInstance == null) {
            "AutoTableLazyLoadTest requires Stormify.defaultInstance to be null. " +
                    "Some earlier test has called asDefault() without cleaning up. " +
                    "This class is named to run first alphabetically — check test ordering."
        }
    }

    // --- Tests that don't need a database ---

    /** Fresh user construction without any Stormify: setting and reading should work silently. */
    @Test
    fun freshConstructSetThenRead() {
        val p = AutoParentEntity().apply { id = 1; data = "hello"; other = "world" }
        assertEquals(1, p.id)
        assertEquals("hello", p.data)
        assertEquals("world", p.other)
    }

    /**
     * User set field A, reads unset field B: no throw. Once the user has touched any delegated
     * field, the entity is considered "user-owned" and reads return the delegate default silently.
     */
    @Test
    fun freshConstructPartialSetReadUnset() {
        val p = AutoParentEntity().apply { id = 1; data = "hello" }
        // 'other' was never set — should return the delegate default (null) without throwing
        assertNull(p.other)
    }

    /**
     * Only ID is set, no Stormify attached anywhere, user tries to read a delegated field:
     * this is the classic "forgot to attach stormify, expected lazy-load" bug — must throw
     * with our specific delegate message (not the populate "No data found" path).
     */
    @Test
    fun onlyIdSetWithoutStormifyThrowsOnRead() {
        val p = AutoParentEntity().apply { id = 42 }
        val ex = assertFailsWith<SQLException> { p.data }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("Cannot lazy-load"), "expected delegate check message, got: $msg")
        assertTrue(msg.contains("data"), "message should mention the property: $msg")
        assertTrue(msg.contains("AutoParentEntity"), "message should mention the class: $msg")
    }

    /** Same as above but for a different unset delegated property, to confirm the check is generic. */
    @Test
    fun onlyIdSetWithoutStormifyThrowsOnDifferentProperty() {
        val p = AutoParentEntity().apply { id = 99 }
        val ex = assertFailsWith<SQLException> { p.other }
        assertTrue(ex.message?.contains("other") ?: false)
    }

    // --- Tests that need a database ---

    private fun Stormify.createAutoSchema() {
        TestDDL.dropTable("auto_child")
        TestDDL.dropTable("auto_parent")
        executeUpdate(
            TestDDL.createTable(
                "auto_parent",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, other ${TestDDL.textType()}"
            )
        )
        executeUpdate(
            TestDDL.createTable(
                "auto_child",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, " +
                        "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "auto_parent", "id")}"
            )
        )
    }

    /**
     * After findById, the entity has hasRun=true (via markPopulated inside populate).
     * Reading delegated fields returns loaded values with no further populate calls.
     */
    @Test
    fun findByIdResultReturnsLoadedValues() = withDb("AUTO-LAZY-FINDBYID") { s ->
        s.createAutoSchema()
        s.create(AutoParentEntity().apply { id = 1; data = "d1"; other = "o1" })

        val loaded = s.findById<AutoParentEntity>(1)!!
        assertEquals("d1", loaded.data)
        assertEquals("o1", loaded.other)
    }

    /**
     * After create, hasRun=true (set by markPopulated in Stormify.create).
     * Reading unset delegated fields returns the in-memory default without lazy-load attempts.
     */
    @Test
    fun afterCreateReadsReturnInMemory() = withDb("AUTO-LAZY-CREATE") { s ->
        s.createAutoSchema()
        val p = AutoParentEntity().apply { id = 1; data = "d1" }
        // 'other' deliberately not set
        s.create(p)
        // After create, the entity has stormify attached and hasRun=true — reads should just return prop
        assertEquals("d1", p.data)
        assertNull(p.other)
    }

    /**
     * Reference stub: child.parent is a stub with only id set and stormify attached.
     * Reading a field on the stub triggers lazy-load from DB. This is the auto-attached
     * counterpart of the "Manual Stubs" pattern documented in Advanced_topics.md — both
     * go through the same `db` delegate path, the only difference is how the Stormify
     * instance becomes reachable (FK auto-attach vs. `Stormify.defaultInstance`).
     */
    @Test
    fun referenceStubLazyLoads() = withDb("AUTO-LAZY-STUB") { s ->
        s.createAutoSchema()
        s.create(AutoParentEntity().apply { id = 5; data = "parent-data"; other = "parent-other" })
        s.create(AutoChildEntity().apply { id = 10; data = "child-data"; parent = AutoParentEntity().apply { id = 5 } })

        val child = s.findById<AutoChildEntity>(10)!!
        val parent = child.parent!!
        // Accessing parent.data on the stub should trigger lazy-load and return the DB value
        assertEquals("parent-data", parent.data)
        assertEquals("parent-other", parent.other)
    }
}
