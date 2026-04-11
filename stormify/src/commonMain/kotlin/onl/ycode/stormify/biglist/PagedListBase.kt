// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import kotlinx.atomicfu.atomic
import onl.ycode.stormify.NativeBigInteger
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.StormifyAware
import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.TypeUtils
import onl.ycode.stormify.enumEntries
import onl.ycode.stormify.enumToInt
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * Abstract base class for `PagedList` — contains the full column-based paginated-list
 * implementation. Users do not instantiate this directly; use the platform-specific
 * `PagedList` subclass instead (it provides language-idiomatic constructors for Kotlin
 * and Java).
 *
 * A `PagedList` implements [kotlin.collections.AbstractList], so it behaves as a normal
 * `List<T>` while loading pages on demand from the database. Filtering and sorting are
 * defined through [Column] objects set up at configuration time.
 *
 * The [Stormify] instance is not passed at construction. It is resolved lazily on first
 * access via (in order):
 *  1. The instance explicitly attached via [Stormify.attach]
 *  2. The registered [Stormify.defaultInstance]
 *
 * If neither is available when the list first needs to touch the database, an error is
 * thrown.
 *
 * ## Usage
 * ```kotlin
 * val list = PagedList<Company>()        // no stormify yet
 * stormify.attach(list)                   // binds the instance
 * list.addColumn("name")                  // text filter + sort
 * list.addColumn("contactPerson.firstName",
 *                "contactPerson.lastName") // OR filter via FK
 * list.addRawColumn("SUM(amount)", Column.NUMERIC)
 *
 * list.getColumn(0).filter = "Acme"
 * list.getColumn(1).sort = Column.ASCENDING
 *
 * val company = list[0]   // triggers page load
 * val total = list.size   // triggers COUNT query
 * ```
 *
 * @param T The entity type
 * @param classType The KClass of the entity type
 */
abstract class PagedListBase<T : Any> internal constructor(
    val classType: KClass<T>
) : AbstractList<T>(), StormifyAware {

    override var `!stormify`: Stormify? = null

    override fun onAttached() {
        // The attached Stormify may have different naming policies or registered entities
        // than whatever resolved the cached state previously. Drop the cached metadata
        // and query tree so they get rebuilt on next access.
        _info = null
        _root = null
        refresh()
    }

    /** Resolves the Stormify instance — explicitly attached, default, or error. */
    private val stormify: Stormify
        get() = `!stormify` ?: Stormify.defaultInstance
            ?: error(
                "No Stormify instance attached to this PagedList and no default instance " +
                        "is configured. Call stormify.attach(list) or Stormify.asDefault() first."
            )

    // Lazy table metadata — resolved on first access so construction does not require Stormify
    private var _info: TableInfo<T>? = null
    internal val info: TableInfo<T>
        get() = _info ?: @Suppress("UNCHECKED_CAST") (stormify.resolveTableInfo(classType) as TableInfo<T>)
            .also { _info = it }

    private val tableCounter = atomic(0)
    private var _root: NodeTable? = null
    private val root: NodeTable
        get() = _root ?: NodeTable("", "", classType, null, stormify).also { _root = it }

    private val _columns = mutableListOf<Column>()
    private var treeIsDirty = true

    // --- Configuration ---

    /**
     * The columns defined on this list. Columns are added via [addColumn] or [addRawColumn]
     * and define what can be filtered and sorted.
     */
    val columns: List<Column> get() = _columns

    /**
     * Returns the column at the given index.
     */
    fun getColumn(index: Int): Column = _columns[index]

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
     * Input parser for this list. Overrides [defaultInputParser].
     * Set to [NoInputParser] (default) to fall through to the global level.
     * @see InputParser
     */
    var inputParser: InputParser = NoInputParser

    /**
     * Whether the query should return only distinct results.
     */
    var isDistinct = false
        set(value) {
            if (field != value) refresh()
            field = value
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
    private var constraintClause = ""
    private var constraintArgs: Array<out Any> = emptyArray()

    /**
     * Sets a fixed constraint (WHERE clause) for this list. This constraint is always
     * applied in addition to any column filters.
     */
    fun setConstraints(query: String, vararg args: Any) {
        constraintClause = query.trim()
        this.constraintArgs = args
        refresh()
    }

    // --- Column setup ---

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
    fun addColumn(vararg fieldPaths: String): Column =
        addColumnInternal(fieldPaths.map { FieldPath(it) }, null, null)

    /**
     * Adds a column with an explicit [type] override. Use this when the auto-detected
     * type (based on the field's Kotlin type) is not what you want — for example, to
     * treat a string zip-code column as numeric.
     */
    fun addColumn(type: Column.Type, vararg fieldPaths: String): Column =
        addColumnInternal(fieldPaths.map { FieldPath(it) }, type, null)

    /**
     * Adds an [ENUM][Column.Type.ENUM] column with a custom display-name-to-DB-value map.
     * Use this when the field is not a Kotlin enum but logically represents one (e.g., a
     * status integer column with human-readable labels), or to override the auto-built
     * map for a real enum field.
     */
    fun addEnumColumn(enumValues: Map<String, Any>, vararg fieldPaths: String): Column =
        addColumnInternal(fieldPaths.map { FieldPath(it) }, Column.Type.ENUM, enumValues)

    /**
     * Adds a column using type-safe KSP-generated path objects.
     */
    fun addColumn(vararg paths: ScalarPath): Column =
        addColumnInternal(paths.map { FieldPath(it.toPath()) }, null, null)

    /** Explicit-type variant of [addColumn] using typed paths. */
    fun addColumn(type: Column.Type, vararg paths: ScalarPath): Column =
        addColumnInternal(paths.map { FieldPath(it.toPath()) }, type, null)

    /** Enum-column variant using typed paths. */
    fun addEnumColumn(enumValues: Map<String, Any>, vararg paths: ScalarPath): Column =
        addColumnInternal(paths.map { FieldPath(it.toPath()) }, Column.Type.ENUM, enumValues)

    private fun addColumnInternal(
        paths: List<FieldPath>,
        type: Column.Type?,
        enumValues: Map<String, Any>?
    ): Column {
        require(paths.isNotEmpty()) { "At least one field path is required" }
        paths.forEach { resolveFieldPath(it) } // validate + build tree
        val resolvedType = type ?: detectType(paths.first())
        val resolvedEnumValues = enumValues ?: if (resolvedType == Column.Type.ENUM)
            buildEnumValues(resolveFieldPath(paths.first()).type) else null
        val column = Column(this, paths, resolvedType, resolvedEnumValues, null, null)
        _columns.add(column)
        refresh()
        return column
    }

    /**
     * Adds a raw/custom column backed by an arbitrary SQL expression.
     *
     * @param expression The SQL expression (e.g., `"SUM(amount)"`, `"COALESCE(a, b)"`)
     * @param type The column type, which determines how filter values are interpreted.
     *             Defaults to [Column.Type.TEXT].
     * @return The created column
     */
    @JvmOverloads
    fun addRawColumn(
        expression: String,
        type: Column.Type = Column.Type.TEXT
    ): Column = addRawColumnInternal(expression, type, null)

    /**
     * Adds a raw/custom column with a custom SQL generator. The [sqlGenerator] receives
     * the column expression and the user's filter value, and returns a SQL fragment
     * while staging bind parameters via [SqlArgsCollector].
     */
    fun addRawColumn(
        expression: String,
        type: Column.Type,
        sqlGenerator: SqlGenerator
    ): Column = addRawColumnInternal(expression, type, sqlGenerator)

    private fun addRawColumnInternal(
        expression: String,
        type: Column.Type,
        sqlGenerator: SqlGenerator?
    ): Column {
        val column = Column(this, emptyList(), type, null, expression, sqlGenerator)
        _columns.add(column)
        refresh()
        return column
    }

    // --- List operations ---

    private var fragment: List<T>? = null
    private var lowBound = 0  // inclusive
    private var upperBound = 0 // exclusive
    private var _size: Int? = null

    /**
     * Total number of rows that match the current filters and constraints. The first
     * access issues a `COUNT(*)` (or `COUNT(DISTINCT ...)` if [isDistinct]) query and
     * caches the result for reuse.
     */
    override val size: Int
        get() = _size ?: run {
            val (query, arguments) = constraintPart
            (stormify.readOne<Int>(
                "SELECT ${distinctPart}COUNT(*) FROM ${tablesPart}$query",
                *arguments.toTypedArray()
            ) ?: 0).also { _size = it }
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
        if (index < lowBound || index >= upperBound || fragment == null)
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
        for (column in _columns) {
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
     * Kotlin resolves this member in preference to the
     * [kotlin.collections.Iterable.forEach] extension, so
     * `list.forEach { ... }` benefits automatically.
     */
    fun forEach(action: (T) -> Unit) {
        val (query, arguments) = constraintPart
        val sql = "SELECT ${distinctPart}${info.tableName}.* FROM ${tablesPart}$query ORDER BY $sortingPart"
        stormify.readCursor(null, classType, sql, *arguments.toTypedArray()) { row -> action(row) }
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
        for (column in _columns) {
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
        for (column in _columns) {
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
    fun getAggregator(): PagedAggregator = PagedAggregator(SingleAggregatorCore(this))

    // --- SQL generation ---

    private val distinctPart: String
        get() = if (isDistinct) "DISTINCT " else ""

    private val sortingPart: String
        get() {
            resolveCurrentTree()
            // Collect active column sorts
            val columnSorts = _columns
                .filter { it.hasActiveSort() }
                .flatMap { col ->
                    val direction = if (col.sort == SortState.DESCENDING) " DESC" else ""
                    if (col.fields.isNotEmpty())
                        listOf(resolveFieldPath(col.fields.first()).columnHandler + direction)
                    else if (col.rawExpression != null)
                        listOf(col.rawExpression + direction)
                    else
                        emptyList()
                }

            // Selected entity always appears first (regardless of explicit sort)
            val singlePk = info.primaryKeys.singleOrNull()?.dbName
            val selectedPrefix = if (singlePk != null && selectedID != null)
                stormify.sqlDialect.orderByIdDialect(singlePk, selectedID)?.let { "$it, " } ?: ""
            else ""

            if (columnSorts.isNotEmpty())
                return selectedPrefix + columnSorts.joinToString(", ")

            // Default: selected first + PK
            val pk = singlePk ?: throw IllegalStateException(
                "PagedList for ${info.tableName} requires explicit sorting via column.sort = " +
                        "because the entity has a composite primary key"
            )
            return selectedPrefix + info.tableName + "." + pk
        }

    private val tablesPart: String
        get() {
            resolveCurrentTree()
            val out = StringBuilder(info.tableName)
            root.appendJoins(out)
            return out.toString()
        }

    private val constraintPart: Pair<String, List<Any>>
        get() = buildConstraintPart(excludeColumn = null)

    internal fun buildConstraintPart(excludeColumn: Column?): Pair<String, List<Any>> {
        resolveCurrentTree()
        val andOut = StringBuilder()
        val args = constraintArgs.toMutableList()
        val argsCollector = SqlArgsCollector { args.add(it) }

        // Fixed constraints
        if (constraintClause.isNotEmpty())
            andOut.append(constraintClause)

        // Column filters
        for (column in _columns) {
            if (column === excludeColumn) continue
            val filterVal = column.filter ?: continue
            val isNullFilter = filterVal == NULL
            val parser = resolveInputParser(column)
            val orParts = mutableListOf<String>()

            // Wrap custom sqlGenerator to apply the InputParser before it sees the value
            fun wrapUserGenerator(gen: SqlGenerator): (String, String, InputParser, SqlArgsCollector) -> String =
                { col, input, p, a -> gen.generate(col, p.parse(input, column.type), a) }

            if (column.rawExpression != null) {
                // Raw column
                if (isNullFilter) {
                    orParts.add("${column.rawExpression} IS NULL")
                } else {
                    val generator = column.sqlGenerator?.let(::wrapUserGenerator)
                        ?: DefaultDataConverter.guessConverter(column.type, stormify.sqlDialect, column.enumValues)
                    orParts.add(generator(column.rawExpression, filterVal, parser, argsCollector))
                }
            } else {
                // Field-based column — OR between fields
                for (fieldPath in column.fields) {
                    val node = resolveFieldPath(fieldPath)
                    if (isNullFilter) {
                        orParts.add("${node.columnHandler} IS NULL")
                    } else {
                        val generator = column.sqlGenerator?.let(::wrapUserGenerator)
                            ?: DefaultDataConverter.guessConverterForNode(
                                node, column.type, stormify.sqlDialect,
                                { column.isCaseSensitive }, column.enumValues
                            )
                        orParts.add(generator(node.columnHandler, filterVal, parser, argsCollector))
                    }
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

    private fun ensurePage(index: Int): List<T> {
        if (index < 0 || index >= size)
            throw IndexOutOfBoundsException("Index $index out of bounds for size $size")
        if (index < lowBound || index >= upperBound)
            fragment = null
        return fragment ?: run {
            val page = index / pageSize
            lowBound = page * pageSize
            upperBound = min(size, (page + 1) * pageSize)
            val (query, arguments) = constraintPart
            // Use `<table>.*` so JOINs with duplicate column names don't clobber entity mapping
            val result = stormify.read(
                null, classType, stormify.sqlDialect.queryFormatter(
                    "${info.tableName}.*", distinctPart, tablesPart, query, sortingPart, lowBound, upperBound
                ), *arguments.toTypedArray()
            )
            // Silent re-count: if page returned fewer items than expected
            if (result.size < (upperBound - lowBound) && upperBound <= size) {
                _size = null // force re-count on next access
            }
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
        fragment = null
        _size = null
        treeIsDirty = true
        _columns.forEach { it.invalidateFilterValues() }
    }

    internal fun getStormify(): Stormify = stormify

    internal fun getTablesPart(): String = tablesPart

    internal fun resolveColumnExpression(column: Column): String {
        if (column.rawExpression != null) return column.rawExpression
        require(column.fields.isNotEmpty()) { "Column has no fields" }
        return resolveFieldPath(column.fields.first()).columnHandler
    }

    /**
     * Resolves a dot-notation field path to its fully-qualified SQL column
     * expression (with the necessary joins registered on the tree). Used by
     * [SingleAggregatorCore] to build aggregate `SELECT` lists.
     */
    internal fun resolveAggregateExpression(path: String): String {
        val node = resolveFieldPath(FieldPath(path))
        node.activate()
        treeIsDirty = true // ensures tablesPart regenerates joins on next access
        return node.columnHandler
    }

    private fun resolveCurrentTree() {
        if (!treeIsDirty) return
        treeIsDirty = false
        root.deactivate()
        root.activate()
        for (column in _columns) {
            if (!column.hasActiveFilter() && !column.hasActiveSort()) continue
            for (fieldPath in column.fields)
                resolveFieldPath(fieldPath).activate()
        }
    }

    private fun resolveFieldPath(fieldPath: FieldPath): NodeField {
        var last: Node = root
        for (segment in fieldPath.segments)
            last = last.findChild(segment) { tableCounter.incrementAndGet() }
        require(last is NodeField) { "Last field in path '${fieldPath.path}' must be a scalar, not a table reference" }
        return last
    }

    private fun detectType(fieldPath: FieldPath): Column.Type {
        val node = resolveFieldPath(fieldPath)
        if (node.isEnum) return Column.Type.ENUM
        val typeName = node.type.simpleName ?: return Column.Type.TEXT
        return when {
            typeName in setOf("String", "Char", "StringBuilder") -> Column.Type.TEXT
            typeName in setOf(
                "Int", "Long", "Short", "Byte", "Float", "Double",
                "BigDecimal", "BigInteger"
            ) -> Column.Type.NUMERIC

            typeName.contains("Date") || typeName.contains("Time") ||
                    typeName.contains("Instant") || typeName.contains("Timestamp") -> Column.Type.TEMPORAL

            else -> Column.Type.TEXT
        }
    }

    private fun buildEnumValues(enumType: KClass<*>): Map<String, Any>? {
        val entries = enumEntries(enumType) ?: return null
        return entries.associate { e ->
            val display = if (e is HumanReadable) e.displayName() else e.name
            display to enumToInt(e)
        }
    }

    internal fun resolveInputParser(column: Column): InputParser =
        if (column.inputParser !== NoInputParser) column.inputParser
        else if (inputParser !== NoInputParser) inputParser
        else defaultInputParser

    /** Global configuration shared by every `PagedList` instance. */
    companion object {
        /**
         * Global input parser for all PagedList instances. Overridden by
         * [PagedListBase.inputParser] (per-list) and [Column.inputParser] (per-column).
         * @see InputParser
         */
        @JvmStatic
        var defaultInputParser: InputParser = NoInputParser

        /**
         * The string representation of a null value. Use this to search for NULL values
         * in a filter instead of using a regular null.
         */
        const val NULL: String = "―"

        /** Classloader-leak cleanup hook — on JVM, invoked by `StormifyLifecycle.clear()`. */
        internal fun clearDefaultInputParser() { defaultInputParser = NoInputParser }
    }
}
