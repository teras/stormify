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
        enumValues: Map<String, Any>? = null,
        folding: TextFolding = TextFolding.DEFAULT
    ): Converter = when (type) {
        Facet.Type.TEXT -> textConverter(dialect, syntax, folding) { false }
        Facet.Type.NUMERIC -> orderedConverter(Number::class, Facet.Type.NUMERIC, syntax)
        Facet.Type.DATE, Facet.Type.TIME, Facet.Type.TIMESTAMP -> rawConverterWithCast(type, dialect, syntax)
        Facet.Type.ENUM -> enumConverter(enumValues, syntax, folding)
    }

    fun guessConverterForNode(
        node: NodeField,
        type: Facet.Type,
        dialect: SqlDialect,
        syntax: FilterSyntax,
        caseSensitive: () -> Boolean,
        enumValues: Map<String, Any>? = null,
        folding: TextFolding = TextFolding.DEFAULT
    ): Converter = when (type) {
        Facet.Type.TEXT -> textConverter(dialect, syntax, folding, caseSensitive)
        Facet.Type.ENUM -> enumConverter(enumValues, syntax, folding)
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
        folding: TextFolding,
        caseSensitive: () -> Boolean
    ): Converter = Converter { col, input, _, args ->
        TextQuery.parseAndRender(input, col, dialect, caseSensitive(), args, syntax, folding)
            ?: IMPOSSIBLE
    }

    private fun enumConverter(
        enumValues: Map<String, Any>?,
        syntax: FilterSyntax,
        folding: TextFolding
    ): Converter {
        if (enumValues.isNullOrEmpty()) {
            return Converter { column, input, _, args ->
                args.add(input)
                "$column = ?"
            }
        }
        // Precompute folded keys once — filters repeatedly folded keys and
        // search text on every atom, producing N × K String allocations per parse.
        val entries = enumValues.entries.map { it.key to it.value }
        val foldedEntries = entries.map { (k, v) -> folding.value(k) to v }
        // The default folding keeps the historical ignore-case comparison of a quoted
        // name; a custom folding compares the folded texts.
        val phraseMatches: (String, String) -> Boolean =
            if (folding === TextFolding.DEFAULT) { key, text -> key.equals(text, ignoreCase = true) }
            else { foldedKey, text -> foldedKey == folding.value(text) }
        val phraseEntries = if (folding === TextFolding.DEFAULT) entries else foldedEntries
        return Converter conv@{ column, input, _, args ->
            val ast = TextQuery.parse(input, syntax) ?: return@conv IMPOSSIBLE
            TextQuery.renderAst(ast, column) { text, isPhrase ->
                val matched = if (isPhrase) {
                    phraseEntries.filter { (k, _) -> phraseMatches(k, text) }.map { it.second }
                } else {
                    val needle = folding.value(text)
                    foldedEntries.filter { (k, _) -> k.contains(needle) }.map { it.second }
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
    ): Converter = Converter { column, input, parser, args ->
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
        return Converter { column, input, parser, args ->
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
        // A null token without letters (`--`, `―`) would take the ordered shortcut
        // below and fail to parse as a value. Matched whole, together with its
        // negation, so negative numbers and dates are never mistaken for it.
        if (syntax.nullToken.none { it.isLetter() }) {
            val compact = trimmed.filterNot { it.isWhitespace() }
            if (compact == syntax.nullToken) return "$column IS NULL"
            if (compact == syntax.not + syntax.nullToken) return "NOT ($column IS NULL)"
        }
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
