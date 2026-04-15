// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import kotlin.jvm.JvmOverloads

/**
 * Entry point for the aggregation DSL on a [PagedListBase].
 *
 * Each builder method registers one aggregate and returns a [SingleAggregator]
 * — a view that can either be executed immediately for a single scalar value
 * (`.execute<BigDecimal>()`) or chained into additional aggregations to
 * produce a [MultiAggregator] (whose `execute()` returns a `Map` keyed by
 * alias).
 *
 * Aggregations inherit the parent list's constraints and per-column filters
 * — `list.getFacet(0).filter = "Acme"` is honored by `sum`/`avg`/… — but the
 * `isDistinct` flag is ignored for aggregates.
 *
 * ```kotlin
 * val list = PagedList<Company>()
 * stormify.attach(list)
 * list.addFacet("industry").filter = "Tech"
 *
 * // Single value
 * val total: BigDecimal? = list.getAggregator()
 *     .sum(Company_.revenue)
 *     .execute<BigDecimal>()
 *
 * // Multiple values
 * val row: Map<String, Any?> = list.getAggregator()
 *     .sum(Company_.revenue, "total")
 *     .avg(Company_.revenue, "average")
 *     .count("*", "cnt")
 *     .execute()
 * ```
 */
class PagedAggregator internal constructor(
    private val core: SingleAggregatorCore
) {
    /** Starts a `SUM(path)` aggregation. */
    @JvmOverloads
    fun sum(path: String, alias: String? = null): SingleAggregator {
        core.add("sum", path, alias)
        return SingleAggregator(core)
    }

    /** Starts a `SUM(path)` aggregation using a type-safe [ScalarPath]. */
    @JvmOverloads
    fun sum(path: ScalarPath, alias: String? = null): SingleAggregator = sum(path.toPath(), alias)

    /** Starts an `AVG(path)` aggregation. */
    @JvmOverloads
    fun avg(path: String, alias: String? = null): SingleAggregator {
        core.add("avg", path, alias)
        return SingleAggregator(core)
    }

    /** Starts an `AVG(path)` aggregation using a type-safe [ScalarPath]. */
    @JvmOverloads
    fun avg(path: ScalarPath, alias: String? = null): SingleAggregator = avg(path.toPath(), alias)

    /** Starts a `MIN(path)` aggregation. */
    @JvmOverloads
    fun min(path: String, alias: String? = null): SingleAggregator {
        core.add("min", path, alias)
        return SingleAggregator(core)
    }

    /** Starts a `MIN(path)` aggregation using a type-safe [ScalarPath]. */
    @JvmOverloads
    fun min(path: ScalarPath, alias: String? = null): SingleAggregator = min(path.toPath(), alias)

    /** Starts a `MAX(path)` aggregation. */
    @JvmOverloads
    fun max(path: String, alias: String? = null): SingleAggregator {
        core.add("max", path, alias)
        return SingleAggregator(core)
    }

    /** Starts a `MAX(path)` aggregation using a type-safe [ScalarPath]. */
    @JvmOverloads
    fun max(path: ScalarPath, alias: String? = null): SingleAggregator = max(path.toPath(), alias)

    /** Starts a `COUNT(path)` aggregation. Pass `"*"` for `COUNT(*)`. */
    @JvmOverloads
    fun count(path: String, alias: String? = null): SingleAggregator {
        core.add("count", path, alias)
        return SingleAggregator(core)
    }

    /** Starts a `COUNT(path)` aggregation using a type-safe [ScalarPath]. */
    @JvmOverloads
    fun count(path: ScalarPath, alias: String? = null): SingleAggregator = count(path.toPath(), alias)

    /** Starts a `COUNT(DISTINCT path)` aggregation. */
    @JvmOverloads
    fun countDistinct(path: String, alias: String? = null): SingleAggregator {
        core.add("countDistinct", path, alias)
        return SingleAggregator(core)
    }

    /** Starts a `COUNT(DISTINCT path)` aggregation using a type-safe [ScalarPath]. */
    @JvmOverloads
    fun countDistinct(path: ScalarPath, alias: String? = null): SingleAggregator =
        countDistinct(path.toPath(), alias)

    /**
     * Starts an aggregation from an arbitrary SQL [expression]. The expression
     * is emitted verbatim into the generated `SELECT` list — callers are
     * responsible for writing safe, dialect-compatible SQL.
     */
    @JvmOverloads
    fun raw(expression: String, alias: String? = null): SingleAggregator {
        core.add("raw", expression, alias)
        return SingleAggregator(core)
    }
}
