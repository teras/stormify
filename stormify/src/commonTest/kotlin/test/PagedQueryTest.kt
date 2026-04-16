package test

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.SortDir
import onl.ycode.stormify.biglist.execute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PagedQueryTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(test)
    }

    private fun setupTable(s: Stormify, count: Int) {
        TestDDL.dropTable("test")
        s.executeUpdate(
            TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}")
        )
        if (count > 0) s.create((1..count).map { TestC(it, "Item$it") })
    }

    @Test
    fun basicExecuteReturnsFirstPage() = withDb("PQ-BASIC") {
        setupTable(it, 5)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val page = query.execute(PageSpec(0, 3))
        assertEquals(5L, page.total)
        assertEquals(3, page.rows.size)
        assertEquals(2, page.totalPages)
        assertEquals("Item1", page.rows.first().name)
    }

    @Test
    fun specFilterNarrowsResults() = withDb("PQ-FILTER") {
        setupTable(it, 10)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val page = query.execute(PageSpec(filters = mapOf("name" to "Item3")))
        assertEquals(1L, page.total)
        assertEquals("Item3", page.rows.single().name)
    }

    @Test
    fun specSortChangesOrder() = withDb("PQ-SORT") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val asc = query.execute(PageSpec(sorts = mapOf("name" to SortDir.ASC)))
        val desc = query.execute(PageSpec(sorts = mapOf("name" to SortDir.DESC)))
        assertEquals("Item1", asc.rows.first().name)
        assertEquals("Item3", desc.rows.first().name)
    }

    @Test
    fun unknownAliasIsSilentlyIgnored() = withDb("PQ-UNKNOWN") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        // "bogus" alias is not configured — must be dropped, not throw
        val page = query.execute(PageSpec(filters = mapOf("bogus" to "whatever")))
        assertEquals(3L, page.total)
    }

    @Test
    fun facetStateMutationIsForbidden() = withDb("PQ-IMMUTABLE") {
        setupTable(it, 1)
        val query = PagedQuery<TestC>()
        val col = query.addFacet("name", "name")
        assertFailsWith<IllegalArgumentException> { col.filter = "Item1" }
        assertFailsWith<IllegalArgumentException> { col.sort = Facet.ASCENDING }
        assertFailsWith<IllegalArgumentException> { col.isCaseSensitive = true }
    }

    @Test
    fun filterOnNonFilterableFacetThrows() = withDb("PQ-NONFILTERABLE") {
        setupTable(it, 1)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name").also { c -> c.isFilterable = false }
        }
        assertFailsWith<IllegalArgumentException> {
            query.execute(PageSpec(filters = mapOf("name" to "Item1")))
        }
    }

    @Test
    fun sortOnNonSortableFacetThrows() = withDb("PQ-NONSORTABLE") {
        setupTable(it, 1)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name").also { c -> c.isSortable = false }
        }
        assertFailsWith<IllegalArgumentException> {
            query.execute(PageSpec(sorts = mapOf("name" to SortDir.ASC)))
        }
    }

    @Test
    fun duplicateExplicitAliasThrows() = withDb("PQ-DUPE") {
        setupTable(it, 1)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        assertFailsWith<IllegalArgumentException> {
            query.addFacet("name", "id")
        }
    }

    @Test
    fun sharedInstanceServesConcurrentSpecs() = withDb("PQ-SHARED") {
        setupTable(it, 20)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        // Two requests with disjoint specs, serialized but using the same instance,
        // must not corrupt each other's state.
        val a = query.execute(PageSpec(filters = mapOf("name" to "Item5")))
        val b = query.execute(PageSpec(filters = mapOf("name" to "Item15")))
        assertEquals("Item5", a.rows.single().name)
        assertEquals("Item15", b.rows.single().name)
        // After B, repeating A must still yield the same result — no leaked state.
        val a2 = query.execute(PageSpec(filters = mapOf("name" to "Item5")))
        assertEquals("Item5", a2.rows.single().name)
    }

    @Test
    fun forEachStreamingIteratesAllMatching() = withDb("PQ-STREAM") {
        setupTable(it, 30)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val collected = mutableListOf<String>()
        query.forEachStreaming(PageSpec()) { row -> collected += row.name!! }
        assertEquals(30, collected.size)
    }

    @Test
    fun forEachStreamingRespectsFilter() = withDb("PQ-STREAM-FILTER") {
        setupTable(it, 30)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val collected = mutableListOf<String>()
        query.forEachStreaming(PageSpec(filters = mapOf("name" to "Item1"))) { row ->
            collected += row.name!!
        }
        assertTrue(collected.size >= 1)
        assertTrue(collected.all { it.contains("Item1") })
    }

    @Test
    fun forEachStreamingIgnoresPagination() = withDb("PQ-STREAM-IGN") {
        setupTable(it, 20)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val collected = mutableListOf<TestC>()
        query.forEachStreaming(PageSpec(5, 3)) { row -> collected += row }
        assertEquals(20, collected.size)
    }

    @Test
    fun aggregatorRespectsSpecFilter() = withDb("PQ-AGG-FILTER") {
        setupTable(it, 20)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val total = query.getAggregator(PageSpec(filters = mapOf("name" to "Item1")))
            .count("*")
            .execute<Int>()
        // Item1, Item10..Item19 → 11 matches via LIKE substring
        assertEquals(11, total)
    }

    @Test
    fun aggregatorSingleValueWithSpec() = withDb("PQ-AGG-SINGLE") {
        setupTable(it, 5)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val sum = query.getAggregator(PageSpec())
            .sum("id")
            .execute<Long>()
        assertEquals(15L, sum)   // 1+2+3+4+5
    }

    @Test
    fun filterValuesDistinct() = withDb("PQ-FV") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        // Three distinct values: A, A, B, C
        s.create(listOf(
            TestC(1, "A"), TestC(2, "A"), TestC(3, "B"), TestC(4, "C")
        ))
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val page = query.filterValues("name", PageSpec(0, 10))
        assertEquals(3L, page.total)
        assertEquals(listOf("A", "B", "C"), page.rows.sorted())
    }

    @Test
    fun filterValuesDistinctPagination() = withDb("PQ-FV-PAGE") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        // 4 distinct values with 5 duplicates each (20 rows total).
        // With ROW_NUMBER, DISTINCT would fail (each row gets unique rn).
        // With DENSE_RANK, duplicates share rn → DISTINCT collapses them → pagination works.
        val rows = mutableListOf<TestC>()
        var id = 1
        for (name in listOf("A", "B", "C", "D")) {
            repeat(5) { rows.add(TestC(id++, name)) }
        }
        s.create(rows)

        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }

        val page0 = query.filterValues("name", PageSpec(0, 2))
        val page1 = query.filterValues("name", PageSpec(1, 2))

        assertEquals(4L, page0.total)
        assertEquals(4L, page1.total)
        assertEquals(listOf("A", "B"), page0.rows.sorted())
        assertEquals(listOf("C", "D"), page1.rows.sorted())
        // No overlap between pages
        assertEquals(emptySet<String>(), page0.rows.toSet().intersect(page1.rows.toSet()))
    }

    @Test
    fun filterValuesRespectsOtherFacetFilters() = withDb("PQ-FV-OTHER") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(
            TestC(1, "Apple"), TestC(2, "Apricot"),
            TestC(3, "Banana"), TestC(4, "Cherry")
        ))
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
            addFacet("id", "id")
        }
        // Filter by id > 2 (narrows to rows 3, 4)
        val page = query.filterValues("name", PageSpec(filters = mapOf("id" to "> 2")))
        assertEquals(2L, page.total)
        assertEquals(listOf("Banana", "Cherry"), page.rows.sorted())
    }

    @Test
    fun filterValuesIgnoresOwnFilter() = withDb("PQ-FV-OWN") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "A"), TestC(2, "B"), TestC(3, "C")))
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        // User is "looking at A"; distinct values should still show all three
        val page = query.filterValues("name", PageSpec(filters = mapOf("name" to "A")))
        assertEquals(3L, page.total)
    }

    @Test
    fun filterValuesWithCounts() = withDb("PQ-FV-CNT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(
            TestC(1, "A"), TestC(2, "A"), TestC(3, "A"),
            TestC(4, "B"), TestC(5, "C")
        ))
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val page = query.filterValuesWithCounts("name", PageSpec(0, 10))
        assertEquals(3L, page.total)
        val byValue = page.rows.associateBy { it.value }
        assertEquals(3L, byValue["A"]?.count)
        assertEquals(1L, byValue["B"]?.count)
        assertEquals(1L, byValue["C"]?.count)
    }

    @Test
    fun aggregatorMultiWithSpec() = withDb("PQ-AGG-MULTI") {
        setupTable(it, 5)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val row = query.getAggregator(PageSpec())
            .sum("id", "total")
            .count("*", "cnt")
            .execute()
        assertEquals(5L, (row["cnt"] as Number).toLong())
        assertEquals(15L, (row["total"] as Number).toLong())
    }

    @Test
    fun autoSealAfterFirstExecute() = withDb("PQ-SEAL") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        query.execute(PageSpec())
        // Any subsequent setup-time mutation must throw.
        assertFailsWith<IllegalArgumentException> { query.addFacet("id", "id") }
        assertFailsWith<IllegalArgumentException> { query.setConstraints("1=1") }
        assertFailsWith<IllegalArgumentException> { query.isDistinct = true }
        assertFailsWith<IllegalArgumentException> { query.addTableRef() }
        val facet = query.getFacet("name")
        assertFailsWith<IllegalArgumentException> { facet.alias = "renamed" }
        assertFailsWith<IllegalArgumentException> { facet.isFilterable = false }
        assertFailsWith<IllegalArgumentException> { facet.isSortable = false }
    }

    @Test
    fun autoSealAllowsRepeatedExecutes() = withDb("PQ-SEAL-REPEAT") {
        setupTable(it, 5)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        val a = query.execute(PageSpec())
        val b = query.execute(PageSpec(filters = mapOf("name" to "Item1")))
        val c = query.forEachStreaming(PageSpec()) { }
        assertEquals(5L, a.total)
        assertEquals(1L, b.total)
    }

    @Test
    fun sealIsTriggeredByForEachStreaming() = withDb("PQ-SEAL-STREAM") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        query.forEachStreaming(PageSpec()) { }
        assertFailsWith<IllegalArgumentException> { query.addFacet("id", "id") }
    }

    @Test
    fun sealIsTriggeredByAggregator() = withDb("PQ-SEAL-AGG") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply { addFacet("name", "name") }
        query.getAggregator(PageSpec()).count("*").execute<Int>()
        assertFailsWith<IllegalArgumentException> { query.addFacet("id", "id") }
    }

    @Test
    fun emptyResultSet() = withDb("PQ-EMPTY") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val page = query.execute(PageSpec(filters = mapOf("name" to "DoesNotExist")))
        assertEquals(0L, page.total)
        assertTrue(page.rows.isEmpty())
        assertEquals(0, page.totalPages)
    }

    @Test
    fun pageBeyondTotalReturnsEmpty() = withDb("PQ-OVERPAGE") {
        setupTable(it, 3)
        val query = PagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val page = query.execute(PageSpec(10, 5))
        assertEquals(3L, page.total)
        assertTrue(page.rows.isEmpty())
    }

    @Test
    fun setConstraintsApplyAlongsideSpec() = withDb("PQ-CONSTRAINTS") {
        setupTable(it, 10)
        val query = PagedQuery<TestC>().apply {
            setConstraints("test.id <= ?", 5)
            addFacet("name", "name")
        }
        // Total must reflect the fixed constraint regardless of spec.
        val none = query.execute(PageSpec())
        assertEquals(5L, none.total)
        // Spec filter AND constraint compose.
        val narrow = query.execute(PageSpec(filters = mapOf("name" to "Item3")))
        assertEquals(1L, narrow.total)
        val outside = query.execute(PageSpec(filters = mapOf("name" to "Item9")))
        assertEquals(0L, outside.total)  // blocked by constraint
    }
}
