// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify.biglist

import kotlin.reflect.KClass

/**
 * A single-aggregation view on a [PagedAggregator] chain. Returned by each
 * [PagedAggregator] builder method (the "one aggregate" case).
 *
 * Two ways to execute:
 * 1. Immediately as a typed scalar via [execute] — returns the single column value.
 * 2. Chain another aggregation (`.sum`, `.avg`, …, `.raw`) to produce a
 *    [MultiAggregator] whose `execute()` returns a `Map<String, Any?>`.
 *
 * The `expect` / `actual` split exists so the JVM actual can add a
 * `execute(Class<R>)` overload for Java callers without polluting the
 * Native surface.
 *
 * ```kotlin
 * val total: BigDecimal? = list.getAggregator()
 *     .sum(Company_.revenue)
 *     .execute<BigDecimal>()
 * ```
 */
expect class SingleAggregator internal constructor(core: SingleAggregatorCore) {
    /** Chains a `SUM(path)` aggregation and returns the resulting [MultiAggregator]. */
    fun sum(path: String, alias: String? = null): MultiAggregator
    /** Chains a `SUM(path)` aggregation using a type-safe [ScalarPath]. */
    fun sum(path: ScalarPath, alias: String? = null): MultiAggregator
    /** Chains an `AVG(path)` aggregation and returns the resulting [MultiAggregator]. */
    fun avg(path: String, alias: String? = null): MultiAggregator
    /** Chains an `AVG(path)` aggregation using a type-safe [ScalarPath]. */
    fun avg(path: ScalarPath, alias: String? = null): MultiAggregator
    /** Chains a `MIN(path)` aggregation and returns the resulting [MultiAggregator]. */
    fun min(path: String, alias: String? = null): MultiAggregator
    /** Chains a `MIN(path)` aggregation using a type-safe [ScalarPath]. */
    fun min(path: ScalarPath, alias: String? = null): MultiAggregator
    /** Chains a `MAX(path)` aggregation and returns the resulting [MultiAggregator]. */
    fun max(path: String, alias: String? = null): MultiAggregator
    /** Chains a `MAX(path)` aggregation using a type-safe [ScalarPath]. */
    fun max(path: ScalarPath, alias: String? = null): MultiAggregator
    /** Chains a `COUNT(path)` aggregation. Pass `"*"` for `COUNT(*)`. */
    fun count(path: String, alias: String? = null): MultiAggregator
    /** Chains a `COUNT(path)` aggregation using a type-safe [ScalarPath]. */
    fun count(path: ScalarPath, alias: String? = null): MultiAggregator
    /** Chains a `COUNT(DISTINCT path)` aggregation. */
    fun countDistinct(path: String, alias: String? = null): MultiAggregator
    /** Chains a `COUNT(DISTINCT path)` aggregation using a type-safe [ScalarPath]. */
    fun countDistinct(path: ScalarPath, alias: String? = null): MultiAggregator
    /** Chains an arbitrary SQL [expression] into the aggregation list. */
    fun raw(expression: String, alias: String? = null): MultiAggregator

    /** The SQL that [execute] will run against the database. */
    val query: String

    /**
     * Executes the single-value aggregation and returns the column value
     * cast to [type], or `null` if no rows match the current filter state.
     */
    fun <R : Any> execute(type: KClass<R>): R?
}

/**
 * Reified convenience over [SingleAggregator.execute] — keeps the Kotlin
 * call sites (`aggregator.sum(path).execute<BigDecimal>()`) concise.
 */
inline fun <reified R : Any> SingleAggregator.execute(): R? = execute(R::class)
