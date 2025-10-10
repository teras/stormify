// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.TypeUtils.castTo
import onl.ycode.stormify.isTextualClass
import kotlin.reflect.KClass

internal object DefaultDataConverter {
    fun guessConverter(type: KClass<*>, caseSensitive: () -> Boolean): (String, String, (Any) -> Unit) -> String =
        if (isTextualClass(type))
            { column: String, input: String, args: (Any) -> Unit ->
                var column = column
                var input = input
                if (!caseSensitive()) {
                    column = "LOWER($column)"
                    input = input.lowercase()
                }
                if (!input.startsWith("*") && !input.endsWith("*")) input = "*$input*"
                input = input.replace('*', '%')
                args(input)
                column + (if (input.contains("%")) " LIKE ?" else " = ?")
            }
        else { column: String, input: String, args: (Any) -> Unit ->
            runCatching { breakdownParts(column, input, { part -> args(castTo(type, part) ?: "") }) }
                .getOrElse { throw SQLException("Unable to convert '$input' to number", it) }
        }

    private fun breakdownParts(column: String, userInput: String, args: (String) -> Unit): String {
        val input = userInput.trim { it <= ' ' }
        val biggerOrEqual = input.startsWith(">=")
        val smallerOrEqual = input.startsWith("<=")
        val bigger = !biggerOrEqual && input.startsWith(">")
        val smaller = !smallerOrEqual && input.startsWith("<")
        val dots = input.indexOf("...")
        if ((bigger || smaller || biggerOrEqual || smallerOrEqual) && dots >= 0) throw SQLException("Cannot use '...' together with '<' or '>'")
        return when {
            bigger || smaller -> {
                args(part(input.substring(1), userInput))
                column + (if (bigger) " > ?" else " < ?")
            }

            biggerOrEqual || smallerOrEqual -> {
                args(part(input.substring(2), userInput))
                column + (if (biggerOrEqual) " >= ?" else " <= ?")
            }

            dots >= 0 -> {
                val parts = input.split("\\.\\.\\.".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
                if (parts.size != 2) throw SQLException("Invalid range format")
                args(part(parts[0], userInput))
                args(part(parts[1], userInput))
                "$column BETWEEN ? AND ?"
            }

            else -> {
                args(part(input, userInput))
                "$column = ?"
            }
        }
    }

    private fun part(input: String, fullData: String): String {
        var input = input
        input = input.trim { it <= ' ' }
        if (input.isEmpty()) throw SQLException("Invalid syntax, a required part was not found: '$fullData'")
        return input
    }
}
