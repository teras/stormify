package test

import onl.ycode.stormify.pagedQuery
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.PageSpec
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.SortState
import onl.ycode.stormify.biglist.execute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

open class PagedQueryTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(); test(it)
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
        val query = pagedQuery<TestC>().apply {
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
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val page = query.execute(PageSpec(filters = mapOf("name" to "Item3")))
        assertEquals(1L, page.total)
        assertEquals("Item3", page.rows.single().name)
    }

    @Test
    fun orConstraintStaysGroupedWithFilters() = withDb("PQ-OR-CONSTRAINT") {
        setupTable(it, 0)
        it.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Alicia"), TestC(4, "Bobby")))
        val query = pagedQuery<TestC>().apply {
            setConstraints("test.id = ? OR test.id = ?", 1, 2)
            addFacet("name", "name")
            addFacet("id", "id")
        }
        val spec = PageSpec(filters = mapOf("name" to "Bob"))
        val page = query.execute(spec)
        assertEquals(1L, page.total)
        assertEquals(listOf(2), page.rows.map { r -> r.id })
        val streamed = mutableListOf<Int>()
        query.forEachStreaming(spec) { r -> streamed += r.id }
        assertEquals(listOf(2), streamed)
        assertEquals(1L, query.getAggregator(spec).count("*").execute<Long>())
        assertEquals(listOf("2"), query.filterValues("id", spec).rows)
        assertEquals(1L, query.filterValuesWithCounts("id", spec).total)
    }

    @Test
    fun sortTieBrokenByPrimaryKeyAcrossPages() = withDb("PQ-SORT-TIE") {
        setupTable(it, 0)
        it.create((30 downTo 1).map { i -> TestC(i, "Same") })
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val ids = (0 until 5).flatMap { p ->
            query.execute(PageSpec(p, 7, sorts = mapOf("name" to SortState.DESCENDING))).rows.map { r -> r.id }
        }
        assertEquals((1..30).toList(), ids)
    }

    @Test
    fun keySortDescending() = withDb("PQ-KEY-SORT") {
        setupTable(it, 0)
        it.create((1..10).map { i -> TestC(i, if (i <= 5) "A" else "B") })
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
            keySort = SortState.DESCENDING
        }
        assertEquals((10 downTo 1).toList(), query.execute(PageSpec(0, 10)).rows.map { r -> r.id })
        assertEquals(listOf(5, 4, 3, 2, 1, 10, 9, 8, 7, 6),
            query.execute(PageSpec(0, 10, sorts = mapOf("name" to SortState.ASCENDING))).rows.map { r -> r.id })
        // Configuration is sealed after the first execution.
        assertFailsWith<IllegalArgumentException> { query.keySort = SortState.ASCENDING }
    }

    @Test
    fun filterValuesAndAggregatesOnUnfilteredForeignKeyPath() = withDb("PQ-FK-PATH") {
        TestDDL.dropTable("person")
        TestDDL.dropTable("city")
        TestDDL.dropTable("country")
        it.executeUpdate(TestDDL.createTable("country", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        it.executeUpdate(TestDDL.createTable("city", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                "${TestDDL.intColumn("country")}, ${TestDDL.foreignKey("country", "country", "id")}"))
        it.executeUpdate(TestDDL.createTable("person", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                "${TestDDL.intColumn("city")}, ${TestDDL.foreignKey("city", "city", "id")}"))
        val greece = Country(1, "Greece")
        val italy = Country(2, "Italy")
        it.create(listOf(greece, italy))
        val athens = City(1, "Athens", greece)
        val rome = City(2, "Rome", italy)
        it.create(listOf(athens, rome))
        it.create(listOf(Person(1, "Alice", athens), Person(2, "Bob", rome), Person(3, "Carlo", rome)))

        val query = pagedQuery<Person>().apply {
            addFacet("name", "name")
            addFacet("country", "city.country.name")
        }
        val all = PageSpec(0, 10)
        assertEquals(listOf("Greece", "Italy"), query.filterValues("country", all).rows)
        assertEquals(listOf("Greece" to 1L, "Italy" to 2L),
            query.filterValuesWithCounts("country", all).rows.map { r -> r.value to r.count })
        assertEquals(2L, query.getAggregator(all).countDistinct("city.country.name", "n").execute<Long>())

        val bob = PageSpec(0, 10, filters = mapOf("name" to "Bob"))
        assertEquals(listOf("Italy"), query.filterValues("country", bob).rows)
        assertEquals(1L, query.getAggregator(bob).countDistinct("city.country.name", "n").execute<Long>())
    }

    @Test
    fun filterValuesWithNull() = withDb("PQ-FV-NULL") {
        setupTable(it, 0)
        it.create(listOf(TestC(1, "Bob"), TestC(2, null), TestC(3, "Alice"), TestC(4, null)))
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val first = query.filterValues("name", PageSpec(0, 2))
        assertEquals(3L, first.total)
        assertEquals(listOf("Alice", "Bob"), first.rows)
        assertEquals(listOf("NULL"), query.filterValues("name", PageSpec(1, 2)).rows)
        val counted = query.filterValuesWithCounts("name", PageSpec(0, 10))
        assertEquals(3L, counted.total)
        assertEquals(listOf("Alice" to 1L, "Bob" to 1L, "NULL" to 2L), counted.rows.map { r -> r.value to r.count })
        assertEquals(2L, query.execute(PageSpec(filters = mapOf("name" to "NULL"))).total)
    }

    @Test
    fun enumFilterValuesShowDisplayNames() = withDb("PQ-ENUM-FV-NAMES") {
        TestDDL.dropTable("enum_test")
        it.executeUpdate(TestDDL.createTable("enum_test",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"))
        it.create(listOf(
            EnumEntity(1, PlainStatus.ACTIVE, CustomStatus.BANNED),
            EnumEntity(2, PlainStatus.BANNED, CustomStatus.ACTIVE),
        ))
        val query = pagedQuery<EnumEntity>().apply {
            addFacet("custom", "customStatus")
        }
        assertEquals(listOf("ACTIVE", "BANNED"), query.filterValues("custom", PageSpec(0, 10)).rows)
        assertEquals(listOf("ACTIVE" to 1L, "BANNED" to 1L),
            query.filterValuesWithCounts("custom", PageSpec(0, 10)).rows.map { r -> r.value to r.count })
    }

    @Test
    fun blankSpecFilterIsNoFilter() = withDb("PQ-FILTER-BLANK") {
        setupTable(it, 4)
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
            addFacet("id", "id")
            addFacet("sortOnly", "name").isFilterable = false
        }
        assertEquals(4L, query.execute(PageSpec(filters = mapOf("name" to "", "id" to "  "))).total)
        // A blank filter on a facet that is not filterable is no filter, not an error.
        assertEquals(4L, query.execute(PageSpec(filters = mapOf("sortOnly" to ""))).total)
        assertEquals(4L, query.filterValues("name", PageSpec(0, 10, filters = mapOf("id" to ""))).total)
        assertEquals(4L, query.getAggregator(PageSpec(filters = mapOf("id" to " ")))
            .count("*").execute<Long>())
    }

    @Test
    fun specSortChangesOrder() = withDb("PQ-SORT") {
        setupTable(it, 3)
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val asc = query.execute(PageSpec(sorts = mapOf("name" to SortState.ASCENDING)))
        val desc = query.execute(PageSpec(sorts = mapOf("name" to SortState.DESCENDING)))
        assertEquals("Item1", asc.rows.first().name)
        assertEquals("Item3", desc.rows.first().name)
    }

    @Test
    fun unknownAliasIsSilentlyIgnored() = withDb("PQ-UNKNOWN") {
        setupTable(it, 3)
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        // "bogus" alias is not configured — must be dropped, not throw
        val page = query.execute(PageSpec(filters = mapOf("bogus" to "whatever")))
        assertEquals(3L, page.total)
    }

    @Test
    fun facetStateMutationIsForbidden() = withDb("PQ-IMMUTABLE") {
        setupTable(it, 1)
        val query = pagedQuery<TestC>()
        val col = query.addFacet("name", "name")
        assertFailsWith<IllegalArgumentException> { col.filter = "Item1" }
        assertFailsWith<IllegalArgumentException> { col.sort = SortState.ASCENDING }
        assertFailsWith<IllegalArgumentException> { col.isCaseSensitive = true }
    }

    @Test
    fun filterOnNonFilterableFacetThrows() = withDb("PQ-NONFILTERABLE") {
        setupTable(it, 1)
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name").also { c -> c.isFilterable = false }
        }
        assertFailsWith<IllegalArgumentException> {
            query.execute(PageSpec(filters = mapOf("name" to "Item1")))
        }
    }

    @Test
    fun sortOnNonSortableFacetThrows() = withDb("PQ-NONSORTABLE") {
        setupTable(it, 1)
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name").also { c -> c.isSortable = false }
        }
        assertFailsWith<IllegalArgumentException> {
            query.execute(PageSpec(sorts = mapOf("name" to SortState.ASCENDING)))
        }
    }

    @Test
    fun duplicateExplicitAliasThrows() = withDb("PQ-DUPE") {
        setupTable(it, 1)
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        assertFailsWith<IllegalArgumentException> {
            query.addFacet("name", "id")
        }
    }

    @Test
    fun sharedInstanceServesConcurrentSpecs() = withDb("PQ-SHARED") {
        setupTable(it, 20)
        val query = pagedQuery<TestC>().apply {
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        val collected = mutableListOf<String>()
        query.forEachStreaming(PageSpec()) { row -> collected += row.name!! }
        assertEquals(30, collected.size)
    }

    @Test
    fun forEachStreamingRespectsFilter() = withDb("PQ-STREAM-FILTER") {
        setupTable(it, 30)
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        val collected = mutableListOf<TestC>()
        query.forEachStreaming(PageSpec(5, 3)) { row -> collected += row }
        assertEquals(20, collected.size)
    }

    @Test
    fun aggregatorRespectsSpecFilter() = withDb("PQ-AGG-FILTER") {
        setupTable(it, 20)
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        val total = query.getAggregator(PageSpec(filters = mapOf("name" to "Item1")))
            .count("*")
            .execute<Int>()
        // Item1, Item10..Item19 → 11 matches via LIKE substring
        assertEquals(11, total)
    }

    @Test
    fun aggregatorSingleValueWithSpec() = withDb("PQ-AGG-SINGLE") {
        setupTable(it, 5)
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        val sum = query.getAggregator(PageSpec())
            .sum("id")
            .execute<Long>()
        assertEquals(15L, sum)   // 1+2+3+4+5
    }

    @Test
    fun sqlFacetNumericExpressionWithOperators() = withDb("PQ-SQL-NUM-EXPR") { s ->
        // Use TestC.id as base; computed expression "id * 2" simulates a generated field.
        // Without parsing of operators against the SQL expression, none of these would work.
        setupTable(s, 10)  // creates rows with id 1..10
        val query = pagedQuery<TestC>().apply {
            addSqlFacet("dbl", "(test.id * 2)", Facet.Type.NUMERIC)
        }

        // dbl < 10  →  id*2 < 10  →  id ∈ {1,2,3,4}  →  4 rows
        val lt = query.execute(PageSpec(filters = mapOf("dbl" to "< 10")))
        assertEquals(4L, lt.total)

        // dbl >= 16  →  id*2 >= 16  →  id ∈ {8,9,10}  →  3 rows
        val gte = query.execute(PageSpec(filters = mapOf("dbl" to ">= 16")))
        assertEquals(3L, gte.total)

        // dbl between 6 and 12  →  id*2 in [6,12]  →  id ∈ {3,4,5,6}  →  4 rows
        val range = query.execute(PageSpec(filters = mapOf("dbl" to "6...12")))
        assertEquals(4L, range.total)

        // dbl = 4  →  id*2 = 4  →  id=2  →  1 row
        val exact = query.execute(PageSpec(filters = mapOf("dbl" to "4")))
        assertEquals(1L, exact.total)
    }

    @Test
    fun sqlFacetTextExpressionWithBooleanOps() = withDb("PQ-SQL-TXT-EXPR") { s ->
        // Computed text expression — verify Google AST works on SQL facets too
        setupTable(s, 5)  // Item1..Item5
        val query = pagedQuery<TestC>().apply {
            // UPPER() simulates a generated text field
            addSqlFacet("upper", "UPPER(test.name)", Facet.Type.TEXT)
        }

        // Boolean OR on computed expression
        val orQ = query.execute(PageSpec(filters = mapOf("upper" to "ITEM1 OR ITEM3")))
        assertEquals(2L, orQ.total)

        // Boolean NOT
        val notQ = query.execute(PageSpec(filters = mapOf("upper" to "-ITEM1 -ITEM2")))
        assertEquals(3L, notQ.total)
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
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

        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }

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
        val query = pagedQuery<TestC>().apply {
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
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
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        val a = query.execute(PageSpec())
        val b = query.execute(PageSpec(filters = mapOf("name" to "Item1")))
        val c = query.forEachStreaming(PageSpec()) { }
        assertEquals(5L, a.total)
        assertEquals(1L, b.total)
    }

    @Test
    fun sealIsTriggeredByForEachStreaming() = withDb("PQ-SEAL-STREAM") {
        setupTable(it, 3)
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        query.forEachStreaming(PageSpec()) { }
        assertFailsWith<IllegalArgumentException> { query.addFacet("id", "id") }
    }

    @Test
    fun sealIsTriggeredByAggregator() = withDb("PQ-SEAL-AGG") {
        setupTable(it, 3)
        val query = pagedQuery<TestC>().apply { addFacet("name", "name") }
        query.getAggregator(PageSpec()).count("*").execute<Int>()
        assertFailsWith<IllegalArgumentException> { query.addFacet("id", "id") }
    }

    @Test
    fun emptyResultSet() = withDb("PQ-EMPTY") {
        setupTable(it, 3)
        val query = pagedQuery<TestC>().apply {
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
        val query = pagedQuery<TestC>().apply {
            addFacet("name", "name")
        }
        val page = query.execute(PageSpec(10, 5))
        assertEquals(3L, page.total)
        assertTrue(page.rows.isEmpty())
    }

    @Test
    fun setConstraintsApplyAlongsideSpec() = withDb("PQ-CONSTRAINTS") {
        setupTable(it, 10)
        val query = pagedQuery<TestC>().apply {
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
