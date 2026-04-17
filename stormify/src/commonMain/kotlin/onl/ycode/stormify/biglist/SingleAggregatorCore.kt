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
    internal val core: PagedQueryCore<*>,
    private val state: PagedQueryCore.RequestState? = null,
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
        // `any(isLetterOrDigit)` rejects blank strings AND pure-symbol strings
        // ("$$$", ":::") which would otherwise hit the database as broken SQL.
        // `"*"` is the only documented symbol-only expression — it stands for
        // `COUNT(*)` and must be allowed through.
        require(expression == "*" || expression.any(Char::isLetterOrDigit)) {
            "Aggregate expression must contain at least one letter or digit " +
                "(function='$function', expression='$expression')"
        }
        require(alias == null || alias.any(Char::isLetterOrDigit)) {
            "Aggregate alias must contain at least one letter or digit " +
                "(function='$function', expression='$expression', alias='$alias')"
        }
        val resolvedAlias = when {
            alias == null -> generateAlias(function, expression)
            else -> {
                val normalized = normalizeFirstChar(alias)
                if (entries.any { it.alias == normalized })
                    throw IllegalArgumentException("Duplicate aggregate alias '$alias'")
                normalized
            }
        }
        val sql = buildExpressionSql(function, expression)
        return AggregateEntry(sql, resolvedAlias).also { entries.add(it) }
    }

    /**
     * SQL identifiers must start with a letter or `_` on most dialects.
     * Prepends `_` if the first character is anything else; returns the
     * alias unchanged otherwise.
     */
    private fun normalizeFirstChar(alias: String): String =
        if (alias.firstOrNull()?.let { it.isLetter() || it == '_' } == true) alias else "_$alias"

    private fun buildExpressionSql(function: String, expression: String): String {
        if (function == "raw") return expression
        // Resolve field paths using the same tree that PagedListBase uses for
        // column expressions — this handles FK traversal and join aliasing.
        val resolved = when (expression) {
            "*" -> "*"
            else -> core.resolveAggregateExpression(expression)
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
     *
     * Rules:
     *  - `count("*")` → `"count"` (the function name alone).
     *  - Structured aggregates (`sum`, `avg`, …) → `"${function}_${sanitized}"`
     *    so the alias is descriptive and always letter-first via the function
     *    prefix.
     *  - `raw()` keeps the "expression names itself" convention —
     *    `raw("SUM(test.id)")` → `"SUM_test_id"` — because the expression
     *    already contains its own meaningful label.
     *
     * Every generated alias then passes through [normalizeFirstChar], which
     * prepends `_` if the first character is not a letter — the single place
     * where digit-first raw expressions like `raw("1")` / `raw("2 * amount")`
     * get turned into `"_1"` / `"_2_amount"`. Structured auto-aliases always
     * begin with the function name so the normalization is a no-op for them.
     */
    internal fun generateAlias(function: String, expression: String): String {
        val rawBase = when {
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
        val base = normalizeFirstChar(rawBase)
        if (entries.none { it.alias == base }) return base
        var i = 2
        while (entries.any { it.alias == "${base}_$i" }) i++
        return "${base}_$i"
    }

    /** Builds the final SQL for the current entry list. */
    internal fun buildQuery(): String = buildPlan().first

    /** Returns the positional argument list that accompanies [buildQuery]. */
    internal fun buildArgs(): List<Any> = buildPlan().second

    private fun buildPlan(): Pair<String, List<Any>> {
        require(entries.isNotEmpty()) { "Aggregator has no aggregations to run" }
        val dialect = core.stormify.sqlDialect
        val select = entries.joinToString(", ") { "${it.expression} AS ${dialect.quoteAlias(it.alias)}" }
        return core.planAggregate(select, state)
    }

    /**
     * Executes the query and returns a [Map] keyed by [AggregateEntry.alias]
     * with the raw column values (no type coercion — callers do the casting).
     */
    internal fun executeMulti(): Map<String, Any?> {
        val stormify = core.stormify
        val (sql, args) = buildPlan()
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
        val stormify = core.stormify
        val (sql, args) = buildPlan()
        @Suppress("UNCHECKED_CAST")
        val row = stormify.readOne(
            null, Map::class as KClass<Map<String, Any?>>, sql, *args.toTypedArray()
        ) ?: return null
        val alias = entries.first().alias
        val raw = row[alias] ?: row[alias.lowercase()] ?: return null
        return TypeUtils.castTo(type, raw, stormify)
    }

    private fun sanitize(s: String): String {
        val clean = SANITIZE_NON_ALNUM.replace(s, "_")
            .let { SANITIZE_MULTI_UNDERSCORE.replace(it, "_") }
            .trim('_')
        return if (clean.length > 32) clean.take(32).trimEnd('_') else clean
    }

    companion object {
        private val SANITIZE_NON_ALNUM = Regex("[^a-zA-Z0-9_]")
        private val SANITIZE_MULTI_UNDERSCORE = Regex("_+")
    }
}
