package test

import onl.ycode.stormify.pagedList
import onl.ycode.logger.LogLevel
import onl.ycode.logger.WatchLogger
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.SqlArgsCollector
import onl.ycode.stormify.biglist.TextFolding
import onl.ycode.stormify.biglist.TextQuery
import kotlin.test.*

/** Uppercases and drops dashes, on both sides of the comparison. */
private object UpperNoDash : TextFolding {
    override fun column(columnExpr: String, dialect: SqlDialect) = "REPLACE(UPPER($columnExpr), '-', '')"
    override fun value(text: String) = text.uppercase().replace("-", "")
}

/** Uppercases and drops every punctuation mark, the wildcard included if it ever saw one. */
private object UpperNoPunctuation : TextFolding {
    override fun column(columnExpr: String, dialect: SqlDialect) = "REPLACE(UPPER($columnExpr), '-', '')"
    override fun value(text: String) = text.uppercase().filter { it.isLetterOrDigit() || it == ' ' }
}

open class TextFoldingTest {
    private fun withDb(name: String, test: (Stormify) -> Unit) = TestHelper.withDb(name) {
        it.asDefault(); test(it)
    }

    private fun render(dialect: SqlDialect, folding: TextFolding = TextFolding.DEFAULT): Pair<String, List<Any>> {
        val args = mutableListOf<Any>()
        val sql = TextQuery.parseAndRender("Ab-C", "col", dialect, false, SqlArgsCollector { args.add(it) }, folding = folding)
        return sql!! to args
    }

    @Test
    fun defaultFoldingKeepsTheDialectBehavior() {
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'" to listOf<Any>("%ab-c%"), render(SqlDialect.SQLITE))
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'" to listOf<Any>("%ab-c%"), render(SqlDialect.ORACLE_OLD))
        assertEquals("col ILIKE ? ESCAPE '\\'" to listOf<Any>("%Ab-C%"), render(SqlDialect.POSTGRESQL))
        assertEquals("col LIKE ?" to listOf<Any>("%Ab-C%"), render(SqlDialect.SQL_SERVER_NEW).let { (s, a) -> s.substringBefore(" ESCAPE") to a })
    }

    @Test
    fun customFoldingRendersItsExpressions() {
        assertEquals(
            "REPLACE(UPPER(col), '-', '') LIKE ? ESCAPE '\\'" to listOf<Any>("%ABC%"),
            render(SqlDialect.SQLITE, UpperNoDash)
        )
    }

    @Test
    fun defaultInstanceFoldingIsTheDefault() = withDb("FOLDING-DEFAULT") { s ->
        assertSame(TextFolding.DEFAULT, s.textFolding)
    }

    private fun setup(s: Stormify) {
        TestDDL.dropTable("test")
        s.executeUpdate(TestDDL.createTable("test", "${TestDDL.intPrimaryKey("id")}, name ${TestDDL.textType()}"))
        s.create(listOf(TestC(1, "Ab-c"), TestC(2, "abc"), TestC(3, "ABX"), TestC(4, "x-b-c-y")))
    }

    @Test
    fun customFoldingFiltersText() = withDb("FOLDING-TEXT") { s ->
        setup(s)
        val list = pagedList<TestC>().facet("name")
        val facet = list.getFacet(0)

        facet.filter = "bc"
        assertEquals(listOf(2), list.map { it.id }, "default folding keeps the dash")

        s.textFolding = UpperNoDash
        list.refresh()
        val selects = mutableListOf<String>()
        val previous = s.logger
        s.logger = WatchLogger(previous) { _, message, _ ->
            if (message.trimStart().startsWith("SELECT", ignoreCase = true)) selects += message
        }.also { it.level = LogLevel.DEBUG }
        try {
            assertEquals(listOf(1, 2, 4), list.map { it.id })
        } finally {
            s.logger = previous
        }
        assertTrue(selects.any { "REPLACE(UPPER(test.name), '-', '') LIKE ?" in it }, selects.toString())

        // Case-sensitive facets do not fold
        facet.isCaseSensitive = true
        assertEquals(listOf(2), list.map { it.id })
    }

    @Test
    fun customFoldingMatchesEnumNames() = withDb("FOLDING-ENUM") { s ->
        setup(s)
        val list = pagedList<TestC>().facet(mapOf("Ab-Cd" to 1, "Xy" to 3), "id")
        val facet = list.getFacet(0)

        facet.filter = "abcd"
        assertEquals(emptyList(), list.map { it.id })
        facet.filter = "AB-CD"
        assertEquals(listOf(1), list.map { it.id }, "default folding ignores case")
        facet.filter = "\"ab-cd\""
        assertEquals(listOf(1), list.map { it.id })

        s.textFolding = UpperNoDash
        list.refresh()
        facet.filter = "abcd"
        assertEquals(listOf(1), list.map { it.id })
        facet.filter = "\"a-bcd\""
        assertEquals(listOf(1), list.map { it.id })
        facet.filter = "\"abc\""
        assertEquals(emptyList(), list.map { it.id })
    }

    @Test
    fun foldingDoesNotSeeTheWildcards() {
        fun args(input: String): List<Any> {
            val out = mutableListOf<Any>()
            TextQuery.parseAndRender(input, "col", SqlDialect.SQLITE, false, SqlArgsCollector { out.add(it) }, folding = UpperNoPunctuation)
            return out
        }
        assertEquals(listOf<Any>("AB%"), args("a-b*"))
        assertEquals(listOf<Any>("%AB%CD"), args("*a.b*c-d"))
        assertEquals(listOf<Any>("%AB%"), args("a-b"))
        assertEquals(listOf<Any>("%A B%"), args("\"a- b!\""))
    }

    @Test
    fun foldingKeepsStartsWithOnTheDatabase() = withDb("FOLDING-WILDCARD") { s ->
        setup(s)
        s.textFolding = UpperNoPunctuation
        val list = pagedList<TestC>().facet("name")
        list.getFacet(0).filter = "a-b*"
        assertEquals(listOf(1, 2, 3), list.map { it.id })   // Ab-c, abc, ABX; not x-b-c-y
        list.getFacet(0).filter = "*b-c"
        assertEquals(listOf(1, 2), list.map { it.id })
    }
}
