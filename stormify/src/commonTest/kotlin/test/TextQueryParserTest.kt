package test

import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.biglist.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextQueryParserTest {

    private fun parse(input: String): TextNode? = TextQueryParser.parse(input)

    private fun render(input: String, caseSensitive: Boolean = false): Pair<String, List<Any>> {
        val args = mutableListOf<Any>()
        val collector = SqlArgsCollector { args.add(it) }
        val sql = TextQueryParser.parseAndRender(input, "col", SqlDialect.SQLITE, caseSensitive, collector)
        return (sql ?: "1 = 0") to args
    }

    // --- AST structure tests ---

    @Test
    fun singleTerm() {
        val node = parse("foo")
        assertEquals(TermNode("foo"), node)
    }

    @Test
    fun implicitAnd() {
        val node = parse("foo bar")
        assertEquals(AndNode(listOf(TermNode("foo"), TermNode("bar"))), node)
    }

    @Test
    fun explicitOr() {
        val node = parse("foo OR bar")
        assertEquals(OrNode(listOf(TermNode("foo"), TermNode("bar"))), node)
    }

    @Test
    fun negation() {
        val node = parse("-foo")
        assertEquals(NotNode(TermNode("foo")), node)
    }

    @Test
    fun phrase() {
        val node = parse("\"foo bar\"")
        assertEquals(PhraseNode("foo bar"), node)
    }

    @Test
    fun negatedPhrase() {
        val node = parse("-\"foo bar\"")
        assertEquals(NotNode(PhraseNode("foo bar")), node)
    }

    @Test
    fun grouping() {
        val node = parse("(foo OR bar) baz")
        assertEquals(
            AndNode(listOf(OrNode(listOf(TermNode("foo"), TermNode("bar"))), TermNode("baz"))),
            node
        )
    }

    @Test
    fun precedenceAndOverOr() {
        val node = parse("a OR b c")
        assertEquals(
            OrNode(listOf(TermNode("a"), AndNode(listOf(TermNode("b"), TermNode("c"))))),
            node
        )
    }

    @Test
    fun nestedGroups() {
        val node = parse("(a OR (b c))")
        assertEquals(
            OrNode(listOf(TermNode("a"), AndNode(listOf(TermNode("b"), TermNode("c"))))),
            node
        )
    }

    @Test
    fun wildcardPreserved() {
        val node = parse("foo*")
        assertEquals(TermNode("foo*"), node)
    }

    @Test
    fun literalHyphenInsideWord() {
        val node = parse("part-number")
        assertEquals(TermNode("part-number"), node)
    }

    @Test
    fun lowercaseOrIsLiteral() {
        val node = parse("foo or bar")
        assertEquals(AndNode(listOf(TermNode("foo"), TermNode("or"), TermNode("bar"))), node)
    }

    @Test
    fun emptyInput() {
        assertNull(parse(""))
        assertNull(parse("   "))
    }

    @Test
    fun unclosedQuote() {
        val node = parse("\"foo")
        assertEquals(TermNode("\"foo"), node)
    }

    @Test
    fun unclosedQuoteWithMore() {
        val node = parse("\"foo bar")
        assertEquals(TermNode("\"foo bar"), node)
    }

    @Test
    fun unbalancedParen() {
        val node = parse("(foo")
        assertEquals(TermNode("foo"), node)
    }

    @Test
    fun trailingOr() {
        val node = parse("foo OR")
        assertEquals(TermNode("foo"), node)
    }

    @Test
    fun leadingOr() {
        val node = parse("OR foo")
        assertEquals(AndNode(listOf(TermNode("OR"), TermNode("foo"))), node)
    }

    @Test
    fun onlyMinus() {
        val node = parse("-")
        assertEquals(TermNode("-"), node)
    }

    @Test
    fun onlyOr() {
        val (sql, _) = render("OR")
        val node = parse("OR")
        assertEquals(TermNode("OR"), node)
    }

    @Test
    fun multipleOrTerms() {
        val node = parse("a OR b OR c")
        assertEquals(OrNode(listOf(TermNode("a"), TermNode("b"), TermNode("c"))), node)
    }

    @Test
    fun complexExpression() {
        val node = parse("(error OR fail) -timeout \"connection reset\"")
        assertEquals(
            AndNode(
                listOf(
                    OrNode(listOf(TermNode("error"), TermNode("fail"))),
                    NotNode(TermNode("timeout")),
                    PhraseNode("connection reset")
                )
            ),
            node
        )
    }

    // --- Render tests ---

    @Test
    fun renderSingleTerm() {
        val (sql, args) = render("foo")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo%"), args)
    }

    @Test
    fun renderWildcardPrefix() {
        val (sql, args) = render("*foo")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo"), args)
    }

    @Test
    fun renderWildcardSuffix() {
        val (sql, args) = render("foo*")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("foo%"), args)
    }

    @Test
    fun renderPhrase() {
        val (sql, args) = render("\"foo bar\"")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo bar%"), args)
    }

    @Test
    fun renderAnd() {
        val (sql, args) = render("foo bar")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\' AND LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo%", "%bar%"), args)
    }

    @Test
    fun renderOr() {
        val (sql, args) = render("foo OR bar")
        assertEquals("(LOWER(col) LIKE ? ESCAPE '\\' OR LOWER(col) LIKE ? ESCAPE '\\')", sql)
        assertEquals(listOf("%foo%", "%bar%"), args)
    }

    @Test
    fun renderNot() {
        val (sql, args) = render("-foo")
        assertEquals("NOT (LOWER(col) LIKE ? ESCAPE '\\')", sql)
        assertEquals(listOf("%foo%"), args)
    }

    @Test
    fun renderComplex() {
        val (sql, args) = render("(a OR b) -c")
        assertEquals(
            "(LOWER(col) LIKE ? ESCAPE '\\' OR LOWER(col) LIKE ? ESCAPE '\\') AND NOT (LOWER(col) LIKE ? ESCAPE '\\')",
            sql
        )
        assertEquals(listOf("%a%", "%b%", "%c%"), args)
    }

    @Test
    fun renderCaseSensitive() {
        val (sql, args) = render("foo", caseSensitive = true)
        assertEquals("col LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo%"), args)
    }

    // --- Security tests ---

    @Test
    fun sqlInjectionAttempt() {
        val (sql, args) = render("'; DROP TABLE test; --")
        // Spaces split into AND terms: "';", "DROP", "TABLE", "test;", "--"
        // All go as bound params, no SQL injection possible
        assertTrue(sql.contains("LIKE ?"))
        assertTrue(args.size >= 1)
        assertTrue(args.all { it is String })
        // The raw SQL injection chars are just literal pattern text
        assertTrue(args.any { (it as String).contains("';") || (it as String).contains("drop") })
    }

    @Test
    fun likeMetacharPercent() {
        val (sql, args) = render("50%")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%50\\%%"), args)
    }

    @Test
    fun likeMetacharUnderscore() {
        val (sql, args) = render("foo_bar")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo\\_bar%"), args)
    }

    @Test
    fun backslashInInput() {
        val (sql, args) = render("foo\\bar")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%foo\\\\bar%"), args)
    }

    @Test
    fun unicodeInput() {
        val (sql, args) = render("αλίκη")
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(listOf("%αλίκη%"), args)
    }

    @Test
    fun emptyReturnsImpossible() {
        val (sql, _) = render("")
        assertEquals("1 = 0", sql)
    }

    @Test
    fun maxInputLengthTruncated() {
        val longInput = "a".repeat(2000)
        val (sql, args) = render(longInput)
        assertEquals("LOWER(col) LIKE ? ESCAPE '\\'", sql)
        assertEquals(1000 + 2, (args[0] as String).length) // 1000 chars + %...%
    }

    @Test
    fun deepNesting() {
        val input = "(" .repeat(25) + "foo" + ")".repeat(25)
        val (sql, _) = render(input)
        // Should not crash — either renders or returns impossible
        assertTrue(sql.isNotEmpty())
    }
}
