// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.pagedList
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.generated.Tables.TestC_
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The filter values of a facet follow the filters of the other facets, not its own,
 * and the same values instance reads the new values after every filter change.
 */
open class CrossFacetFilterValuesTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(); test(it)
    }

    /** Twelve rows: ids 1..3 named AA, 4..6 AB, 7..9 BB, 10..12 CC. */
    private fun setup(s: Stormify) {
        TestDDL.dropTable("child")
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        val names = listOf("AA", "AB", "BB", "CC")
        s.create((1..12).map { TestC(it, names[(it - 1) / 3]) })
    }

    private fun ids(range: IntRange) = range.map { it.toString() }

    @Test
    fun theValuesFollowTheOtherFacetsFilter() = withDb("CROSS-FACET-VALUES") { s ->
        setup(s)
        val list = pagedList<TestC>().facet(TestC_.name).facet(TestC_.id)
        val name = list.getFacet(0)
        val id = list.getFacet(1)
        val values = id.getFilterValues()
        val raw = id.getFilterRawValues()

        fun check(expected: IntRange?) {
            val range = expected ?: IntRange.EMPTY
            assertEquals(ids(range), values.toList(), "values with name filter '${name.filter}'")
            assertEquals(range.toList(), raw.toList(), "raw values with name filter '${name.filter}'")
            // The same instances are handed out again
            assertEquals(ids(range), id.getFilterValues().toList())
        }

        check(1..12)
        name.filter = "A"
        check(1..6)
        name.filter = ""
        check(1..12)
        name.filter = "B"
        check(4..9)
        name.filter = "C"
        check(10..12)
        name.filter = "D"
        check(null)
        name.filter = null
        check(1..12)
    }

    @Test
    fun aFacetsOwnFilterDoesNotRestrictItsValues() = withDb("CROSS-FACET-OWN") { s ->
        setup(s)
        val list = pagedList<TestC>().facet(TestC_.name).facet(TestC_.id)
        val name = list.getFacet(0)
        val id = list.getFacet(1)
        val idValues = id.getFilterValues()
        val nameValues = name.getFilterValues()
        assertEquals(listOf("AA", "AB", "BB", "CC"), nameValues.toList())
        id.filter = "5"
        assertEquals(ids(1..12), idValues.toList())
        assertEquals((1..12).toList(), id.getFilterRawValues().toList())
        assertEquals(listOf("AB"), nameValues.toList())
        assertEquals(listOf(5), list.map { it.id })
    }

    /** With several filters active, each facet lists the values the other facets' filters leave. */
    @Test
    fun severalFiltersCombine() = withDb("CROSS-FACET-SEVERAL") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        try {
            s.create(listOf(
                CamelEntity(1, "Ann", "Smith"),
                CamelEntity(2, "Bob", "Smith"),
                CamelEntity(3, "Ann", "Jones"),
                CamelEntity(4, "Cid", "Jones"),
                CamelEntity(5, "Bob", "Brown"),
                CamelEntity(6, "Ann", "Brown"),
            ))
            val list = pagedList<CamelEntity>().facet("firstName").facet("lastName").facet("id")
            val first = list.getFacet(0)
            val last = list.getFacet(1)
            val id = list.getFacet(2)
            val firstValues = first.getFilterValues()
            val lastValues = last.getFilterValues()
            val idValues = id.getFilterRawValues()

            fun check(firsts: List<String>, lasts: List<String>, ids: List<Int>, rows: List<Int>) {
                val state = "first '${first.filter}', last '${last.filter}', id '${id.filter}'"
                assertEquals(firsts, firstValues.toList(), "first names with $state")
                assertEquals(lasts, lastValues.toList(), "last names with $state")
                assertEquals(ids, idValues.toList(), "ids with $state")
                assertEquals(rows, list.map { it.id }, "rows with $state")
            }

            check(listOf("Ann", "Bob", "Cid"), listOf("Brown", "Jones", "Smith"), (1..6).toList(), (1..6).toList())
            first.filter = "Ann"
            check(listOf("Ann", "Bob", "Cid"), listOf("Brown", "Jones", "Smith"), listOf(1, 3, 6), listOf(1, 3, 6))
            last.filter = "Smith"
            check(listOf("Ann", "Bob"), listOf("Brown", "Jones", "Smith"), listOf(1), listOf(1))
            id.filter = ">= 2"
            check(listOf("Bob"), listOf("Brown", "Jones"), listOf(1), emptyList())
            first.filter = null
            check(listOf("Bob"), listOf("Brown", "Jones", "Smith"), listOf(1, 2), listOf(2))
        } finally {
            TestDDL.dropTable("camel_entity")
        }
    }

    /** Three facets: a second filter added on top of the first, then both removed in turn. */
    @Test
    fun aSecondFilterAddedAndBothRemoved() = withDb("CROSS-FACET-ADD-REMOVE") { s ->
        TestDDL.dropTable("camel_entity")
        s.executeUpdate(TestDDL.createTable("camel_entity",
            "${TestDDL.intPrimaryKey("id")}, first_name ${TestDDL.textType()}, last_name ${TestDDL.textType()}"))
        try {
            // firstName is the name (AA, AB, BB, CC by three), lastName the tag: x on 1, 4, 7
            val names = listOf("AA", "AB", "BB", "CC")
            s.create((1..12).map { CamelEntity(it, names[(it - 1) / 3], if (it in setOf(1, 4, 7)) "x" else "y") })
            val list = pagedList<CamelEntity>().facet("firstName").facet("lastName").facet("id")
            val name = list.getFacet(0)
            val tag = list.getFacet(1)
            val id = list.getFacet(2)
            val nameValues = name.getFilterValues()
            val nameRaw = name.getFilterRawValues()
            val tagValues = tag.getFilterValues()
            val idValues = id.getFilterValues()
            val idRaw = id.getFilterRawValues()

            fun check(ids: List<Int>, names: List<String>, tags: List<String>) {
                val state = "name '${name.filter}', tag '${tag.filter}'"
                assertEquals(ids.map { it.toString() }, idValues.toList(), "ids with $state")
                assertEquals(ids, idRaw.toList(), "raw ids with $state")
                assertEquals(names, nameValues.toList(), "names with $state")
                assertEquals(names, nameRaw.toList(), "raw names with $state")
                assertEquals(tags, tagValues.toList(), "tags with $state")
            }

            name.filter = "A"
            check((1..6).toList(), listOf("AA", "AB", "BB", "CC"), listOf("x", "y"))
            tag.filter = "x"
            check(listOf(1, 4), listOf("AA", "AB", "BB"), listOf("x", "y"))
            name.filter = null
            check(listOf(1, 4, 7), listOf("AA", "AB", "BB"), listOf("x", "y"))
            tag.filter = null
            check((1..12).toList(), listOf("AA", "AB", "BB", "CC"), listOf("x", "y"))
        } finally {
            TestDDL.dropTable("camel_entity")
        }
    }
}
