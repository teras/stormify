package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.SqlGenerator
import onl.ycode.stormify.biglist.execute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FacetSqlGeneratorTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(test)
    }

    private fun setupTable(s: Stormify) {
        TestDDL.dropTable("test")
        s.executeUpdate(
            TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}")
        )
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Charlie"), TestC(4, "Alice2")))
    }

    @Test
    fun customGeneratorOnFieldFacet() = withDb("FG-FIELD") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.sqlGenerator = SqlGenerator { columnRef, filterValue, args ->
            args("%$filterValue")
            "$columnRef LIKE ?"
        }
        facet.filter = "lice"   // suffix match: "Alice" ends with "lice"
        assertEquals(1, list.size)
        assertEquals("Alice", list[0].name)
    }

    @Test
    fun customGeneratorPrefixMatch() = withDb("FG-PREFIX") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.sqlGenerator = SqlGenerator { columnRef, filterValue, args ->
            args("$filterValue%")
            "$columnRef LIKE ?"
        }
        facet.filter = "Alic"   // prefix match: both "Alice" and "Alice2" start with "Alic"
        assertEquals(2, list.size)
    }

    @Test
    fun placeholderMismatchThrows() = withDb("FG-MISMATCH") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.sqlGenerator = SqlGenerator { columnRef, _, args ->
            // Bug: two args but only one placeholder
            args("x")
            args("y")
            "$columnRef = ?"
        }
        facet.filter = "anything"
        val err = assertFailsWith<IllegalArgumentException> { list.size }
        // Message should mention the facet
        assertTrue(err.message!!.contains("emitted 1 placeholders but pushed 2"))
    }

    @Test
    fun invokeSyntaxForArgs() = withDb("FG-INVOKE") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        // Use invoke syntax: args(x) instead of args.accept(x)
        facet.sqlGenerator = SqlGenerator { columnRef, filterValue, args ->
            args(filterValue)  // Kotlin invoke sugar
            "$columnRef = ?"
        }
        facet.filter = "Bob"
        assertEquals(1, list.size)
        assertEquals("Bob", list[0].name)
    }

    @Test
    fun customGeneratorOnStatelessPagedQuery() = withDb("FG-QUERY") { s ->
        setupTable(s)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name").sqlGenerator = SqlGenerator { col, v, args ->
                args(v.uppercase())   // Normalize to uppercase before compare
                "UPPER($col) = ?"
            }
        }
        val page = query.execute(PageSpec(filters = mapOf("name" to "alice")))
        assertEquals(1L, page.total)
    }

}
