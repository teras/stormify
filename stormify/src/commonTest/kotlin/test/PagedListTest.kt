@file:OptIn(kotlin.time.ExperimentalTime::class)

package test

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Column
import onl.ycode.stormify.biglist.InputParser
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedListSort
import onl.ycode.stormify.biglist.PagedListState
import onl.ycode.stormify.biglist.SortState
import onl.ycode.stormify.biglist.execute
import kotlin.test.*

open class PagedListTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault()
        test(it)
    }

    private fun setupTable(s: Stormify, count: Int) {
        TestDDL.dropTable("test")
        s.executeUpdate(
            TestDDL.createTable(
                "test",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"
            )
        )
        if (count > 0)
            s.create((1..count).map { TestC(it, "Item$it") })
    }

    /**
     * Runs [block] with a [WatchLogger] that captures every SELECT statement
     * Stormify emits, and returns the captured list. The original logger is
     * restored on exit even if [block] throws.
     */
    private inline fun Stormify.captureSelects(block: () -> Unit): List<String> {
        val captured = mutableListOf<String>()
        val previousLogger = logger
        logger = WatchLogger(previousLogger) { _, message, _ ->
            if (message.trimStart().startsWith("SELECT", ignoreCase = true))
                captured += message
        }
        try {
            block()
        } finally {
            logger = previousLogger
        }
        return captured
    }

    private fun setupParentChild(s: Stormify) {
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(
            TestDDL.createTable(
                "test",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"
            )
        )
        s.executeUpdate(
            TestDDL.createTable(
                "child",
                "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}, " +
                        "${TestDDL.intColumn("parent")}, ${TestDDL.foreignKey("parent", "test", "id")}"
            )
        )
        val p1 = TestC(1, "Parent1")
        val p2 = TestC(2, "Parent2")
        s.create(listOf(p1, p2))
        s.create(listOf(
            Child(1, "Child1", p1),
            Child(2, "Child2", p1),
            Child(3, "Child3", p2)
        ))
    }

    // --- Basic ---

    @Test
    fun testBasicPagedList() = withDb("PAGED-BASIC") { s ->
        setupTable(s, 50)
        val list = PagedList<TestC>()
        assertEquals(50, list.size)
        assertEquals("Item1", list[0].name)
        assertEquals("Item50", list[49].name)
    }

    @Test
    fun testEmptyList() = withDb("PAGED-EMPTY") { s ->
        setupTable(s, 0)
        val list = PagedList<TestC>()
        assertEquals(0, list.size)
        assertTrue(list.isEmpty())
    }

    @Test
    fun testSingleElement() = withDb("PAGED-SINGLE") { s ->
        setupTable(s, 1)
        val list = PagedList<TestC>()
        assertEquals(1, list.size)
        assertEquals("Item1", list[0].name)
    }

    // --- Page size ---

    @Test
    fun testPageSize() = withDb("PAGED-PAGESIZE") { s ->
        setupTable(s, 30)
        val list = PagedList<TestC>()
        list.pageSize = 10
        assertEquals("Item1", list[0].name)
        assertEquals("Item15", list[14].name)
        assertEquals("Item30", list[29].name)
        assertEquals(30, list.size)
    }

    @Test
    fun testPageSizeValidation() = withDb("PAGED-PAGESIZE-VALIDATE") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        assertFailsWith<IllegalArgumentException> { list.pageSize = 0 }
        assertFailsWith<IllegalArgumentException> { list.pageSize = -1 }
    }

    @Test
    fun testPageBoundaries() = withDb("PAGED-BOUNDARIES") { s ->
        setupTable(s, 25)
        val list = PagedList<TestC>()
        list.pageSize = 10
        assertEquals("Item1", list[0].name)
        assertEquals("Item10", list[9].name)
        assertEquals("Item11", list[10].name)
        assertEquals("Item20", list[19].name)
        assertEquals("Item21", list[20].name)
        assertEquals("Item25", list[24].name)
    }

    @Test
    fun testIndexOutOfBounds() = withDb("PAGED-OOB") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        assertFailsWith<IndexOutOfBoundsException> { list[10] }
        assertFailsWith<IndexOutOfBoundsException> { list[-1] }
        assertFailsWith<IndexOutOfBoundsException> { list[100] }
    }

    // --- Iterator ---

    @Test
    fun testIterator() = withDb("PAGED-ITERATOR") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        list.pageSize = 2
        val names = list.map { it.name }
        assertEquals(listOf("Item1", "Item2", "Item3", "Item4", "Item5"), names)
    }

    // --- Constraints ---

    @Test
    fun testConstraints() = withDb("PAGED-CONSTRAINTS") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        list.setConstraints("test.id > ?", 10)
        assertEquals(10, list.size)
        for (i in 0 until list.size)
            assertTrue(list[i].id > 10)
    }

    // --- Column text filter ---

    @Test
    fun testTextFilter() = withDb("PAGED-TEXT-FILTER") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Alicia"), TestC(4, "Charlie")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "Al"

        // Default: case-insensitive LIKE %Al%
        assertEquals(2, list.size)
    }

    @Test
    fun testFilterClear() = withDb("PAGED-FILTER-CLEAR") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "Alice"
        assertEquals(1, list.size)

        col.clearFilter()
        assertEquals(2, list.size)
    }

    @Test
    fun testFilterNull() = withDb("PAGED-FILTER-NULL") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, null), TestC(3, null)))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = Column.NULL
        assertEquals(2, list.size)
    }

    // --- Column sorting ---

    @Test
    fun testSortingAscending() = withDb("PAGED-SORT-ASC") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Charlie"), TestC(2, "Alice"), TestC(3, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.sort = Column.ASCENDING
        assertEquals("Alice", list[0].name)
        assertEquals("Bob", list[1].name)
        assertEquals("Charlie", list[2].name)
    }

    @Test
    fun testSortingDescending() = withDb("PAGED-SORT-DESC") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Charlie"), TestC(2, "Alice"), TestC(3, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.sort = Column.DESCENDING
        assertEquals("Charlie", list[0].name)
        assertEquals("Bob", list[1].name)
        assertEquals("Alice", list[2].name)
    }

    @Test
    fun testClearSort() = withDb("PAGED-SORT-CLEAR") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.sort = Column.DESCENDING
        assertEquals("Item5", list[0].name)

        col.clearSort()
        // Default: order by PK
        assertEquals("Item1", list[0].name)
    }

    // --- OR filter (multi-field column) ---

    @Test
    fun testOrFilterMultiField() = withDb("PAGED-OR-MULTI") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        s.create(listOf(
            CamelEntity(1, "Alice", "Smith"),
            CamelEntity(2, "Bob", "Alison"),
            CamelEntity(3, "Charlie", "Brown")
        ))

        val list = PagedList<CamelEntity>()
        // Single column with two fields — OR between them
        val col = list.addColumn("firstName", "lastName")
        col.filter = "Ali"

        // "Alice" matches firstName, "Alison" matches lastName
        assertEquals(2, list.size)
    }

    // --- AND filter (multiple columns) ---

    @Test
    fun testAndFilterMultiColumn() = withDb("PAGED-AND-MULTI") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Alicia"), TestC(4, "Charlie")))

        val list = PagedList<TestC>()
        val nameCol = list.addColumn("name")
        val idCol = list.addColumn("id")

        nameCol.filter = "Al"
        idCol.filter = "<= 2"

        // name LIKE %al% AND id <= 2 → only Alice (id=1)
        assertEquals(1, list.size)
        assertEquals("Alice", list[0].name)
    }

    // --- FK path filter ---

    @Test
    fun testForeignKeyFilter() = withDb("PAGED-FK-FILTER") { s ->
        setupParentChild(s)

        val list = PagedList<Child>()
        val col = list.addColumn("parent.name")
        col.filter = "Parent1"

        assertEquals(2, list.size)
    }

    // --- Constraints + filter ---

    @Test
    fun testFilterWithConstraints() = withDb("PAGED-FILTER-CONSTRAINT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Alicia"), TestC(4, "Charlie")))

        val list = PagedList<TestC>()
        list.setConstraints("test.id <= ?", 3)
        val col = list.addColumn("name")
        col.filter = "Al"

        // id <= 3 AND name LIKE %al%  →  Alice (1), Alicia (3)
        assertEquals(2, list.size)
    }

    // --- CamelCase property → snake_case column ---

    @Test
    fun testCamelCaseProperty() = withDb("PAGED-CAMEL") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        s.create(listOf(
            CamelEntity(1, "Alice", "Smith"),
            CamelEntity(2, "Bob", "Jones"),
            CamelEntity(3, "Alicia", "Brown")
        ))

        val list = PagedList<CamelEntity>()
        val col = list.addColumn("firstName")
        col.filter = "Ali"

        assertEquals(2, list.size)
    }

    // --- Distinct ---

    @Test
    fun testDistinct() = withDb("PAGED-DISTINCT") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        list.isDistinct = true
        assertEquals(10, list.size)
    }

    // --- Reset ---

    @Test
    fun testReset() = withDb("PAGED-RESET") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "Alice"
        col.sort = Column.DESCENDING
        assertEquals(1, list.size)

        list.reset()
        assertEquals(2, list.size)
        assertNull(col.filter)
        assertNull(col.sort)
    }

    // --- Pagination with 100 entries ---

    @Test
    fun testPagination100() = withDb("PAGED-100") { s ->
        setupTable(s, 100)
        val list = PagedList<TestC>()
        list.pageSize = 7
        list.addColumn("name")

        assertEquals(100, list.size)

        for (i in 0 until 100)
            assertEquals("Item${i + 1}", list[i].name)

        // Non-sequential
        assertEquals("Item99", list[98].name)
        assertEquals("Item1", list[0].name)
        assertEquals("Item50", list[49].name)

        // Change page size
        list.pageSize = 20
        assertEquals(100, list.size)
        assertEquals("Item1", list[0].name)
        assertEquals("Item100", list[99].name)
    }

    // --- add / remove ---

    @Test
    fun testAddRemove() = withDb("PAGED-ADD-REMOVE") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        assertEquals(5, list.size)

        // Create and add
        val newItem = TestC(6, "Item6")
        s.create(newItem)
        list.add(newItem)
        assertEquals(6, list.size)
        assertEquals(newItem, list.selected)

        // Delete and remove
        s.delete(newItem)
        list.remove(newItem)
        assertEquals(5, list.size)
        assertNull(list.selected)
    }

    // --- set ---

    @Test
    fun testSetInCachedPage() = withDb("PAGED-SET") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        list.pageSize = 10

        // Access to load page
        assertEquals("Item1", list[0].name)

        // Set in cached page
        val updated = TestC(1, "Updated")
        val old = list.set(0, updated)
        assertEquals("Item1", old.name)
        assertEquals("Updated", list[0].name)
    }

    @Test
    fun testSetOutsideCachedPage() = withDb("PAGED-SET-OOB") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        list.pageSize = 5

        // Load first page
        list[0]

        // Try to set outside cached page
        assertFailsWith<IndexOutOfBoundsException> {
            list.set(10, TestC(11, "X"))
        }
    }

    // --- Enum filter ---

    @Test
    fun testEnumFilter() = withDb("PAGED-ENUM") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Charlie")))

        val list = PagedList<TestC>()
        // Enum mapping: display name → DB value
        val nameMap = mapOf("Alice" to "Alice", "Bob" to "Bob", "Charlie" to "Charlie")
        val col = list.addColumn(nameMap, "name")
        col.filter = "Ali"  // substring match → "Alice"

        assertEquals(1, list.size)
        assertEquals("Alice", list[0].name)
    }

    @Test
    fun testEnumFilterMultipleMatch() = withDb("PAGED-ENUM-MULTI") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Active"), TestC(2, "Inactive"), TestC(3, "Archived")))

        val list = PagedList<TestC>()
        val nameMap = mapOf("Active" to "Active", "Inactive" to "Inactive", "Archived" to "Archived")
        val col = list.addColumn(nameMap, "name")
        col.filter = "active"  // case-insensitive → "Active", "Inactive"

        assertEquals(2, list.size)
    }

    @Test
    fun testEnumFilterNoMatch() = withDb("PAGED-ENUM-NONE") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob")))

        val list = PagedList<TestC>()
        val nameMap = mapOf("Alice" to "Alice", "Bob" to "Bob")
        val col = list.addColumn(nameMap, "name")
        col.filter = "xyz"  // no match → 0 results

        assertEquals(0, list.size)
    }

    @Test
    fun testEnumAutoDetect() = withDb("PAGED-ENUM-AUTO") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(TestDDL.createTable("enum_test",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"))
        s.create(listOf(
            EnumEntity(1, PlainStatus.ACTIVE, CustomStatus.ACTIVE),
            EnumEntity(2, PlainStatus.INACTIVE, CustomStatus.INACTIVE),
            EnumEntity(3, PlainStatus.BANNED, CustomStatus.BANNED)
        ))

        val list = PagedList<EnumEntity>()
        // No explicit type — auto-detects enum, auto-builds enumValues
        val col = list.addColumn("plainStatus")
        col.filter = "BANNED"

        assertEquals(1, list.size)
    }

    // --- Numeric filter details ---

    @Test
    fun testNumericFilterRange() = withDb("PAGED-NUM-RANGE") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "5 ... 10"

        assertEquals(6, list.size)  // 5,6,7,8,9,10
    }

    @Test
    fun testNumericFilterGreaterThan() = withDb("PAGED-NUM-GT") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "> 8"
        assertEquals(2, list.size)  // 9, 10
    }

    @Test
    fun testNumericFilterGreaterOrEqual() = withDb("PAGED-NUM-GTE") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = ">= 8"
        assertEquals(3, list.size)  // 8, 9, 10
    }

    @Test
    fun testNumericFilterLessThan() = withDb("PAGED-NUM-LT") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "< 3"
        assertEquals(2, list.size)  // 1, 2
    }

    @Test
    fun testNumericFilterLessOrEqual() = withDb("PAGED-NUM-LTE") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "<= 3"
        assertEquals(3, list.size)  // 1, 2, 3
    }

    // --- Text filter details ---

    @Test
    fun testTextFilterWildcardPrefix() = withDb("PAGED-TEXT-PREFIX") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Malice")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "*lice"  // ends with "lice"

        assertEquals(2, list.size)  // Alice, Malice
    }

    @Test
    fun testTextFilterExactQuoted() = withDb("PAGED-TEXT-EXACT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Alicia")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "\"alice\""  // exact match, case-insensitive

        assertEquals(1, list.size)
        assertEquals("Alice", list[0].name)
    }

    // --- Case-insensitive (ASCII) ---

    @Test
    fun testCaseInsensitiveFilter() = withDb("PAGED-CI-ASCII") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "ALICE"), TestC(2, "alice"), TestC(3, "Alice"), TestC(4, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "aLiCe"

        assertEquals(3, list.size)
    }

    @Test
    fun testCaseSensitiveFilter() = withDb("PAGED-CS") { s ->
        if (s.sqlDialect in setOf(SqlDialect.SQLITE, SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW,
                SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW,
                SqlDialect.SQL_SERVER_OLD, SqlDialect.SQL_SERVER_NEW))
            skipTest(SkipReason.DIALECT_QUIRK,
                "LIKE is case-insensitive by default — working around it is out of scope")

        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "ALICE"), TestC(2, "alice"), TestC(3, "Alice"), TestC(4, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.isCaseSensitive = true
        col.filter = "Alice"

        assertEquals(1, list.size)
        assertEquals("Alice", list[0].name)
    }

    // --- Multi-column sort ---

    @Test
    fun testMultiColumnSort() = withDb("PAGED-MULTI-SORT") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        s.create(listOf(
            CamelEntity(1, "Alice", "Smith"),
            CamelEntity(2, "Bob", "Smith"),
            CamelEntity(3, "Alice", "Brown")
        ))

        val list = PagedList<CamelEntity>()
        val lastCol = list.addColumn("lastName")
        val firstCol = list.addColumn("firstName")
        lastCol.sort = Column.ASCENDING
        firstCol.sort = Column.ASCENDING

        // Sort by lastName ASC, then firstName ASC
        assertEquals("Brown", list[0].lastName)     // Alice Brown
        assertEquals("Smith", list[1].lastName)      // Alice Smith
        assertEquals("Smith", list[2].lastName)      // Bob Smith
        assertEquals("Alice", list[1].firstName)
        assertEquals("Bob", list[2].firstName)
    }

    // --- Selected entity ---

    @Test
    fun testSelectedEntityFirst() = withDb("PAGED-SELECTED") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val item3 = s.read<TestC>("SELECT * FROM test WHERE id = ?", 3).first()
        list.selected = item3

        // Selected entity should appear first
        assertEquals("Item3", list[0].name)
    }

    // --- Silent re-count ---

    @Test
    fun testSilentRecount() = withDb("PAGED-RECOUNT") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        list.pageSize = 5

        assertEquals(10, list.size)
        assertEquals("Item1", list[0].name)

        // Delete some rows behind the list's back
        s.executeUpdate("DELETE FROM test WHERE id > ?", 5)

        // Accessing a page beyond current data triggers silent re-count
        list.refresh()
        assertEquals(5, list.size)
    }

    // --- Selection values ---

    @Test
    fun testSelectionValues() = withDb("PAGED-SEL-VALUES") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Alice"), TestC(4, "Charlie")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        val values = col.getFilterValues()

        // Distinct names: Alice, Bob, Charlie
        assertEquals(3, values.size)
    }

    @Test
    fun testSelectionValuesFilteredByOtherColumn() = withDb("PAGED-SEL-FILTERED") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        s.create(listOf(
            CamelEntity(1, "Alice", "Smith"),
            CamelEntity(2, "Bob", "Smith"),
            CamelEntity(3, "Alice", "Brown"),
            CamelEntity(4, "Charlie", "Jones")
        ))

        val list = PagedList<CamelEntity>()
        val lastNameCol = list.addColumn("lastName")
        val firstNameCol = list.addColumn("firstName")

        // Without filter: all distinct first names
        val firstNames = firstNameCol.getFilterValues()
        assertEquals(3, firstNames.size) // Alice, Bob, Charlie

        // Filter lastName to "Smith" — only Alice and Bob have Smith
        lastNameCol.filter = "Smith"
        val filteredFirstNames = firstNameCol.getFilterValues()
        assertEquals(2, filteredFirstNames.size) // Alice, Bob
    }

    @Test
    fun testSelectionValuesPaginated() = withDb("PAGED-SEL-PAGED") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create((1..20).map { TestC(it, "Name${it.toString().padStart(2, '0')}") })

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        val values = col.getFilterValues()
        values.pageSize = 5

        assertEquals(20, values.size)
        assertEquals("Name01", values[0])
        assertEquals("Name10", values[9])
        assertEquals("Name20", values[19])
    }

    @Test
    fun testSelectionValuesAutoInvalidate() = withDb("PAGED-SEL-INVAL") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Charlie")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        val idCol = list.addColumn("id")

        val values = col.getFilterValues()
        assertEquals(3, values.size)

        // Filter by id — should auto-invalidate selection values
        idCol.filter = "<= 2"
        assertEquals(2, values.size) // Only Alice, Bob
    }

    // --- Raw column ---

    @Test
    fun testRawColumnFilter() = withDb("PAGED-RAW") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addRawColumn("test.id", Column.NUMERIC)
        col.filter = "> 7"

        assertEquals(3, list.size) // 8, 9, 10
    }

    @Test
    fun testRawColumnSort() = withDb("PAGED-RAW-SORT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Charlie"), TestC(2, "Alice"), TestC(3, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addRawColumn("test.name", Column.TEXT)
        col.sort = Column.ASCENDING

        assertEquals("Alice", list[0].name)
        assertEquals("Bob", list[1].name)
        assertEquals("Charlie", list[2].name)
    }

    // --- Invalid input ---

    @Test
    fun testInvalidNumericInput() = withDb("PAGED-INVALID-NUM") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "abc"  // invalid number → 0 results, no crash

        assertEquals(0, list.size)
    }

    @Test
    fun testInvalidDateInput() = withDb("PAGED-INVALID-DATE") { s ->
        setupDateTable(s)
        val list = PagedList<Event>()
        val col = list.addColumn("eventDate")
        col.filter = "not-a-date"

        assertEquals(0, list.size)
    }

    @Test
    fun testRawColumnCustomSqlGenerator() = withDb("PAGED-RAW-CUSTOM") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val isOracle = s.sqlDialect == SqlDialect.ORACLE_NEW || s.sqlDialect == SqlDialect.ORACLE_OLD
        val col = list.addRawColumn("test.id", Column.RAW) { column, value, args ->
            val mod = value.toIntOrNull() ?: 0
            args.accept(mod)
            if (isOracle) "MOD($column, ?) = 0" else "$column % ? = 0"
        }
        col.filter = "3"

        assertEquals(3, list.size) // id 3, 6, 9
    }

    // --- InputParser ---

    @Test
    fun testInputParserPerColumn() = withDb("PAGED-PARSER-COL") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        // Simulate Greek locale: "1.000" means 1000, not 1.000
        col.inputParser = InputParser { input, _ -> input.replace(".", "") }
        col.filter = "> 1.000"

        // Without parser: "> 1.000" → "> 1.000" (error or wrong result)
        // With parser: "> 1.000" → "> 1000" → all ids don't exceed 1000
        // But we have 20 items, so "> 1000" → 0 results (ids are 1..20)
        assertEquals(0, list.size)
    }

    @Test
    fun testInputParserPerList() = withDb("PAGED-PARSER-LIST") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        list.inputParser = InputParser { input, type ->
            if (type == Column.NUMERIC) input.replace(".", "") else input
        }
        val col = list.addColumn("id")
        col.filter = "> 1.5"  // "1.5" → "15" after parser

        assertEquals(5, list.size) // ids 16..20
    }

    @Test
    fun testInputParserGlobal() = withDb("PAGED-PARSER-GLOBAL") { s ->
        val oldParser = PagedList.defaultInputParser
        try {
            // Simulate locale where dot is thousand separator
            PagedList.defaultInputParser = InputParser { input, type ->
                if (type == Column.NUMERIC) input.replace(".", "") else input
            }
            setupTable(s, 20)
            val list = PagedList<TestC>()
            val col = list.addColumn("id")
            col.filter = "> 1.8"  // "1.8" → "18"

            assertEquals(2, list.size) // ids 19, 20
        } finally {
            PagedList.defaultInputParser = oldParser
        }
    }

    @Test
    fun testInputParserResolutionOrder() = withDb("PAGED-PARSER-ORDER") { s ->
        val oldParser = PagedList.defaultInputParser
        try {
            // Global: replace comma
            PagedList.defaultInputParser = InputParser { input, _ -> input.replace(",", ".") }

            setupTable(s, 20)
            val list = PagedList<TestC>()
            // List: replace dot
            list.inputParser = InputParser { input, _ -> input.replace(".", "") }

            val col = list.addColumn("id")
            // Column parser wins over list parser
            col.inputParser = InputParser { input, _ -> input.replace("X", "1") }
            col.filter = "> X8"  // column parser: "X8" → "18"

            assertEquals(2, list.size) // ids 19, 20
        } finally {
            PagedList.defaultInputParser = oldParser
        }
    }

    // --- Date InputParser ---

    private val dateParser: InputParser = InputParser { input, _ ->
        // InputParser receives individual values only (no operators)
        // Convert dd/MM/yyyy → yyyy-MM-dd
        val parts = input.split("/")
        if (parts.size == 3) "${parts[2]}-${parts[1]}-${parts[0]}" else input
    }

    private fun setupDateTable(s: Stormify) {
        TestDDL.dropTable("event")
        s.executeUpdate(TestDDL.createTable("event",
            "${TestDDL.intPrimaryKey("id")}, title ${TestDDL.textType()}, event_date DATE"))
        s.create(listOf(
            Event(1, "New Year", LocalDate(2026, 1, 15)),
            Event(2, "Spring", LocalDate(2026, 3, 20)),
            Event(3, "Summer", LocalDate(2026, 6, 1)),
            Event(4, "Autumn", LocalDate(2026, 9, 10)),
            Event(5, "Christmas", LocalDate(2026, 12, 25))
        ))
    }

    @Test
    fun testDateFilterGreaterThan() = withDb("PAGED-DATE-GT") { s ->
        setupDateTable(s)
        val list = PagedList<Event>()
        val col = list.addColumn("eventDate")
        col.inputParser = dateParser
        col.filter = "> 01/06/2026"

        assertEquals(2, list.size)
    }

    @Test
    fun testDateFilterLessThan() = withDb("PAGED-DATE-LT") { s ->
        setupDateTable(s)
        val list = PagedList<Event>()
        val col = list.addColumn("eventDate")
        col.inputParser = dateParser
        col.filter = "< 01/06/2026"

        assertEquals(2, list.size)
    }

    @Test
    fun testDateFilterRange() = withDb("PAGED-DATE-RANGE") { s ->
        setupDateTable(s)
        val list = PagedList<Event>()
        val col = list.addColumn("eventDate")
        col.inputParser = dateParser
        col.filter = "01/03/2026 ... 30/09/2026"

        assertEquals(3, list.size)
    }

    @Test
    fun testDateFilterExact() = withDb("PAGED-DATE-EXACT") { s ->
        setupDateTable(s)
        val list = PagedList<Event>()
        val col = list.addColumn("eventDate")
        col.inputParser = dateParser
        col.filter = "20/03/2026"

        assertEquals(1, list.size)
        assertEquals("Spring", list[0].title)
    }

    // --- Composite primary key error ---

    @Test
    fun testCompositeKeyRequiresExplicitSort() = withDb("PAGED-COMPOSITE-PK") { s ->
        TestDDL.dropTable("dual_key")
        s.executeUpdate(TestDDL.createTable("dual_key",
            "${TestDDL.intColumn("id1")}, ${TestDDL.intColumn("id2")}, data ${TestDDL.textType()}, PRIMARY KEY (id1, id2)"))
        s.create(listOf(DualKey(1, 1, "a"), DualKey(1, 2, "b")))

        val list = PagedList<DualKey>()
        // No explicit sort → should fail when fetching data (sortingPart is called)
        assertFailsWith<IllegalStateException> { list[0] }
    }

    @Test
    fun testCompositeKeyWithExplicitSort() = withDb("PAGED-COMPOSITE-PK-OK") { s ->
        TestDDL.dropTable("dual_key")
        s.executeUpdate(TestDDL.createTable("dual_key",
            "${TestDDL.intColumn("id1")}, ${TestDDL.intColumn("id2")}, data ${TestDDL.textType()}, PRIMARY KEY (id1, id2)"))
        s.create(listOf(DualKey(1, 1, "a"), DualKey(1, 2, "b")))

        val list = PagedList<DualKey>()
        list.addColumn("id1").sort = Column.ASCENDING
        // With explicit sort it should work
        assertEquals(2, list.size)
    }

    // --- Raw column TEMPORAL ---

    @Test
    fun testRawColumnTemporal() = withDb("PAGED-RAW-DATE") { s ->
        if (s.sqlDialect == SqlDialect.SQLITE)
            skipTest(SkipReason.LIBRARY_LIMITATION,
                "SQLite stores LocalDate as epoch ms (kdbc choice) — raw ISO comparison mismatches")
        setupDateTable(s)
        val list = PagedList<Event>()
        // Raw column — the default TEMPORAL converter wraps the placeholder with
        // a dialect-aware cast (e.g., TO_DATE on Oracle, CAST on others)
        val col = list.addRawColumn("event.event_date", Column.TEMPORAL)
        col.inputParser = dateParser
        // Use >= to avoid noon-vs-midnight edge case on Oracle (DATE includes time)
        col.filter = ">= 02/06/2026"  // Sep 10, Dec 25 (strictly after June 1)

        assertEquals(2, list.size)
    }

    // --- Selected + sort + filter ---

    @Test
    fun testSelectedWithSortAndFilter() = withDb("PAGED-SEL-SORT-FILTER") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.sort = Column.DESCENDING
        col.filter = "Item"

        val item5 = s.read<TestC>("SELECT * FROM test WHERE id = ?", 5).first()
        list.selected = item5

        // Selected entity should still appear first even with sort + filter
        assertEquals("Item5", list[0].name)
        // Size should reflect the filter
        assertEquals(10, list.size)
    }

    // --- HumanReadable enum ---

    @Test
    fun testEnumWithHumanReadable() = withDb("PAGED-HUMAN-READABLE") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        // Store enum names directly in the TEXT column
        s.create(listOf(TestC(1, "ACTIVE"), TestC(2, "INACTIVE"), TestC(3, "BANNED")))

        val list = PagedList<TestC>()
        // Map: HumanReadable display name → DB value (enum name)
        // The user types "Ενερ" — reverse substring lookup finds "Ενεργή" → "ACTIVE"
        // Note: "Ενερ" also matches "Ανενεργή" (contains "ενερ"), so we get 2 results
        val displayMap = HRStatus.entries.associate { it.displayName to it.name }
        val col = list.addColumn(displayMap, "name")
        col.filter = "Ενεργή"  // exact substring — matches "Ενεργή" and "Ανενεργή"

        // Should match both ACTIVE and INACTIVE (both contain "Ενεργή" in their display names)
        assertEquals(2, list.size)
    }

    // --- Strong enum auto-detect with CustomStatus ---

    @Test
    fun testEnumAutoDetectWithDbValue() = withDb("PAGED-ENUM-DBVALUE") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(TestDDL.createTable("enum_test",
            "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"))
        s.create(listOf(
            EnumEntity(1, PlainStatus.ACTIVE, CustomStatus.ACTIVE),   // custom_status = 10
            EnumEntity(2, PlainStatus.INACTIVE, CustomStatus.INACTIVE), // custom_status = 20
            EnumEntity(3, PlainStatus.BANNED, CustomStatus.BANNED)    // custom_status = 99
        ))

        val list = PagedList<EnumEntity>()
        // Auto-detect — for CustomStatus the map should be { name → dbValue(Int) }
        val col = list.addColumn("customStatus")
        col.filter = "BANNED"

        // Verify correct row and correct DB value is sent
        assertEquals(1, list.size)
        assertEquals(3, list[0].id)
        assertEquals(CustomStatus.BANNED, list[0].customStatus)
    }

    // --- refresh ---

    @Test
    fun testRefresh() = withDb("PAGED-REFRESH") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        assertEquals(5, list.size)

        // Insert a new row behind the list's back — size is still cached
        s.executeUpdate("INSERT INTO test (id, name) VALUES (?, ?)", 6, "Item6")
        assertEquals(5, list.size)  // still cached

        // refresh() drops the cache, next access re-queries
        list.refresh()
        assertEquals(6, list.size)
    }

    @Test
    fun testRefreshAfterExternalMutation() = withDb("PAGED-REFRESH-MUT") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        assertEquals("Item1", list[0].name)

        // Mutate a row via raw SQL outside the list's awareness
        s.executeUpdate("UPDATE test SET name = ? WHERE id = ?", "Renamed", 1)

        // Cached page still shows the old value
        assertEquals("Item1", list[0].name)

        // refresh() forces the next access to re-query
        list.refresh()
        assertEquals("Renamed", list[0].name)
    }

    // --- isCaseSensitive setter refreshes ---

    @Test
    fun testIsCaseSensitiveSetterRefreshes() = withDb("PAGED-CS-SETTER") { s ->
        if (s.sqlDialect in setOf(SqlDialect.SQLITE, SqlDialect.MYSQL_OLD, SqlDialect.MYSQL_NEW,
                SqlDialect.MARIA_DB_OLD, SqlDialect.MARIA_DB_NEW,
                SqlDialect.SQL_SERVER_OLD, SqlDialect.SQL_SERVER_NEW))
            skipTest(SkipReason.DIALECT_QUIRK,
                "LIKE is case-insensitive by default on this dialect — no observable change")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "ALICE"), TestC(2, "alice"), TestC(3, "Alice"), TestC(4, "Bob")))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "Alice"
        assertEquals(3, list.size)  // case-insensitive default

        col.isCaseSensitive = true
        // Setter must refresh — the next access re-queries with the new flag
        assertEquals(1, list.size)
    }

    // --- forEachStreaming ---

    @Test
    fun testForEachStreaming() = withDb("PAGED-FOREACH") { s ->
        setupTable(s, 100)
        val list = PagedList<TestC>()
        list.pageSize = 5

        val names = mutableListOf<String?>()
        list.forEachStreaming { names.add(it.name) }

        assertEquals(100, names.size)
        assertEquals("Item1", names.first())
        assertEquals("Item100", names.last())
    }

    @Test
    fun testForEachWithFilter() = withDb("PAGED-FOREACH-FILTER") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "> 15"

        val ids = mutableListOf<Int>()
        list.forEachStreaming { ids.add(it.id) }

        assertEquals(listOf(16, 17, 18, 19, 20), ids.sorted())
    }

    /**
     * `forEachStreaming` buffers rows in chunks of 32 internally (so sibling-batch
     * lazy-load works in streaming mode). A size that is **not** a multiple of 32
     * exercises both the full-chunk flush path and the final partial-chunk drain
     * path. With 200 rows we get 6 full chunks of 32 plus one partial chunk of 8,
     * which surfaces any off-by-one in the buffer (`buffer.clear()` missing,
     * final drain missing, duplicate flush, out-of-order flush).
     */
    @Test
    fun testForEachStreaming200RowsCrossesChunkBoundary() = withDb("PAGED-FOREACH-200") { s ->
        setupTable(s, 200)
        val list = PagedList<TestC>()

        val seen = mutableListOf<Int>()
        list.forEachStreaming { seen.add(it.id) }

        // No lost rows, no duplicates, order preserved.
        assertEquals(200, seen.size, "expected every buffered row to reach the callback")
        assertEquals(200, seen.toSet().size, "forEachStreaming must not emit duplicates from the chunk flush")
        assertEquals((1..200).toList(), seen, "forEachStreaming must preserve the cursor's row order across chunks")
    }

    /**
     * Direct verification that [PagedListBase.forEachStreaming] really drives
     * a single cursor SELECT — not a fallback to paged iteration. Captures
     * every SELECT emitted during a 20-row scan and asserts there is exactly
     * one query, no `LIMIT … OFFSET …`. If this ever reverts to paged mode
     * the whole sibling-batch optimisation for streaming exports is gone.
     */
    @Test
    fun testForEachStreamingUsesSingleCursorQuery() = withDb("PAGED-FOREACH-DISPATCH") { s ->
        setupTable(s, 20)

        val names = mutableListOf<String?>()
        val captured = s.captureSelects {
            PagedList<TestC>().forEachStreaming { names.add(it.name) }
        }

        assertEquals(20, names.size)
        // Exactly one SELECT means we went through the cursor path.
        assertEquals(
            1,
            captured.size,
            "expected a single cursor SELECT, got ${captured.size}: $captured"
        )
        assertFalse(
            captured.first().contains("LIMIT") || captured.first().contains("OFFSET"),
            "expected the cursor path (no LIMIT/OFFSET), got: ${captured.first()}"
        )
    }

    /**
     * End-to-end check that `forEachStreaming` drives sibling-batch lazy-
     * loading on foreign-key references. Seeds 50 parents and 200 children
     * (4 per parent), then walks every child and touches `child.parent?.data`.
     * A [WatchLogger] counts the SELECTs Stormify emits during the scan.
     *
     * By the time the first `.parent` touch fires inside a chunk, all 32
     * buffered children have already been enrolled in the cursor's sibling
     * group, so the first lazy load collapses into a single
     * `WHERE id IN (…)` covering the whole chunk. The per-row fallback would
     * emit ~51 SELECTs; the sibling-batch path stays well under 10. The ≥ 2
     * lower bound guards against a regression where the FK path silently
     * never fires.
     */
    @Test
    fun testForEachStreamingBatchesParentFetchesSiblingGroup() = withDb("FOREACH-SIBLING-BATCH") { s ->
        TestDDL.dropTable("auto_child")   // drop child first to release any FK
        TestDDL.dropTable("auto_parent")
        s.executeUpdate(
            TestDDL.createTable(
                "auto_parent",
                "${TestDDL.intPrimaryKey("id")}, other ${TestDDL.textType()}, data ${TestDDL.textType()}"
            )
        )
        s.executeUpdate(
            TestDDL.createTable(
                "auto_child",
                "${TestDDL.intPrimaryKey("id")}, data ${TestDDL.textType()}, parent ${TestDDL.intType()}"
            )
        )

        // 50 parents, 4 children per parent → 200 children total. Raw INSERTs
        // bypass the ORM layer entirely so we don't have to care about the
        // default-Stormify / `db`-delegate populate-on-setValue interaction.
        for (pid in 1..50) {
            s.executeUpdate(
                "INSERT INTO auto_parent (id, other, data) VALUES (?, ?, ?)",
                pid, "other$pid", "data$pid"
            )
        }
        for (cid in 1..200) {
            // children 1-4 → parent 1, children 5-8 → parent 2, …, children 197-200 → parent 50
            val parentId = ((cid - 1) / 4) + 1
            s.executeUpdate(
                "INSERT INTO auto_child (id, data, parent) VALUES (?, ?, ?)",
                cid, "child$cid", parentId
            )
        }

        // Recording starts after the seeding above, so the INSERTs / DDL
        // don't pollute the count.
        var visited = 0
        val captured = s.captureSelects {
            PagedList<AutoChildEntity>().forEachStreaming { child ->
                val expectedParentId = ((child.id!! - 1) / 4) + 1
                assertEquals(
                    "data$expectedParentId",
                    child.parent?.data,
                    "child ${child.id} must see parent $expectedParentId's data"
                )
                visited++
            }
        }
        assertEquals(200, visited, "expected forEachStreaming to visit all 200 children")

        // 1 SELECT for the cursor + several IN batches for parent stubs.
        // The per-row fallback would emit ~51 SELECTs; the sibling-batch
        // path stays well under 10. The ≥ 2 lower bound guards against a
        // regression where the FK sibling-load path silently never fires
        // (which would leave only the cursor SELECT and still pass under
        // a `1..10` check).
        assertTrue(
            captured.size in 2..10,
            "expected 2..10 SELECT statements with sibling batching, got ${captured.size}: $captured"
        )
    }

    // --- Aggregator (single) ---

    @Test
    fun testAggregatorSingleSum() = withDb("PAGED-AGG-SUM") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val total = list.getAggregator().sum("id").execute<Long>()
        assertEquals(55L, total)  // 1+2+…+10
    }

    @Test
    fun testAggregatorSingleCount() = withDb("PAGED-AGG-COUNT") { s ->
        setupTable(s, 7)
        val list = PagedList<TestC>()
        val count = list.getAggregator().count("*").execute<Long>()
        assertEquals(list.size.toLong(), count)
    }

    @Test
    fun testAggregatorSingleCountDistinct() = withDb("PAGED-AGG-COUNT-DISTINCT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(
            TestC(1, "Alice"), TestC(2, "Alice"),
            TestC(3, "Bob"), TestC(4, "Charlie")
        ))

        val list = PagedList<TestC>()
        val distinct = list.getAggregator().countDistinct("name").execute<Long>()
        assertEquals(3L, distinct)  // Alice, Bob, Charlie
    }

    @Test
    fun testAggregatorSingleMinMax() = withDb("PAGED-AGG-MINMAX") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val min = list.getAggregator().min("id").execute<Int>()
        val max = list.getAggregator().max("id").execute<Int>()
        assertEquals(1, min)
        assertEquals(10, max)
    }

    // --- Aggregator (multi) ---

    @Test
    fun testAggregatorMulti() = withDb("PAGED-AGG-MULTI") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .sum("id", "total")
            .min("id", "mn")
            .max("id", "mx")
            .count("*", "cnt")
            .execute()

        assertEquals(55L, (row["total"] as Number).toLong())
        assertEquals(1, (row["mn"] as Number).toInt())
        assertEquals(10, (row["mx"] as Number).toInt())
        assertEquals(10L, (row["cnt"] as Number).toLong())
    }

    @Test
    fun testAggregatorWithFilter() = withDb("PAGED-AGG-FILTER") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "<= 5"

        // Aggregator must honor the active filter
        val sum = list.getAggregator().sum("id").execute<Long>()
        assertEquals(15L, sum)  // 1+2+3+4+5
    }

    @Test
    fun testAggregatorRaw() = withDb("PAGED-AGG-RAW") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // SUM(id * id) = 1+4+9+16+25 = 55
        val result = list.getAggregator()
            .raw("SUM(test.id * test.id)")
            .execute<Long>()
        assertEquals(55L, result)
    }

    @Test
    fun testAggregatorDuplicateAliasThrows() = withDb("PAGED-AGG-DUP") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        assertFailsWith<IllegalArgumentException> {
            list.getAggregator()
                .sum("id", "total")
                .max("id", "total")
                .execute()
        }
    }

    // --- Aggregator alias naming ---

    @Test
    fun testAggregatorAutoAliasSimple() = withDb("PAGED-AGG-ALIAS-SIMPLE") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .sum("id")           // → "sum_id"
            .avg("id")           // → "avg_id"
            .min("id")           // → "min_id"
            .max("id")           // → "max_id"
            .count("*")          // → "count"
            .countDistinct("id") // → "countDistinct_id"
            .execute()

        assertTrue("sum_id" in row, "missing sum_id: $row")
        assertTrue("avg_id" in row, "missing avg_id: $row")
        assertTrue("min_id" in row, "missing min_id: $row")
        assertTrue("max_id" in row, "missing max_id: $row")
        assertTrue("count" in row, "missing count: $row")
        assertTrue("countDistinct_id" in row, "missing countDistinct_id: $row")
        assertEquals(15L, (row["sum_id"] as Number).toLong())
        assertEquals(1, (row["min_id"] as Number).toInt())
        assertEquals(5, (row["max_id"] as Number).toInt())
        assertEquals(5L, (row["count"] as Number).toLong())
        assertEquals(5L, (row["countDistinct_id"] as Number).toLong())
    }

    @Test
    fun testAggregatorAutoAliasCollision() = withDb("PAGED-AGG-ALIAS-COLL") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // Two sum("id") calls should produce "sum_id" and "sum_id_2"
        val row = list.getAggregator()
            .sum("id")
            .sum("id")
            .execute()
        assertTrue("sum_id" in row)
        assertTrue("sum_id_2" in row)
        assertEquals(15L, (row["sum_id"] as Number).toLong())
        assertEquals(15L, (row["sum_id_2"] as Number).toLong())
    }

    @Test
    fun testAggregatorAutoAliasCollisionTriple() = withDb("PAGED-AGG-ALIAS-COLL3") { s ->
        setupTable(s, 3)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .sum("id")
            .sum("id")
            .sum("id")
            .execute()
        assertTrue("sum_id" in row)
        assertTrue("sum_id_2" in row)
        assertTrue("sum_id_3" in row)
    }

    @Test
    fun testAggregatorRawAliasNoPrefix() = withDb("PAGED-AGG-ALIAS-RAW") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // raw() never gets a "raw_" prefix — the expression itself names the function
        val row = list.getAggregator()
            .raw("SUM(test.id)")
            .raw("COUNT(test.id)")
            .execute()
        assertTrue("SUM_test_id" in row, "got keys: ${row.keys}")
        assertTrue("COUNT_test_id" in row, "got keys: ${row.keys}")
    }

    @Test
    fun testAggregatorRawAliasCollision() = withDb("PAGED-AGG-ALIAS-RAW-COLL") { s ->
        setupTable(s, 3)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .raw("SUM(test.id)")
            .raw("SUM(test.id)")
            .execute()
        assertTrue("SUM_test_id" in row)
        assertTrue("SUM_test_id_2" in row)
    }

    @Test
    fun testAggregatorRawAliasWithOperators() = withDb("PAGED-AGG-ALIAS-OPS") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // Operators (*, parens) get sanitized to underscores, coalesced, trimmed
        val row = list.getAggregator()
            .raw("SUM(test.id * test.id)")
            .count("*")
            .execute()
        assertTrue("SUM_test_id_test_id" in row, "got keys: ${row.keys}")
        // 1 + 4 + 9 + 16 + 25 = 55
        assertEquals(55L, (row["SUM_test_id_test_id"] as Number).toLong())
    }

    @Test
    fun testAggregatorRawAliasStarFallback() = withDb("PAGED-AGG-ALIAS-RAW-STAR") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // raw("COUNT(*)") — parens sanitized → "COUNT"
        val cnt = list.getAggregator()
            .raw("COUNT(*)")
            .execute<Long>()
        assertEquals(5L, cnt)
    }

    /**
     * Auto-generated raw aliases must be valid SQL identifiers. A raw
     * expression whose sanitized form would start with a digit
     * (`"1 + SUM(test.id)"` sanitizes to `"1_SUM_test_id"`) would otherwise
     * produce the illegal alias `SELECT … AS 1_SUM_test_id FROM …` — and
     * for a bare `raw("1")` the query would even become the unparseable
     * `SELECT 1 AS 1 FROM …`. The generator therefore prepends a single
     * `_` to digit-first sanitized aliases, matching the same rule applied
     * to user-supplied aliases.
     */
    @Test
    fun testAggregatorRawAliasDigitFirstGetsPrefix() = withDb("PAGED-AGG-ALIAS-RAW-DIGIT") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .raw("1 + SUM(test.id)")       // digit-first — alias "_1_SUM_test_id"
            .raw("2 * COUNT(test.id)")     // digit-first — alias "_2_COUNT_test_id"
            .execute()

        assertTrue("_1_SUM_test_id" in row, "got: ${row.keys}")
        assertTrue("_2_COUNT_test_id" in row, "got: ${row.keys}")
        // 1 + SUM(1..5) = 1 + 15 = 16
        assertEquals(16L, (row["_1_SUM_test_id"] as Number).toLong())
        // 2 * COUNT(...) = 2 * 5 = 10
        assertEquals(10L, (row["_2_COUNT_test_id"] as Number).toLong())
    }

    /**
     * Same rule applies to user-supplied aliases — if the first character is
     * not a letter, a single `_` is prepended so the generated SQL remains
     * valid on every mainstream dialect. The explicit name is otherwise
     * preserved verbatim (no sanitization of the rest of the string).
     */
    @Test
    fun testAggregatorUserSuppliedAliasDigitFirstGetsUnderscore() =
        withDb("PAGED-AGG-ALIAS-USER-DIGIT") { s ->
            setupTable(s, 5)
            val list = PagedList<TestC>()
            val row = list.getAggregator()
                .sum("id", alias = "1total")   // explicit digit-first → "_1total"
                .count("*", alias = "42rows")  // explicit digit-first → "_42rows"
                .execute()

            assertTrue("_1total" in row, "got: ${row.keys}")
            assertTrue("_42rows" in row, "got: ${row.keys}")
            assertEquals(15L, (row["_1total"] as Number).toLong())   // 1+2+3+4+5
            assertEquals(5L, (row["_42rows"] as Number).toLong())
        }

    /**
     * Degenerate case: `raw("1")` — a bare digit literal. Without the
     * digit-first normalization the emitted query would be the unparseable
     * `SELECT 1 AS 1 FROM …`; the single `_` prefix turns the alias into
     * `"_1"` and the query becomes `SELECT 1 AS _1 FROM …`. The table has
     * exactly one row so `executeSingle` can return the scalar `1`.
     */
    @Test
    fun testAggregatorRawBareDigitLiteralGetsPrefix() = withDb("PAGED-AGG-ALIAS-RAW-BARE") { s ->
        setupTable(s, 1)
        val list = PagedList<TestC>()
        val value = list.getAggregator()
            .raw("1")                // degenerate case — alias "_1"
            .execute<Long>()
        assertEquals(1L, value)
    }

    /**
     * Regression test: an explicit alias that already starts with `_`
     * (a valid SQL identifier lead character) must be preserved verbatim
     * — never double-prefixed to `"__foo"`. `_` is a valid first character
     * for SQL identifiers on every mainstream dialect, so no normalization
     * is required.
     */
    @Test
    fun testAggregatorUserSuppliedAliasUnderscoreFirstPreserved() =
        withDb("PAGED-AGG-ALIAS-USER-UNDERSCORE") { s ->
            setupTable(s, 5)
            val list = PagedList<TestC>()
            val row = list.getAggregator()
                .sum("id", alias = "_total")   // leading `_` — preserved as-is
                .count("*", alias = "_cnt")    // leading `_` — preserved as-is
                .execute()

            assertTrue("_total" in row, "got: ${row.keys}")
            assertTrue("_cnt" in row, "got: ${row.keys}")
            assertFalse("__total" in row, "alias must not be double-prefixed, got: ${row.keys}")
            assertFalse("__cnt" in row, "alias must not be double-prefixed, got: ${row.keys}")
            assertEquals(15L, (row["_total"] as Number).toLong())
            assertEquals(5L, (row["_cnt"] as Number).toLong())
        }

    @Test
    fun testAggregatorExplicitAlias() = withDb("PAGED-AGG-ALIAS-EXPLICIT") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .sum("id", "grand_total")
            .count("*", "row_count")
            .execute()
        assertEquals(55L, (row["grand_total"] as Number).toLong())
        assertEquals(10L, (row["row_count"] as Number).toLong())
    }

    @Test
    fun testAggregatorMixedExplicitAndAuto() = withDb("PAGED-AGG-ALIAS-MIXED") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .sum("id", "total")  // explicit
            .avg("id")           // auto → "avg_id"
            .count("*")          // auto → "count"
            .execute()
        assertTrue("total" in row)
        assertTrue("avg_id" in row)
        assertTrue("count" in row)
    }

    @Test
    fun testAggregatorExplicitCollidesWithAuto() = withDb("PAGED-AGG-ALIAS-COLLIDE-AUTO") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // Auto gives "sum_id"; explicit "sum_id" later should collide (fail-fast)
        assertFailsWith<IllegalArgumentException> {
            list.getAggregator()
                .sum("id")
                .max("id", "sum_id")
                .execute()
        }
    }

    @Test
    fun testAggregatorCountStarAlias() = withDb("PAGED-AGG-COUNT-STAR") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // count("*") → plain "count" (no "_*" suffix); a second count("*") → "count_2"
        val row = list.getAggregator()
            .count("*")
            .count("*")
            .execute()
        assertTrue("count" in row)
        assertTrue("count_2" in row)
    }

    @Test
    fun testAggregatorSingleUsesFirstAlias() = withDb("PAGED-AGG-SINGLE-ALIAS") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        // Single-value execute reads the single column regardless of alias
        val explicit = list.getAggregator().sum("id", "foo").execute<Long>()
        val auto = list.getAggregator().sum("id").execute<Long>()
        assertEquals(55L, explicit)
        assertEquals(55L, auto)
    }

    // --- MultiAggregator: broader coverage ---

    @Test
    fun testAggregatorMultiAllFunctionsOnSameField() = withDb("PAGED-AGG-MULTI-ALL") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        // sum + avg + min + max + count + countDistinct on the same numeric field
        val row = list.getAggregator()
            .sum("id")
            .avg("id")
            .min("id")
            .max("id")
            .count("id")
            .countDistinct("id")
            .execute()
        assertEquals(6, row.size, "expected 6 keys, got: ${row.keys}")
        assertEquals(55L, (row["sum_id"] as Number).toLong())
        // avg may come back as integer (SQL integer division) or double — both valid
        assertTrue((row["avg_id"] as Number).toDouble() in 5.0..5.5)
        assertEquals(1, (row["min_id"] as Number).toInt())
        assertEquals(10, (row["max_id"] as Number).toInt())
        assertEquals(10L, (row["count_id"] as Number).toLong())
        assertEquals(10L, (row["countDistinct_id"] as Number).toLong())
    }

    @Test
    fun testAggregatorMultiCountVsCountDistinct() = withDb("PAGED-AGG-MULTI-DISTINCT") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(
            TestC(1, "Alice"), TestC(2, "Alice"), TestC(3, "Alice"),
            TestC(4, "Bob"), TestC(5, "Bob"),
            TestC(6, "Charlie")
        ))

        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .count("name")                     // all non-null rows → 6
            .countDistinct("name")             // distinct values → 3
            .count("*", "total_rows")          // total rows → 6
            .execute()
        assertEquals(6L, (row["count_name"] as Number).toLong())
        assertEquals(3L, (row["countDistinct_name"] as Number).toLong())
        assertEquals(6L, (row["total_rows"] as Number).toLong())
    }

    @Test
    fun testAggregatorMultiMixedRawAndField() = withDb("PAGED-AGG-MULTI-RAW-FIELD") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .sum("id")                               // → "sum_id"
            .raw("SUM(test.id * 2)", "doubled")      // explicit alias
            .raw("COUNT(*)")                         // → "COUNT"
            .count("*", "cnt")                       // explicit alias
            .execute()
        assertEquals(4, row.size)
        assertEquals(15L, (row["sum_id"] as Number).toLong())
        assertEquals(30L, (row["doubled"] as Number).toLong())
        assertEquals(5L, (row["COUNT"] as Number).toLong())
        assertEquals(5L, (row["cnt"] as Number).toLong())
    }

    @Test
    fun testAggregatorMultiRespectsFilterAcrossAll() = withDb("PAGED-AGG-MULTI-FILTER") { s ->
        setupTable(s, 20)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "5 ... 10"  // 6 rows: 5..10

        val row = list.getAggregator()
            .sum("id")    // 5+6+7+8+9+10 = 45
            .min("id")    // 5
            .max("id")    // 10
            .count("*")   // 6
            .execute()
        assertEquals(45L, (row["sum_id"] as Number).toLong())
        assertEquals(5, (row["min_id"] as Number).toInt())
        assertEquals(10, (row["max_id"] as Number).toInt())
        assertEquals(6L, (row["count"] as Number).toLong())
    }

    @Test
    fun testAggregatorMultiPreservesInsertionOrder() = withDb("PAGED-AGG-MULTI-ORDER") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .max("id")
            .min("id")
            .sum("id")
            .count("*")
            .execute()
        // Map should preserve insertion order
        val keys = row.keys.toList()
        assertEquals(listOf("max_id", "min_id", "sum_id", "count"), keys)
    }

    @Test
    fun testAggregatorMultiManyCollisions() = withDb("PAGED-AGG-MULTI-COLL") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        // Four sum("id") in a row — uniqueness via _2, _3, _4 suffix
        val row = list.getAggregator()
            .sum("id")
            .sum("id")
            .sum("id")
            .sum("id")
            .execute()
        assertEquals(4, row.size)
        assertEquals(setOf("sum_id", "sum_id_2", "sum_id_3", "sum_id_4"), row.keys)
        // All four report the same sum (15)
        for (v in row.values) assertEquals(15L, (v as Number).toLong())
    }

    @Test
    fun testAggregatorMultiEmptyResult() = withDb("PAGED-AGG-MULTI-EMPTY") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val col = list.addColumn("id")
        col.filter = "> 1000"  // no rows match

        val row = list.getAggregator()
            .sum("id")
            .count("*")
            .min("id")
            .execute()
        // Aggregators still return a single row, but numeric sums/mins are null,
        // and count(*) is 0.
        assertEquals(0L, (row["count"] as Number).toLong())
        assertNull(row["sum_id"])
        assertNull(row["min_id"])
    }

    @Test
    fun testAggregatorMultiWithSelectedEntity() = withDb("PAGED-AGG-MULTI-SEL") { s ->
        // Selected entity must not bias the aggregator — it's a cosmetic ordering hint only
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val item5 = s.read<TestC>("SELECT * FROM test WHERE id = ?", 5).first()
        list.selected = item5

        val row = list.getAggregator()
            .sum("id")
            .count("*")
            .execute()
        assertEquals(55L, (row["sum_id"] as Number).toLong())
        assertEquals(10L, (row["count"] as Number).toLong())
    }

    @Test
    fun testAggregatorMultiIgnoresIsDistinctFlag() = withDb("PAGED-AGG-MULTI-DIST") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        // 5 rows with duplicate names
        s.create(listOf(
            TestC(1, "A"), TestC(2, "A"), TestC(3, "B"), TestC(4, "B"), TestC(5, "C")
        ))

        val list = PagedList<TestC>()
        list.isDistinct = true  // affects list.size via DISTINCT, but NOT aggregates

        val row = list.getAggregator()
            .count("*")
            .sum("id")
            .execute()
        assertEquals(5L, (row["count"] as Number).toLong())
        assertEquals(15L, (row["sum_id"] as Number).toLong())
    }

    @Test
    fun testAggregatorMultiRawOnly() = withDb("PAGED-AGG-MULTI-RAW-ONLY") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val row = list.getAggregator()
            .raw("SUM(test.id)")
            .raw("AVG(test.id)")
            .raw("MIN(test.id)")
            .raw("MAX(test.id)")
            .execute()
        assertEquals(4, row.size)
        assertTrue("SUM_test_id" in row)
        assertTrue("AVG_test_id" in row)
        assertTrue("MIN_test_id" in row)
        assertTrue("MAX_test_id" in row)
        assertEquals(15L, (row["SUM_test_id"] as Number).toLong())
        assertEquals(1, (row["MIN_test_id"] as Number).toInt())
        assertEquals(5, (row["MAX_test_id"] as Number).toInt())
    }

    @Test
    fun testAggregatorMultiRawAndMixedCollision() = withDb("PAGED-AGG-MULTI-RAW-MIX-COLL") { s ->
        setupTable(s, 3)
        val list = PagedList<TestC>()
        // Three raws that would all produce "SUM_test_id" → _2, _3 suffixes
        val row = list.getAggregator()
            .raw("SUM(test.id)")
            .raw("SUM(test.id)")
            .raw("SUM(test.id)")
            .execute()
        assertEquals(setOf("SUM_test_id", "SUM_test_id_2", "SUM_test_id_3"), row.keys)
    }

    @Test
    fun testAggregatorMultiQueryField() = withDb("PAGED-AGG-MULTI-QUERY") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val agg = list.getAggregator()
            .sum("id", "total")
            .count("*", "cnt")
        // query should contain both expressions aliased
        val q = agg.query
        assertTrue("SUM" in q)
        assertTrue("total" in q)
        assertTrue("COUNT" in q)
        assertTrue("cnt" in q)
    }

    // --- FilterCountedValues ---

    @Test
    fun testFilterCountedValues() = withDb("PAGED-FILTER-COUNTED") { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test",
            "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(
            TestC(1, "Alice"), TestC(2, "Alice"), TestC(3, "Alice"),
            TestC(4, "Bob"), TestC(5, "Bob"),
            TestC(6, "Charlie")
        ))

        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        val counted = col.getFilterValues().withCounts()

        assertEquals(3, counted.size)  // 3 distinct names
        val byName = counted.associate { it.value to it.count }
        assertEquals(3L, byName["Alice"])
        assertEquals(2L, byName["Bob"])
        assertEquals(1L, byName["Charlie"])
    }

    @Test
    fun testFilterCountedValuesCached() = withDb("PAGED-FILTER-COUNTED-CACHE") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        val values = col.getFilterValues()
        val a = values.withCounts()
        val b = values.withCounts()
        // Same instance — caching guarantee
        assertSame(a, b)
    }

    // --- saveState / restoreState ---

    @Test
    fun testSaveRestoreState() = withDb("PAGED-STATE") { s ->
        setupTable(s, 10)
        val list = PagedList<TestC>()
        val nameCol = list.addColumn("name")
        val idCol = list.addColumn("id")

        nameCol.filter = "Item1"
        idCol.sort = Column.DESCENDING
        list.pageSize = 7
        list.isDistinct = true

        val saved = list.saveState()

        // Mutate the list state
        nameCol.filter = null
        idCol.sort = null
        list.pageSize = 15
        list.isDistinct = false

        // Restore
        list.restoreState(saved)

        assertEquals("Item1", nameCol.filter)
        assertEquals(Column.DESCENDING, idCol.sort)
        assertEquals(7, list.pageSize)
        assertTrue(list.isDistinct)
    }

    @Test
    fun testStateKeyDeterministic() = withDb("PAGED-STATE-KEY") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        s.create(listOf(CamelEntity(1, "Alice", "Smith")))

        val list1 = PagedList<CamelEntity>()
        list1.addColumn("firstName", "lastName").filter = "Ali"

        val list2 = PagedList<CamelEntity>()
        list2.addColumn("lastName", "firstName")  // swapped order

        // state key is derived from sorted paths — save/restore across swapped order
        val saved = list1.saveState()
        list2.restoreState(saved)
        assertEquals("Ali", list2.getColumn(0).filter)
    }

    @Test
    fun testRestoreIgnoresUnknownKeys() = withDb("PAGED-STATE-UNKNOWN") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "Item1"
        val saved = list.saveState()

        // New list with different columns — restore must not throw
        val list2 = PagedList<TestC>()
        list2.addColumn("id")
        list2.restoreState(saved)  // should silently ignore the "name" key
        assertNull(list2.getColumn(0).filter)
    }

    @Test
    fun testRestoreEmptyState() = withDb("PAGED-STATE-EMPTY") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.filter = "Item1"
        list.isDistinct = true

        // Restore default state → clears everything
        list.restoreState(PagedListState())
        assertNull(col.filter)
        assertEquals(15, list.pageSize)
        assertFalse(list.isDistinct)
    }

    // --- PagedListState plain-data serialization ---

    @Test
    fun testSavedStateSortsAreAscDescStrings() = withDb("PAGED-STATE-SORT-STR") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val nameCol = list.addColumn("name")
        val idCol = list.addColumn("id")

        nameCol.sort = Column.ASCENDING
        idCol.sort = Column.DESCENDING

        val saved = list.saveState()

        // Sorts are stored as plain "ASC" / "DESC" strings — no enum leakage.
        assertEquals(2, saved.sorts.size)
        assertTrue(saved.sorts.values.all { it == "ASC" || it == "DESC" })
        // Exactly one ASC entry and one DESC entry across the two columns.
        assertEquals(1, saved.sorts.values.count { it == PagedListSort.ASC })
        assertEquals(1, saved.sorts.values.count { it == PagedListSort.DESC })
        assertEquals("ASC", PagedListSort.ASC)
        assertEquals("DESC", PagedListSort.DESC)
    }

    @Test
    fun testRestoreSortFromHandCraftedPlainState() = withDb("PAGED-STATE-SORT-PLAIN") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val nameCol = list.addColumn("name")
        val idCol = list.addColumn("id")

        // Build a reference state from saveState() so we know the keys, then
        // rebuild it from scratch using only plain data types — the shape any
        // serialization framework produces after a JSON round-trip.
        nameCol.sort = Column.ASCENDING
        idCol.sort = Column.DESCENDING
        val reference = list.saveState()
        val handCrafted = PagedListState(
            filters = reference.filters.toMap(),
            sorts = reference.sorts.toMap(),  // already plain Map<String, String>
            caseSensitive = reference.caseSensitive.toMap(),
            pageSize = reference.pageSize,
            isDistinct = reference.isDistinct
        )

        // Mutate the list, then restore from the hand-crafted state.
        nameCol.sort = null
        idCol.sort = null
        list.restoreState(handCrafted)

        assertEquals(Column.ASCENDING, nameCol.sort)
        assertEquals(Column.DESCENDING, idCol.sort)
    }

    @Test
    fun testRestoreSortIgnoresInvalidDirection() = withDb("PAGED-STATE-SORT-INVALID") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val col = list.addColumn("name")
        col.sort = Column.ASCENDING

        // Grab the actual key from a save round-trip, then override the value with garbage.
        val reference = list.saveState()
        val key = reference.sorts.keys.single()
        val poisoned = PagedListState(sorts = mapOf(key to "gibberish"))
        list.restoreState(poisoned)

        // Unknown sort value treated as absent — column sort is cleared.
        assertNull(col.sort)
    }

    @Test
    fun testPagedListStatePureDataRoundTrip() = withDb("PAGED-STATE-ROUND") { s ->
        setupTable(s, 5)
        val list = PagedList<TestC>()
        val nameCol = list.addColumn("name")
        val idCol = list.addColumn("id")

        nameCol.filter = "Item"
        nameCol.sort = Column.DESCENDING
        nameCol.isCaseSensitive = true
        idCol.sort = Column.ASCENDING
        list.pageSize = 25
        list.isDistinct = true

        val saved = list.saveState()

        // Deconstruct the state into its plain components and rebuild from them.
        // If any field had enum / complex types this would not compile as plain data.
        val rebuilt = PagedListState(
            filters = saved.filters.toMap(),
            sorts = saved.sorts.toMap(),
            caseSensitive = saved.caseSensitive.toMap(),
            pageSize = saved.pageSize,
            isDistinct = saved.isDistinct
        )
        assertEquals(saved, rebuilt)

        // All sort entries are the sentinel strings, not enum-style values.
        for (value in rebuilt.sorts.values)
            assertTrue(
                value == PagedListSort.ASC || value == PagedListSort.DESC,
                "unexpected sort value: $value"
            )

        // Apply the rebuilt state to a fresh list and verify everything came back.
        val list2 = PagedList<TestC>()
        val name2 = list2.addColumn("name")
        val id2 = list2.addColumn("id")
        list2.restoreState(rebuilt)

        assertEquals("Item", name2.filter)
        assertEquals(Column.DESCENDING, name2.sort)
        assertTrue(name2.isCaseSensitive)
        assertEquals(Column.ASCENDING, id2.sort)
        assertEquals(25, list2.pageSize)
        assertTrue(list2.isDistinct)
    }
}
