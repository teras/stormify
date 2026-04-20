// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.TypeUtils.castTo
import kotlin.reflect.KClass

internal object DefaultDataConverter {

    fun guessConverter(
        type: Facet.Type,
        dialect: SqlDialect,
        syntax: FilterSyntax,
        enumValues: Map<String, Any>? = null
    ): Converter = when (type) {
        Facet.Type.TEXT -> textConverter(dialect, syntax) { false }
        Facet.Type.NUMERIC -> orderedConverter(Number::class, Facet.Type.NUMERIC, syntax)
        Facet.Type.DATE, Facet.Type.TIME, Facet.Type.TIMESTAMP -> rawConverterWithCast(type, dialect, syntax)
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
        Facet.Type.NUMERIC,
        Facet.Type.DATE,
        Facet.Type.TIME,
        Facet.Type.TIMESTAMP -> orderedConverter(node.type, type, syntax)
    }

    // Exhaustive dispatcher: adding a new Facet.Type bucket forces a decision here.
    // Non-temporals return identity — safe no-op when wrapped into SQL.
    private fun dialectCastFor(type: Facet.Type, dialect: SqlDialect): (String) -> String = when (type) {
        Facet.Type.DATE -> dialect::castToDate
        Facet.Type.TIME -> dialect::castToTime
        Facet.Type.TIMESTAMP -> dialect::castToTimestamp
        Facet.Type.TEXT, Facet.Type.NUMERIC, Facet.Type.ENUM -> { placeholder -> placeholder }
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
    ): Converter {
        if (enumValues.isNullOrEmpty()) {
            return { column, input, _, args ->
                args.add(input)
                "$column = ?"
            }
        }
        // Precompute lowercased keys once — filters repeatedly lowercased keys and
        // search text on every atom, producing N × K String allocations per parse.
        val entries = enumValues.entries.map { it.key to it.value }
        val lowercaseEntries = entries.map { (k, v) -> k.lowercase() to v }
        return conv@{ column, input, _, args ->
            val ast = TextQuery.parse(input, syntax) ?: return@conv IMPOSSIBLE
            TextQuery.renderAst(ast, column) { text, isPhrase ->
                val matched = if (isPhrase) {
                    entries.filter { (k, _) -> k.equals(text, ignoreCase = true) }.map { it.second }
                } else {
                    val needle = text.lowercase()
                    lowercaseEntries.filter { (k, _) -> k.contains(needle) }.map { it.second }
                }
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

    private fun orderedConverter(
        type: KClass<*>,
        columnType: Facet.Type,
        syntax: FilterSyntax
    ): Converter = { column, input, parser, args ->
        orderedWithBoolean(input, column, parser, columnType, syntax) { value ->
            args.add(castOrFail(type, value))
        }
    }

    private fun rawConverterWithCast(
        columnType: Facet.Type,
        dialect: SqlDialect,
        syntax: FilterSyntax
    ): Converter {
        val castedPlaceholder = dialectCastFor(columnType, dialect)("?")
        return { column, input, parser, args ->
            val sql = orderedWithBoolean(input, column, parser, columnType, syntax) { value ->
                args.add(value)
            }
            sql.replace("?", castedPlaceholder)
        }
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
