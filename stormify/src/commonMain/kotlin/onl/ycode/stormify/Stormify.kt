// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify

import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.synchronized
import onl.ycode.kdbc.*
import onl.ycode.logger.LogManager
import onl.ycode.stormify.SqlDialect.GeneratedKeyRetrieval
import onl.ycode.stormify.Stormify.Companion.defaultInstance
import onl.ycode.stormify.TypeUtils.castTo
import onl.ycode.stormify.biglist.AbstractPagedList
import onl.ycode.stormify.biglist.FilterSyntax
import onl.ycode.stormify.biglist.InputParser
import onl.ycode.stormify.biglist.ReferencePath
import kotlin.reflect.KClass


private class Reference<T>(var item: T? = null)
private class FixedParams(val query: String, val params: List<Any?>)

/**
 * The main ORM controller. Entry point for all database operations.
 *
 * ## Configuration lifecycle
 *
 * A `Stormify` instance is designed to be **configured once, then shared** across
 * threads. The intended usage is:
 *
 * 1. Construct the instance (optionally passing registrars).
 * 2. Apply configuration — assign to [namingPolicy], [unmatchedColumnPolicy],
 *    [logger], [filterSyntax], [inputParser]; call [addBlacklistField],
 *    [registerPrimaryKeyResolver], etc.
 * 3. Publish the instance to whoever needs it (DI container, `asDefault()`,
 *    dependency parameter, …) and start issuing queries.
 *
 * After step 3, treat all configuration as frozen. Mutating configuration
 * while other threads are executing queries is not supported — behaviour is
 * undefined and any entity metadata already cached will not reflect the
 * change. The query path itself (CRUD, `read`, `findAll`, stored procedures,
 * transactions) is safe to call concurrently from many threads on a
 * fully-configured instance.
 *
 * @param dataSource the data source for all database operations
 * @param registrars optional entity registrars to register at construction time
 */
class Stormify(val dataSource: DataSource, vararg registrars: EntityRegistrar) {

    /**
     * Single-argument convenience constructor. Exists primarily so Spring XML
     * `<constructor-arg ref="dataSource"/>` (and similar DI containers that
     * resolve constructors by arity) can pick an unambiguous one-arg match.
     */
    constructor(dataSource: DataSource) : this(dataSource, *emptyArray())

    init {
        for (r in registrars) EntityMeta.invokeRegistrar(r)
    }

    /** Holds the library-wide [defaultInstance] used when no instance is explicitly attached. */
    companion object {
        private val _defaultInstance = kotlinx.atomicfu.atomic<Stormify?>(null)

        /**
         * The default Stormify instance, used by [AutoTable], [PagedList][AbstractPagedList], and the
         * Kotlin entity extensions when no instance is explicitly attached. Set this
         * once at startup via [asDefault]; callers can then omit the receiver.
         */
        var defaultInstance: Stormify?
            get() = _defaultInstance.value
            private set(value) {
                _defaultInstance.value = value
            }

        /** Classloader-leak cleanup hook — on JVM, invoked by `StormifyLifecycle.clear()`. */
        internal fun clearDefault() { _defaultInstance.value = null }
    }

    /** Sets this instance as [defaultInstance] and returns it. */
    fun asDefault(): Stormify {
        defaultInstance = this
        return this
    }

    /**
     * Runs [block] with this instance as the default, restoring the previous default
     * when the block exits. Use for scoped overrides such as per-request tenants.
     *
     * ```kotlin
     * stormify.asDefault { s -> s.read<User>(...) }
     * ```
     */
    fun <R> asDefault(block: (Stormify) -> R): R {
        val previous = defaultInstance
        defaultInstance = this
        try {
            return block(this)
        } finally {
            defaultInstance = previous
        }
    }

    // --- Policies ---

    /**
     * The naming policy used to convert Kotlin property names to database column names.
     * Default is [NamingPolicy.LOWER_CASE_WITH_UNDERSCORES] (snake_case).
     * Changing this only affects entities resolved after the change.
     */
    @Volatile
    var namingPolicy: (String) -> String = NamingPolicy.LOWER_CASE_WITH_UNDERSCORES
    // Exclude common Java/JPA base-class fields that should never be mapped to database columns
    private val blacklist = mutableSetOf("serialVersionUID", "idFieldValue", "transientId")
    private val pkResolvers = mutableMapOf<Int, (String, String) -> Boolean>()

    /** Excludes a field name from all entity mappings (e.g. inherited fields that have no database column). */
    fun addBlacklistField(name: String) { blacklist.add(name) }

    /** Removes a previously blacklisted field name, allowing it to be mapped again. */
    fun removeBlacklistField(name: String) { blacklist.remove(name) }

    /**
     * Registers a primary key resolver used when no `@Id` or `@DbField(primaryKey=true)` annotation is present.
     * The [resolver] receives the table name and field name and returns `true` if the field is a primary key.
     * Lower [priority] values are evaluated first.
     */
    fun registerPrimaryKeyResolver(priority: Int, resolver: (String, String) -> Boolean) {
        pkResolvers[priority] = resolver
    }

    // --- Resolve pipeline ---
    // The cache is lazily populated on the query hot path, so its getOrPut must be
    // atomic even though configuration above is documented as startup-only.

    private val cacheLock = kotlinx.atomicfu.locks.SynchronizedObject()
    private val tableInfoCache = mutableMapOf<KClass<*>, TableInfo<*>>()

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> resolveTableInfo(type: KClass<out T>): TableInfo<T> =
        synchronized(cacheLock) {
            tableInfoCache.getOrPut(type) {
                val meta = EntityMeta.find(type) ?: tryReflection(type)
                ?: throw SQLException("Unknown entity: ${type.simpleName}")
                TableInfo.build(meta, namingPolicy, blacklist, pkResolvers.entries.sortedBy { it.key }.map { it.value })
            } as TableInfo<T>
        }

    // --- SQL Dialect ---
    @Volatile
    private var _sqlDialect: SqlDialect? = null

    /**
     * The SQL dialect used by this Stormify instance. Auto-detected from the data source
     * on first access. Can be set manually for proxy scenarios where auto-detection fails.
     */
    var sqlDialect: SqlDialect
        get() {
            if (_sqlDialect == null)
                _sqlDialect = try {
                    SqlDialect.findDialect(dataSource)
                } catch (e: Throwable) {
                    e.throwQuery("Unable to find SQL dialect")
                }
            return _sqlDialect!!
        }
        set(value) {
            _sqlDialect = value
        }

    // --- Configuration ---

    /**
     * Policy for `ResultSet` columns that have no matching field on the target
     * entity. Default is [UnmatchedColumnPolicy.IGNORE].
     */
    @Volatile
    var unmatchedColumnPolicy: UnmatchedColumnPolicy = UnmatchedColumnPolicy.IGNORE

    /** The logger used by this Stormify instance. Defaults to a logger named "Stormify". */
    @Volatile
    var logger = LogManager.getLogger("Stormify")

    /**
     * The filter syntax used by facets on this instance. Controls which tokens
     * represent OR, NOT, NULL, phrase delimiters, grouping, and wildcards.
     * Default is Google-like syntax: `OR`, `-`, `NULL`, `"`, `()`, `*`.
     */
    @Volatile
    var filterSyntax: FilterSyntax = FilterSyntax()

    /**
     * Default input parser for facet filter values. Applied after per-facet and
     * per-list parsers in the resolution chain. Use for locale-aware number/date
     * parsing across all facets.
     */
    @Volatile
    var inputParser: InputParser? = null

    // --- Internal connection management ---

    private inner class ConnectionMaker(connection: Connection?) : AutoCloseable {
        // Explicit > ambient > fresh. When the caller passed null we first
        // check for an active transaction opened by *this* Stormify instance
        // on the current thread (populated by `Stormify.transaction { }` or
        // `SuspendStormify.transaction { }`). Borrowed connections (explicit
        // or ambient) must not be closed here — they are owned by the
        // surrounding transaction scope.
        private val ambient = if (connection == null) ActiveTxRegistry.currentFor(this@Stormify) else null
        val connection by lazy { connection ?: ambient ?: dataSource.getConnection() }
        val shouldClose = connection == null && ambient == null
        override fun close() {
            try {
                if (shouldClose) connection.close()
            } catch (e: Throwable) {
                e.throwQuery("Unable to close connection")
            }
        }
    }

    // --- Parameter handling ---

    private fun fixParams(givenQuery: String, args: List<Any?>): FixedParams {
        if (args.isEmpty()) return FixedParams(givenQuery, emptyList<Any>())
        val params: MutableList<Any?> = mutableListOf()
        val query = StringBuilder(givenQuery.length)
        var countQuestionMarks = 0
        for (i in givenQuery.indices) {
            if (givenQuery[i] == '?') {
                if (countQuestionMarks >= args.size) throw SQLException(
                    ("The number of placeholders (" + count(
                        givenQuery,
                        '?'
                    )) + ") in query '" + givenQuery + "' exceeds the number of parameters (" + args.size + ")"
                )
                val arg = sqlData(args[countQuestionMarks++], true)
                if (arg is List<*>) {
                    query.append("(").append(nCopies("?", ", ", arg.size)).append(")")
                    params.addAll(arg)
                } else {
                    query.append("?")
                    params.add(arg)
                }
            } else query.append(givenQuery[i])
        }
        if (countQuestionMarks != args.size) throw SQLException(
            "The number of placeholders (" + count(givenQuery, '?')
                    + ") in query '" + givenQuery + "' is less than the number of parameters (" + args.size + ")"
        )
        return FixedParams(query.toString(), params)
    }

    private fun bindAndLog(stmt: Statement, query: String, params: List<Any?>) {
        _dbLog(query, *params.toTypedArray())
        for (i in params.indices)
            stmt.setObject(i + 1, params[i])
    }

    private fun <T> performQuery(
        conn: Connection?,
        givenQuery: String,
        givenParams: List<Any?>,
        batchItems: List<*>? = null,
        batchParamOf: ((Any?) -> List<Any?>)? = null,
        generatedKeys: Boolean = false,
        code: (Statement) -> T
    ): T {
        val params = fixParams(givenQuery, givenParams)
        val errorMsg = if (params.params.isEmpty()) "" else " with values ${params.params}"
        return ConnectionMaker(conn).useWithException("Unable to execute query '${params.query}'$errorMsg") { maker ->
            maker.connection.initStatement(params.query, generatedKeys, null).use { stmt ->
                if (batchItems != null && batchParamOf != null) {
                    for (item in batchItems) {
                        bindAndLog(stmt, params.query, batchParamOf(item))
                        stmt.addBatch()
                    }
                } else {
                    bindAndLog(stmt, params.query, params.params)
                }
                code(stmt)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun createReferenceStub(refType: KClass<*>, idValue: Any): Any {
        val refInfo = resolveTableInfo(refType) as TableInfo<Any>
        val wrapper = refInfo.create()
        if (wrapper is StormifyEntity) wrapper._stormify = this
        refInfo.setField(wrapper, refInfo.idDbNames[0], idValue, this)
        return wrapper
    }

    private fun sqlData(value: Any?, recursively: Boolean): Any? {
        if (value == null || isScalarObject(value))
            return if (value is CharArray) value.concatToString() else value
        if (value is Enum<*>)
            return enumToInt(value)
        if (recursively) {
            if (value is Array<*>)
                return sqlData(value.toList(), true)
            if (value is Iterable<*>)
                return value.map { sqlData(it, false) }
        }
        @Suppress("UNCHECKED_CAST")
        val info = resolveTableInfo(value::class) as TableInfo<Any>
        return if (info.idNames.size == 1) info.getIdValues(value)[0] else
            throw SQLException("Multiple primary keys found in ${info.tableName}")
    }

    // --- Attach stormify to entity ---

    /**
     * Attaches this Stormify instance to [target] so the target can use it for database
     * operations without receiving it as an explicit parameter.
     *
     * Works for any [StormifyAware] — [StormifyEntity] subclasses (entities, [AutoTable])
     * and [PagedList][AbstractPagedList] instances. Returns [target] for fluent chaining.
     *
     * ```kotlin
     * // Manual stub — user knows the ID, lets Stormify lazy-load the rest
     * val user = stormify.attach(User().apply { id = 42 })
     * println(user.name)  // triggers SELECT via the attached instance
     *
     * // Paged list — attach before use (or rely on Stormify.defaultInstance)
     * val list = stormify.attach(PagedList<Company>())
     * list.addFacet("name")
     * ```
     */
    fun <T : StormifyAware> attach(target: T): T = target.also { it.attachTo(this) }

    private fun attachStormify(target: Any) {
        if (target is StormifyAware) target.attachTo(this)
    }

    // --- Read operations ---

    /** Executes an SQL UPDATE/INSERT/DELETE and returns the number of affected rows. */
    @Throws(SQLException::class)
    fun executeUpdate(query: String, vararg params: Any?) =
        executeUpdate(null, query, *params)

    internal fun executeUpdate(conn: Connection?, query: String, vararg params: Any?): Int {
        return performQuery(conn, query, params.toList(), code = { it.executeUpdate() })
    }

    /**
     * Executes a SELECT query and processes results row-by-row via [consumer]. Returns row count.
     *
     * @param customFields optional per-column interceptors. Any column whose label matches a
     * key (case-insensitively) is passed to the associated lambda instead of being mapped onto the
     * entity, allowing sidecar aggregates (e.g. `COUNT(*) OVER () AS __total`) to be captured in
     * a single roundtrip without breaking entity mapping. Ignored for `Map` and scalar result
     * types since those expose every column to the caller directly.
     */
    @Throws(SQLException::class)
    inline fun <reified T : Any> readCursor(
        query: String,
        vararg params: Any?,
        customFields: Map<String, (Any?) -> Unit>? = null,
        noinline consumer: (T) -> Unit
    ) = readCursor(null, T::class, query, *params, customFields = customFields, consumer = consumer)

    @PublishedApi
    internal fun <T : Any> readCursor(
        conn: Connection?,
        baseClass: KClass<T>,
        query: String,
        vararg params: Any?,
        customFields: Map<String, (Any?) -> Unit>? = null,
        consumer: (T) -> Unit
    ) = performQuery(conn, query, params.toList(), code = { statement ->
        val isMap = Map::class == baseClass
        val info = if (TypeConversion.isKnownScalar(baseClass) || isMap) null else resolveTableInfo(baseClass)
        val normalizedCustomFields = customFields?.mapKeys { it.key.lowercase() }
        statement.executeQuery().use { rs ->
            val context = if (info != null) PopulationContext() else null
            var count = 0
            while (rs.next()) {
                count++
                if (isMap) {
                    val meta = rs.getMetaData()
                    val row = LinkedHashMap<String, Any?>()
                    for (i in 1..meta.columnCount)
                        row[meta.getColumnLabel(i).lowercase()] = rs.getObject(i, Any::class)
                    @Suppress("UNCHECKED_CAST")
                    consumer(row as T)
                } else {
                    consumer(
                        if (info != null) populate(
                            info.create().also { attachStormify(it) }, rs, context, normalizedCustomFields
                        )
                        else castTo(baseClass, rs.getObject(1, baseClass), this)
                            ?: throw SQLException("Expecting type ${baseClass.fullName} but found null")
                    )
                }
            }
            count
        }
    })

    /**
     * Executes a SELECT query and returns all results as a list.
     *
     * @param customFields optional per-column interceptors. See [readCursor] for semantics.
     */
    @Throws(SQLException::class)
    inline fun <reified T : Any> read(
        query: String,
        vararg params: Any?,
        customFields: Map<String, (Any?) -> Unit>? = null
    ): List<T> = read(null, T::class, query, *params, customFields = customFields)

    @PublishedApi
    internal fun <T : Any> read(
        conn: Connection?,
        baseClass: KClass<T>,
        query: String,
        vararg params: Any?,
        customFields: Map<String, (Any?) -> Unit>? = null
    ): List<T> =
        with(mutableListOf<T>()) {
            readCursor(conn, baseClass, query, *params, customFields = customFields) { add(it) }
            return this
        }

    /** Executes a SELECT query and returns exactly one result, or null if none found. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        readOne(null, T::class, query, *params)

    @PublishedApi
    internal fun <T : Any> readOne(conn: Connection?, baseClass: KClass<T>, query: String, vararg params: Any?): T? {
        val result = Reference<T?>()
        readCursor(conn, baseClass, query, *params) {
            if (result.item != null)
                throw SQLException("Multiple results found for query '$query'")
            result.item = it
        }
        return result.item
    }

    // --- ID validation ---

    private fun <T : Any> getValidIds(entity: T, info: TableInfo<T>): List<Any?> {
        val idValues = info.getIdValues(entity)
        if (idValues.isEmpty())
            throw SQLException("No primary key found for object ${info.classType}")
        return idValues
    }

    // --- Populate ---

    /** Refreshes an entity with fresh data from the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> populate(entity: T): T =
        populate(null, entity)

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> populate(conn: Connection?, entity: T): T {
        attachStormify(entity)
        val info = resolveTableInfo(entity::class) as TableInfo<T>
        val idValues = getValidIds(entity, info)
        if (idValues.any { it == null }) return entity  // null PK = nothing to populate
        performQuery<Any>(conn, info.populateQuery, idValues, code = { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) return@performQuery populate<T>(entity, rs)
                else throw SQLException("No data found for ${info.tableName}:${info.getIdValues(entity)}")
            }
        })
        return entity
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> populate(
        item: T,
        rs: ResultSet,
        context: PopulationContext? = null,
        customFields: Map<String, (Any?) -> Unit>? = null
    ): T {
        attachStormify(item)
        if (item is AutoTable) item.markPopulated()
        val info = resolveTableInfo(item::class) as TableInfo<T>
        val metaData = rs.getMetaData()
        val columnCount = metaData.columnCount
        for (i in 1..columnCount) {
            val col = metaData.getColumnLabel(i)

            val handler = customFields?.get(col.lowercase())
            if (handler != null) {
                try {
                    handler(transformResultValue(rs.getObject(i, Any::class)))
                } catch (e: Exception) {
                    throw SQLException("Custom field handler for column '$col' threw", e)
                }
                continue
            }

            val colType = info.getScalarType(col)
            val value =
                transformResultValue(if (colType != null) rs.getObject(i, colType) else rs.getObject(i, Any::class))
            // Reference resolution: if field is a reference type, create a stub entity with just the FK ID set
            if (value != null && info.isReferenceField(col)) {
                val refType = info.getReferenceType(col)!!
                val ref = try {
                    if (context != null)
                        context.getOrCreateReference(refType, value, this)
                    else
                        createReferenceStub(refType, value)
                } catch (e: Exception) {
                    throw SQLException(
                        "Failed to resolve reference for column '$col' in ${item::class.simpleName}: " +
                                "cannot create stub of type ${refType.simpleName} with id $value",
                        e
                    )
                }
                info.setField(item, col, ref, this, unmatchedColumnPolicy)
                continue
            }
            try {
                info.setField(item, col, value, this, unmatchedColumnPolicy)
            } catch (e: NullPointerException) {
                throw SQLException("Null value for non-null field '$col' in ${item::class.simpleName}", e)
            }
        }
        return item
    }

    @Suppress("UNCHECKED_CAST")
    internal fun batchPopulate(members: List<AutoTable>) {
        if (members.isEmpty()) return
        val info = resolveTableInfo(members[0]::class) as TableInfo<AutoTable>

        // Collect unique IDs and map members by string key
        val byId = LinkedHashMap<String, MutableList<AutoTable>>()
        val uniqueIds = mutableListOf<Any?>()

        for (member in members) {
            val id = info.getIdValues(member)[0]
            val key = id.toString()
            if (byId.containsKey(key))
                byId[key]!!.add(member)
            else {
                byId[key] = mutableListOf(member)
                uniqueIds.add(id)
            }
        }

        val placeholders = nCopies("?", ", ", uniqueIds.size)
        val query =
            "SELECT * FROM ${info.tableName} WHERE ${info.idDbNames[0]} IN ($placeholders)"

        val pkDbName = info.idDbNames[0]
        val nestedContext = PopulationContext()
        performQuery<Any>(null, query, uniqueIds, code = { statement ->
            statement.executeQuery().use { rs ->
                val meta = rs.getMetaData()
                var pkColIdx = 1
                for (c in 1..meta.columnCount)
                    if (meta.getColumnLabel(c).equals(pkDbName, ignoreCase = true)) {
                        pkColIdx = c; break
                    }
                while (rs.next()) {
                    val key = rs.getObject(pkColIdx, info.idTypes[0]).toString()
                    val targets = byId.remove(key)
                    if (targets != null)
                        for (target in targets)
                            populate(target, rs, nestedContext)
                }
                0
            }
        })

        if (byId.isNotEmpty())
            throw SQLException("No data found for ${info.tableName} with ids ${byId.keys}")
    }

    // --- Sequence support ---

    private fun getNextSequences(conn: Connection?, sequence: String, count: Int): List<NativeBigInteger> {
        val sql = sqlDialect.sequenceDialect(sequence, count) ?: return emptyList()
        val result = mutableListOf<NativeBigInteger>()
        readCursor(conn, NativeBigInteger::class, sql) { result.add(it) }
        if (result.isNotEmpty())
            _dbLog("Sequence $sequence incremented by ${result.size} to ${result.last()}")
        return result
    }

    private fun getNextSequence(conn: Connection?, sequence: String) =
        getNextSequences(conn, sequence, 1).firstOrNull()

    // --- Create ---

    /** Inserts a new entity into the database and returns it with generated values populated. */
    @Throws(SQLException::class)
    fun <T : Any> create(item: T): T = create(null, listOf(item))[0]

    /** Inserts multiple entities in a batch and returns them. Generated keys are only populated for single-item batches. */
    @Throws(SQLException::class)
    fun <T : Any> create(items: Collection<T>): List<T> = create(null, items)

    internal fun <T : Any> create(conn: Connection?, item: T) = create(conn, listOf(item))[0]

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> create(conn: Connection?, items: Collection<T>): List<T> {
        if (items.isEmpty()) return emptyList()
        val itemList = if (items is List) items else items.toList()
        itemList.forEach {
            attachStormify(it)
            if (it is AutoTable) it.markPopulated() // Prevent lazy-load during value extraction
        }

        return ConnectionMaker(conn).useWithException("Unable to batch create") { maker ->
            val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
            val hasSinglePk = info.idNames.size == 1

            // Identify items needing IDs (single PK)
            val isAutoIncrement = hasSinglePk && info.primaryKeys[0].isAutoIncrement
            val needsId = mutableListOf<Int>()
            if (hasSinglePk) {
                if (isAutoIncrement) {
                    // Auto-increment: all items need generated IDs
                    for (i in itemList.indices) needsId.add(i)
                } else {
                    for (i in itemList.indices)
                        if (info.getIdValues(itemList[i])[0] == null)
                            needsId.add(i)
                }
            }

            // Bulk fetch sequences if applicable
            val sequence = if (hasSinglePk) info.idSequences[0] else ""
            if (needsId.isNotEmpty() && sequence.isNotBlank()) {
                val seqs = getNextSequences(maker.connection, sequence, needsId.size)
                for (i in needsId.indices)
                    info.setField(itemList[needsId[i]], info.idNames[0], seqs[i], this)
                needsId.clear()
            }

            val hasGK = sqlDialect.generatedKeyRetrieval !== GeneratedKeyRetrieval.NONE
            val fetchGeneratedKeys = hasGK && needsId.size == 1
            val pkColumn = if (hasSinglePk) info.singleKeyDbName else null

            sqlDialect.prepareForInsert(maker.connection, info.createQuery, fetchGeneratedKeys, pkColumn).use { stmt ->
                for (item in itemList) {
                    bindAndLog(stmt, info.createQuery, info.getCreateValues(item).map { sqlData(it, false) })
                    if (fetchGeneratedKeys)
                        stmt.executeUpdate()
                    else
                        stmt.addBatch()
                }
                if (!fetchGeneratedKeys)
                    stmt.executeBatch()

                // Fetch generated key only for single item (no order guarantee for batch)
                if (fetchGeneratedKeys) {
                    stmt.getGeneratedKeys().use { rs ->
                        if (rs.next()) {
                            if (sqlDialect.generatedKeyRetrieval === GeneratedKeyRetrieval.BY_INDEX)
                                info.setField(
                                    itemList[needsId[0]],
                                    info.singleKeyDbName,
                                    rs.getObject(1, NativeBigInteger::class),
                                    this
                                )
                            else
                                populate(itemList[needsId[0]], rs)
                        }
                    }
                }
            }
            itemList
        }
    }

    // --- Update ---

    /** Updates an existing entity in the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> update(updatedItem: T): T = update(null, listOf(updatedItem))[0]

    /** Updates multiple entities in a batch based on their primary keys. */
    @Throws(SQLException::class)
    fun <T : Any> update(items: Collection<T>): List<T> = update(null, items)

    internal fun <T : Any> update(conn: Connection?, updatedItem: T): T = update(conn, listOf(updatedItem))[0]

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> update(conn: Connection?, items: Collection<T>): List<T> {
        if (items.isEmpty()) return emptyList()
        val itemList = if (items is List) items else items.toList()
        itemList.forEach { attachStormify(it) }
        val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
        @Suppress("UNCHECKED_CAST")
        performQuery(conn, info.updateQuery, emptyList(),
            batchItems = itemList,
            batchParamOf = { (info as TableInfo<Any>).getUpdateValues(it!!).map { v -> sqlData(v, false) } }
        ) { it.executeBatch() }
        return itemList
    }

    // --- Delete ---

    /** Deletes an entity from the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> delete(deletedItem: T) = delete(null, listOf(deletedItem))

    /** Deletes multiple entities from the database based on their primary keys. */
    @Throws(SQLException::class)
    fun <T : Any> delete(items: Collection<T>) = delete(null, items)

    internal fun <T : Any> delete(conn: Connection?, deletedItem: T) = delete(conn, listOf(deletedItem))

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> delete(conn: Connection?, items: Collection<T>) {
        if (items.isEmpty()) return
        val itemList = if (items is List) items else items.toList()

        val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
        val idCondition = info.idDbNames.joinToString(" AND ") { "$it = ?" }

        val allParams = mutableListOf<Any?>()
        val conditions = mutableListOf<String>()

        for (item in itemList) {
            val idValues = getValidIds(item, info)
            allParams.addAll(idValues)
            conditions.add("($idCondition)")
        }

        val query = "DELETE FROM ${info.tableName} WHERE ${conditions.joinToString(" OR ")}"
        performQuery<Any>(conn, query, allParams, code = Statement::executeUpdate)
    }

    // --- Detail retrieval ---

    /**
     * Retrieves all detail (child) entities related to a parent entity through a foreign key.
     *
     * When [propertyName] is `null`, the child type [D] is scanned for exactly one field
     * whose type matches the parent's class and that field's column is used. When the
     * child type has multiple foreign keys of the same parent type, pass the **Kotlin
     * property name on the child class** (not the database column name) to
     * disambiguate. The value must be a single field identifier — a dotted traversal
     * path (e.g. `"user.address"`) is rejected with an error.
     */
    @Throws(SQLException::class)
    inline fun <reified D : Any> getDetails(parent: Any, propertyName: String? = null): List<D> =
        getDetails(null, parent, D::class, propertyName)

    /**
     * Type-safe variant of [getDetails] that accepts an annotation-processor-generated
     * reference path (e.g. `Paths.AuditEntry_.createdBy`) instead of a string. The
     * compiler guarantees the referenced property exists on the child type, so typos
     * and renames surface at build time rather than on first query.
     */
    @Throws(SQLException::class)
    inline fun <reified D : Any> getDetails(parent: Any, referenceField: ReferencePath): List<D> =
        getDetails(null, parent, D::class, referenceField.toString().trimEnd('.'))

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <M : Any, D : Any> getDetails(
        conn: Connection?,
        parent: M,
        detailsClass: KClass<D>,
        propertyName: String? = null
    ): List<D> {
        require(propertyName == null || '.' !in propertyName) {
            "getDetails propertyName must be a single field name on ${detailsClass.fullName}, " +
                "not a traversal path — got '$propertyName'"
        }
        val parentInfo = resolveTableInfo(parent::class) as TableInfo<M>
        val parentId = this.getValidIds(parent, parentInfo)
        require(parentId.size == 1) { "Parent class ${parent::class.fullName} should have exactly one primary key" }

        val detailInfo = resolveTableInfo(detailsClass)
        val nonKeyFields = detailInfo.fieldInfos.filter { !it.isPrimaryKey }

        val matchIndex: Int
        if (propertyName == null) {
            val types = nonKeyFields.map { it.type }
            matchIndex = findItemOnce(types, parent::class, detailsClass.fullName)
        } else {
            val names = nonKeyFields.map { it.name }
            matchIndex = findItemOnce(names, propertyName, detailsClass.fullName)
            if (nonKeyFields[matchIndex].type != parent::class)
                throw SQLException("Field $propertyName is not of type ${parent::class.fullName} in class ${detailsClass.fullName}")
        }
        val propertyDbName = nonKeyFields[matchIndex].dbName

        val details = read(
            conn,
            detailsClass,
            "SELECT * FROM ${detailInfo.tableName} WHERE $propertyDbName = ?",
            parentId[0]
        )
        for (detail in details)
            detailInfo.setField(detail, propertyDbName, parent, this)
        return details
    }

    // --- Find operations ---

    /** Finds all entities, optionally filtered by a WHERE clause. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> findAll(whereClause: String = "", vararg arguments: Any?): List<T> =
        findAll(null, T::class, whereClause, *arguments)

    @PublishedApi
    internal fun <T : Any> findAll(
        conn: Connection?,
        kclass: KClass<T>,
        whereClause: String = "",
        vararg arguments: Any?
    ): List<T> = read(
        conn,
        kclass,
        resolveTableInfo(kclass).let { "SELECT * FROM ${it.tableName}" + (if (whereClause.isEmpty()) "" else " $whereClause") },
        *arguments
    )

    /** Finds a single entity by its primary key value (single PK only). */
    @Throws(SQLException::class)
    inline fun <reified T : Any> findById(id: Any) =
        findById(null, T::class, id)

    @PublishedApi
    internal fun <T : Any> findById(conn: Connection?, kclass: KClass<T>, id: Any) = resolveTableInfo(kclass).let {
        readOne(conn, kclass, "SELECT * FROM ${it.tableName} WHERE ${it.singleKeyDbName} = ?", id)
    }

    // --- Transaction ---

    /**
     * Executes [block] within a database transaction with automatic commit on success
     * and rollback on any thrown exception, returning the block's result.
     *
     * **Nesting.** If this call is made from inside another `transaction { }` on the
     * same [Stormify] instance and the same thread (detected via [ActiveTxRegistry]),
     * the inner call reuses the outer connection and bounds its work with a database
     * savepoint — so a failure inside the nested block rolls back only its work, not
     * the outer transaction. There is no syntactic distinction: the same call is a
     * top-level tx when there is no ambient one, and a savepoint otherwise.
     *
     * **Ambient connection.** While the block runs, all convenience operations on
     * this instance (including [create]/[read]/[update]/[delete]/[findById]/…,
     * top-level extensions, lazy-loading delegates, `PagedList`, etc.) transparently
     * route through this transaction's connection — you do not need to thread a
     * context parameter through your code.
     */
    @Throws(SQLException::class)
    fun <R> transaction(block: () -> R): R {
        val ambient = ActiveTxRegistry.currentFor(this)
        return if (ambient != null) runNestedSavepoint(ambient, block)
        else runTopLevelTransaction(block)
    }

    private fun <R> runTopLevelTransaction(block: () -> R): R {
        val conn = tryQuery("Unable to get connection") { dataSource.getConnection() }
        return conn.use { runTransactionBody(conn, block) }
    }

    private fun <R> runTransactionBody(conn: Connection, block: () -> R): R {
        ActiveTxRegistry.push(this, conn)
        try {
            conn.setAutoCommit(false)
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            // The connection may already be broken (the reason we landed here);
            // swallow rollback / autocommit failures so the original exception
            // propagates unchanged.
            runCatching { conn.rollback() }
            e.throwQuery("Unable to execute transaction")
        } finally {
            runCatching { conn.setAutoCommit(true) }
            ActiveTxRegistry.pop(this, conn)
        }
    }

    private fun <R> runNestedSavepoint(conn: Connection, block: () -> R): R {
        val savepoint = try {
            conn.setSavepoint(nextSavepointName())
        } catch (e: Throwable) {
            e.throwQuery("Unable to open savepoint for nested transaction")
        }
        return try {
            val result = block()
            if (sqlDialect.supportsReleaseSavepoint)
                runCatching { conn.releaseSavepoint(savepoint) }
            result
        } catch (e: Throwable) {
            runCatching { conn.rollback(savepoint) }
            e.throwQuery("Unable to execute nested transaction")
        }
    }

    // --- Stored Procedures ---

    /**
     * Executes a stored procedure with IN / OUT / INOUT parameters.
     *
     * Each [args] entry is either:
     *  - an [Sp.Out] or [Sp.InOut] reference that the caller holds, to be
     *    populated after execution, OR
     *  - an [Sp.In] wrapper, OR
     *  - any other value — automatically wrapped as [Sp.In].
     *
     * After the call returns, OUT and INOUT refs carry the typed `value`
     * produced by the procedure.
     */
    @Throws(SQLException::class)
    fun procedure(name: String, vararg args: Any?) = procedure(null, name, *args)

    internal fun procedure(conn: Connection?, name: String, vararg args: Any?) {
        val params: Array<Sp> = Array(args.size) { i ->
            val a = args[i]
            if (a is Sp) a else Sp.In(a)
        }
        // Use ConnectionMaker so the call joins an ambient transaction when the
        // caller did not pass an explicit connection, matching the behaviour of
        // every other CRUD / query entry point.
        ConnectionMaker(conn).useWithException("Unable to execute stored procedure $name") { maker ->
            val placeholders: String = nCopies("?", ", ", params.size)
            val statement = "CALL $name($placeholders)"
            _dbLog(statement, *params)
            maker.connection.prepareCall(statement).use { cs ->
                for (i in params.indices) {
                    when (val p = params[i]) {
                        is Sp.In -> cs.setObject(i + 1, p.value)
                        is Sp.Out<*> -> cs.registerOutParameter(i + 1, p.type)
                        is Sp.InOut<*> -> {
                            cs.registerOutParameter(i + 1, p.type)
                            cs.setObject(i + 1, p.input)
                        }
                        else -> throw SQLException("Unexpected Sp subtype: ${p::class}")
                    }
                }
                cs.execute()
                for (i in params.indices) {
                    when (val p = params[i]) {
                        is Sp.In -> { /* no post-execute state */ }
                        is Sp.Out<*> ->
                            p.assign(castTo(p.type, cs.getObject(i + 1, p.type), this))
                        is Sp.InOut<*> ->
                            p.assign(castTo(p.type, cs.getObject(i + 1, p.type), this))
                        else -> throw SQLException("Unexpected Sp subtype: ${p::class}")
                    }
                }
            }
        }
    }

    // --- Public introspection ---

    /** Returns the [TableInfo] metadata for the given entity class, resolving and caching it if necessary. */
    @Throws(SQLException::class)
    fun <T : Any> getTableInfo(kclass: KClass<T>): TableInfo<T> = resolveTableInfo(kclass)

    @Suppress("FunctionName")
    internal fun _dbLog(query: String, vararg params: Any?) =
        logger.debug("{}{}", query, if (params.isEmpty()) "" else " -- " + params.contentToString())
}
