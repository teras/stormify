package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.PagedList
import kotlin.test.Test
import kotlin.test.assertEquals

class FilterValuesToStringTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(test)
    }

    private fun setup(s: Stormify, rows: List<TestC>) {
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(rows)
    }

    @Test fun filterValuesJsonArray() = withDb("FV-JSON") { s ->
        setup(s, listOf(TestC(1, "A"), TestC(2, "B"), TestC(2, "B"), TestC(3, "C")).distinctBy { it.id })
        val list = PagedList<TestC>()
        val facet = list.addFacet("name")
        val fv = facet.getFilterValues()
        val json = fv.toString()
        // Distinct values ordered alphabetically by the facet column.
        assertEquals("""["A","B","C"]""", json)
    }

    @Test fun filterValuesEscapesSpecialChars() = withDb("FV-ESC") { s ->
        setup(s, listOf(TestC(1, "a\"b"), TestC(2, "c\\d")))
        val list = PagedList<TestC>()
        val fv = list.addFacet("name").getFilterValues()
        val json = fv.toString()
        // JSON output must quote and escape both values.
        assertEquals("""["a\"b","c\\d"]""", json)
    }

    @Test fun filterCountedValuesJsonArray() = withDb("FCV-JSON") { s ->
        setup(s, listOf(
            TestC(1, "A"), TestC(2, "A"), TestC(3, "A"),
            TestC(4, "B"), TestC(5, "C"),
        ))
        val list = PagedList<TestC>()
        val counted = list.addFacet("name").getFilterValues().withCounts()
        val json = counted.toString()
        // Format: [{"value":"A","count":3},{"value":"B","count":1},{"value":"C","count":1}]
        assertEquals("""[{"value":"A","count":3},{"value":"B","count":1},{"value":"C","count":1}]""", json)
    }

    @Test fun emptyFilterValuesJsonArray() = withDb("FV-EMPTY") { s ->
        setup(s, emptyList())
        val list = PagedList<TestC>()
        val fv = list.addFacet("name").getFilterValues()
        assertEquals("[]", fv.toString())
    }
}
