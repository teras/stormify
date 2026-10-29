// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
package onl.ycode.stormify.biglist

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import onl.ycode.stormify.NativeBigInteger
import onl.ycode.stormify.SiblingGroup
import onl.ycode.stormify.SqlDialect
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.enumEntries
import onl.ycode.stormify.enumToInt
import onl.ycode.stormify.scanPlaceholders
import kotlin.reflect.KClass

// Internal SQL aliases — emitted via `SqlDialect.quoteAlias` to stay valid on Oracle.
internal const val TOTAL_ALIAS = "__strm_total"
internal const val FV_VAL_ALIAS = "__strm_fv_v"
internal const val FV_CNT_ALIAS = "__strm_fv_c"
private const val FV_SUB_ALIAS = "__strm_fv_s"
private const val COUNT_SUB_ALIAS = "__strm_ds"

// NULLS LAST is not portable (MySQL, SQL Server, older SQLite): order the NULL
// group last explicitly, whatever the database's own NULL ordering.
private fun nullsLast(expr: String) = "CASE WHEN $expr IS NULL THEN 1 ELSE 0 END, $expr"

internal fun buildFilterValuesSql(
    dialect: SqlDialect,
    columnExpr: String,
    fromWhere: String,
): String {
    val v = dialect.quoteAlias(FV_VAL_ALIAS)
    val c = dialect.quoteAlias(FV_CNT_ALIAS)
    val s = dialect.quoteAlias(FV_SUB_ALIAS)
    val inner = "SELECT $columnExpr AS $v, COUNT(*) AS $c FROM $fromWhere GROUP BY $columnExpr"
    return dialect.queryFormatter("$v, $c", "", "($inner) $s", "", nullsLast(v))
}

/**
 * One page of the distinct values of [columnExpr], the NULL group included and
 * ordered last. A grouped subquery rather than `SELECT DISTINCT`, because
 * PostgreSQL rejects an `ORDER BY` expression that is not in a DISTINCT list.
 */
internal fun buildFilterValuesOnlySql(
    dialect: SqlDialect,
    columnExpr: String,
    fromWhere: String,
): String {
    val v = dialect.quoteAlias(FV_VAL_ALIAS)
    val s = dialect.quoteAlias(FV_SUB_ALIAS)
    val inner = "SELECT $columnExpr AS $v FROM $fromWhere GROUP BY $columnExpr"
    return dialect.queryFormatter(v, "", "($inner) $s", "", nullsLast(v))
}

/** Number of distinct values of [columnExpr], counting the NULL group as one value. */
internal fun buildFilterValuesCountSql(dialect: SqlDialect, columnExpr: String, fromWhere: String): String {
    val v = dialect.quoteAlias(FV_VAL_ALIAS)
    val s = dialect.quoteAlias(FV_SUB_ALIAS)
    return "SELECT COUNT(*) FROM (SELECT $columnExpr AS $v FROM $fromWhere GROUP BY $columnExpr) $s"
}

/**
 * `SELECT DISTINCT` of every expression in [exprs] over [fromWhere], joined with
 * `UNION` so a value present under several expressions appears once.
 */
internal fun buildRawValuesUnion(dialect: SqlDialect, exprs: List<String>, fromWhere: String): String {
    val v = dialect.quoteAlias(FV_VAL_ALIAS)
    return exprs.joinToString(" UNION ") { "SELECT DISTINCT $it AS $v FROM $fromWhere" }
}

/** One page of the values of [union], ordered by value with `NULL` last ([nullsLast]). */
internal fun buildRawValuesPageSql(dialect: SqlDialect, union: String): String {
    val v = dialect.quoteAlias(FV_VAL_ALIAS)
    val s = dialect.quoteAlias(FV_SUB_ALIAS)
    return dialect.queryFormatter(v, "", "($union) $s", "", nullsLast(v))
}

/** Number of values of [union], `NULL` counted as one. */
internal fun buildRawValuesCountSql(dialect: SqlDialect, union: String): String =
    "SELECT COUNT(*) FROM ($union) ${dialect.quoteAlias(FV_SUB_ALIAS)}"

/**
 * Stateless SQL-generation engine shared by the UI-model façade
 * ([PagedList]) and the stateless query executor façade ([PagedQuery]).
 * Holds column definitions, constraints, the JOIN-tree and all SQL-building
 * logic — but owns no per-request mutable view state (no cached page, no
 * selected entity, no AbstractList surface).
 *
 * Not a public API — the two façades expose what is appropriate for their
 * respective use cases.
 */
internal class PagedQueryCore<T : Any>(
    internal val classType: KClass<T>,
    private val stormifyAccessor: () -> Stormify,
    private val listInputParserAccessor: () -> InputParser?,
) {
    internal val stormify: Stormify get() = stormifyAccessor()

    private var _info: TableInfo<T>? = null
    internal val info: TableInfo<T>
        get() = _info ?: @Suppress("UNCHECKED_CAST") (stormify.resolveTableInfo(classType) as TableInfo<T>)
            .also { _info = it }

    private val tableCounter = atomic(0)
    private var _root: NodeTable? = null
    private val root: NodeTable
        get() = _root ?: NodeTable("", "", classType, null, stormify).also { _root = it }

    private val _facets = mutableListOf<Facet>()
    internal val facets: List<Facet> get() = _facets
    internal fun getFacet(index: Int): Facet = _facets[index]

    private val _tableRefs = mutableListOf<TableRef>()
    internal val tableRefs: List<TableRef> get() = _tableRefs

    private var treeIsDirty = true

    /**
     * When `true`, per-column mutable state (`Facet.filter`, `.sort`,
     * `.isCaseSensitive`) is forbidden — the owning façade is a stateless
     * executor and filter/sort flow through per-request specs instead. The
     * façade sets this at construction time and it never changes afterwards.
     */
    internal var isStateless: Boolean = false

    /**
     * When `true`, setup-time configuration is locked: adding facets, table
     * refs or constraints, mutating per-facet aliases / flags / parsers /
     * converters, or toggling `isDistinct` / `TableRef.isActive` throws.
     * Stateless façades set this automatically on first execution so that
     * concurrent callers cannot observe a half-reconfigured engine.
     */
    internal var isSealed: Boolean = false

    /** Throws if the core is sealed; used by every setup-time mutation path. */
    internal fun checkMutable() {
        require(!isSealed) {
            "PagedQuery is sealed after its first execution — configure it fully before running any query"
        }
    }

    /**
     * Hook invoked when a column's filter / sort / case-sensitivity state
     * changes. In UI mode the façade uses this to drop its cached page /
     * size / filter-value views. In stateless mode the hook stays a no-op
     * (mutating setters throw anyway).
     */
    internal var invalidateHook: () -> Unit = {}

    /**
     * Called by [Facet] setters when state changes. Marks the join tree
     * dirty so it rebuilds on the next SQL pass, and fires the UI-side
     * invalidation hook.
     */
    internal fun invalidate() {
        treeIsDirty = true
        invalidateHook()
    }

    /**
     * Lock held around SQL-plan generation in stateless mode. Building the
     * plan mutates per-node active flags; without serialization, concurrent
     * `execute()` calls on a shared [PagedQuery] instance would corrupt
     * each other's JOIN activation. UI mode does not take this lock — it
     * runs single-threaded by convention.
     */
    private val queryLock = reentrantLock()

    private var sortCounter = 0L

    /** Next position in the sorting order; see [Facet.sortOrder]. */
    internal fun nextSortOrder() = ++sortCounter

    private var _isDistinct = false
    internal var isDistinct: Boolean
        get() = _isDistinct
        set(value) {
            checkMutable()
            _isDistinct = value
        }

    /** Direction of the primary key columns in `ORDER BY`; see [PagedList.keySort]. */
    internal var keySort: SortState = SortState.ASCENDING
        set(value) {
            checkMutable()
            field = value
        }

    private var constraintClause = ""
    private var constraintArgs: Array<out Any?> = emptyArray()

    internal fun setConstraints(query: String, args: Array<out Any?>) {
        checkMutable()
        constraintClause = query.trim()
        constraintArgs = args
    }

    // --- Facet registration ---

    internal fun addFacet(
        paths: List<FieldPath>,
        type: Facet.Type?,
        enumValues: Map<String, Any>?,
        alias: String?,
    ): Facet {
        checkMutable()
        require(paths.isNotEmpty()) { "At least one field path is required" }
        paths.forEach { resolveFieldPath(it) } // validate + build tree
        val resolvedType = type ?: detectType(paths.first())
        val resolvedEnumValues = enumValues ?: if (resolvedType == Facet.Type.ENUM)
            resolveFieldPath(paths.first()).let { buildEnumValues(it.type, it.isEnumAsString) } else null
        val resolvedAlias = resolveAliasForNewFacet(alias)
        val column = Facet(this, paths, resolvedType, resolvedEnumValues, null, null, resolvedAlias)
        _facets.add(column)
        return column
    }

    internal fun addSqlFacet(
        expression: String,
        type: Facet.Type,
        converter: Converter?,
        alias: String?,
    ): Facet {
        checkMutable()
        val resolvedAlias = resolveAliasForNewFacet(alias)
        val column = Facet(this, emptyList(), type, null, expression, converter, resolvedAlias)
        _facets.add(column)
        return column
    }

    internal fun facetByPath(path: FieldPath): Facet? = _facets.firstOrNull { path in it.fields }

    // --- Table refs (handles to join-tree nodes for raw SQL access) ---

    /** Creates a [TableRef] for the root entity table. */
    internal fun addTableRef(): TableRef {
        checkMutable()
        return TableRef(this, root).also { _tableRefs.add(it) }
    }

    /**
     * Creates a [TableRef] for a table reached through the given dotted [path].
     * Each segment must identify an FK field on the previous table; the final
     * segment must be a reference field (not a scalar). Throws
     * [IllegalArgumentException] if the path ends at a scalar field.
     */
    internal fun addTableRef(path: String): TableRef {
        checkMutable()
        val segments = path.split(".").filter { it.isNotBlank() }
        require(segments.isNotEmpty()) { "Table ref path must not be empty" }
        var last: Node = root
        for (segment in segments)
            last = last.findChild(segment) { tableCounter.incrementAndGet() }
        require(last is NodeTable) {
            "Path '$path' must point to a table reference, not a scalar field"
        }
        return TableRef(this, last).also { _tableRefs.add(it) }
    }

    /** Creates a [TableRef] from a KSP-generated typed [ReferencePath]. */
    internal fun addTableRef(path: ReferencePath): TableRef =
        addTableRef(path.toString().trimEnd('.'))

    private fun resolveAliasForNewFacet(explicit: String?): String {
        if (explicit != null) {
            require(explicit.isNotBlank()) { "Facet alias must not be blank" }
            require(_facets.none { it.alias == explicit }) { "Duplicate column alias '$explicit'" }
            return explicit
        }
        // Pick the lowest non-negative integer (stringified) not already in use.
        var i = 0
        while (_facets.any { it.alias == i.toString() }) i++
        return i.toString()
    }

    // --- SQL fragments (UI / mutable-column mode) ---

    internal val distinctPart: String
        get() = if (isDistinct) "DISTINCT " else ""

    internal fun sortingPart(selectedId: NativeBigInteger?): String =
        buildSortingPart(selectedId, null)

    internal val tablesPart: String
        get() {
            resolveCurrentTree(null)
            val out = StringBuilder(info.tableName)
            root.appendJoins(out)
            return out.toString()
        }

    internal fun buildConstraintPart(excludeFacet: Facet?): Pair<String, List<Any?>> =
        buildConstraintPartImpl(excludeFacet, null)

    /**
     * Column expression suffixed with a `__strm_total` sidecar that carries
     * the total count via a window function — COUNT(*) OVER () for the plain
     * path, or DENSE_RANK ASC + DESC − 1 for DISTINCT (COUNT(*) counts rows
     * before DISTINCT, which inflates the total when joins duplicate).
     */
    internal fun windowCountColumns(): String {
        val alias = stormify.sqlDialect.quoteAlias(TOTAL_ALIAS)
        if (!isDistinct) return "${info.tableName}.*, COUNT(*) OVER () AS $alias"
        val pkAsc = info.primaryKeys.joinToString(", ") { "${info.tableName}.${it.dbName}" }
        val pkDesc = info.primaryKeys.joinToString(", ") { "${info.tableName}.${it.dbName} DESC" }
        return "${info.tableName}.*, DENSE_RANK() OVER (ORDER BY $pkAsc) + DENSE_RANK() OVER (ORDER BY $pkDesc) - 1 AS $alias"
    }

    /**
     * Classic count SQL for the two-roundtrip fallback path. Wraps DISTINCT
     * in a subquery so `SELECT DISTINCT COUNT(*)` (a no-op) is avoided.
     */
    internal fun countSql(tables: String, where: String): String {
        if (!isDistinct) return "SELECT COUNT(*) FROM $tables$where"
        val sub = stormify.sqlDialect.quoteAlias(COUNT_SUB_ALIAS)
        return "SELECT COUNT(*) FROM (SELECT DISTINCT ${info.tableName}.* FROM $tables$where) $sub"
    }

    // --- Stateless plan (spec-driven, thread-safe) ---

    /**
     * Captures the per-request filter/sort state passed through [PagedQuery.execute]
     * so the engine can build SQL without touching per-column mutable state.
     */
    internal class RequestState(
        internal val filters: Map<String, String>,
        internal val sorts: Map<String, SortState>,
        internal val caseSensitive: Map<String, Boolean>,
    )

    internal data class QueryPlan(
        val distinctPart: String,
        val tablesPart: String,
        val wherePart: String,
        val orderBy: String,
        val args: List<Any?>,
    )

    /**
     * Produces a full SQL plan for a single [PagedQuery.execute] call.
     * Serialized via the per-core lock because it mutates the join tree's
     * active flags during tree resolution.
     */
    internal fun planQuery(state: RequestState, selectedId: NativeBigInteger?): QueryPlan =
        queryLock.withLock {
            resolveCurrentTree(state)
            val tablesOut = StringBuilder(info.tableName)
            root.appendJoins(tablesOut)
            val (where, args) = buildConstraintPartImpl(null, state)
            val orderBy = buildSortingPart(selectedId, state)
            QueryPlan(distinctPart, tablesOut.toString(), where, orderBy, args)
        }

    /**
     * Produces the (sql, args) for an aggregate query whose `SELECT` list is
     * [selectClause] and whose `FROM`/`WHERE` mirror the facet configuration
     * plus optional per-request [state]. [joinNodes] are the field nodes the
     * aggregate expressions read; their JOINs are emitted even when no facet
     * filters or sorts on them. Serialized via the core lock when
     * [state] is non-null so concurrent stateless callers do not corrupt
     * the join-tree activation; UI mode (null state) runs lock-free.
     */
    internal fun planAggregate(
        selectClause: String,
        state: RequestState?,
        joinNodes: Collection<Node> = emptyList(),
    ): Pair<String, List<Any?>> {
        val build: () -> Pair<String, List<Any?>> = {
            resolveCurrentTree(state, joinNodes)
            val tablesOut = StringBuilder(info.tableName)
            root.appendJoins(tablesOut)
            val (where, args) = buildConstraintPartImpl(null, state)
            "SELECT $selectClause FROM $tablesOut$where" to args
        }
        return if (state != null) queryLock.withLock(build) else build()
    }

    /**
     * Produces the (columnExpr, fromWhereFragment, args) tuple for a
     * distinct-values query over [facet], filtered by [state] minus the
     * facet's own filter. Serialized under the core lock when [state] is
     * non-null to keep concurrent stateless callers safe.
     */
    internal fun planFilterValuesPieces(
        facet: Facet,
        state: RequestState?,
    ): Triple<String, String, List<Any?>> {
        val build: () -> Triple<String, String, List<Any?>> = {
            // The facet's own path must be joined even when it has no filter or sort.
            val facetNode = if (facet.rawExpression == null && facet.fields.isNotEmpty())
                listOf(resolveFieldPath(facet.fields.first())) else emptyList()
            resolveCurrentTree(state, facetNode)
            val tablesOut = StringBuilder(info.tableName)
            root.appendJoins(tablesOut)
            val columnExpr = resolveFacetExpression(facet)
            val (where, args) = buildConstraintPartImpl(facet, state)
            Triple(columnExpr, "$tablesOut$where", args)
        }
        return if (state != null) queryLock.withLock(build) else build()
    }

    /**
     * Produces the `UNION` of the distinct values of every path of [facet] (or of its
     * SQL expression), filtered by the other facets and the constraints (and [state]
     * when stateless), and its arguments. The JOINs of all the facet's paths are
     * emitted for this statement only, like [planFilterValuesPieces].
     */
    internal fun planFilterRawValues(facet: Facet, state: RequestState? = null): Pair<String, List<Any?>> {
        val build: () -> Pair<String, List<Any?>> = {
            val nodes = if (facet.rawExpression == null) facet.fields.map { resolveFieldPath(it) } else emptyList()
            resolveCurrentTree(state, nodes)
            // Joins first: building the constraints rebuilds the tree without the extra nodes.
            val tablesOut = StringBuilder(info.tableName)
            root.appendJoins(tablesOut)
            val exprs = facet.rawExpression?.let { listOf(it) } ?: nodes.map { it.columnHandler }
            val (where, args) = buildConstraintPartImpl(facet, state)
            val union = buildRawValuesUnion(stormify.sqlDialect, exprs, "$tablesOut$where")
            union to List(exprs.size) { args }.flatten()
        }
        return if (state != null) queryLock.withLock(build) else build()
    }

    /** The field type of the paths of [facet] (all the same, see [Facet.getFilterRawValues]), or `null` for SQL facets. */
    internal fun rawValueType(facet: Facet): KClass<*>? =
        facet.fields.firstOrNull()?.let { resolveFieldPath(it).type }

    // --- Cursor-based streaming ---

    /**
     * Streams every row matching the current filter/sort/constraint state
     * through [action] via a database cursor. Rows are buffered in chunks of
     * [SiblingGroup.DEFAULT_BATCH_SIZE] so that FK touches inside [action]
     * benefit from sibling batch resolution (one query per chunk instead of
     * one per row).
     *
     * When [state] is non-null (stateless mode), the SQL plan is built under
     * the core's lock — but the lock is released before the cursor starts,
     * so concurrent callers do not block on each other's iteration time.
     */
    internal fun forEachStreaming(
        state: RequestState?,
        selectedId: NativeBigInteger?,
        action: (T) -> Unit,
    ) {
        val sql: String
        val args: List<Any?>
        if (state != null) {
            val plan = planQuery(state, selectedId)
            sql = "SELECT ${plan.distinctPart}${info.tableName}.* FROM " +
                    "${plan.tablesPart}${plan.wherePart} ORDER BY ${plan.orderBy}"
            args = plan.args
        } else {
            val (where, argList) = buildConstraintPart(null)
            sql = "SELECT $distinctPart${info.tableName}.* FROM " +
                    "$tablesPart$where ORDER BY ${sortingPart(selectedId)}"
            args = argList
        }
        val buffer = ArrayList<T>(SiblingGroup.DEFAULT_BATCH_SIZE)
        stormify.readCursor(null, classType, sql, *args.toTypedArray()) { row ->
            buffer.add(row)
            if (buffer.size >= SiblingGroup.DEFAULT_BATCH_SIZE) {
                buffer.forEach(action)
                buffer.clear()
            }
        }
        buffer.forEach(action)
    }

    // --- Core building blocks (shared) ---

    private fun facetFilter(column: Facet, state: RequestState?): String? =
        if (state != null) state.filters[column.alias] else column.filter

    private fun facetSort(column: Facet, state: RequestState?): SortState? =
        if (state != null) state.sorts[column.alias] else column.sort

    private fun facetCaseSensitive(column: Facet, state: RequestState?): Boolean =
        if (state != null) state.caseSensitive[column.alias] ?: false else column.isCaseSensitive

    private fun buildSortingPart(selectedId: NativeBigInteger?, state: RequestState?): String {
        resolveCurrentTree(state)
        // Sorted facets in the order they were sorted: by the position recorded when
        // the facet's sort was set, or by the order of the sorts in the request state.
        val sortedFacets =
            if (state != null) state.sorts.keys.mapNotNull { alias -> _facets.firstOrNull { it.alias == alias } }
            else _facets.filter { it.sort != null }.sortedBy { it.sortOrder }
        val facetSorts = sortedFacets
            .flatMap { col ->
                val direction = if (facetSort(col, state) == SortState.DESCENDING) " DESC" else ""
                if (col.fields.isNotEmpty())
                    listOf(resolveFieldPath(col.sortField).columnHandler + direction)
                else if (col.rawExpression != null)
                    listOf(col.rawExpression + direction)
                else
                    emptyList()
            }

        val singlePk = info.primaryKeys.singleOrNull()?.dbName
        // Qualified with the root table: a join to the same table (a self-reference) has a
        // column of the same name, which an unqualified key would make ambiguous.
        val selectedPrefix = if (singlePk != null && selectedId != null)
            stormify.sqlDialect.orderByIdDialect("${info.tableName}.$singlePk", selectedId)?.let { "$it, " } ?: ""
        else ""

        // Every primary key column closes the ORDER BY, so rows that tie on the
        // facet sorts still have one fixed order and paging neither repeats nor
        // skips them. Columns a facet already sorts on are not repeated (SQL
        // Server rejects a column listed twice in ORDER BY).
        val sortedExpressions = facetSorts.map { it.removeSuffix(" DESC") }.toSet()
        val keyDirection = if (keySort == SortState.DESCENDING) " DESC" else ""
        val keySorts = info.primaryKeys
            .map { "${info.tableName}.${it.dbName}" }
            .filter { it !in sortedExpressions }
            .map { it + keyDirection }
        val allSorts = facetSorts + keySorts
        if (allSorts.isEmpty()) throw IllegalStateException(
            "PagedList for ${info.tableName} requires explicit sorting via column.sort = " +
                    "because the entity has no primary key"
        )
        return selectedPrefix + allSorts.joinToString(", ")
    }

    private fun buildConstraintPartImpl(
        excludeFacet: Facet?,
        state: RequestState?,
    ): Pair<String, List<Any?>> {
        resolveCurrentTree(state)
        val andOut = StringBuilder()
        val args = constraintArgs.toMutableList()
        val argsCollector = SqlArgsCollector { args.add(it) }

        // Parenthesized so a constraint containing OR cannot absorb the AND of the facet filters.
        // The parenthesis closes on its own line, past any trailing line comment.
        if (constraintClause.isNotEmpty())
            andOut.append("(").append(constraintClause).append("\n)")

        for (column in _facets) {
            if (column === excludeFacet) continue
            val filterVal = facetFilter(column, state) ?: continue
            val parser = resolveInputParser(column)
            val caseSensitive = facetCaseSensitive(column, state)
            val orParts = mutableListOf<String>()

            fun wrapUserConverter(conv: Converter): Converter =
                Converter { col, input, p, a ->
                    val before = args.size
                    val fragment = conv(col, input, p, a)
                    val pushed = args.size - before
                    // Same scanner as the statement binder: '?' inside literals,
                    // quoted identifiers and comments is not a placeholder.
                    var placeholders = 0
                    scanPlaceholders(fragment) { placeholders++ }
                    require(placeholders == pushed) {
                        "Facet '${column.alias}' converter emitted $placeholders placeholders but pushed $pushed args"
                    }
                    fragment
                }

            if (column.rawExpression != null) {
                val conv = column.converter?.let(::wrapUserConverter)
                    ?: DefaultDataConverter.guessConverter(
                        column.type, stormify.sqlDialect, stormify.filterSyntax, column.enumValues, stormify.textFolding
                    )
                orParts.add(conv(column.rawExpression, filterVal, parser, argsCollector))
            } else {
                for (fieldPath in column.fields) {
                    val node = resolveFieldPath(fieldPath)
                    val conv = column.converter?.let(::wrapUserConverter)
                        ?: DefaultDataConverter.guessConverterForNode(
                            node, column.type, stormify.sqlDialect, stormify.filterSyntax,
                            { caseSensitive }, column.enumValues, stormify.textFolding
                        )
                    orParts.add(conv(node.columnHandler, filterVal, parser, argsCollector))
                }
            }

            if (orParts.isNotEmpty()) {
                if (andOut.isNotEmpty()) andOut.append(" AND ")
                if (orParts.size > 1)
                    andOut.append("(").append(orParts.joinToString(" OR ")).append(")")
                else
                    andOut.append(orParts[0])
            }
        }

        return (if (andOut.isEmpty()) "" else " WHERE $andOut") to args
    }

    // --- Tree / path resolution ---

    private fun resolveCurrentTree(state: RequestState?, extra: Collection<Node> = emptyList()) {
        // UI mode: use the treeIsDirty flag as a short-circuit cache — columns'
        // active sets only change when a setter fires, which clears the flag.
        // Stateless mode: always re-resolve (the spec provides the state).
        if (state == null && !treeIsDirty && extra.isEmpty()) return
        treeIsDirty = false
        root.deactivate()
        root.activate()
        for (facet in _facets) {
            val hasFilter = facetFilter(facet, state) != null
            val hasSort = facetSort(facet, state) != null
            if (!hasFilter && !hasSort) continue
            for (fieldPath in facet.fields)
                resolveFieldPath(fieldPath).activate()
        }
        for (ref in _tableRefs) ref.tryActivate()
        // Extra nodes serve a single statement (filter values, aggregates): the
        // next plain build must start from a clean tree without their JOINs.
        if (extra.isNotEmpty()) {
            for (node in extra) node.activate()
            treeIsDirty = true
        }
    }

    internal fun resolveFieldPath(fieldPath: FieldPath): NodeField {
        var last: Node = root
        for (segment in fieldPath.segments)
            last = last.findChild(segment) { tableCounter.incrementAndGet() }
        require(last is NodeField) { "Last field in path '${fieldPath.path}' must be a scalar, not a table reference" }
        return last
    }

    internal fun resolveFacetExpression(column: Facet): String {
        if (column.rawExpression != null) return column.rawExpression
        require(column.fields.isNotEmpty()) { "Facet has no fields" }
        return resolveFieldPath(column.fields.first()).columnHandler
    }

    /** Resolves an aggregate's field path; its JOINs are activated by [planAggregate]. */
    internal fun resolveAggregateNode(path: String): NodeField = resolveFieldPath(FieldPath(path))

    private fun detectType(fieldPath: FieldPath): Facet.Type {
        val node = resolveFieldPath(fieldPath)
        if (node.isEnum) return Facet.Type.ENUM
        return ScalarTypes.categoryOf(node.type) ?: Facet.Type.TEXT
    }

    /** Display name to stored value: the enum name for [asString] columns, else ordinal / `DbValue`. */
    private fun buildEnumValues(enumType: KClass<*>, asString: Boolean): Map<String, Any>? {
        val entries = enumEntries(enumType) ?: return null
        return entries.associate { e ->
            val display = if (e is HumanReadable) e.displayName else e.name
            display to (if (asString) e.name else enumToInt(e))
        }
    }

    internal fun resolveInputParser(column: Facet): InputParser =
        column.inputParser ?: listInputParserAccessor()
            ?: stormify.inputParser ?: InputParser { s, _ -> s }
}
