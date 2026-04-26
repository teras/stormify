// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.CRUDTable
import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Covers [CRUDTable] — the Java-friendly mixin interface whose `create/update/delete`
 * methods delegate to the default Stormify instance. Ambient-transaction tracking
 * must make those methods transparently join an enclosing `transaction { }` block.
 */
open class CrudTableTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) { s ->
        s.asDefault { test(s) }
    }

    @Test
    fun crudTableDirectCallsAutoCommit() = withDb("CT-DIRECT") { s ->
        TestDDL.dropTable("ct_test")
        s.executeUpdate(TestDDL.createTable("ct_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        CrudTableRow(1, "Alice").create()
        assertEquals("Alice", s.readOne<String>("SELECT name FROM ct_test WHERE id = 1"))

        val row = s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 1")!!
        row.name = "Alice Updated"
        row.update()
        assertEquals("Alice Updated", s.readOne<String>("SELECT name FROM ct_test WHERE id = 1"))

        row.delete()
        assertNull(s.readOne<String>("SELECT name FROM ct_test WHERE id = 1"))
    }

    @Test
    fun crudTableJoinsActiveTransaction() = withDb("CT-TX") { s ->
        TestDDL.dropTable("ct_test")
        s.executeUpdate(TestDDL.createTable("ct_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        // Commit path — all three calls share the tx connection.
        s.transaction {
            CrudTableRow(10, "A").create()
            CrudTableRow(11, "B").create()
            val fetched = s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 10")!!
            assertEquals("A", fetched.name)
            fetched.name = "A2"
            fetched.update()
        }
        assertEquals("A2", s.readOne<String>("SELECT name FROM ct_test WHERE id = 10"))
        assertEquals("B", s.readOne<String>("SELECT name FROM ct_test WHERE id = 11"))

        // Rollback path — CRUDTable.create() must roll back with the tx.
        assertFailsWith<RuntimeException> {
            s.transaction {
                CrudTableRow(20, "ghost").create()
                // Ambient read must see the uncommitted row.
                assertNotNull(s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 20"))
                throw RuntimeException("abort")
            }
        }
        assertNull(s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 20"))
    }

    @Test
    fun crudTableJoinsNestedSavepoint() = withDb("CT-NESTED") { s ->
        TestDDL.dropTable("ct_test")
        s.executeUpdate(TestDDL.createTable("ct_test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))

        s.transaction {
            CrudTableRow(30, "outer").create()
            try {
                s.transaction {
                    CrudTableRow(31, "inner").create()
                    throw RuntimeException("inner-abort")
                }
            } catch (_: RuntimeException) {}
            // Savepoint rolled back → inner row gone, outer still in place.
            assertNull(s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 31"))
            assertNotNull(s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 30"))
        }
        assertNotNull(s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 30"))
        assertNull(s.readOne<CrudTableRow>("SELECT * FROM ct_test WHERE id = 31"))
    }
}
