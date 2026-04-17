// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.SqlDialect

/**
 * AST node produced by [TextQuery.parse]. Custom [Converter] implementations
 * can pattern-match on these nodes to render the filter as dialect-specific SQL.
 */
sealed interface TextNode

/** A single search term, possibly with wildcards (`*`). */
data class TermNode(
    /** The raw text of the term, including any wildcards. */
    val text: String
) : TextNode

/** A quoted phrase — treated as literal substring (operators and wildcards inside are ignored). */
data class PhraseNode(
    /** The literal content between the phrase delimiters. */
    val text: String
) : TextNode

/** The null sentinel — matches rows where the column is SQL `NULL`. */
data object NullNode : TextNode

/** Negation of a child node. */
data class NotNode(
    /** The negated subtree. */
    val child: TextNode
) : TextNode

/** Conjunction (AND) of multiple children. */
data class AndNode(
    /** Operands joined by AND. */
    val children: List<TextNode>
) : TextNode

/** Disjunction (OR) of multiple children. */
data class OrNode(
    /** Operands joined by OR. */
    val children: List<TextNode>
) : TextNode

/**
 * Configurable token set for [TextQuery] parsing. Lets you localise the
 * boolean search syntax — e.g. use `|` for OR instead of `OR`, `--` for NULL,
 * or Greek/German keywords.
 *
 * Word-like tokens (containing letters, like `OR` or `NULL`) require word
 * boundaries to match (so `OR` in `CONSTRUCTOR` is not mistaken for the
 * operator). Symbol-like tokens (`|`, `-`, `--`) match at any position.
 *
 * Longest match wins among overlapping tokens: configuring `not = "-"` and
 * `nullToken = "--"` is fine — `--` is tried before `-`.
 */
data class FilterSyntax(
    /** OR operator. Default `"OR"`. */
    val or: String = "OR",
    /** NOT operator. Default `"-"`. */
    val not: String = "-",
    /** Token that matches SQL `NULL`. Default `"NULL"`. */
    val nullToken: String = "NULL",
    /** Character that opens/closes a phrase. Default `"`. */
    val phraseDelimiter: Char = '"',
    /** Grouping open character. Default `(`. */
    val groupOpen: Char = '(',
    /** Grouping close character. Default `)`. */
    val groupClose: Char = ')',
    /** Wildcard character, converted to SQL `%` in LIKE patterns. Default `*`. */
    val wildcard: Char = '*',
) {
    init {
        require(or.isNotEmpty()) { "FilterSyntax.or must not be empty" }
        require(not.isNotEmpty()) { "FilterSyntax.not must not be empty" }
        require(nullToken.isNotEmpty()) { "FilterSyntax.nullToken must not be empty" }
    }
}

private const val MAX_INPUT_LENGTH = 1000
private const val MAX_TOKENS = 100
private const val MAX_DEPTH = 20

private enum class TokenType { WORD, QUOTED, LPAREN, RPAREN, OR, NOT, NULL_TOKEN }
private data class Token(val type: TokenType, val value: String = "")

/**
 * Google-like boolean query parser and renderer for facet filters.
 *
 * Grammar (default syntax):
 * - `foo bar` — implicit AND
 * - `foo OR bar` — OR (uppercase keyword)
 * - `-foo` — NOT
 * - `"foo bar"` — phrase (literal substring)
 * - `(foo OR bar)` — grouping
 * - `foo*`, `*foo` — LIKE prefix/suffix wildcard
 * - `NULL` — SQL NULL match
 *
 * Precedence: AND binds tighter than OR. Tokens are configurable via [FilterSyntax].
 *
 * Used internally by the default text converter. Custom [Converter]
 * implementations can call [parse] to walk the AST directly.
 */
object TextQuery {

    /** Default token configuration — English Google-like syntax. */
    val defaultSyntax = FilterSyntax()

    /**
     * Parses [input] into a [TextNode] AST, or returns `null` if empty/malformed.
     * Respects input-length, token-count, and nesting-depth safety limits.
     */
    fun parse(input: String, syntax: FilterSyntax = defaultSyntax): TextNode? {
        val trimmed = input.take(MAX_INPUT_LENGTH).trim()
        if (trimmed.isEmpty()) return null
        val tokens = tokenize(trimmed, syntax)
        if (tokens.isEmpty()) return null
        return Parser(tokens).parse()
    }

    /**
     * Renders [ast] to SQL using a caller-supplied [atomRenderer] that handles
     * [TermNode] and [PhraseNode] leaves. Boolean composition (AND/OR/NOT) and
     * [NullNode] are emitted as standard SQL.
     */
    fun renderAst(
        ast: TextNode,
        column: String,
        atomRenderer: (text: String, isPhrase: Boolean) -> String
    ): String = when (ast) {
        is TermNode -> atomRenderer(ast.text, false)
        is PhraseNode -> atomRenderer(ast.text, true)
        is NullNode -> "$column IS NULL"
        is NotNode -> "NOT (${renderAst(ast.child, column, atomRenderer)})"
        is AndNode -> ast.children.joinToString(" AND ") { renderAst(it, column, atomRenderer) }
        is OrNode -> "(${ast.children.joinToString(" OR ") { renderAst(it, column, atomRenderer) }})"
    }

    /**
     * Convenience combining [parse] + [renderAst] with the default LIKE atom
     * renderer. Returns `null` for empty/malformed input.
     */
    fun parseAndRender(
        input: String,
        column: String,
        dialect: SqlDialect,
        caseSensitive: Boolean,
        args: SqlArgsCollector,
        syntax: FilterSyntax = defaultSyntax
    ): String? {
        val ast = parse(input, syntax) ?: return null
        return renderAst(ast, column) { text, isPhrase ->
            renderLikeTerm(text, isPhrase, column, dialect, caseSensitive, args)
        }
    }

    private data class SpecialToken(val pattern: String, val type: TokenType, val needsBoundary: Boolean)

    private fun tokenize(input: String, syntax: FilterSyntax): List<Token> {
        val specials = listOf(
            SpecialToken(syntax.nullToken, TokenType.NULL_TOKEN, syntax.nullToken.any { it.isLetterOrDigit() }),
            SpecialToken(syntax.or, TokenType.OR, syntax.or.any { it.isLetterOrDigit() }),
            SpecialToken(syntax.not, TokenType.NOT, syntax.not.any { it.isLetterOrDigit() }),
        ).sortedByDescending { it.pattern.length }

        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length && tokens.size < MAX_TOKENS) {
            when {
                input[i].isWhitespace() -> i++
                input[i] == syntax.groupOpen -> { tokens.add(Token(TokenType.LPAREN)); i++ }
                input[i] == syntax.groupClose -> { tokens.add(Token(TokenType.RPAREN)); i++ }
                input[i] == syntax.phraseDelimiter -> {
                    val end = input.indexOf(syntax.phraseDelimiter, i + 1)
                    if (end < 0) {
                        val rest = input.substring(i)
                        tokens.add(Token(TokenType.WORD, rest))
                        i = input.length
                    } else {
                        tokens.add(Token(TokenType.QUOTED, input.substring(i + 1, end)))
                        i = end + 1
                    }
                }
                else -> {
                    val matched = specials.firstOrNull { sp ->
                        input.startsWith(sp.pattern, i) && hasValidBoundary(input, i, sp)
                    }
                    if (matched != null) {
                        tokens.add(Token(matched.type))
                        i += matched.pattern.length
                    } else {
                        val word = readWord(input, i, syntax)
                        tokens.add(Token(TokenType.WORD, word))
                        i += word.length
                    }
                }
            }
        }
        return tokens
    }

    private fun hasValidBoundary(input: String, pos: Int, sp: SpecialToken): Boolean {
        if (!sp.needsBoundary) return true
        val before = pos == 0 || !input[pos - 1].isLetterOrDigit()
        val afterPos = pos + sp.pattern.length
        val after = afterPos >= input.length || !input[afterPos].isLetterOrDigit()
        return before && after
    }

    private fun readWord(input: String, start: Int, syntax: FilterSyntax): String {
        var i = start
        while (i < input.length && !input[i].isWhitespace()
            && input[i] != syntax.groupOpen && input[i] != syntax.groupClose
            && input[i] != syntax.phraseDelimiter
        ) {
            i++
        }
        return input.substring(start, i)
    }

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun parse(): TextNode? {
            val result = parseOrExpr(0)
            return result
        }

        private fun peek(): Token? = if (pos < tokens.size) tokens[pos] else null
        private fun advance(): Token = tokens[pos++]

        private fun parseOrExpr(depth: Int): TextNode? {
            if (depth > MAX_DEPTH) return null
            val first = parseAndExpr(depth) ?: return null
            val parts = mutableListOf(first)
            while (peek()?.type == TokenType.OR) {
                advance()
                val next = parseAndExpr(depth) ?: break
                parts.add(next)
            }
            return if (parts.size == 1) parts[0] else OrNode(parts)
        }

        private fun parseAndExpr(depth: Int): TextNode? {
            if (depth > MAX_DEPTH) return null
            val first = parseFactor(depth) ?: return null
            val parts = mutableListOf(first)
            while (peek() != null && peek()!!.type != TokenType.OR && peek()!!.type != TokenType.RPAREN) {
                val next = parseFactor(depth) ?: break
                parts.add(next)
            }
            return if (parts.size == 1) parts[0] else AndNode(parts)
        }

        private fun parseFactor(depth: Int): TextNode? {
            if (depth > MAX_DEPTH) return null
            if (peek()?.type == TokenType.NOT) {
                advance()
                val atom = parseAtom(depth) ?: return null
                return NotNode(atom)
            }
            return parseAtom(depth)
        }

        private fun parseAtom(depth: Int): TextNode? {
            val token = peek() ?: return null
            return when (token.type) {
                TokenType.LPAREN -> {
                    advance()
                    val inner = parseOrExpr(depth + 1)
                    if (peek()?.type == TokenType.RPAREN) advance()
                    inner
                }
                TokenType.QUOTED -> { advance(); PhraseNode(token.value) }
                TokenType.WORD -> { advance(); TermNode(token.value) }
                TokenType.NULL_TOKEN -> { advance(); NullNode }
                else -> null
            }
        }
    }

    internal fun renderLikeTerm(
        input: String,
        isPhrase: Boolean,
        column: String,
        dialect: SqlDialect,
        caseSensitive: Boolean,
        args: SqlArgsCollector
    ): String {
        var col = column
        var likeOp = "LIKE"
        var text = input

        if (!caseSensitive) {
            val (foldedCol, op) = dialect.caseInsensitiveLike(col)
            col = foldedCol
            likeOp = op
            text = dialect.transformLikeValue(text)
        }

        if (isPhrase) {
            text = "%${escapeLike(text)}%"
        } else {
            if (!text.startsWith("*") && !text.endsWith("*")) text = "*$text*"
            text = COLLAPSE_WILDCARDS.replace(text, "*")
            text = escapeLike(text).replace('*', '%')
        }

        args.add(text)
        return "$col $likeOp ? ${dialect.likeEscapeClause()}"
    }

    private val COLLAPSE_WILDCARDS = Regex("\\*+")

    private fun escapeLike(text: String): String = buildString(text.length) {
        for (c in text) when (c) {
            '\\' -> append("\\\\")
            '_' -> append("\\_")
            '%' -> append("\\%")
            else -> append(c)
        }
    }
}
