// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import kotlin.jvm.JvmOverloads

/**
 * A multi-value aggregator built from a chain of aggregation requests over a
 * [PagedList][AbstractPagedList]. Each chain method adds another expression to the generated
 * `SELECT` list. [execute] returns a `Map<String, Any?>` keyed by the alias
 * of each added aggregation.
 *
 * Obtained by chaining additional methods onto a [SingleAggregator] —
 * conceptually the "two or more aggregations" view. Acquire via
 * [PagedList.getAggregator][AbstractPagedList.getAggregator] and then chain `.sum(…)`, `.avg(…)`, …
 * Aggregations respect the parent list's constraints and per-column filters.
 *
 * ```kotlin
 * val row = list.getAggregator()
 *     .sum("revenue", "total")
 *     .avg("revenue", "average")
 *     .count("*", "cnt")
 *     .execute()
 * val total = row["total"] as BigDecimal
 * ```
 */
class MultiAggregator internal constructor(
    private val core: SingleAggregatorCore
) {

    /** Adds `SUM(path)` with an optional [alias]. */
    @JvmOverloads
    fun sum(path: String, alias: String? = null): MultiAggregator {
        core.add("sum", path, alias)
        return this
    }

    /** Adds `SUM(path)` using a type-safe [ScalarPath] with an optional [alias]. */
    @JvmOverloads
    fun sum(path: ScalarPath, alias: String? = null): MultiAggregator = sum(path.toString(), alias)

    /** Adds `AVG(path)` with an optional [alias]. */
    @JvmOverloads
    fun avg(path: String, alias: String? = null): MultiAggregator {
        core.add("avg", path, alias)
        return this
    }

    /** Adds `AVG(path)` using a type-safe [ScalarPath] with an optional [alias]. */
    @JvmOverloads
    fun avg(path: ScalarPath, alias: String? = null): MultiAggregator = avg(path.toString(), alias)

    /** Adds `MIN(path)` with an optional [alias]. */
    @JvmOverloads
    fun min(path: String, alias: String? = null): MultiAggregator {
        core.add("min", path, alias)
        return this
    }

    /** Adds `MIN(path)` using a type-safe [ScalarPath] with an optional [alias]. */
    @JvmOverloads
    fun min(path: ScalarPath, alias: String? = null): MultiAggregator = min(path.toString(), alias)

    /** Adds `MAX(path)` with an optional [alias]. */
    @JvmOverloads
    fun max(path: String, alias: String? = null): MultiAggregator {
        core.add("max", path, alias)
        return this
    }

    /** Adds `MAX(path)` using a type-safe [ScalarPath] with an optional [alias]. */
    @JvmOverloads
    fun max(path: ScalarPath, alias: String? = null): MultiAggregator = max(path.toString(), alias)

    /** Adds `COUNT(path)` with an optional [alias]. Pass `"*"` for `COUNT(*)`. */
    @JvmOverloads
    fun count(path: String, alias: String? = null): MultiAggregator {
        core.add("count", path, alias)
        return this
    }

    /** Adds `COUNT(path)` using a type-safe [ScalarPath] with an optional [alias]. */
    @JvmOverloads
    fun count(path: ScalarPath, alias: String? = null): MultiAggregator = count(path.toString(), alias)

    /** Adds `COUNT(DISTINCT path)` with an optional [alias]. */
    @JvmOverloads
    fun countDistinct(path: String, alias: String? = null): MultiAggregator {
        core.add("countDistinct", path, alias)
        return this
    }

    /** Adds `COUNT(DISTINCT path)` using a type-safe [ScalarPath] with an optional [alias]. */
    @JvmOverloads
    fun countDistinct(path: ScalarPath, alias: String? = null): MultiAggregator =
        countDistinct(path.toString(), alias)

    /**
     * Adds an arbitrary SQL [expression] as a standalone aggregation column.
     * The expression is emitted verbatim — callers are responsible for writing
     * safe, dialect-compatible SQL.
     */
    @JvmOverloads
    fun raw(expression: String, alias: String? = null): MultiAggregator {
        core.add("raw", expression, alias)
        return this
    }

    /** The SQL that [execute] will run against the database. */
    val query: String get() = core.buildQuery()

    /**
     * Executes the chained aggregations in a single query and returns a map
     * keyed by each added aggregation's alias. The map values are the raw
     * column results — callers are responsible for any type coercion.
     */
    fun execute(): Map<String, Any?> = core.executeMulti()
}
