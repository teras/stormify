// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.kdbc.SQLException

/**
 * Parsed operator expression for ordered (numeric/temporal) facet filters.
 * Returned by [OrderedQuery.parse].
 */
sealed interface OrderedNode

/** `value` — exact match. */
data class EqualNode(
    /** The value to match. */
    val value: String
) : OrderedNode

/** `< value` */
data class LessThanNode(
    /** The upper bound (exclusive). */
    val value: String
) : OrderedNode

/** `> value` */
data class GreaterThanNode(
    /** The lower bound (exclusive). */
    val value: String
) : OrderedNode

/** `<= value` */
data class LessOrEqualNode(
    /** The upper bound (inclusive). */
    val value: String
) : OrderedNode

/** `>= value` */
data class GreaterOrEqualNode(
    /** The lower bound (inclusive). */
    val value: String
) : OrderedNode

/** `low...high` — inclusive range. */
data class BetweenNode(
    /** The lower bound (inclusive). */
    val low: String,
    /** The upper bound (inclusive). */
    val high: String
) : OrderedNode

/**
 * Parses the operator syntax for numeric and temporal facet filters:
 * `>`, `<`, `>=`, `<=`, `low...high`, or a bare value for equality.
 *
 * Used internally by the default numeric/temporal converters. Custom
 * [Converter] implementations can call [parse] and pattern-match on the
 * [OrderedNode] subtypes for custom rendering.
 */
object OrderedQuery {

    /**
     * Parses [input] into an [OrderedNode]. Throws [SQLException] on malformed
     * input (empty, missing operand, conflicting operators, invalid range).
     */
    fun parse(input: String): OrderedNode {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw SQLException("Empty filter value")

        val biggerOrEqual = trimmed.startsWith(">=")
        val smallerOrEqual = trimmed.startsWith("<=")
        val bigger = !biggerOrEqual && trimmed.startsWith(">")
        val smaller = !smallerOrEqual && trimmed.startsWith("<")
        val hasRange = "..." in trimmed

        if ((bigger || smaller || biggerOrEqual || smallerOrEqual) && hasRange)
            throw SQLException("Cannot use '...' together with '<' or '>'")

        return when {
            bigger -> GreaterThanNode(requirePart(trimmed.substring(1), trimmed))
            smaller -> LessThanNode(requirePart(trimmed.substring(1), trimmed))
            biggerOrEqual -> GreaterOrEqualNode(requirePart(trimmed.substring(2), trimmed))
            smallerOrEqual -> LessOrEqualNode(requirePart(trimmed.substring(2), trimmed))
            hasRange -> {
                val parts = trimmed.split("...")
                if (parts.size != 2) throw SQLException("Invalid range format")
                BetweenNode(requirePart(parts[0], trimmed), requirePart(parts[1], trimmed))
            }
            else -> EqualNode(trimmed)
        }
    }

    private fun requirePart(input: String, fullData: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw SQLException("Invalid syntax, a required part was not found: '$fullData'")
        return trimmed
    }
}
