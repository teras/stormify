package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.execute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FacetConverterTest {
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
    fun customConverterOnFieldFacet() = withDb("FC-FIELD") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.converter = { columnRef, filterValue, _, args ->
            args.add("%$filterValue")
            "$columnRef LIKE ?"
        }
        facet.filter = "lice"
        assertEquals(1, list.size)
        assertEquals("Alice", list[0].name)
    }

    @Test
    fun customConverterPrefixMatch() = withDb("FC-PREFIX") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.converter = { columnRef, filterValue, _, args ->
            args.add("$filterValue%")
            "$columnRef LIKE ?"
        }
        facet.filter = "Alic"
        assertEquals(2, list.size)
    }

    @Test
    fun placeholderMismatchThrows() = withDb("FC-MISMATCH") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.converter = { columnRef, _, _, args ->
            args.add("x")
            args.add("y")
            "$columnRef = ?"
        }
        facet.filter = "anything"
        val err = assertFailsWith<IllegalArgumentException> { list.size }
        assertTrue(err.message!!.contains("emitted 1 placeholders but pushed 2"))
    }

    @Test
    fun customConverterAddForArgs() = withDb("FC-ADD") { s ->
        setupTable(s)
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        facet.converter = { columnRef, filterValue, _, args ->
            args.add(filterValue)
            "$columnRef = ?"
        }
        facet.filter = "Bob"
        assertEquals(1, list.size)
        assertEquals("Bob", list[0].name)
    }

    @Test
    fun customConverterOnStatelessPagedQuery() = withDb("FC-QUERY") { s ->
        setupTable(s)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name").converter = { col, v, _, args ->
                args.add(v.uppercase())
                "UPPER($col) = ?"
            }
        }
        val page = query.execute(PageSpec(filters = mapOf("name" to "alice")))
        assertEquals(1L, page.total)
    }
}
