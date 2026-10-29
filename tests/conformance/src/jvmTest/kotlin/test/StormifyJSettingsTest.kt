// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package test

import onl.ycode.stormify.biglist.InputParser
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.StormifyJ
import onl.ycode.stormify.biglist.Facet
import onl.ycode.stormify.biglist.FilterSyntax
import onl.ycode.stormify.biglist.PagedList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The Java wrapper exposes the instance settings of the underlying Stormify, and
 * setting them through the wrapper changes the behavior of the lists attached to it.
 */
class StormifyJSettingsTest {

    private fun withJ(name: String, test: (StormifyJ) -> Unit) = TestHelper.withDb(name) { s ->
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Alice"), TestC(2, "Bob"), TestC(3, "Charlie")))
        val j = StormifyJ(s.dataSource)
        j.registerPrimaryKeyResolver(0) { _, field -> field.lowercase().startsWith("id") }
        test(j)
    }

    @Test
    fun settingsRoundTrip() = withJ("STORMIFYJ-SETTINGS") { j ->
        val detected = j.sqlDialect
        val other = if (detected == SqlDialect.FAILSAFE) SqlDialect.UNKNOWN else SqlDialect.FAILSAFE
        j.sqlDialect = other
        assertSame(other, j.sqlDialect)
        j.sqlDialect = detected

        j.cursorFetchSize = 17
        assertEquals(17, j.cursorFetchSize)

        val syntax = FilterSyntax(or = "|")
        j.filterSyntax = syntax
        assertSame(syntax, j.filterSyntax)

        assertNull(j.inputParser)
        val parser = InputParser { input, _ -> input }
        j.inputParser = parser
        assertSame(parser, j.inputParser)
        j.inputParser = null
        assertNull(j.inputParser)
    }

    @Test
    fun filterSyntaxAppliesToAttachedLists() = withJ("STORMIFYJ-FILTER-SYNTAX") { j ->
        j.filterSyntax = FilterSyntax(or = "|")
        val list = j.pagedList(TestC::class.java)
        list.addFacet("name").filter = "Alice | Bob"
        assertEquals(2, list.size)
    }

    @Test
    fun inputParserAppliesToAttachedLists() = withJ("STORMIFYJ-INPUT-PARSER") { j ->
        j.inputParser = InputParser { input, type -> if (type == Facet.Type.NUMERIC && input == "two") "2" else input }
        val list = j.pagedList(TestC::class.java)
        list.addFacet(Facet.Type.NUMERIC, "id").filter = "two"
        assertEquals(listOf("Bob"), list.map { it.name })
    }
}
