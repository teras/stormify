// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.create
import onl.ycode.stormify.findById
import onl.ycode.stormify.generated.Tables.TestC_
import onl.ycode.stormify.pagedList
import onl.ycode.stormify.pagedQuery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A call that names no instance works on the instance of the enclosing transaction,
 * and on the default one outside of it.
 */
open class AmbientInstanceTest {
    private fun withTwo(name: String, test: (default: Stormify, other: Stormify) -> Unit) =
        TestHelper.withDb(name) { s ->
            TestDDL.dropTable("child")
            TestDDL.dropTable("test")
            s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
            val other = Stormify(s.dataSource)
            other.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
            s.asDefault()
            test(s, other)
        }

    @Test
    fun callsWithoutAnInstanceJoinTheEnclosingTransaction() = withTwo("AMBIENT-TX") { _, other ->
        assertFailsWith<IllegalStateException> {
            other.transaction {
                TestC(1, "inside").create()
                // Read through the same transaction, so the uncommitted row is visible
                assertEquals("inside", findById<TestC>(1)?.name)
                throw IllegalStateException("roll back")
            }
        }
        // The row was written on the transaction, not on a connection of the default
        assertNull(other.findById<TestC>(1))
    }

    @Test
    fun aListWithoutAnInstanceBindsToTheEnclosingTransaction() = withTwo("AMBIENT-PAGED") { s, other ->
        s.create(listOf(TestC(1, "Alpha"), TestC(2, "Beta")))
        other.transaction {
            TestC(3, "Gamma").create()
            val list = pagedList<TestC>().facet(TestC_.name)
            // Sees the uncommitted row of the transaction
            assertEquals(listOf(1, 2, 3), list.map { it.id })
        }
        assertEquals(3, pagedList<TestC>().size)
    }

    @Test
    fun aPagedQueryIsDeclaredFluently() = withTwo("PAGED-QUERY-FLUENT") { s, _ ->
        s.create(listOf(TestC(1, "Alpha"), TestC(2, "Beta"), TestC(3, "Beta")))
        val query = s.pagedQuery<TestC>()
            .facet("name", TestC_.name)
            .constraints("id > ?", 1)
            .distinct()
        assertEquals(listOf(2, 3), query.execute(PageSpec(filters = mapOf("name" to "Beta"))).rows.map { it.id })
    }

    @Test
    fun aConstraintBindsANull() = withTwo("CONSTRAINT-NULL") { s, _ ->
        s.create(listOf(TestC(1, null), TestC(2, "Beta")))
        // A null bind compares as unknown: only the IS NULL branch matches
        val query = pagedQuery<TestC>().constraints("(name = ? OR name IS NULL)", null)
        assertEquals(listOf(1), query.execute(PageSpec()).rows.map { it.id })
        val list = pagedList<TestC>().constraints("(name = ? OR name IS NULL)", null)
        assertEquals(listOf(1), list.map { it.id })
    }
}
