// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.SqlDialect

internal sealed interface TextNode
internal data class TermNode(val text: String) : TextNode
internal data class PhraseNode(val text: String) : TextNode
internal data class NotNode(val child: TextNode) : TextNode
internal data class AndNode(val children: List<TextNode>) : TextNode
internal data class OrNode(val children: List<TextNode>) : TextNode

private const val MAX_INPUT_LENGTH = 1000
private const val MAX_TOKENS = 100
private const val MAX_DEPTH = 20

private enum class TokenType { WORD, QUOTED, LPAREN, RPAREN, OR, MINUS }
private data class Token(val type: TokenType, val value: String)

internal object TextQueryParser {

    fun parse(input: String): TextNode? {
        val trimmed = input.take(MAX_INPUT_LENGTH).trim()
        if (trimmed.isEmpty()) return null
        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null
        return Parser(tokens).parse()
    }

    fun parseAndRender(
        input: String,
        column: String,
        dialect: SqlDialect,
        caseSensitive: Boolean,
        args: SqlArgsCollector
    ): String? {
        val trimmed = input.take(MAX_INPUT_LENGTH).trim()
        if (trimmed.isEmpty()) return null
        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null
        val ast = Parser(tokens).parse() ?: return null
        return render(ast, column, dialect, caseSensitive, args)
    }

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length && tokens.size < MAX_TOKENS) {
            when {
                input[i].isWhitespace() -> i++
                input[i] == '(' -> { tokens.add(Token(TokenType.LPAREN, "(")); i++ }
                input[i] == ')' -> { tokens.add(Token(TokenType.RPAREN, ")")); i++ }
                input[i] == '"' -> {
                    val end = input.indexOf('"', i + 1)
                    if (end < 0) {
                        val rest = input.substring(i)
                        tokens.add(Token(TokenType.WORD, rest))
                        i = input.length
                    } else {
                        tokens.add(Token(TokenType.QUOTED, input.substring(i + 1, end)))
                        i = end + 1
                    }
                }
                input[i] == '-' && (i == 0 || input[i - 1].isWhitespace() || input[i - 1] == '(') -> {
                    if (i + 1 < input.length && !input[i + 1].isWhitespace()) {
                        tokens.add(Token(TokenType.MINUS, "-"))
                        i++
                    } else {
                        val word = readWord(input, i)
                        tokens.add(Token(TokenType.WORD, word))
                        i += word.length
                    }
                }
                else -> {
                    val word = readWord(input, i)
                    if (word == "OR" && tokens.isNotEmpty() && tokens.last().type != TokenType.LPAREN
                        && tokens.last().type != TokenType.OR && tokens.last().type != TokenType.MINUS
                    ) {
                        tokens.add(Token(TokenType.OR, "OR"))
                    } else {
                        tokens.add(Token(TokenType.WORD, word))
                    }
                    i += word.length
                }
            }
        }
        return tokens
    }

    private fun readWord(input: String, start: Int): String {
        var i = start
        while (i < input.length && !input[i].isWhitespace() && input[i] != '(' && input[i] != ')' && input[i] != '"') {
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
            if (peek()?.type == TokenType.MINUS) {
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
                else -> null
            }
        }
    }

    private fun render(
        node: TextNode,
        column: String,
        dialect: SqlDialect,
        caseSensitive: Boolean,
        args: SqlArgsCollector
    ): String = when (node) {
        is TermNode -> renderLikeTerm(node.text, false, column, dialect, caseSensitive, args)
        is PhraseNode -> renderLikeTerm(node.text, true, column, dialect, caseSensitive, args)
        is NotNode -> "NOT (${render(node.child, column, dialect, caseSensitive, args)})"
        is AndNode -> node.children.joinToString(" AND ") { render(it, column, dialect, caseSensitive, args) }
        is OrNode -> "(${node.children.joinToString(" OR ") { render(it, column, dialect, caseSensitive, args) }})"
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
            text = text.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("%", "\\%")
            text = "%$text%"
        } else {
            if (!text.startsWith("*") && !text.endsWith("*")) text = "*$text*"
            text = text.replace(Regex("\\*+"), "*")
            text = text.replace("\\", "\\\\")
                .replace("_", "\\_")
                .replace("%", "\\%")
            text = text.replace('*', '%')
        }

        args.accept(text)
        return "$col $likeOp ? ${dialect.likeEscapeClause()}"
    }
}
