package test

import onl.ycode.stormify.pagedList
import onl.ycode.stormify.pagedQuery
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Converter
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.SortState
import onl.ycode.stormify.generated.Tables.Child_
import onl.ycode.stormify.generated.Tables.TestC_
import kotlin.test.*

open class FluentPagedListTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(); test(it)
    }

    private fun setupParentChild(s: Stormify) {
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.executeUpdate(
            TestDDL.createTable(
                "child",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                        "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "test", "id")}"
            )
        )
        val alpha = TestC(1, "Alpha")
        val beta = TestC(2, "Beta")
        s.create(listOf(alpha, beta))
        s.create(
            listOf(
                Child(1, "Apple", alpha),
                Child(2, "Banana", alpha),
                Child(3, "Cherry", beta),
                Child(4, "Date", beta),
            )
        )
    }

    @Test
    fun testFluentChainBuildsTheSameList() = withDb("FLUENT-CHAIN") { s ->
        setupParentChild(s)
        val list: PagedList<Child> = pagedList<Child>()
            .facet(Child_.name)
            .facet(Child_.parent.name, Child_.name)
            .sort(Child_.name, SortState.DESCENDING)
            .constraints("child.id > ?", 1)

        assertEquals(2, list.facets.size)
        assertEquals(listOf("Date", "Cherry", "Banana"), list.map { it.name })

        // The OR facet matches the parent name or the child name
        list.getFacet(1).filter = "Alpha"
        assertEquals(listOf("Banana"), list.map { it.name })
        list.getFacet(1).filter = "Cherry"
        assertEquals(listOf("Cherry"), list.map { it.name })
    }

    @Test
    fun testConstraintEndingInALineCommentKeepsTheFacetFilters() = withDb("FLUENT-COMMENT") { s ->
        setupParentChild(s)
        val list = pagedList<Child>()
            .facet(Child_.name)
            .constraints("child.id > ? -- all but the first", 1)
        assertEquals(listOf("Banana", "Cherry", "Date"), list.map { it.name!! }.sorted())
        list.getFacet(0).filter = "Cherry"
        assertEquals(listOf("Cherry"), list.map { it.name })
    }

    @Test
    fun testFacetByPathFindsEveryPathOfAFacet() = withDb("FLUENT-BYPATH") { s ->
        setupParentChild(s)
        val list = pagedList<Child>()
            .facet(Child_.parent.name, Child_.name)
            .facet("id")
        val orFacet = list.getFacet(0)
        assertSame(orFacet, list.facetByPath(Child_.parent.name))
        assertSame(orFacet, list.facetByPath("name"))
        assertSame(orFacet, list.facetByPath("parent.name"))
        assertSame(list.getFacet(1), list.facetByPath("id"))
        assertNull(list.facetByPath("parent.id"))
        assertNull(list.facetByPath("missing"))
    }

    @Test
    fun testSortOnAPathNoFacetCoversThrows() = withDb("FLUENT-SORT-UNDECLARED") { s ->
        setupParentChild(s)
        val list = pagedList<Child>().facet("name")
        assertFailsWith<IllegalArgumentException> { list.sort("parent.name", SortState.DESCENDING) }
        assertFailsWith<IllegalArgumentException> { list.sort(Child_.parent.name, SortState.DESCENDING) }
        assertEquals(1, list.facets.size)
        assertNull(list.facetByPath("parent.name"))

        // Sorting a covered path sets the sort on its facet
        list.sort("name", SortState.DESCENDING)
        assertEquals(1, list.facets.size)
        assertEquals(SortState.DESCENDING, list.getFacet(0).sort)
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), list.map { it.name })
    }

    @Test
    fun testOrderByFollowsTheOrderOfTheSortCalls() = withDb("FLUENT-SORT-ORDER") { s ->
        setupParentChild(s)
        // Facet order would give parent.name ASC, name DESC: Banana, Apple, Date, Cherry
        val list = pagedList<Child>()
            .facet(Child_.parent.name)
            .facet(Child_.name)
            .sort(Child_.name, SortState.DESCENDING)
            .sort(Child_.parent.name, SortState.ASCENDING)
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), list.map { it.name })

        // Changing the direction of a sorted facet keeps its position
        list.sort(Child_.name, SortState.ASCENDING)
        assertEquals(listOf("Apple", "Banana", "Cherry", "Date"), list.map { it.name })

        // Clearing and sorting again puts the facet last
        list.getFacet(1).sort = null
        list.getFacet(1).sort = SortState.DESCENDING
        assertEquals(listOf("Banana", "Apple", "Date", "Cherry"), list.map { it.name })

        // Facet.sort set directly follows the same rule
        list.reset()
        list.getFacet(1).sort = SortState.DESCENDING
        list.getFacet(0).sort = SortState.ASCENDING
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), list.map { it.name })

        // The saved state restores the same ORDER BY
        val restored = pagedList<Child>().facet(Child_.parent.name).facet(Child_.name)
        restored.restoreState(list.saveState())
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), restored.map { it.name })
    }

    @Test
    fun testPagedQueryOrderByFollowsTheOrderOfTheRequestedSorts() = withDb("FLUENT-SORT-ORDER-PQ") { s ->
        setupParentChild(s)
        val query = pagedQuery<Child>().apply {
            addFacet("owner", Child_.parent.name)
            addFacet("name", Child_.name)
        }
        val page = query.execute(PageSpec(sorts = mapOf("name" to SortState.DESCENDING, "owner" to SortState.ASCENDING)))
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), page.rows.map { it.name })
        val other = query.execute(PageSpec(sorts = mapOf("owner" to SortState.ASCENDING, "name" to SortState.DESCENDING)))
        assertEquals(listOf("Banana", "Apple", "Date", "Cherry"), other.rows.map { it.name })
    }

    @Test
    fun testEnumMapAndTypedFacets() = withDb("FLUENT-ENUM") { s ->
        setupParentChild(s)
        val list = pagedList<Child>()
            .facet(mapOf("first" to 1, "second" to 2), Child_.id)
            .facet(Facet.Type.TEXT, "parent.name")
            .facet(mapOf("low" to 1, "high" to 4), "id")
        list.getFacet(0).filter = "second"
        assertEquals(listOf("Banana"), list.map { it.name })
        list.getFacet(0).filter = null
        list.getFacet(1).filter = "bet"
        assertEquals(listOf("Cherry", "Date"), list.map { it.name })
        list.getFacet(2).filter = "high"
        assertEquals(listOf("Date"), list.map { it.name })
    }

    @Test
    fun testConverterFacet() = withDb("FLUENT-CONVERTER") { s ->
        setupParentChild(s)
        // Matches by the first letter of the name, whatever the default text filter would do
        val firstLetter: Converter = Converter { col, input, _, args ->
            args.add(input.take(1) + "%")
            "$col LIKE ?"
        }
        val list = pagedList<Child>().apply {
            addFacet(Child_.name).converter = firstLetter
            facet(Facet.Type.TEXT, "parent.name").facetByPath("parent.name")!!.converter = firstLetter
        }
        assertSame(firstLetter, list.getFacet(0).converter)
        assertSame(firstLetter, list.getFacet(1).converter)
        list.getFacet(0).filter = "Bxyz"
        assertEquals(listOf("Banana"), list.map { it.name })
        list.getFacet(0).filter = null
        list.getFacet(1).filter = "Bzz"
        assertEquals(listOf("Cherry", "Date"), list.map { it.name })
    }

    @Test
    fun testKeySort() = withDb("FLUENT-KEY-SORT") { s ->
        setupParentChild(s)
        val list = pagedList<TestC>().facet(TestC_.name).keySort(SortState.DESCENDING)
        assertEquals(SortState.DESCENDING, list.keySort)
        assertEquals(2, list[0].id)   // the larger id first
    }

    @Test
    fun testDistinct() = withDb("FLUENT-DISTINCT") { s ->
        setupParentChild(s)
        val list = pagedList<TestC>().facet(TestC_.name).distinct()
        assertTrue(list.isDistinct)
        assertEquals(2, list.size)
        list.isDistinct = false
        assertFalse(list.isDistinct)
    }

    @Test
    fun testSortByTheRequestedPathOfAMultiPathFacet() = withDb("FLUENT-SORT-PATH") { s ->
        setupParentChild(s)
        // parent.name DESC would give Beta's children first: Cherry, Date, ...
        val list = pagedList<Child>()
            .facet(Child_.parent.name, Child_.name)
            .sort(Child_.name, SortState.DESCENDING)
        assertEquals("name", list.getFacet(0).sortPath)
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), list.map { it.name })

        list.sort(Child_.parent.name, SortState.ASCENDING)
        assertEquals("parent.name", list.getFacet(0).sortPath)
        assertEquals(listOf("Alpha", "Alpha", "Beta", "Beta"), list.map { s.findById<TestC>(it.parent!!.id)!!.name })

        // Default: the first path; only the facet's own paths are accepted
        list.getFacet(0).sortPath = null
        assertNull(list.getFacet(0).sortPath)
        assertFailsWith<IllegalArgumentException> { list.getFacet(0).sortPath = "id" }
    }

    @Test
    fun testSortPathOnPagedQuery() = withDb("FLUENT-SORT-PATH-PQ") { s ->
        setupParentChild(s)
        val query = pagedQuery<Child>().apply {
            addFacet("owner", Child_.parent.name, Child_.name).sortPath = "name"
        }
        val page = query.execute(PageSpec(sorts = mapOf("owner" to SortState.DESCENDING)))
        assertEquals(listOf("Date", "Cherry", "Banana", "Apple"), page.rows.map { it.name })
    }

    /** The factories of an instance attach the list and the query to it, with no default instance. */
    @Test
    fun theInstanceFactoriesAttachToTheInstance() = TestHelper.withDb("PAGED-FACTORIES") { s ->
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alpha"), TestC(2, "Beta")))
        val list = s.pagedList<TestC>().facet(TestC_.name)
        list.getFacet(0).filter = "Beta"
        assertEquals(listOf(2), list.map { it.id })
        val query = s.pagedQuery<TestC>()
        query.addFacet("name", TestC_.name)
        assertEquals(listOf(1, 2), query.execute(PageSpec()).rows.map { it.id })
    }
}
