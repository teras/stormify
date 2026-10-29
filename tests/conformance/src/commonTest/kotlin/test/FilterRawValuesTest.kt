package test

import onl.ycode.stormify.pagedList
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.generated.Tables.Child_
import kotlin.test.*

open class FilterRawValuesTest {
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
                Child(2, "Beta", alpha),     // same text as a parent name
                Child(3, "Cherry", beta),
                Child(4, null, null),        // NULL child name and NULL parent
            )
        )
    }

    @Test
    fun testUnionOfAllPathsWithNullLast() = withDb("RAW-UNION") { s ->
        setupParentChild(s)
        val list = pagedList<Child>().facet(Child_.parent.name, Child_.name)
        val values = list.getFacet(0).getFilterRawValues()
        assertEquals(listOf("Alpha", "Apple", "Beta", "Cherry", null), values.toList())
        assertEquals(5, values.size)
    }

    @Test
    fun testPagingAcrossTheUnion() = withDb("RAW-PAGING") { s ->
        setupParentChild(s)
        val list = pagedList<Child>().facet(Child_.parent.name, Child_.name)
        val values = list.getFacet(0).getFilterRawValues()
        values.pageSize = 2
        assertEquals(null, values[4])
        assertEquals("Alpha", values[0])
        assertEquals("Cherry", values[3])
        assertEquals("Beta", values[2])
        assertFailsWith<IndexOutOfBoundsException> { values[5] }
    }

    @Test
    fun testOtherFacetFiltersAndInvalidation() = withDb("RAW-FILTERED") { s ->
        setupParentChild(s)
        val list = pagedList<Child>()
            .facet(Child_.parent.name, Child_.name)
            .facet("id")
        val values = list.getFacet(0).getFilterRawValues()
        assertEquals(5, values.size)
        list.getFacet(1).filter = "<= 2"
        assertEquals(listOf("Alpha", "Apple", "Beta"), values.toList())
        // Its own filter does not restrict the values
        list.getFacet(0).filter = "Apple"
        assertEquals(listOf("Alpha", "Apple", "Beta"), values.toList())
        // The list itself still works after the extra joins of the values query
        assertEquals(listOf(1), list.map { it.id })
    }

    @Test
    fun testValuesAreConvertedToTheFieldType() = withDb("RAW-TYPED") { s ->
        setupParentChild(s)
        val list = pagedList<Child>().facet("parent.id").facet("id")
        assertEquals(listOf<Any?>(1, 2, null), list.getFacet(0).getFilterRawValues().toList())
        assertEquals(listOf<Any?>(1, 2, 3, 4), list.getFacet(1).getFilterRawValues().toList())
        assertTrue(list.getFacet(1).getFilterRawValues().all { it is Int })
    }

    @Test
    fun testEnumFieldGivesEnumConstantsAndMapFacetGivesStoredValues() = withDb("RAW-ENUM") { s ->
        TestDDL.dropTable("enum_test")
        s.executeUpdate(
            TestDDL.createTable(
                "enum_test",
                "${TestDDL.intPrimaryKey("id")}, ${TestDDL.intColumn("plain_status")}, ${TestDDL.intColumn("custom_status")}"
            )
        )
        s.create(EnumEntity(1, PlainStatus.ACTIVE, CustomStatus.BANNED))
        s.create(EnumEntity(2, PlainStatus.BANNED, CustomStatus.ACTIVE))
        s.create(EnumEntity(3, null, CustomStatus.ACTIVE))

        val list = pagedList<EnumEntity>()
            .facet("plainStatus")
            .facet(mapOf("one" to 1, "two" to 2, "three" to 3), "id")
        assertEquals(listOf<Any?>(PlainStatus.ACTIVE, PlainStatus.BANNED, null), list.getFacet(0).getFilterRawValues().toList())
        assertEquals(listOf<Any?>(1, 2, 3), list.getFacet(1).getFilterRawValues().toList())
    }

    @Test
    fun testSqlFacetGivesDriverValues() = withDb("RAW-SQL") { s ->
        setupParentChild(s)
        val list = pagedList<Child>()
        list.addSqlFacet("child.name")
        assertEquals(listOf("Apple", "Beta", "Cherry", null), list.getFacet(0).getFilterRawValues().toList())
    }

    @Test
    fun testPathsOfDifferentTypesAreRejected() = withDb("RAW-MIXED") { s ->
        setupParentChild(s)
        val list = pagedList<Child>().facet("name", "id")
        val e = assertFailsWith<IllegalArgumentException> { list.getFacet(0).getFilterRawValues() }
        assertTrue("different types" in e.message!!, e.message)
        // Same type through different paths is fine
        assertEquals(listOf<Any?>(1, 2, 3, 4, null), pagedList<Child>().facet("id", "parent.id").getFacet(0).getFilterRawValues().toList())
    }
}
