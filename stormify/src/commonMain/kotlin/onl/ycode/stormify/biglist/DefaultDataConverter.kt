// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.TypeUtils.castTo
import onl.ycode.stormify.isTextualClass
import kotlin.reflect.KClass

internal object DefaultDataConverter {

    /**
     * Returns a filter converter based on column type.
     */
    fun guessConverter(type: Column.Type, dialect: SqlDialect, enumValues: Map<String, Any>? = null): (String, String, InputParser, (Any) -> Unit) -> String = when (type) {
        Column.Type.TEXT -> withParser(textConverter(dialect) { false })
        Column.Type.NUMERIC -> numericConverter(Double::class)
        Column.Type.TEMPORAL -> rawDateConverter(dialect)
        Column.Type.ENUM -> withParser(enumConverter(enumValues))
        Column.Type.RAW -> withParser(textConverter(dialect) { false })
    }

    /**
     * Returns a filter converter based on NodeField type and column type.
     */
    fun guessConverterForNode(
        node: NodeField,
        type: Column.Type,
        dialect: SqlDialect,
        caseSensitive: () -> Boolean,
        enumValues: Map<String, Any>? = null
    ): (String, String, InputParser, (Any) -> Unit) -> String = when (type) {
        Column.Type.TEXT -> withParser(textConverter(dialect, caseSensitive))
        Column.Type.ENUM -> withParser(enumConverter(enumValues))
        Column.Type.TEMPORAL -> dateConverter(node.type)
        else -> if (isTextualClass(node.type)) withParser(textConverter(dialect, caseSensitive))
        else numericConverter(node.type)
    }

    /**
     * Adapts a 3-arg converter (no operator breakdown) to the 4-arg signature.
     * The InputParser is applied to the whole filter value before it reaches the converter.
     */
    private fun withParser(
        converter: (String, String, (Any) -> Unit) -> String
    ): (String, String, InputParser, (Any) -> Unit) -> String =
        { col, input, parser, args -> converter(col, parser(input, Column.Type.TEXT), args) }

    private fun textConverter(dialect: SqlDialect, caseSensitive: () -> Boolean): (String, String, (Any) -> Unit) -> String =
        { col: String, input: String, args: (Any) -> Unit ->
            val isCaseSensitive = caseSensitive()
            var text = input

            // Quoted exact match: "text"
            if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
                text = text.substring(1, text.length - 1)
                if (!isCaseSensitive) {
                    // Exact match always needs LOWER — `=` is case-sensitive on all DBs
                    args(text.lowercase())
                    "LOWER($col) = ?"
                } else {
                    args(text)
                    "$col = ?"
                }
            } else {
                var column = col
                var likeOp = "LIKE"

                // Dialect-aware case folding for LIKE
                if (!isCaseSensitive) {
                    val (foldedCol, op) = dialect.caseInsensitiveLike(column)
                    column = foldedCol
                    likeOp = op
                    text = dialect.transformLikeValue(text)
                }

                // Wildcard handling
                if (!text.startsWith("*") && !text.endsWith("*")) text = "*$text*"
                // Collapse multiple wildcards
                text = text.replace(Regex("\\*+"), "*")
                // Escape SQL LIKE metacharacters before converting wildcards
                text = text.replace("\\", "\\\\")
                    .replace("_", "\\_")
                    .replace("%", "\\%")
                // Convert wildcards
                text = text.replace('*', '%')
                args(text)
                "$column $likeOp ? ${dialect.likeEscapeClause()}"
            }
        }

    private fun enumConverter(enumValues: Map<String, Any>?): (String, String, (Any) -> Unit) -> String =
        { column: String, input: String, args: (Any) -> Unit ->
            if (enumValues.isNullOrEmpty()) {
                // No mapping available — fall back to exact match
                args(input)
                "$column = ?"
            } else {
                val search = input.lowercase()
                val matched = enumValues.entries
                    .filter { it.key.lowercase().contains(search) }
                    .map { it.value }
                if (matched.isEmpty()) {
                    // No match — produce impossible condition
                    "1 = 0"
                } else if (matched.size == 1) {
                    args(matched[0])
                    "$column = ?"
                } else {
                    matched.forEach { args(it) }
                    "$column IN (${matched.joinToString(", ") { "?" }})"
                }
            }
        }

    private fun dateConverter(type: KClass<*>): (String, String, InputParser, (Any) -> Unit) -> String =
        { column: String, input: String, parser: InputParser, args: (Any) -> Unit ->
            safeBreakdown(column, input, parser, Column.Type.TEMPORAL, type, args)
        }

    /**
     * Date converter for raw columns where we don't know the field type.
     * Sends values as String and wraps the placeholder with a dialect-specific
     * CAST so strict DBs (PostgreSQL, Oracle) can compare ISO strings to date columns.
     */
    private fun rawDateConverter(dialect: SqlDialect): (String, String, InputParser, (Any) -> Unit) -> String =
        { column: String, input: String, parser: InputParser, args: (Any) -> Unit ->
            safeBreakdownRaw(column, input, parser, Column.Type.TEMPORAL, args) { dialect.castToDate("?") }
        }

    /**
     * Variant of [safeBreakdown] that does NOT cast values (keeps String) and
     * substitutes the `?` placeholder in the generated SQL with a wrapped version.
     */
    private fun safeBreakdownRaw(
        column: String,
        input: String,
        parser: InputParser,
        columnType: Column.Type,
        args: (Any) -> Unit,
        placeholder: () -> String
    ): String {
        val staged = mutableListOf<Any>()
        return runCatching {
            breakdownParts(column, input, parser, columnType) { part -> staged.add(part) }
        }.fold(
            onSuccess = { sql -> staged.forEach(args); sql.replace("?", placeholder()) },
            onFailure = { IMPOSSIBLE }
        )
    }

    private fun numericConverter(type: KClass<*>): (String, String, InputParser, (Any) -> Unit) -> String =
        { column: String, input: String, parser: InputParser, args: (Any) -> Unit ->
            safeBreakdown(column, input, parser, Column.Type.NUMERIC, type, args)
        }

    /**
     * Runs [breakdownParts] with a staging buffer — on error, no bind args leak to the caller
     * and [IMPOSSIBLE] is returned. Successful casts are flushed to [args] at the end.
     */
    private fun safeBreakdown(
        column: String,
        input: String,
        parser: InputParser,
        columnType: Column.Type,
        targetType: KClass<*>,
        args: (Any) -> Unit
    ): String {
        val staged = mutableListOf<Any>()
        return runCatching {
            breakdownParts(column, input, parser, columnType) { part ->
                staged.add(castOrFail(targetType, part))
            }
        }.fold(
            onSuccess = { sql -> staged.forEach(args); sql },
            onFailure = { IMPOSSIBLE }
        )
    }

    /** Casts [value] to [type] or throws — never returns a fallback that could cause strict DB type errors. */
    private fun castOrFail(type: KClass<*>, value: String): Any =
        castTo(type, value) ?: throw SQLException("Cannot convert '$value' to ${type.simpleName}")

    /** Impossible SQL condition — produces 0 results for invalid input. */
    private const val IMPOSSIBLE = "1 = 0"

    private fun breakdownParts(
        column: String,
        userInput: String,
        parser: InputParser,
        type: Column.Type,
        args: (String) -> Unit
    ): String {
        val input = userInput.trim()
        val biggerOrEqual = input.startsWith(">=")
        val smallerOrEqual = input.startsWith("<=")
        val bigger = !biggerOrEqual && input.startsWith(">")
        val smaller = !smallerOrEqual && input.startsWith("<")
        val dots = input.indexOf("...")
        if ((bigger || smaller || biggerOrEqual || smallerOrEqual) && dots >= 0)
            throw SQLException("Cannot use '...' together with '<' or '>'")

        fun transform(raw: String) = parser(part(raw, userInput), type)

        return when {
            bigger || smaller -> {
                args(transform(input.substring(1)))
                column + if (bigger) " > ?" else " < ?"
            }

            biggerOrEqual || smallerOrEqual -> {
                args(transform(input.substring(2)))
                column + if (biggerOrEqual) " >= ?" else " <= ?"
            }

            dots >= 0 -> {
                val parts = input.split("\\.\\.\\."   .toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (parts.size != 2) throw SQLException("Invalid range format")
                args(transform(parts[0]))
                args(transform(parts[1]))
                "$column BETWEEN ? AND ?"
            }

            else -> {
                args(transform(input))
                "$column = ?"
            }
        }
    }

    private fun part(input: String, fullData: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw SQLException("Invalid syntax, a required part was not found: '$fullData'")
        return trimmed
    }
}
