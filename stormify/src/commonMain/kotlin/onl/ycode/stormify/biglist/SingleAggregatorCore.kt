// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import onl.ycode.stormify.TypeUtils
import kotlin.reflect.KClass

/**
 * Internal shared implementation for [SingleAggregator] actual classes.
 *
 * Holds the mutable list of [AggregateEntry]s, generates default aliases,
 * builds the final aggregate SQL and executes it against the parent
 * [PagedListBase]. Both the JVM and Native `SingleAggregator` actuals
 * delegate to a single instance of this class, so the aggregation logic
 * lives here once.
 */
internal class SingleAggregatorCore(
    internal val pagedList: PagedListBase<*>
) {
    internal val entries: MutableList<AggregateEntry> = mutableListOf()

    /**
     * Registers an aggregate. [function] is the tag used for alias
     * generation (`"sum"`, `"avg"`, …, `"raw"`); [expression] is the
     * field path for structured aggregates or the full SQL expression
     * for `raw`. [alias] is the user-supplied alias — when `null` a
     * unique alias is auto-generated.
     *
     * Returns the [AggregateEntry] that was added so callers can
     * access its alias for single-value execution.
     */
    internal fun add(function: String, expression: String, alias: String?): AggregateEntry {
        val resolvedAlias = when {
            alias == null -> generateAlias(function, expression)
            entries.any { it.alias == alias } ->
                throw IllegalArgumentException("Duplicate aggregate alias '$alias'")
            else -> alias
        }
        val sql = buildExpressionSql(function, expression)
        return AggregateEntry(sql, resolvedAlias).also { entries.add(it) }
    }

    private fun buildExpressionSql(function: String, expression: String): String {
        if (function == "raw") return expression
        // Resolve field paths using the same tree that PagedListBase uses for
        // column expressions — this handles FK traversal and join aliasing.
        val resolved = when (expression) {
            "*" -> "*"
            else -> pagedList.resolveAggregateExpression(expression)
        }
        return when (function) {
            "sum" -> "SUM($resolved)"
            "avg" -> "AVG($resolved)"
            "min" -> "MIN($resolved)"
            "max" -> "MAX($resolved)"
            "count" -> "COUNT($resolved)"
            "countDistinct" -> "COUNT(DISTINCT $resolved)"
            else -> throw IllegalArgumentException("Unknown aggregate function '$function'")
        }
    }

    /**
     * Produces a unique alias for an entry whose caller passed `alias = null`.
     * See the plan docs for the sanitization + uniqueness rules.
     */
    internal fun generateAlias(function: String, expression: String): String {
        val base = when {
            expression == "*" -> function
            function == "raw" -> {
                val sanitized = sanitize(expression)
                sanitized.ifEmpty { function }
            }
            else -> {
                val sanitized = sanitize(expression)
                if (sanitized.isEmpty()) function else "${function}_$sanitized"
            }
        }
        if (entries.none { it.alias == base }) return base
        var i = 2
        while (entries.any { it.alias == "${base}_$i" }) i++
        return "${base}_$i"
    }

    /** Builds the final SQL for the current entry list. */
    internal fun buildQuery(): String {
        require(entries.isNotEmpty()) { "Aggregator has no aggregations to run" }
        val (where, _) = pagedList.buildConstraintPart(excludeColumn = null)
        val select = entries.joinToString(", ") { "${it.expression} AS ${it.alias}" }
        return "SELECT $select FROM ${pagedList.getTablesPart()}$where"
    }

    /** Returns the positional argument list that accompanies [buildQuery]. */
    internal fun buildArgs(): List<Any> = pagedList.buildConstraintPart(excludeColumn = null).second

    /**
     * Executes the query and returns a [Map] keyed by [AggregateEntry.alias]
     * with the raw column values (no type coercion — callers do the casting).
     */
    internal fun executeMulti(): Map<String, Any?> {
        val stormify = pagedList.getStormify()
        val sql = buildQuery()
        val args = buildArgs()
        @Suppress("UNCHECKED_CAST")
        val row = stormify.readOne(
            null, Map::class as KClass<Map<String, Any?>>, sql, *args.toTypedArray()
        ) ?: emptyMap()
        // Normalize keys to the requested aliases; result-set labels may be
        // returned lower-cased by certain dialects so we remap.
        val out = linkedMapOf<String, Any?>()
        for (entry in entries) {
            val key = entry.alias
            out[key] = row[key] ?: row[key.lowercase()]
        }
        return out
    }

    /**
     * Executes a single-value query and casts the returned column to [type].
     */
    internal fun <R : Any> executeSingle(type: KClass<R>): R? {
        val stormify = pagedList.getStormify()
        val sql = buildQuery()
        val args = buildArgs()
        @Suppress("UNCHECKED_CAST")
        val row = stormify.readOne(
            null, Map::class as KClass<Map<String, Any?>>, sql, *args.toTypedArray()
        ) ?: return null
        val alias = entries.first().alias
        val raw = row[alias] ?: row[alias.lowercase()] ?: return null
        return TypeUtils.castTo(type, raw, stormify)
    }

    private fun sanitize(s: String): String {
        val clean = s.replace(Regex("[^a-zA-Z0-9_]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        return if (clean.length > 32) clean.take(32).trimEnd('_') else clean
    }
}
