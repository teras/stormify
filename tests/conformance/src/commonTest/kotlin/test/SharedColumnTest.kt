// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A column mapped twice, once as a plain value and once as a read-only reference
 * (`insertable = false, updatable = false`), is the usual way to expose both the raw
 * foreign key and the associated entity. The column must appear once in INSERT and
 * UPDATE, and on read each property must get its own form of the value: the raw key
 * for the scalar, the referenced entity for the reference.
 */
open class SharedColumnTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(); test(it)
    }

    private fun Stormify.setup() {
        TestDDL.dropTable("shared_col_child")
        TestDDL.dropTable("test")
        executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        executeUpdate(
            TestDDL.createTable(
                "shared_col_child",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("parent")}, " +
                        TestDDL.foreignKey("parent", "test", "id")
            )
        )
        create(listOf(TestC(1, "ParentA"), TestC(2, "ParentB")))
    }

    @Test
    fun scalarAndReadOnlyReferenceShareOneColumn() = withDb("SHARED-COLUMN") { s ->
        s.setup()

        // The reference is ignored on write: the column takes the scalar value.
        s.create(SharedColumnChild(10, 1, TestC(2)))
        assertEquals(1, s.readOne<Int>("SELECT parent FROM shared_col_child WHERE id = ?", 10))

        val loaded = s.findById<SharedColumnChild>(10)!!
        assertEquals(1, loaded.parentId)
        assertEquals(1, loaded.parentRef?.id)

        loaded.parentId = 2
        loaded.parentRef = null
        s.update(loaded)
        assertEquals(2, s.readOne<Int>("SELECT parent FROM shared_col_child WHERE id = ?", 10))

        val reloaded = s.findById<SharedColumnChild>(10)!!
        assertEquals(2, reloaded.parentId)
        assertEquals(2, reloaded.parentRef?.id)

        s.create(SharedColumnChild(11, null, null))
        val empty = s.findById<SharedColumnChild>(11)!!
        assertNull(empty.parentId)
        assertNull(empty.parentRef)
    }
}
