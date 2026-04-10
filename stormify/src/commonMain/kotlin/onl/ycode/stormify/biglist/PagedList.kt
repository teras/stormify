// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package onl.ycode.stormify.biglist

import kotlinx.atomicfu.atomic
import onl.ycode.stormify.NativeBigInteger
import onl.ycode.stormify.Stormify
import onl.ycode.stormify.TableInfo
import onl.ycode.stormify.TypeUtils
import kotlin.math.min
import kotlin.reflect.KClass

/**
 * A lazy-loading, column-based paginated list backed by database queries.
 *
 * Elements are loaded in pages on demand. Filtering and sorting are defined
 * through [Column] objects, which are set up before data access.
 *
 * ## Usage
 * ```kotlin
 * val list = PagedList<Company>()
 * list.addColumn("name")                                    // text filter
 * list.addColumn("contactPerson.firstName",
 *                "contactPerson.lastName")                   // OR filter via FK
 * list.addRawColumn("SUM(amount)", Column.NUMERIC)          // calculated
 *
 * list.getColumn(0).setFilter("Acme")
 * list.getColumn(1).setSort(true)
 *
 * val company = list[0]   // triggers page load
 * val total = list.size   // triggers COUNT query
 * ```
 *
 * @param T The entity type
 * @param classType The KClass of the entity type
 * @param stormify The Stormify instance for database operations
 */
class PagedList<T : Any>(val classType: KClass<T>, private val stormify: Stormify) : AbstractList<T>() {
    private val info: TableInfo<T> = stormify.resolveTableInfo(classType)
    private val tableCounter = atomic(0)
    private val root = NodeTable("", "", classType, null, stormify)
    private val _columns = mutableListOf<Column<T>>()
    private var treeIsDirty = true

    // --- Configuration ---

    /**
     * The columns defined on this list. Columns are added via [addColumn] or [addRawColumn]
     * and define what can be filtered and sorted.
     */
    val columns: List<Column<T>> get() = _columns

    /**
     * Returns the column at the given index.
     */
    fun getColumn(index: Int): Column<T> = _columns[index]

    /**
     * The page size — the number of elements loaded into memory at a time. Default is 15.
     */
    var pageSize = 15
        set(value) {
            require(value >= 1) { "Page size must be at least 1" }
            if (field != value) invalidate()
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
            if (field != value) invalidate()
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
                invalidate()
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
        invalidate()
    }

    // --- Column setup ---

    /**
     * Adds a column with one or more field paths. Multiple paths use OR logic for filtering.
     *
     * Field paths use dot notation for FK traversal: `"contactPerson.firstName"`.
     *
     * The column type is auto-detected from the field type, or can be specified explicitly.
     *
     * @param fieldPaths One or more field paths (dot notation)
     * @param type The column type (auto-detected if null)
     * @param enumValues Enum display-name-to-DB-value mapping (for [Column.ENUM] columns)
     * @return The created column
     */
    @JvmOverloads
    fun addColumn(
        vararg fieldPaths: String,
        type: Column.Type? = null,
        enumValues: Map<String, Any>? = null
    ): Column<T> {
        require(fieldPaths.isNotEmpty()) { "At least one field path is required" }
        val paths = fieldPaths.map { path ->
            val fp = FieldPath(path)
            resolveFieldPath(fp) // validate + build tree
            fp
        }
        val resolvedType = type ?: detectType(paths.first())
        val column = Column(this, paths, resolvedType, enumValues, null, null)
        _columns.add(column)
        invalidate()
        return column
    }

    /**
     * Adds a column using type-safe KSP-generated path objects.
     *
     * @param paths One or more [ScalarPath] objects from generated entity path classes
     * @param type The column type (auto-detected if null)
     * @param enumValues Enum display-name-to-DB-value mapping (for [Column.ENUM] columns)
     * @return The created column
     */
    @JvmOverloads
    fun addColumn(
        vararg paths: ScalarPath,
        type: Column.Type? = null,
        enumValues: Map<String, Any>? = null
    ): Column<T> {
        require(paths.isNotEmpty()) { "At least one path is required" }
        return addColumn(*paths.map { it.toPath() }.toTypedArray(), type = type, enumValues = enumValues)
    }

    /**
     * Adds a raw/custom column backed by an arbitrary SQL expression.
     *
     * @param expression The SQL expression (e.g., `"SUM(amount)"`, `"COALESCE(a, b)"`)
     * @param type The column type, which determines how filter values are interpreted
     * @param sqlGenerator Optional custom SQL generator for filtering. If null, the default
     *        converter for the given type is used.
     * @return The created column
     */
    @JvmOverloads
    fun addRawColumn(
        expression: String,
        type: Column.Type = Column.Type.TEXT,
        sqlGenerator: ((column: String, value: String, args: (Any) -> Unit) -> String)? = null
    ): Column<T> {
        val column = Column(this, emptyList(), type, null, expression, sqlGenerator)
        _columns.add(column)
        invalidate()
        return column
    }

    // --- List operations ---

    private var fragment: List<T>? = null
    private var lowBound = 0  // inclusive
    private var upperBound = 0 // exclusive
    private var _size: Int? = null

    override val size: Int
        get() = _size ?: run {
            val (query, arguments) = constraintPart
            (stormify.readOne<Int>(
                "SELECT ${distinctPart}COUNT(*) FROM ${tablesPart}$query",
                *arguments.toTypedArray()
            ) ?: 0).also { _size = it }
        }

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
     * Marks the entity as selected (appears first) and invalidates the list.
     * Call this after creating the entity via Stormify.
     */
    fun add(entity: T) {
        selected = entity
        invalidate()
    }

    /**
     * Clears the selection and invalidates the list.
     * Call this after deleting the entity via Stormify.
     */
    fun remove(entity: T) {
        selected = null
        invalidate()
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

            if (columnSorts.isNotEmpty()) return columnSorts.joinToString(", ")

            // Default: selected entity first + PK
            val pk = info.primaryKeys.singleOrNull()?.dbName
                ?: throw IllegalStateException(
                    "PagedList for ${info.tableName} requires explicit sorting via column.setSort() " +
                            "because the entity has a composite primary key"
                )
            return (stormify.sqlDialect.orderByIdDialect(pk, selectedID)
                ?.let { "$it, " } ?: "") + info.tableName + "." + pk
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

    internal fun buildConstraintPart(excludeColumn: Column<T>?): Pair<String, List<Any>> {
        resolveCurrentTree()
        val andOut = StringBuilder()
        val args = constraintArgs.toMutableList()

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

            if (column.rawExpression != null) {
                // Raw column
                if (isNullFilter) {
                    orParts.add("${column.rawExpression} IS NULL")
                } else {
                    val generator = column.sqlGenerator?.let { gen ->
                        { col: String, input: String, _: InputParser, args: (Any) -> Unit -> gen(col, input, args) }
                    } ?: DefaultDataConverter.guessConverter(column.type, stormify.sqlDialect, column.enumValues)
                    orParts.add(generator(column.rawExpression, filterVal, parser, args::add))
                }
            } else {
                // Field-based column — OR between fields
                for (fieldPath in column.fields) {
                    val node = resolveFieldPath(fieldPath)
                    if (isNullFilter) {
                        orParts.add("${node.columnHandler} IS NULL")
                    } else {
                        val generator = column.sqlGenerator?.let { gen ->
                            { col: String, input: String, _: InputParser, args: (Any) -> Unit -> gen(col, input, args) }
                        } ?: DefaultDataConverter.guessConverterForNode(node, column.type, stormify.sqlDialect, { column.isCaseSensitive }, column.enumValues)
                        orParts.add(generator(node.columnHandler, filterVal, parser, args::add))
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
            val result = stormify.read(
                null, classType, stormify.sqlDialect.queryFormatter(
                    "*", distinctPart, tablesPart, query, sortingPart, lowBound, upperBound
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

    internal fun invalidate() {
        fragment = null
        _size = null
        treeIsDirty = true
        _columns.forEach { it.invalidateSelectionValues() }
    }

    internal fun getStormify(): Stormify = stormify

    internal fun getTablesPart(): String = tablesPart

    internal fun resolveColumnExpression(column: Column<T>): String {
        if (column.rawExpression != null) return column.rawExpression
        require(column.fields.isNotEmpty()) { "Column has no fields" }
        return resolveFieldPath(column.fields.first()).columnHandler
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
        val typeName = node.type.simpleName ?: return Column.Type.TEXT
        return when {
            typeName in setOf("String", "Char", "StringBuilder") -> Column.Type.TEXT
            typeName in setOf("Int", "Long", "Short", "Byte", "Float", "Double",
                "BigDecimal", "BigInteger") -> Column.Type.NUMERIC
            typeName.contains("Date") || typeName == "LocalDate" -> Column.Type.DATE
            typeName.contains("Time") || typeName.contains("Instant") ||
                typeName.contains("Timestamp") -> Column.Type.TEMPORAL
            else -> Column.Type.TEXT
        }
    }

    internal fun resolveInputParser(column: Column<T>): InputParser =
        if (column.inputParser !== NoInputParser) column.inputParser
        else if (inputParser !== NoInputParser) inputParser
        else defaultInputParser

    companion object {
        /**
         * Global input parser for all PagedList instances. Overridden by
         * [PagedList.inputParser] (per-list) and [Column.inputParser] (per-column).
         * @see InputParser
         */
        @JvmStatic
        var defaultInputParser: InputParser = NoInputParser

        /**
         * The string representation of a null value. Use this to search for NULL values
         * in a filter instead of using a regular null.
         */
        const val NULL: String = "―"
    }
}

/**
 * Creates a new [PagedList] for the given entity type.
 */
inline fun <reified T : Any> PagedList(stormify: Stormify) = PagedList(T::class, stormify)

/**
 * Creates a new [PagedList] for the given entity type using the [default Stormify instance][Stormify.defaultInstance].
 */
inline fun <reified T : Any> PagedList() =
    PagedList(T::class, Stormify.defaultInstance ?: error("No default Stormify instance configured; call Stormify.asDefault() first"))
