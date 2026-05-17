// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the auto-hydrate behavior of [onl.ycode.stormify.AutoTable] and the
 * [onl.ycode.stormify.db] property delegate.
 *
 * The contract under test:
 *
 * - **User-constructed entities** (`Entity()` in user code) never auto-hydrate.
 *   Reads return the in-memory value or the delegate default; writes just store.
 * - **Library-constructed shadow references** (the FK stubs produced when a parent
 *   is loaded) auto-hydrate on first access to any [db]-delegated property.
 * - **Already-loaded entities** (results of `findById`, post-`create`, etc.) never
 *   auto-hydrate — they're marked hydrated by the loading path.
 */
open class AutoTableLazyLoadTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name, test)

    // --- Tests that don't need a database ---

    /** User construction without any Stormify: setting and reading work silently. */
    @Test
    fun freshConstructSetThenRead() {
        val p = AutoParentEntity().apply { id = 1; data = "hello"; other = "world" }
        assertEquals(1, p.id)
        assertEquals("hello", p.data)
        assertEquals("world", p.other)
    }

    /** User sets one field, reads a different unset field — returns the delegate default silently. */
    @Test
    fun freshConstructPartialSetReadUnset() {
        val p = AutoParentEntity().apply { id = 1; data = "hello" }
        assertNull(p.other)
    }

    /**
     * Only id is set, no Stormify reachable, reads return the delegate default silently.
     * Auto-hydrate is not attempted on user-constructed entities — the user is responsible
     * for fetching data (via `findById` or an explicit [AutoTable.refresh] call).
     */
    @Test
    fun onlyIdSetWithoutStormifyReadsDefaultSilently() {
        val p = AutoParentEntity().apply { id = 42 }
        assertNull(p.data)
        assertNull(p.other)
    }

    // --- Tests that need a database ---

    /**
     * A user-constructed entity with an id that does not exist in the database, while a
     * default Stormify is attached. Setting a field on it must not trigger a SELECT, and
     * the user's value must be preserved.
     */
    @Test
    fun freshConstructWithDefaultStormifyAttached() = withDb("AUTO-LAZY-FRESH-DEFAULT") { s ->
        s.createAutoSchema()
        s.asDefault {
            val p = AutoParentEntity().apply { id = 999; data = "hello" }
            assertEquals(999, p.id)
            assertEquals("hello", p.data)
            assertNull(p.other)
        }
    }

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

    /** After `findById`, the entity is marked hydrated — reads return loaded values. */
    @Test
    fun findByIdResultReturnsLoadedValues() = withDb("AUTO-LAZY-FINDBYID") { s ->
        s.createAutoSchema()
        s.create(AutoParentEntity().apply { id = 1; data = "d1"; other = "o1" })

        val loaded = s.findById<AutoParentEntity>(1)!!
        assertEquals("d1", loaded.data)
        assertEquals("o1", loaded.other)
    }

    /** After `create`, the entity is marked hydrated — reads return the in-memory values used to insert. */
    @Test
    fun afterCreateReadsReturnInMemory() = withDb("AUTO-LAZY-CREATE") { s ->
        s.createAutoSchema()
        val p = AutoParentEntity().apply { id = 1; data = "d1" }
        s.create(p)
        assertEquals("d1", p.data)
        assertNull(p.other)
    }

    /**
     * The classic shadow-reference case: a child loaded from the DB carries a FK stub for its
     * parent. Reading any field on the stub auto-hydrates it from the database.
     */
    @Test
    fun referenceStubLazyLoads() = withDb("AUTO-LAZY-STUB") { s ->
        s.createAutoSchema()
        s.create(AutoParentEntity().apply { id = 5; data = "parent-data"; other = "parent-other" })
        s.create(AutoChildEntity().apply { id = 10; data = "child-data"; parent = AutoParentEntity().apply { id = 5 } })

        val child = s.findById<AutoChildEntity>(10)!!
        val parent = child.parent!!
        assertEquals("parent-data", parent.data)
        assertEquals("parent-other", parent.other)
    }

    /**
     * Writing a field on a shadow reference must auto-hydrate the row first so that the other
     * fields keep their DB values; the just-written field is then overwritten with the user's value.
     */
    @Test
    fun referenceStubWriteAutoPopulatesThenOverrides() = withDb("AUTO-LAZY-STUB-WRITE") { s ->
        s.createAutoSchema()
        s.create(AutoParentEntity().apply { id = 7; data = "p-data"; other = "p-other" })
        s.create(AutoChildEntity().apply { id = 20; data = "c-data"; parent = AutoParentEntity().apply { id = 7 } })

        val child = s.findById<AutoChildEntity>(20)!!
        val parent = child.parent!!
        parent.data = "overridden"
        assertEquals("overridden", parent.data)
        // 'other' was filled by the auto-hydrate that ran before the write
        assertEquals("p-other", parent.other)
    }

    /**
     * Explicit [Stormify.refresh] on a user-constructed entity reloads from the database:
     * the database row overwrites any in-memory values that were set before the call.
     */
    @Test
    fun explicitRefreshOverwritesUserValues() = withDb("AUTO-LAZY-EXPLICIT-REFRESH") { s ->
        s.createAutoSchema()
        s.create(AutoParentEntity().apply { id = 30; data = "db-data"; other = "db-other" })

        val p = AutoParentEntity().apply { id = 30; data = "user-data" }
        s.refresh(p)
        assertEquals("db-data", p.data)
        assertEquals("db-other", p.other)
    }
}
