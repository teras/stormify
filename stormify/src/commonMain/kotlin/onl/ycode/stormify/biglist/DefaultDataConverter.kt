// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.TypeUtils.castTo
import onl.ycode.stormify.isTextualClass
import kotlin.reflect.KClass

internal object DefaultDataConverter {

    fun guessConverter(
        type: Facet.Type,
        dialect: SqlDialect,
        syntax: FilterSyntax,
        enumValues: Map<String, Any>? = null
    ): Converter = when (type) {
        Facet.Type.TEXT -> textConverter(dialect, syntax) { false }
        Facet.Type.NUMERIC -> orderedConverter(Number::class, syntax)
        Facet.Type.TEMPORAL -> rawDateConverter(dialect, syntax)
        Facet.Type.ENUM -> enumConverter(enumValues, syntax)
    }

    fun guessConverterForNode(
        node: NodeField,
        type: Facet.Type,
        dialect: SqlDialect,
        syntax: FilterSyntax,
        caseSensitive: () -> Boolean,
        enumValues: Map<String, Any>? = null
    ): Converter = when (type) {
        Facet.Type.TEXT -> textConverter(dialect, syntax, caseSensitive)
        Facet.Type.ENUM -> enumConverter(enumValues, syntax)
        Facet.Type.TEMPORAL -> orderedConverter(node.type, syntax)
        else -> if (isTextualClass(node.type)) textConverter(dialect, syntax, caseSensitive)
        else orderedConverter(node.type, syntax)
    }

    private fun textConverter(
        dialect: SqlDialect,
        syntax: FilterSyntax,
        caseSensitive: () -> Boolean
    ): Converter = { col, input, _, args ->
        TextQuery.parseAndRender(input, col, dialect, caseSensitive(), args, syntax)
            ?: IMPOSSIBLE
    }

    private fun enumConverter(
        enumValues: Map<String, Any>?,
        syntax: FilterSyntax
    ): Converter = conv@{ column, input, _, args ->
        if (enumValues.isNullOrEmpty()) {
            args.add(input)
            "$column = ?"
        } else {
            val ast = TextQuery.parse(input, syntax) ?: return@conv IMPOSSIBLE
            val allValues = enumValues.entries.toSet()
            TextQuery.renderAst(ast, column) { text, isPhrase ->
                val matched = if (isPhrase)
                    allValues.filter { it.key.equals(text, ignoreCase = true) }.map { it.value }
                else
                    allValues.filter { it.key.lowercase().contains(text.lowercase()) }.map { it.value }
                when {
                    matched.isEmpty() -> IMPOSSIBLE
                    matched.size == 1 -> { args.add(matched[0]); "$column = ?" }
                    else -> {
                        matched.forEach { args.add(it) }
                        "$column IN (${matched.joinToString(", ") { "?" }})"
                    }
                }
            }
        }
    }

    private fun orderedConverter(type: KClass<*>, syntax: FilterSyntax): Converter = { column, input, parser, args ->
        orderedWithBoolean(input, column, parser, Facet.Type.NUMERIC, syntax) { value ->
            args.add(castOrFail(type, value))
        }
    }

    private fun rawDateConverter(dialect: SqlDialect, syntax: FilterSyntax): Converter = { column, input, parser, args ->
        val sql = orderedWithBoolean(input, column, parser, Facet.Type.TEMPORAL, syntax) { value ->
            args.add(value)
        }
        sql.replace("?", dialect.castToDate("?"))
    }

    private fun orderedWithBoolean(
        input: String,
        column: String,
        parser: InputParser,
        columnType: Facet.Type,
        syntax: FilterSyntax,
        addArg: (String) -> Unit
    ): String {
        val trimmed = input.trim()
        // Ordered expression (operator prefix or no letters) — skip boolean parsing
        // to preserve spaces in `"> 5"` which TextQuery would split on.
        if (trimmed.startsWith(">") || trimmed.startsWith("<")
            || trimmed.contains("...") || !trimmed.any { it.isLetter() }
        ) {
            return safeRenderOrderedAtom(trimmed, column, parser, columnType, addArg)
        }
        val ast = TextQuery.parse(input, syntax) ?: return IMPOSSIBLE
        return TextQuery.renderAst(ast, column) { text, _ ->
            safeRenderOrderedAtom(text, column, parser, columnType, addArg)
        }
    }

    private fun safeRenderOrderedAtom(
        input: String,
        column: String,
        parser: InputParser,
        columnType: Facet.Type,
        addArg: (String) -> Unit
    ): String = runCatching {
        val node = OrderedQuery.parse(input)
        // Pass transformed string to addArg; the caller's lambda does the type cast.
        fun transform(raw: String) = addArg(parser(raw, columnType))
        when (node) {
            is EqualNode -> { transform(node.value); "$column = ?" }
            is LessThanNode -> { transform(node.value); "$column < ?" }
            is GreaterThanNode -> { transform(node.value); "$column > ?" }
            is LessOrEqualNode -> { transform(node.value); "$column <= ?" }
            is GreaterOrEqualNode -> { transform(node.value); "$column >= ?" }
            is BetweenNode -> { transform(node.low); transform(node.high); "$column BETWEEN ? AND ?" }
        }
    }.getOrDefault(IMPOSSIBLE)

    private fun castOrFail(type: KClass<*>, value: String): Any =
        castTo(type, value) ?: throw SQLException("Cannot convert '$value' to ${type.simpleName}")

    private const val IMPOSSIBLE = "1 = 0"
}
