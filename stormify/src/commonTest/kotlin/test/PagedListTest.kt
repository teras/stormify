@file:OptIn(kotlin.time.ExperimentalTime::class)

package test

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.Column
import onl.ycode.stormify.biglist.PagedList
import kotlin.test.*

class PagedListTest {
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
        col.filter = PagedList.NULL
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
        val col = list.addColumn("name", type = Column.ENUM, enumValues = nameMap)
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
        val col = list.addColumn("name", type = Column.ENUM, enumValues = nameMap)
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
        val col = list.addColumn("name", type = Column.ENUM, enumValues = nameMap)
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
        list.invalidate()
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
        val values = col.getSelectionValues()

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
        val firstNames = firstNameCol.getSelectionValues()
        assertEquals(3, firstNames.size) // Alice, Bob, Charlie

        // Filter lastName to "Smith" — only Alice and Bob have Smith
        lastNameCol.filter = "Smith"
        val filteredFirstNames = firstNameCol.getSelectionValues()
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
        val values = col.getSelectionValues()
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

        val values = col.getSelectionValues()
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
        val col = list.addRawColumn("test.id", Column.RAW,
            sqlGenerator = { column, value, args ->
                val mod = value.toIntOrNull() ?: 0
                args(mod)
                if (isOracle) "MOD($column, ?) = 0" else "$column % ? = 0"
            })
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
        col.inputParser = { input, _ -> input.replace(".", "") }
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
        list.inputParser = { input, type ->
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
            PagedList.defaultInputParser = { input, type ->
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
            PagedList.defaultInputParser = { input, _ -> input.replace(",", ".") }

            setupTable(s, 20)
            val list = PagedList<TestC>()
            // List: replace dot
            list.inputParser = { input, _ -> input.replace(".", "") }

            val col = list.addColumn("id")
            // Column parser wins over list parser
            col.inputParser = { input, _ -> input.replace("X", "1") }
            col.filter = "> X8"  // column parser: "X8" → "18"

            assertEquals(2, list.size) // ids 19, 20
        } finally {
            PagedList.defaultInputParser = oldParser
        }
    }

    // --- Date InputParser ---

    private val dateParser: (String, Column.Type) -> String = { input, _ ->
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
        val displayMap = HRStatus.entries.associate { it.displayName() to it.name }
        val col = list.addColumn("name", type = Column.ENUM, enumValues = displayMap)
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
}
