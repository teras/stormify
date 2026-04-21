// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.NativeBigInteger
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.StormifyAware
import onl.ycode.stormify.TypeUtils
import kotlin.jvm.Transient
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * Abstract base class for `PagedList` — a UI-model façade over the shared
 * [PagedQueryCore] engine. Users do not instantiate this directly; use the
 * platform-specific `PagedList` subclass instead (it provides language-idiomatic
 * constructors for Kotlin and Java).
 *
 * A `PagedList` implements [kotlin.collections.AbstractList], so it behaves as a
 * normal `List<T>` while loading pages on demand from the database. Filtering and
 * sorting are defined through [Facet] objects set up at configuration time. The
 * list caches the current page and the total size, and supports a "selected"
 * entity that always appears first.
 *
 * ## When to use `PagedList`
 *
 * `PagedList` is intended for **UI consumers** — desktop/embedded grids (ZK,
 * Compose, Swing, JavaFX) where a single long-lived list instance drives a view.
 * It is not appropriate for stateless server-side request handling: its per-column
 * filter/sort state is mutable and shared, and its cached page / size / selected
 * entity have no meaning across independent REST requests.
 *
 * For REST/stateless server use, use `PagedQuery` — configure once at startup,
 * call `execute(spec)` per request.
 *
 * The [Stormify] instance is not passed at construction. It is resolved lazily on
 * first access via (in order):
 *  1. The instance explicitly attached via [Stormify.attach]
 *  2. The registered [Stormify.defaultInstance]
 *
 * If neither is available when the list first needs to touch the database, an
 * error is thrown.
 *
 * ## Usage
 * ```kotlin
 * val list = PagedList<Company>()        // no stormify yet
 * stormify.attach(list)                   // binds the instance
 * list.addFacet("name")                  // text filter + sort
 * list.addFacet("contactPerson.firstName",
 *                "contactPerson.lastName") // OR filter via FK
 * list.addSqlFacet("SUM(amount)", Facet.NUMERIC)
 *
 * list.getFacet(0).filter = "Acme"
 * list.getFacet(1).sort = Facet.ASCENDING
 *
 * val company = list[0]   // triggers page load
 * val total = list.size   // triggers COUNT query
 * ```
 *
 * @param T The entity type
 * @param classType The KClass of the entity type
 */
abstract class AbstractPagedList<T : Any> internal constructor(
    val classType: KClass<T>
) : AbstractList<T>(), StormifyAware {

    @Transient internal var _stormify: Stormify? = null

    /** Resolves the Stormify instance — explicitly attached, default, or error. */
    private val stormify: Stormify
        get() = _stormify ?: Stormify.defaultInstance
            ?: throw SQLException(
                "No Stormify instance attached to this PagedList and no default instance " +
                        "is configured. Call stormify.attach(list) or Stormify.asDefault() first."
            )

    internal val core: PagedQueryCore<T> = PagedQueryCore(
        classType = classType,
        stormifyAccessor = { stormify },
        listInputParserAccessor = { inputParser },
    ).also { c ->
        c.invalidateHook = {
            fragment = null
            _size = null
            c.facets.forEach { it.invalidateFilterValues() }
        }
    }

    internal val info get() = core.info

    override fun attachTo(stormify: Stormify) {
        _stormify = stormify
        // The attached Stormify may have different naming policies or registered entities
        // than whatever resolved the cached state previously. Drop the cached metadata
        // and query tree so they get rebuilt on next access.
        core.invalidateCachedMetadata()
        refresh()
    }

    // --- Configuration ---

    /**
     * The columns defined on this list. Columns are added via [addFacet] or [addSqlFacet]
     * and define what can be filtered and sorted.
     */
    val facets: List<Facet> get() = core.facets

    /**
     * Returns the column at the given index.
     */
    fun getFacet(index: Int): Facet = core.getFacet(index)

    /**
     * The page size — the number of elements loaded into memory at a time. Default is 15.
     */
    var pageSize = 15
        set(value) {
            require(value >= 1) { "Page size must be at least 1" }
            if (field != value) refresh()
            field = value
        }

    /**
     * Input parser for this list. Overrides `Stormify.inputParser`.
     */
    var inputParser: InputParser? = null

    /**
     * Whether the query should return only distinct results.
     */
    var isDistinct: Boolean
        get() = core.isDistinct
        set(value) {
            if (core.isDistinct != value) {
                core.isDistinct = value
                refresh()
            }
        }

    /**
     * Sets the selected entity, which will appear first in the list.
     */
    var selected: T? = null
        set(value) {
            if (field != value) {
                selectedID = if (value == null) null
                else TypeUtils.castTo(NativeBigInteger::class, info.getIdValues(value).first(), stormify)
                refresh()
                field = value
            }
        }

    private var selectedID: NativeBigInteger? = null

    /**
     * Sets a fixed constraint (WHERE clause) for this list. This constraint is always
     * applied in addition to any column filters.
     */
    fun setConstraints(query: String, vararg args: Any) {
        core.setConstraints(query, args)
        refresh()
    }

    // --- Facet setup ---

    /**
     * Adds a column with one or more field paths. The column type is auto-detected
     * from the field's Kotlin type. Multiple paths use OR logic for filtering.
     *
     * Field paths use dot notation for foreign-key traversal:
     * `"contactPerson.firstName"`.
     *
     * @param fieldPaths One or more field paths (dot notation)
     * @return The created column
     */
    fun addFacet(vararg fieldPaths: String): Facet =
        registerFacet(core.addFacet(fieldPaths.map { FieldPath(it) }, null, null, null))

    /**
     * Adds a column with an explicit [type] override. Use this when the auto-detected
     * type (based on the field's Kotlin type) is not what you want — for example, to
     * treat a string zip-code column as numeric.
     */
    fun addFacet(type: Facet.Type, vararg fieldPaths: String): Facet =
        registerFacet(core.addFacet(fieldPaths.map { FieldPath(it) }, type, null, null))

    /**
     * Adds an [ENUM][Facet.Type.ENUM] column with a custom display-name-to-DB-value map.
     * Use this when the field is not a Kotlin enum but logically represents one (e.g., a
     * status integer column with human-readable labels), or to override the auto-built
     * map for a real enum field. The `Map` first argument distinguishes this overload
     * from the scalar-typed variants at compile time.
     */
    fun addFacet(enumValues: Map<String, Any>, vararg fieldPaths: String): Facet =
        registerFacet(core.addFacet(fieldPaths.map { FieldPath(it) }, Facet.Type.ENUM, enumValues, null))

    /**
     * Adds a column using type-safe KSP-generated path objects.
     */
    fun addFacet(vararg paths: ScalarPath): Facet =
        registerFacet(core.addFacet(paths.map { FieldPath(it.toPath()) }, null, null, null))

    /** Explicit-type variant of [addFacet] using typed paths. */
    fun addFacet(type: Facet.Type, vararg paths: ScalarPath): Facet =
        registerFacet(core.addFacet(paths.map { FieldPath(it.toPath()) }, type, null, null))

    /** Enum-column variant using typed paths — same rules as the `Map` + `String` overload. */
    fun addFacet(enumValues: Map<String, Any>, vararg paths: ScalarPath): Facet =
        registerFacet(core.addFacet(paths.map { FieldPath(it.toPath()) }, Facet.Type.ENUM, enumValues, null))

    /**
     * Adds a raw/custom column backed by an arbitrary SQL expression.
     *
     * @param expression The SQL expression (e.g., `"SUM(amount)"`, `"COALESCE(a, b)"`)
     * @param type The column type, which determines how filter values are interpreted.
     *             Defaults to [Facet.Type.TEXT].
     * @return The created column
     */
    @kotlin.jvm.JvmOverloads
    fun addSqlFacet(
        expression: String,
        type: Facet.Type = Facet.Type.TEXT
    ): Facet = registerFacet(core.addSqlFacet(expression, type, null, null))

    /**
     * Adds a raw/custom column with a custom [Converter]. The converter receives
     * the column expression and the user's filter value, and returns a SQL fragment
     * while staging bind parameters via [SqlArgsCollector].
     */
    fun addSqlFacet(
        expression: String,
        type: Facet.Type,
        converter: Converter
    ): Facet = registerFacet(core.addSqlFacet(expression, type, converter, null))

    private fun registerFacet(column: Facet): Facet {
        refresh()
        return column
    }

    // --- Table refs ---

    /**
     * Registers a [TableRef] for the root entity table. The ref's [TableRef.alias]
     * resolves to the underlying DB table name, so raw SQL in [setConstraints]
     * or in an [addSqlFacet] expression can safely reference the root without
     * hardcoding the table name.
     */
    fun addTableRef(): TableRef = core.addTableRef().also { refresh() }

    /**
     * Registers a [TableRef] for a table reached through the given dotted [path]
     * (e.g. `"address"`, `"company.hq"`). The final segment must be an FK
     * reference, not a scalar field. The associated JOIN is activated in
     * every subsequent SQL build while the ref's [TableRef.isActive] is `true`.
     */
    fun addTableRef(path: String): TableRef = core.addTableRef(path).also { refresh() }

    /** Registers a [TableRef] from a KSP-generated typed [ReferencePath]. */
    fun addTableRef(path: ReferencePath): TableRef = core.addTableRef(path).also { refresh() }

    // --- List operations ---

    private var fragment: List<T>? = null
    private var lowBound = 0  // inclusive
    private var upperBound = 0 // exclusive
    private var _size: Int? = null

    /**
     * Total number of rows that match the current filters and constraints. The first
     * access issues a `COUNT(*)` (or `COUNT(*) FROM (SELECT DISTINCT ...)` if
     * [isDistinct]) query and caches the result for reuse.
     */
    override val size: Int
        get() = _size ?: run {
            val (query, arguments) = core.buildConstraintPart(excludeFacet = null)
            (stormify.readOne<Int>(core.countSql(core.tablesPart, query), *arguments.toTypedArray()) ?: 0)
                .also { _size = it }
        }

    /**
     * Returns the row at [index]. If the row is not in the currently cached page, a new
     * page is loaded around that index. Out-of-range indices propagate the underlying
     * `IndexOutOfBoundsException` from the loaded page.
     */
    override fun get(index: Int): T = ensurePage(index)[index - lowBound]

    /**
     * Replaces the element at the given index in the cached page.
     * This does NOT update the database — it only affects the in-memory view.
     * The index must be within the currently loaded page.
     *
     * @throws IndexOutOfBoundsException if index is outside the cached page
     */
    operator fun set(index: Int, element: T): T {
        if (index !in lowBound..<upperBound || fragment == null)
            throw IndexOutOfBoundsException("Index $index is outside cached page [$lowBound, $upperBound)")
        val mutableFragment = fragment as? MutableList<T>
            ?: fragment!!.toMutableList().also { fragment = it }
        val old = mutableFragment[index - lowBound]
        mutableFragment[index - lowBound] = element
        return old
    }

    /**
     * Marks [entity] as [selected] so it appears first in the list.
     * Call this after creating the entity via Stormify.
     */
    fun add(entity: T) {
        selected = entity
    }

    /**
     * Clears the current [selected] entity.
     * Call this after deleting the entity via Stormify.
     */
    fun remove(entity: T) {
        selected = null
    }

    /**
     * Searches for the element in the currently cached page.
     * Returns the absolute index, or -1 if not found in the current page.
     */
    override fun indexOf(element: T): Int {
        val frag = fragment ?: return -1
        val localIndex = frag.indexOf(element)
        return if (localIndex >= 0) localIndex + lowBound else -1
    }

    /**
     * Clears all column filters and sorting. Constraints are NOT cleared.
     */
    fun reset() {
        for (column in core.facets) {
            column.filter = null
            column.sort = null
        }
    }

    /**
     * Streams every row matching the current filter / sort / constraint state
     * through [action] via a cursor — a single query that does not materialize
     * the full result set. Use this for exports or bulk processing where
     * paginating through the list's index-based `iterator()` would issue
     * `N / pageSize` queries.
     *
     * Rows are forwarded to [action] in chunks matching the default sibling-
     * batch size, so a foreign-key touch resolves a whole chunk of siblings in
     * one query instead of one per row. See the
     * [PagedList docs](https://ycode.onl/stormify/PagedList/#streaming-with-foreachstreaming).
     */
    fun forEachStreaming(action: (T) -> Unit) {
        core.forEachStreaming(state = null, selectedId = selectedID, action = action)
    }

    /**
     * Captures the per-column filters / sorts / case-sensitivity flags plus the
     * [pageSize] and [isDistinct] flag into a [PagedListState]. Intended for
     * persisting a grid / picker screen's user state across navigation.
     */
    fun saveState(): PagedListState {
        val filters = mutableMapOf<String, String>()
        val sorts = mutableMapOf<String, String>()
        val cs = mutableMapOf<String, Boolean>()
        for (column in core.facets) {
            val key = column.stateKey()
            column.filter?.let { filters[key] = it }
            column.sort?.let {
                sorts[key] = if (it == SortState.ASCENDING) PagedListSort.ASC
                else PagedListSort.DESC
            }
            if (column.isCaseSensitive) cs[key] = true
        }
        return PagedListState(filters, sorts, cs, pageSize, isDistinct)
    }

    /**
     * Re-applies a previously captured [PagedListState]. Keys present in [state]
     * but missing from the current column set are silently ignored (the list's
     * columns may have changed since the state was saved). Sort entries whose
     * value is neither [PagedListSort.ASC] nor [PagedListSort.DESC] are treated
     * as absent.
     */
    fun restoreState(state: PagedListState) {
        for (column in core.facets) {
            val key = column.stateKey()
            column.filter = state.filters[key]
            column.sort = when (state.sorts[key]) {
                PagedListSort.ASC -> SortState.ASCENDING
                PagedListSort.DESC -> SortState.DESCENDING
                else -> null
            }
            column.isCaseSensitive = state.caseSensitive[key] ?: false
        }
        pageSize = state.pageSize
        isDistinct = state.isDistinct
        refresh()
    }

    /**
     * Returns a new [PagedAggregator] bound to this list. Each call returns a
     * fresh aggregator so users can build multiple independent aggregation
     * chains without interference.
     */
    fun getAggregator(): PagedAggregator = PagedAggregator(SingleAggregatorCore(core))

    private fun ensurePage(index: Int): List<T> {
        if (index < 0)
            throw IndexOutOfBoundsException("Index $index out of bounds")
        if (_size != null && index >= _size!!)
            throw IndexOutOfBoundsException("Index $index out of bounds for size ${_size}")
        if (index < lowBound || index >= upperBound)
            fragment = null
        return fragment ?: run {
            val page = index / pageSize
            lowBound = page * pageSize
            val (query, arguments) = core.buildConstraintPart(excludeFacet = null)

            val useMerged = _size == null && stormify.sqlDialect.supportsWindowFunctions
            val columns = if (useMerged) core.windowCountColumns() else "${info.tableName}.*"
            val customFields: Map<String, (Any?) -> Unit>? = if (useMerged)
                mapOf(TOTAL_ALIAS to { v -> _size = (v as Number).toInt() })
            else null

            upperBound = if (_size != null) min(_size!!, lowBound + pageSize) else lowBound + pageSize
            // Use `<table>.*` so JOINs with duplicate column names don't clobber entity mapping
            val result = stormify.read(
                null, classType, stormify.sqlDialect.queryFormatter(
                    columns, core.distinctPart, core.tablesPart, query,
                    core.sortingPart(selectedID), lowBound, upperBound
                ), *arguments.toTypedArray(),
                customFields = customFields
            )
            val observedViaMerged = useMerged && _size != null

            if (_size == null) {
                // Merged query returned 0 rows or dialect lacks window functions —
                // fall back to an explicit count reusing the already-built constraints.
                val resolvedSize = stormify.readOne<Int>(
                    core.countSql(core.tablesPart, query), *arguments.toTypedArray()
                ) ?: 0
                _size = resolvedSize
                if (index >= resolvedSize)
                    throw IndexOutOfBoundsException("Index $index out of bounds for size $resolvedSize")
            }

            upperBound = min(_size!!, lowBound + pageSize)
            // A short page from a classic-path fetch means the cached size is stale
            // (someone deleted rows). When the merged query delivered the size in
            // this same statement, the short page is authoritative — not staleness.
            if (!observedViaMerged && result.size < (upperBound - lowBound) && upperBound <= _size!!)
                _size = null
            fragment = result
            result
        }
    }

    /**
     * Forces the list to re-query the database on its next access.
     *
     * Call this after mutating data outside the list's awareness — e.g.
     * `stormify.create(entity)` / `stormify.update(entity)` / `stormify.delete(entity)`
     * — so the next read reflects the change.
     */
    fun refresh() {
        core.invalidate()
    }

}
