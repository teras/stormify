package test

import onl.ycode.stormify.StormifyJ
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.SortState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The Java face of PagedList / PagedQuery: created by the StormifyJ instance, attached to it. */
class StormifyJPagedTest {
    private fun setup(j: StormifyJ) {
        j.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        TestDDL.dropTable("test")
        j.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        j.create(listOf(TestC(1, "Alpha"), TestC(2, "Beta"), TestC(3, "Gamma")))
    }

    @Test
    fun pagedListIsAttachedToTheInstance() = TestHelper.withDb("JAVA-PAGED-LIST") { s ->
        // No default instance: the list must get its Stormify from the StormifyJ that created it
        val j = StormifyJ(s.dataSource)
        setup(j)
        val list: PagedList<TestC> = j.pagedList(TestC::class.java)
            .facet("name")
            .sort("name", SortState.DESCENDING)
            .constraints("id > ?", 1)
        assertIs<PagedList<TestC>>(list)
        assertEquals(listOf("Gamma", "Beta"), list.map { it.name })
    }

    @Test
    fun pagedQueryIsAttachedToTheInstance() = TestHelper.withDb("JAVA-PAGED-QUERY") { s ->
        val j = StormifyJ(s.dataSource)
        setup(j)
        val query: PagedQuery<TestC> = j.pagedQuery(TestC::class.java)
        query.addFacet("name", "name")
        val page = query.execute(PageSpec(0, 2, sorts = mapOf("name" to SortState.DESCENDING)))
        assertEquals(3L, page.total)
        assertEquals(listOf("Gamma", "Beta"), page.rows.map { it.name })
    }
}
