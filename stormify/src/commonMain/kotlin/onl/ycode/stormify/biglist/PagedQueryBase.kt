// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import onl.ycode.stormify.Stormify
import onl.ycode.stormify.StormifyAware
import kotlin.jvm.JvmOverloads
import kotlin.jvm.Transient
import kotlin.reflect.KClass

/**
 * Abstract base class for `PagedQuery` — a stateless, thread-safe query
 * executor designed for server-side use (REST, RPC, gRPC, any stateless
 * request/response context). Users do not instantiate this directly; use the
 * platform-specific `PagedQuery` subclass instead.
 *
 * A `PagedQuery` holds only column definitions and fixed constraints. It
 * does **not** hold per-request mutable state (no filter/sort on [Facet],
 * no cached page, no selected entity, no [kotlin.collections.AbstractList]
 * surface). All dynamic state flows per request through [execute] via a
 * [PageSpec]; the result of each call is a fresh [Page].
 *
 * Configure a `PagedQuery` once (typically at application startup, per
 * endpoint / grid) and share the instance across arbitrarily many
 * concurrent [execute] calls.
 *
 * ## When to use `PagedQuery` vs `PagedList`
 * - Use `PagedQuery` when the caller is stateless (REST handler, RPC method,
 *   background job that processes spec'd queries). Facet aliases are the
 *   only identifiers that cross the boundary — no schema, no SQL, no paths.
 * - Use `PagedList` when you're driving a stateful UI grid that keeps
 *   selection, lazy page cache, and per-column filter mutation across user
 *   interactions.
 *
 * ## Usage
 * ```kotlin
 * // One-time setup, ideally at application startup:
 * val customers = PagedQuery<Customer>().apply {
 *     addFacet("search", "name", "email", "city").also {
 *         it.isSortable = false  // search is filter-only
 *     }
 *     addFacet("name", "name")  // filterable + sortable
 *     addFacet("city", "city")
 * }
 *
 * // Per request:
 * val page: Page<Customer> = customers.execute(
 *     PageSpec(
 *         filters = mapOf("search" to "acme"),
 *         sorts = mapOf("name" to SortDir.ASC),
 *         page = 0, pageSize = 25,
 *     )
 * )
 * ```
 */
abstract class PagedQueryBase<T : Any> internal constructor(
    /** The entity class whose rows this query paginates. */
    val classType: KClass<T>
) : StormifyAware {

    @Transient internal var _stormify: Stormify? = null

    private val stormify: Stormify
        get() = _stormify ?: Stormify.defaultInstance
            ?: error(
                "No Stormify instance attached to this PagedQuery and no default instance " +
                        "is configured. Call stormify.attach(query) or Stormify.asDefault() first."
            )

    internal val core: PagedQueryCore<T> = PagedQueryCore(
        classType = classType,
        stormifyAccessor = { stormify },
        listInputParserAccessor = { null },
    ).also { it.isStateless = true }

    override fun attachTo(stormify: Stormify) {
        _stormify = stormify
        core.invalidateCachedMetadata()
    }

    /**
     * The columns defined on this query. Read-only view; add columns via
     * [addFacet] / [addSqlFacet].
     */
    val facets: List<Facet> get() = core.facets

    /**
     * Returns the column registered under [alias], or throws if no such alias.
     */
    fun getFacet(alias: String): Facet =
        core.facets.firstOrNull { it.alias == alias }
            ?: throw NoSuchElementException("No column with alias '$alias'")

    /**
     * Whether the query should return only distinct rows. Set at configuration
     * time. Like every PagedQuery setting, intended to be assigned once at
     * startup and not mutated while requests are in flight.
     */
    var isDistinct: Boolean
        get() = core.isDistinct
        set(value) { core.isDistinct = value }

    /**
     * Sets a fixed constraint (baseline `WHERE` clause) that is always applied
     * in addition to any per-request filters. Use for server-enforced narrowing
     * that must not be overrideable by the client (e.g., `"tenant_id = ?"`).
     */
    fun setConstraints(query: String, vararg args: Any) {
        core.setConstraints(query, args)
    }

    // --- Facet setup (alias-first, explicit) ---

    /**
     * Adds a filter/sort column under [alias] with one or more field paths.
     * The column type is auto-detected from the first path's Kotlin type.
     * Multiple paths use OR logic during filtering (e.g., a single "search"
     * column that matches any of several fields).
     *
     * Aliases are the only column identifier visible outside the server —
     * they must not leak schema details. Prefer short, human-readable
     * business names (`"name"`, `"total"`).
     */
    fun addFacet(alias: String, vararg fieldPaths: String): Facet =
        core.addFacet(fieldPaths.map { FieldPath(it) }, null, null, alias)

    /** Explicit-type variant of [addFacet]. */
    fun addFacet(alias: String, type: Facet.Type, vararg fieldPaths: String): Facet =
        core.addFacet(fieldPaths.map { FieldPath(it) }, type, null, alias)

    /** Enum-column variant of [addFacet] with an explicit display→DB value map. */
    fun addFacet(alias: String, enumValues: Map<String, Any>, vararg fieldPaths: String): Facet =
        core.addFacet(fieldPaths.map { FieldPath(it) }, Facet.Type.ENUM, enumValues, alias)

    /** Adds a column using type-safe KSP-generated path objects. */
    fun addFacet(alias: String, vararg paths: ScalarPath): Facet =
        core.addFacet(paths.map { FieldPath(it.toPath()) }, null, null, alias)

    /** Explicit-type typed-path variant. */
    fun addFacet(alias: String, type: Facet.Type, vararg paths: ScalarPath): Facet =
        core.addFacet(paths.map { FieldPath(it.toPath()) }, type, null, alias)

    /** Enum-column typed-path variant. */
    fun addFacet(alias: String, enumValues: Map<String, Any>, vararg paths: ScalarPath): Facet =
        core.addFacet(paths.map { FieldPath(it.toPath()) }, Facet.Type.ENUM, enumValues, alias)

    /**
     * Adds a raw/custom column backed by an arbitrary SQL expression (e.g.
     * `"SUM(amount)"`, `"COALESCE(a, b)"`). The expression is emitted verbatim,
     * so it is the configurator's responsibility to make it safe and
     * dialect-compatible — it is never derived from user input.
     */
    @JvmOverloads
    fun addSqlFacet(
        alias: String,
        expression: String,
        type: Facet.Type = Facet.Type.TEXT,
    ): Facet = core.addSqlFacet(expression, type, null, alias)

    /** Raw column with a custom [Converter] for filter semantics. */
    fun addSqlFacet(
        alias: String,
        expression: String,
        type: Facet.Type,
        converter: Converter,
    ): Facet = core.addSqlFacet(expression, type, converter, alias)

    // --- Table refs ---

    /**
     * Registers a [TableRef] for the root entity table. The ref's [TableRef.alias]
     * resolves to the underlying DB table name, so raw SQL in [setConstraints]
     * or in an [addSqlFacet] expression can safely reference the root without
     * hardcoding the table name.
     */
    fun addTableRef(): TableRef = core.addTableRef()

    /**
     * Registers a [TableRef] for a table reached through the given dotted [path]
     * (e.g. `"address"`, `"company.hq"`). The final segment must be an FK
     * reference, not a scalar field. The associated JOIN is activated in
     * every subsequent SQL build while the ref's [TableRef.isActive] is `true`.
     */
    fun addTableRef(path: String): TableRef = core.addTableRef(path)

    /** Registers a [TableRef] from a KSP-generated typed [ReferencePath]. */
    fun addTableRef(path: ReferencePath): TableRef = core.addTableRef(path)

    // --- Execution ---

    /**
     * Executes a single page query against the configured columns and the
     * caller-supplied [spec]. Thread-safe — multiple concurrent calls on the
     * same `PagedQuery` instance serialize only the (cheap) SQL-plan stage
     * and run the database roundtrip in parallel.
     *
     * Aliases in [spec] that do not correspond to any configured column are
     * silently ignored, mirroring [PagedListBase.restoreState] semantics.
     * Filters on columns whose [Facet.isFilterable] is `false`, or sorts on
     * columns whose [Facet.isSortable] is `false`, throw — the server
     * contract is explicit.
     */
    fun execute(spec: PageSpec): Page<T> {
        core.isSealed = true
        val state = buildRequestState(spec)
        val plan = core.planQuery(state, selectedId = null)
        val pageSize = spec.pageSize
        val page = spec.page
        val low = page * pageSize
        val high = low + pageSize

        val countSql = "SELECT ${plan.distinctPart}COUNT(*) FROM ${plan.tablesPart}${plan.wherePart}"
        val total = (stormify.readOne<Long>(countSql, *plan.args.toTypedArray()) ?: 0L)

        val rows: List<T> = if (total == 0L || low >= total) emptyList()
        else {
            val pageSql = stormify.sqlDialect.queryFormatter(
                "${core.info.tableName}.*",
                plan.distinctPart, plan.tablesPart, plan.wherePart, plan.orderBy,
                low, high.toInt().coerceAtMost(total.toInt())
            )
            stormify.read(null, classType, pageSql, *plan.args.toTypedArray())
        }
        return Page(rows = rows, total = total, page = page, pageSize = pageSize)
    }

    /**
     * Streams every row matching the filters/sorts/constraints in [spec]
     * through [action] via a database cursor. Uses the same chunked
     * sibling-batching as [PagedListBase.forEachStreaming]: FK touches
     * inside [action] resolve in batches of
     * [onl.ycode.stormify.SiblingGroup.DEFAULT_BATCH_SIZE], not one-per-row.
     *
     * The `page` and `pageSize` fields on [spec] are ignored — streaming
     * iterates every matching row. Caller is responsible for bounding the
     * result via filters / constraints.
     */
    fun forEachStreaming(spec: PageSpec, action: (T) -> Unit) {
        core.isSealed = true
        val state = buildRequestState(spec)
        core.forEachStreaming(state = state, selectedId = null, action = action)
    }

    /**
     * Returns an aggregation builder bound to this query and the filters/
     * constraints derived from [spec]. Page/pageSize are ignored — aggregations
     * always consider the entire matching set.
     *
     * Each call returns a fresh aggregator, so callers can build multiple
     * independent chains concurrently without interference.
     *
     * ```kotlin
     * val total: BigDecimal? = query.getAggregator(spec)
     *     .sum(Order_.total)
     *     .execute<BigDecimal>()
     *
     * val row: Map<String, Any?> = query.getAggregator(spec)
     *     .sum(Order_.total, "total")
     *     .avg(Order_.total, "average")
     *     .count("*", "cnt")
     *     .execute()
     * ```
     */
    fun getAggregator(spec: PageSpec): PagedAggregator {
        core.isSealed = true
        val state = buildRequestState(spec)
        return PagedAggregator(SingleAggregatorCore(core, state))
    }

    /**
     * Returns one page of distinct values for the facet registered under
     * [alias], filtered by [spec]'s other filters (the facet's own filter,
     * if any, is ignored — so pickers can display every value that would
     * become available once selected).
     *
     * [spec]'s `page` and `pageSize` paginate the returned distinct values.
     */
    fun filterValues(alias: String, spec: PageSpec): Page<String> {
        core.isSealed = true
        val facet = core.facets.firstOrNull { it.alias == alias }
            ?: throw NoSuchElementException("No facet with alias '$alias'")
        val state = buildRequestState(spec)
        val (columnExpr, fromWhere, args) = core.planFilterValuesPieces(facet, state)

        val total = stormify.readOne<Long>(
            "SELECT COUNT(DISTINCT $columnExpr) FROM $fromWhere",
            *args.toTypedArray()
        ) ?: 0L

        val low = spec.page * spec.pageSize
        val high = low + spec.pageSize
        val rows: List<String> = if (total == 0L || low >= total) emptyList()
        else {
            val sql = stormify.sqlDialect.queryFormatter(
                columnExpr, "DISTINCT ", fromWhere, "", columnExpr,
                low, high.coerceAtMost(total.toInt())
            )
            stormify.read(null, String::class, sql, *args.toTypedArray())
        }
        return Page(rows = rows, total = total, page = spec.page, pageSize = spec.pageSize)
    }

    /**
     * Returns one page of distinct values for [alias] together with their
     * row counts — facet-picker style. Same filter semantics as [filterValues]:
     * the facet's own filter is excluded.
     */
    fun filterValuesWithCounts(alias: String, spec: PageSpec): Page<FilterCountedValue> {
        core.isSealed = true
        val facet = core.facets.firstOrNull { it.alias == alias }
            ?: throw NoSuchElementException("No facet with alias '$alias'")
        val state = buildRequestState(spec)
        val (columnExpr, fromWhere, args) = core.planFilterValuesPieces(facet, state)

        val total = stormify.readOne<Long>(
            "SELECT COUNT(DISTINCT $columnExpr) FROM $fromWhere",
            *args.toTypedArray()
        ) ?: 0L

        val low = spec.page * spec.pageSize
        val high = low + spec.pageSize
        val rows: List<FilterCountedValue> = if (total == 0L || low >= total) emptyList()
        else {
            val inner = "SELECT $columnExpr AS fv_val, COUNT(*) AS fv_cnt FROM $fromWhere " +
                    "GROUP BY $columnExpr"
            val sql = stormify.sqlDialect.queryFormatter(
                "fv_val, fv_cnt", "", "($inner) fv_sub", "", "fv_val",
                low, high.coerceAtMost(total.toInt())
            )
            @Suppress("UNCHECKED_CAST")
            val raw = stormify.read(
                null, Map::class as kotlin.reflect.KClass<Map<String, Any?>>,
                sql, *args.toTypedArray()
            )
            raw.map { FilterCountedValue.fromRow(it) }
        }
        return Page(rows = rows, total = total, page = spec.page, pageSize = spec.pageSize)
    }

    private fun buildRequestState(spec: PageSpec): PagedQueryCore.RequestState {
        val aliasIndex = core.facets.associateBy { it.alias }
        val filters = mutableMapOf<String, String>()
        for ((alias, value) in spec.filters) {
            val column = aliasIndex[alias] ?: continue
            require(column.isFilterable) { "Facet '$alias' is not filterable" }
            filters[alias] = value
        }
        val sorts = mutableMapOf<String, SortState>()
        for ((alias, dir) in spec.sorts) {
            val column = aliasIndex[alias] ?: continue
            require(column.isSortable) { "Facet '$alias' is not sortable" }
            sorts[alias] = dir.toSortState()
        }
        val cs = mutableMapOf<String, Boolean>()
        for ((alias, flag) in spec.caseSensitive) {
            if (alias !in aliasIndex) continue
            cs[alias] = flag
        }
        return PagedQueryCore.RequestState(filters, sorts, cs)
    }
}
