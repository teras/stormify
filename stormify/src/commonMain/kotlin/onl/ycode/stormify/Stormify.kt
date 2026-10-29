// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify

import onl.ycode.stormify.biglist.SortState
import kotlin.concurrent.Volatile
import kotlinx.atomicfu.locks.withLock
import onl.ycode.kdbc.*
import onl.ycode.logger.LogManager
import onl.ycode.stormify.SqlDialect.GeneratedKeyRetrieval
import onl.ycode.stormify.Stormify.Companion.defaultInstance
import onl.ycode.stormify.TypeUtils.castTo
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.FilterSyntax
import onl.ycode.stormify.biglist.InputParser
import onl.ycode.stormify.biglist.ReferencePath
import onl.ycode.stormify.biglist.TextFolding
import onl.ycode.stormify.coroutines.PoolConfig
import onl.ycode.stormify.coroutines.SuspendStormify
import kotlin.reflect.KClass


private class Reference<T>(var item: T? = null)

private class PreparedSql(val sql: String, val params: List<Any?>)

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
 * @param poolConfig tuning for the connection pool behind [suspending]. The defaults
 *   are sensible for most applications — see [PoolConfig] for the double-pooling
 *   warning when [dataSource] already pools (e.g. HikariCP).
 */
class Stormify(
    val dataSource: DataSource,
    vararg registrars: EntityRegistrar,
    val poolConfig: PoolConfig = PoolConfig(),
) {

    /**
     * Single-argument convenience constructor. Exists primarily so Spring XML
     * `<constructor-arg ref="dataSource"/>` (and similar DI containers that
     * resolve constructors by arity) can pick an unambiguous one-arg match.
     */
    constructor(dataSource: DataSource) : this(dataSource, *emptyArray())

    /** Convenience constructor with pool tuning and no registrars. */
    constructor(dataSource: DataSource, poolConfig: PoolConfig) : this(dataSource, *emptyArray(), poolConfig = poolConfig)

    private val suspendingLazy = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SuspendStormify(this, poolConfig) }

    /**
     * The coroutine-aware API of this instance, backed by a single connection pool
     * created lazily on first access with [poolConfig].
     *
     * Inside a scope, every database operation uses the scope's connection — the
     * blocking API is called unchanged and joins the borrowed connection
     * transparently:
     *
     * ```kotlin
     * stormify.suspending.withConnection { /* reads, auto-commit */ }
     * stormify.suspending.transaction { /* writes, BEGIN/COMMIT */ }
     * ```
     *
     * **Pooling is suspend-only, by design.** Pooled connections are pinned to
     * dedicated worker threads, which a blocking caller cannot honor — there is no
     * blocking-API pool and there will be no `PooledDataSource`. If you want
     * pooling, write suspend code.
     *
     * **Lifecycle:** shut the pool down with `stormify.suspending.close()` at
     * application shutdown ([Stormify] itself is not and cannot be `AutoCloseable`
     * — pool closing is a suspending operation). On Native every pooled connection
     * owns a thread, so skipping this leaks threads, not just connections.
     * [closeSuspending] offers the same shutdown without creating the pool when it
     * was never used.
     */
    val suspending: SuspendStormify get() = suspendingLazy.value

    /**
     * Closes the [suspending] connection pool if it was ever created; a no-op
     * otherwise. Use this in shutdown hooks where touching [suspending] just to
     * close it would needlessly spin up a pool.
     */
    suspend fun closeSuspending() {
        if (suspendingLazy.isInitialized()) suspendingLazy.value.close()
    }

    init {
        for (r in registrars) EntityMeta.invokeRegistrar(r)
    }

    /** Holds the library-wide [defaultInstance] used when no instance is explicitly attached. */
    companion object {
        private val _defaultInstance = kotlinx.atomicfu.atomic<Stormify?>(null)

        /**
         * The default Stormify instance, set once at startup via [asDefault]. A call that
         * names no instance (the top-level functions, the lazy load of an entity no
         * instance read) works on the instance of the enclosing transaction or
         * connection scope, and on this one outside of them.
         */
        var defaultInstance: Stormify?
            get() = _defaultInstance.value
            private set(value) {
                _defaultInstance.value = value
            }

        /** Classloader-leak cleanup hook — on JVM, invoked by `StormifyLifecycle.clear()`. */
        internal fun clearDefault() { _defaultInstance.value = null }

        /**
         * The instance a call without one works on: the owner of the innermost transaction
         * or connection scope open on this thread, else [defaultInstance].
         */
        @PublishedApi
        internal fun current(): Stormify? = ActiveTxRegistry.currentOwner() ?: defaultInstance

    }

    /** Sets this instance as [defaultInstance] and returns it. */
    fun asDefault(): Stormify {
        defaultInstance = this
        return this
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

    private val cacheLock = kotlinx.atomicfu.locks.reentrantLock()
    private val tableInfoCache = mutableMapOf<KClass<*>, TableInfo<*>>()

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> resolveTableInfo(type: KClass<out T>): TableInfo<T> =
        cacheLock.withLock {
            tableInfoCache.getOrPut(type) {
                val meta = EntityMeta.find(type) ?: tryReflection(type)
                ?: throw SQLException("Unknown entity: ${type.fullName}")
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

    /**
     * What a reference to a missing row does: throw when loaded ([DanglingReferences.THROW],
     * the default) or read as `null` ([DanglingReferences.NULL], as with JPA).
     */
    @Volatile
    var danglingReferences: DanglingReferences = DanglingReferences.THROW

    /**
     * How many rows the driver buffers per round-trip during streaming
     * reads. Used by [readCursor] on every dialect, and additionally by
     * the eager [read] path on Oracle, whose driver default would
     * otherwise turn a bulk read into a round-trip storm.
     *
     * On PostgreSQL and MySQL the value doubles as the streaming
     * activation gate — `0` keeps the driver in eager full-buffer mode,
     * positive values open a server-side cursor. On Oracle and the native
     * drivers the value is purely a throughput knob: larger means fewer
     * round-trips at the cost of a larger pre-allocated buffer.
     *
     * The default `256` is a balanced choice: within roughly 10 % of
     * arbitrarily large fetch sizes on every driver, and well clear of
     * the memory-pressure point on wide schemas. **Tune up**
     * (e.g. `1000`) for narrow rows on high-latency links; **tune down**
     * (e.g. `64`) for VARCHAR2(4000)-heavy Oracle schemas where the
     * pre-allocated buffer dominates memory. Override per call via
     * [readCursor]'s `fetchSize` parameter.
     */
    @Volatile
    var cursorFetchSize: Int = 256

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

    /**
     * Text folding of case-insensitive facet filters: how [TEXT][onl.ycode.stormify.biglist.Facet.Type.TEXT]
     * filters fold the column and the typed text, and how [ENUM][onl.ycode.stormify.biglist.Facet.Type.ENUM]
     * filters match display names. Default is [TextFolding.DEFAULT], the
     * case-insensitive `LIKE` of the dialect.
     */
    @Volatile
    var textFolding: TextFolding = TextFolding.DEFAULT

    /**
     * When `true`, [update] writes only the columns that changed since the entity was
     * loaded, instead of every updatable column. Default `false`.
     *
     * Applies to entities that extend [StormifyEntity] (and so [AutoTable]): when one is
     * loaded, a copy of its column values is kept with it, and [update] compares the
     * current values against that copy. Entities that are not [StormifyEntity], or that
     * were never loaded (created in code, or loaded while this was off), get the full
     * `UPDATE`. An entity with no changed column issues no statement at all. After an
     * update, the written values become the new reference. In a batch [update], entities
     * are grouped by the set of changed columns, one batched statement per group, on the
     * same connection.
     *
     * Costs a second copy of each loaded row's values in memory. A value changed in
     * place, such as `java.util.Date.setTime`, is detected, since the copy is taken of
     * mutable values. An entity whose primary key changed gets the full `UPDATE`.
     *
     * Inside a [transaction] (or a suspend transaction), the written values become the
     * reference only when the transaction commits: on rollback, of the transaction or
     * of the savepoint of a nested one, the entities it updated lose their reference
     * and their next update is the full `UPDATE`, also when this was switched on inside
     * the transaction. On a connection whose transaction is managed outside Stormify,
     * the reference is not replaced, since the statement may still be rolled back there;
     * the next update writes the changed columns again.
     *
     * An entity with no changed column issues no statement, so nothing checks that its
     * row still exists.
     */
    @Volatile
    var dynamicUpdate: Boolean = false

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

    /**
     * Walks [givenQuery] once, expands list-typed args into `(?, ?, …)`, and
     * produces the SQL string ready for the driver plus the flat parameter
     * list. Empty [args] is legal — it covers both parameterless queries and
     * the batch path where per-row values are supplied later by
     * `batchParamOf` — and skips expansion and count validation entirely.
     */
    private fun prepareSql(givenQuery: String, args: List<Any?>): PreparedSql {
        if (args.isEmpty()) return PreparedSql(givenQuery, emptyList<Any>())
        val sql = StringBuilder(givenQuery.length)
        val params = mutableListOf<Any?>()
        var consumed = 0
        var lastFlush = 0
        scanPlaceholders(givenQuery) { idx ->
            if (consumed >= args.size) throw SQLException(
                "The number of placeholders in query '$givenQuery'" +
                        " exceeds the number of parameters (${args.size})"
            )
            sql.append(givenQuery, lastFlush, idx)
            val arg = sqlData(args[consumed++], true)
            if (arg is List<*>) {
                if (arg.isEmpty()) throw SQLException(
                    "Parameter ${consumed} of query '$givenQuery' is an empty collection;" +
                            " it cannot be expanded into an IN list"
                )
                sql.append("(")
                arg.forEachIndexed { i, v ->
                    if (i > 0) sql.append(", ")
                    sql.append("?")
                    params.add(v)
                }
                sql.append(")")
            } else {
                sql.append("?")
                params.add(arg)
            }
            lastFlush = idx + 1
        }
        if (lastFlush < givenQuery.length) sql.append(givenQuery, lastFlush, givenQuery.length)
        if (consumed < args.size) throw SQLException(
            "The number of placeholders ($consumed) in query '$givenQuery'" +
                    " is less than the number of parameters (${args.size})"
        )
        return PreparedSql(sql.toString(), params)
    }

    private fun bindAndLog(stmt: Statement, sql: String, params: List<Any?>) {
        _dbLog(sql, *params.toTypedArray())
        for (i in params.indices)
            bindParam(stmt, sql, i + 1, params[i])
    }

    /**
     * Single-parameter setObject with contextualized rethrow. When the driver
     * rejects a value, its generic `SQLException` does not say which `?`
     * placeholder failed. This wrapper catches the exception and rethrows it
     * with the SQL query, the parameter index, the runtime type and a bounded
     * preview of the value — so the caller can immediately locate the
     * offending field without counting `?` placeholders in the SQL.
     */
    private fun bindParam(stmt: Statement, query: String, parameterIndex: Int, value: Any?) {
        try {
            stmt.setObject(parameterIndex, value)
        } catch (e: Throwable) {
            val type = value?.let { it::class.qualifiedName ?: it::class.simpleName } ?: "null"
            val preview = when (value) {
                null -> "null"
                is ByteArray -> "ByteArray(size=${value.size})"
                is CharArray -> "CharArray(size=${value.size})"
                else -> value.toString().let { if (it.length > 80) it.take(77) + "..." else it }
            }
            throw SQLException(
                "Bind failed for parameter $parameterIndex (type=$type, value=$preview) in query [$query]", e
            )
        }
    }

    private fun <T> performQuery(
        conn: Connection?,
        givenQuery: String,
        givenParams: List<Any?>,
        batchItems: List<*>? = null,
        batchParamOf: ((Any?) -> List<Any?>)? = null,
        generatedKeys: Boolean = false,
        fetchSize: Int = 0,
        code: (Statement) -> T
    ): T {
        val prepared = prepareSql(givenQuery, givenParams)
        val errorMsg = if (prepared.params.isEmpty()) "" else " with values ${prepared.params}"
        return ConnectionMaker(conn).useWithException("Unable to execute query '${prepared.sql}'$errorMsg") { maker ->
            // PG JDBC binds server-side cursors to transaction lifetime: with
            // autoCommit=true, setFetchSize is silently downgraded to a full
            // client-side buffer. Flip it only on a connection we borrowed —
            // user-owned ones already control their own autocommit state.
            val toggleAutoCommit = fetchSize > 0
                    && maker.shouldClose
                    && sqlDialect == SqlDialect.POSTGRESQL
                    && maker.connection !is NativeKdbcConnection
                    && maker.connection.getAutoCommit()
            if (toggleAutoCommit) maker.connection.setAutoCommit(false)
            try {
                // Cacheable path — non-RETURNING queries flow through the per-connection PS cache.
                val stmtHandle = if (generatedKeys)
                    maker.connection.initStatement(prepared.sql, true, null)
                else
                    maker.connection.acquirePreparedStatement(prepared.sql)
                stmtHandle.use { stmt ->
                    if (fetchSize > 0) {
                        // MySQL Connector/J needs Int.MIN_VALUE as the row-by-row
                        // streaming switch; MariaDB Connector/J rejects negatives.
                        // Native drivers activate cursor mode on any positive value.
                        val effective = if (maker.connection !is NativeKdbcConnection && sqlDialect.isMysqlOnly)
                            Int.MIN_VALUE else fetchSize
                        stmt.setFetchSize(effective)
                    }
                    if (batchItems != null && batchParamOf != null) {
                        for (item in batchItems) {
                            bindAndLog(stmt, prepared.sql, batchParamOf(item))
                            stmt.addBatch()
                        }
                    } else {
                        bindAndLog(stmt, prepared.sql, prepared.params)
                    }
                    code(stmt)
                }
            } finally {
                // setAutoCommit(true) on a non-auto-commit connection commits per
                // the JDBC contract — no separate commit() round-trip needed.
                if (toggleAutoCommit) runCatching { maker.connection.setAutoCommit(true) }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun createReferenceStub(refType: KClass<*>, idValue: Any): Any {
        val refInfo = resolveTableInfo(refType) as TableInfo<Any>
        val wrapper = refInfo.create()
        if (wrapper is StormifyEntity) wrapper._stormify = this
        if (wrapper is AutoTable) wrapper.markNotHydrated()
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
        return if (info.idDbNames.size == 1) info.getIdValues(value)[0] else
            throw SQLException("Multiple primary keys found in ${info.tableName}")
    }

    // --- Attach stormify to entity ---

    /**
     * Attaches this Stormify instance to the entity [target], so it lazy-loads and
     * writes through this instance. Entities this instance reads are attached already.
     * Returns [target] for fluent chaining.
     *
     * ```kotlin
     * // Manual stub: the id is known, the rest loads on first access
     * val user = stormify.attach(User().apply { id = 42 })
     * println(user.name)  // triggers SELECT via the attached instance
     * ```
     */
    fun <T : StormifyEntity> attach(target: T): T = target.also { it._stormify = this }

    /**
     * Creates a [PagedList] of [T] working on this instance, ready to be configured.
     *
     * ```kotlin
     * val list = stormify.pagedList<Company>()
     *     .facet(Company_.name)
     *     .sort(Company_.name, SortState.ASCENDING)
     * ```
     */
    inline fun <reified T : Any> pagedList(): PagedList<T> = PagedList(T::class, this)

    /**
     * Creates a [PagedQuery] of [T] working on this instance, ready to be configured.
     *
     * ```kotlin
     * val customers = stormify.pagedQuery<Customer>()
     * customers.addFacet("name", Customer_.name)
     * ```
     */
    inline fun <reified T : Any> pagedQuery(): PagedQuery<T> = PagedQuery(T::class, this)

    private fun attachStormify(target: Any) {
        if (target is StormifyEntity) target._stormify = this
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
     * @param fetchSize wire-protocol batch size hint passed to the underlying driver via
     *  `Statement.setFetchSize`. Defaults to [cursorFetchSize] (the instance-wide default).
     *  Set to `0` to opt out of streaming for a single call (drivers fall back to their own
     *  default, typically eager). Larger values cut round-trips on high-latency links;
     *  smaller values cap per-batch memory. Independent of the in-process FK-batching size
     *  used by `forEachStreaming` (see `SiblingGroup.DEFAULT_BATCH_SIZE`).
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
        fetchSize: Int = cursorFetchSize,
        customFields: Map<String, (Any?) -> Unit>? = null,
        noinline consumer: (T) -> Unit
    ) = readCursor(null, T::class, query, *params, fetchSize = fetchSize, customFields = customFields, consumer = consumer)

    @PublishedApi
    internal fun <T : Any> readCursor(
        conn: Connection?,
        baseClass: KClass<T>,
        query: String,
        vararg params: Any?,
        fetchSize: Int = cursorFetchSize,
        customFields: Map<String, (Any?) -> Unit>? = null,
        consumer: (T) -> Unit
    ): Int {
      var resultContext: PopulationContext? = null
      // With DanglingReferences.NULL the rows are handed over once the result is complete
      // and checked, so the consumer sees the references to missing rows as null.
      var held: ArrayList<Any>? = null
      val rows = performQuery(conn, query, params.toList(), fetchSize = fetchSize, code = { statement ->
        val isMap = Map::class == baseClass
        val info = if (TypeConversion.isKnownScalar(baseClass) || isMap) null else resolveTableInfo(baseClass)
        val normalizedCustomFields = customFields?.mapKeys { it.key.lowercase() }
        statement.executeQuery().use { rs ->
            val context = if (info != null) PopulationContext().also { resultContext = it } else null
            val deferred = if (context != null && danglingReferences == DanglingReferences.NULL)
                ArrayList<Any>().also { held = it } else null
            var count = 0
            // Lazily build per-result-set plans (only once we see the first row),
            // because some drivers materialize column metadata only after rs.next().
            var entityPlan: ColumnPlan<Any>? = null
            var mapLabels: Array<String>? = null
            while (rs.next()) {
                count++
                if (isMap) {
                    if (mapLabels == null) {
                        val meta = rs.getMetaData()
                        val n = meta.columnCount
                        val arr = Array(n) { i -> meta.getColumnLabel(i + 1).lowercase() }
                        mapLabels = arr
                    }
                    val labels = mapLabels!!
                    val row = LinkedHashMap<String, Any?>(labels.size)
                    for (i in labels.indices)
                        row[labels[i]] = rs.getObject(i + 1, Any::class)
                    @Suppress("UNCHECKED_CAST")
                    consumer(row as T)
                } else if (info != null) {
                    if (entityPlan == null) {
                        @Suppress("UNCHECKED_CAST")
                        entityPlan = buildColumnPlan(rs, info as TableInfo<Any>, normalizedCustomFields)
                    }
                    @Suppress("UNCHECKED_CAST")
                    val item = info.create().also { attachStormify(it) } as Any
                    val populated = populateWithPlan(item, rs, entityPlan, context)
                    @Suppress("UNCHECKED_CAST")
                    if (deferred != null) deferred.add(populated) else consumer(populated as T)
                } else {
                    consumer(
                        castTo(baseClass, rs.getObject(1, baseClass), this)
                            ?: throw SQLException(
                                "Query returned a row whose selected column is NULL, which cannot " +
                                        "be represented as non-nullable ${baseClass.fullName}. If NULL values " +
                                        "are expected, handle them in SQL (e.g. COALESCE)."
                            )
                    )
                }
            }
            count
        }
      })
      // Once the result is complete: references to missing rows read as null, if asked to.
      resultContext?.resolveDanglingReferences(this, conn)
      @Suppress("UNCHECKED_CAST")
      held?.forEach { consumer(it as T) }
      return rows
    }

    /**
     * Reads the first column of every row as a string, keeping SQL `NULL` as
     * `null` where the scalar [read] rejects it. Same per-driver conversion as
     * `read<String>`.
     */
    internal fun readNullableStrings(query: String, vararg params: Any?): List<String?> =
        performQuery(null, query, params.toList(), code = { statement ->
            statement.executeQuery().use { rs ->
                val out = ArrayList<String?>()
                while (rs.next())
                    out.add(castTo(String::class, rs.getObject(1, String::class), this))
                out
            }
        })

    /**
     * Executes a SELECT query and returns every row as an array of its column values, by
     * position. Unlike `read<Map<String, Any?>>`, columns with the same label (`a.id`,
     * `b.id`) all come back. Values are converted as for a `Map` read; SQL `NULL` is
     * `null`. Runs on the connection of the active transaction, if any, like every read.
     */
    @Throws(SQLException::class)
    fun readRows(query: String, vararg params: Any?): List<Array<Any?>> {
        val rows = ArrayList<Array<Any?>>()
        readRowsCursor(query, *params, fetchSize = 0) { rows.add(it) }
        return rows
    }

    /**
     * Streaming form of [readRows]: hands each row to [consumer] as it is read, without
     * materializing the result, and returns the number of rows. [fetchSize] is the driver
     * batch size hint, as for [readCursor].
     */
    @Throws(SQLException::class)
    fun readRowsCursor(
        query: String,
        vararg params: Any?,
        fetchSize: Int = cursorFetchSize,
        consumer: (Array<Any?>) -> Unit
    ): Int = performQuery(null, query, params.toList(), fetchSize = fetchSize, code = { statement ->
        statement.executeQuery().use { rs ->
            var columns = -1
            var count = 0
            while (rs.next()) {
                // Column count only once a row is there: some drivers expose metadata late.
                if (columns < 0) columns = rs.getMetaData().columnCount
                consumer(Array(columns) { rs.getObject(it + 1, Any::class) })
                count++
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
            // Only Oracle needs a fetch-size hint; other drivers buffer the eager
            // result fine without one (and a positive hint would needlessly open a
            // server-side cursor on PostgreSQL).
            val hint = if (sqlDialect.isOracleVariant) cursorFetchSize else 0
            readCursor(conn, baseClass, query, *params, fetchSize = hint, customFields = customFields) { add(it) }
            groupForDetails(this)
            return this
        }

    /**
     * Marks the rows of one result as belonging together, so that reading a
     * `by lazyDetails()` property on any of them can fetch the children of all of them
     * in a single query instead of one query per row.
     *
     * Only whole-result reads get a group. A streaming read deliberately does not: its
     * rows are never all in hand at once, which is the entire point of streaming.
     */
    private fun groupForDetails(rows: List<Any?>) {
        if (rows.size < 2) return
        var group: DetailsGroup? = null
        for (row in rows) {
            if (row !is StormifyEntity) continue
            val g = group ?: DetailsGroup().also { group = it }
            row._detailsGroup = g
            g.add(row)
        }
    }

    /**
     * Executes a SELECT query and returns exactly one result, or null if no row is found.
     *
     * A null return always means "no matching row". If a row exists but the selected
     * scalar column is SQL NULL, an [SQLException] is thrown instead — a non-nullable
     * [T] cannot represent NULL.
     */
    @Throws(SQLException::class)
    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        readOne(null, T::class, query, *params)

    @PublishedApi
    internal fun <T : Any> readOne(conn: Connection?, baseClass: KClass<T>, query: String, vararg params: Any?): T? {
        val result = Reference<T?>()
        // Eager — at most one row expected. See read() comment for rationale.
        readCursor(conn, baseClass, query, *params, fetchSize = 0) {
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

    // --- Refresh ---

    /** Refreshes an entity with fresh data from the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> refresh(entity: T): T =
        refresh(null, entity)

    /**
     * Loads [entity]'s row into it, as [refresh] does, and returns `false` without throwing
     * when the row does not exist. Used by lazy loading, which applies [danglingReferences].
     */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> loadIfExists(entity: T): Boolean {
        attachStormify(entity)
        val info = resolveTableInfo(entity::class) as TableInfo<T>
        val idValues = getValidIds(entity, info)
        if (idValues.any { it == null }) return true
        val context = PopulationContext()
        val found = performQuery(null, info.populateQuery, idValues, code = { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) { populate<T>(entity, rs, context); true } else false
            }
        })
        context.resolveDanglingReferences(this, null)
        return found
    }

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> refresh(conn: Connection?, entity: T): T {
        attachStormify(entity)
        val info = resolveTableInfo(entity::class) as TableInfo<T>
        val idValues = getValidIds(entity, info)
        if (idValues.any { it == null }) return entity  // null PK = nothing to load
        val context = PopulationContext()
        performQuery<Any>(conn, info.populateQuery, idValues, code = { statement ->
            statement.executeQuery().use { rs ->
                if (rs.next()) return@performQuery populate<T>(entity, rs, context)
                else throw SQLException("No data found for ${info.tableName}:${info.getIdValues(entity)}")
            }
        })
        context.resolveDanglingReferences(this, conn)
        return entity
    }

    /**
     * Per-(ResultSet, entity-class, customFields) precomputed column plan.
     *
     * Built once at the top of a `while (rs.next())` loop so that per-row population
     * does not re-fetch [ResultSet.getMetaData], re-allocate the column label, re-lowercase
     * it, and re-resolve scalar/reference type info from [TableInfo] hash maps for every row.
     * Empirically reduces a 1000-row × 5-column read from ~30k incidental allocations to ~5.
     */
    private class ColumnPlan<T : Any>(
        val info: TableInfo<T>,
        val cols: Array<ColumnPlanEntry<T>>
    )

    private class ColumnPlanEntry<T : Any>(
        val index: Int,
        val name: String,                  // original case (for error messages)
        /** Type to request from the driver, or null to read untyped. */
        val readType: kotlin.reflect.KClass<*>?,
        val isReference: Boolean,
        val refType: kotlin.reflect.KClass<*>?,
        val customHandler: ((Any?) -> Unit)?,
        /**
         * Pre-resolved setter lambdas for this column. Empty when the column has no matching
         * entity field — caller applies the unmatched-column policy. Caching this list at plan
         * build time skips a `String.lowercase()` allocation and a `fieldByDbName` HashMap
         * lookup on every cell of every row.
         */
        val setters: List<ResolvedProperty<T>>,
    )

    private fun <T : Any> buildColumnPlan(
        rs: ResultSet,
        info: TableInfo<T>,
        normalizedCustomFields: Map<String, (Any?) -> Unit>?
    ): ColumnPlan<T> {
        val meta = rs.getMetaData()
        val n = meta.columnCount
        val arr = arrayOfNulls<ColumnPlanEntry<T>>(n)
        for (i in 1..n) {
            val name = meta.getColumnLabel(i)
            val handler = normalizedCustomFields?.get(name.lowercase())
            val scalarType = if (handler == null) info.getScalarType(name) else null
            val isRef = handler == null && info.isReferenceField(name)
            val refType = if (isRef) info.getReferenceType(name) else null
            // A reference column carries the referenced entity's primary key, so ask the
            // driver for that type. An untyped read can hand back a driver-native class
            // (ojdbc returns oracle.sql.TIMESTAMP for a temporal key) that no converter
            // accepts, and the reference stub then cannot be built.
            val readType = scalarType
                ?: refType?.let { resolveTableInfo(it).idTypes.singleOrNull() }
            val properties = if (handler == null) info.propertiesForColumn(name) else emptyList()
            arr[i - 1] = ColumnPlanEntry(i, name, readType, isRef, refType, handler, properties)
        }
        @Suppress("UNCHECKED_CAST")
        return ColumnPlan(info, arr as Array<ColumnPlanEntry<T>>)
    }

    /** Compatibility entry: builds a one-shot column plan and delegates. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> populate(
        item: T,
        rs: ResultSet,
        context: PopulationContext? = null,
        customFields: Map<String, (Any?) -> Unit>? = null
    ): T {
        val info = resolveTableInfo(item::class) as TableInfo<T>
        val plan = buildColumnPlan(rs, info, customFields)
        return populateWithPlan(item, rs, plan, context)
    }

    /** Hot-path entry: caller has already precomputed the plan once for the loop. */
    private fun <T : Any> populateWithPlan(
        item: T,
        rs: ResultSet,
        plan: ColumnPlan<T>,
        context: PopulationContext?,
    ): T {
        attachStormify(item)
        // A shadow reference being loaded is marked loaded by its loader once every field
        // is filled; marking it here would let other threads see it half-filled.
        if (item is AutoTable && !item.loadInProgress()) item.markHydrated()
        val info = plan.info
        // For the dynamic update snapshot: the key each reference column holds, as read,
        // so taking the snapshot never reads a referenced entity (and loads its row).
        val referenceKeys = if (dynamicUpdate && item is StormifyEntity) HashMap<String, Any?>() else null
        for (col in plan.cols) {
            val handler = col.customHandler
            if (handler != null) {
                try {
                    handler(transformResultValue(rs.getObject(col.index, Any::class)))
                } catch (e: Exception) {
                    throw SQLException("Custom field handler for column '${col.name}' threw", e)
                }
                continue
            }

            val value = transformResultValue(
                if (col.readType != null) rs.getObject(col.index, col.readType)
                else rs.getObject(col.index, Any::class)
            )

            if (referenceKeys != null && col.isReference) {
                val keyType = resolveTableInfo(col.refType!!).idTypes.singleOrNull()
                referenceKeys[col.name.lowercase()] =
                    if (value != null && keyType != null) castTo(keyType, value, this) else value
            }

            val resolved = if (value != null && col.isReference) {
                val refType = col.refType!!
                try {
                    if (context != null) context.getOrCreateReference(refType, value, this, item, col.setters)
                    else createReferenceStub(refType, value)
                } catch (e: Exception) {
                    throw SQLException(
                        "Failed to resolve reference for column '${col.name}' in ${item::class.simpleName}: " +
                                "cannot create stub of type ${refType.simpleName} with id $value",
                        e
                    )
                }
            } else value

            // Setters were pre-resolved at plan-build time. Empty list = no matching field;
            // apply unmatched-column policy here (deferred to first row, but cheap because
            // it short-circuits on the empty-list branch).
            if (col.setters.isEmpty()) {
                when (unmatchedColumnPolicy) {
                    UnmatchedColumnPolicy.THROW -> throw SQLException(
                        "Column ${col.name} has no matching field in ${info.tableName}"
                    )
                    UnmatchedColumnPolicy.WARN -> logger.warn(
                        "Column ${col.name} has no matching field in ${info.tableName}"
                    )
                    UnmatchedColumnPolicy.IGNORE -> Unit
                }
                continue
            }
            try {
                // A column can back both a reference and a plain scalar (a read-only
                // association next to its raw foreign key): only reference properties
                // take the resolved entity, the others take the column value.
                for (setter in col.setters) setter.setter(item, if (setter.isReference) resolved else value, this)
            } catch (e: NullPointerException) {
                throw SQLException("Null value for non-null field '${col.name}' in ${item::class.simpleName}", e)
            }
        }
        if (referenceKeys != null) (item as StormifyEntity)._updateSnapshot = takeUpdateSnapshot(info, item, referenceKeys)
        return item
    }

    /**
     * Loads [members] (entities of one class, by single primary key) in one query and
     * returns the members whose row was not found, which are left untouched. Throws only
     * when the query itself fails.
     */
    @Suppress("UNCHECKED_CAST")
    internal fun batchPopulate(members: List<AutoTable>): List<AutoTable> {
        if (members.isEmpty()) return emptyList()
        val info = resolveTableInfo(members[0]::class) as TableInfo<AutoTable>

        // Collect unique IDs and map members by string key
        val byId = LinkedHashMap<String, MutableList<AutoTable>>()
        val uniqueIds = mutableListOf<Any?>()

        for (member in members) {
            val id = info.getIdValues(member)[0]
            val key = identityKey(id)
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
                // Build the column plan once for the whole batch (same SELECT, same row shape).
                var plan: ColumnPlan<AutoTable>? = null
                while (rs.next()) {
                    // Convert before keying: a driver may hand back a different
                    // representation of the same id (java.sql.Timestamp for an Instant
                    // key), whose toString() would never match the entity-side key.
                    // identityKey then equates decimals that differ only in scale.
                    val key = identityKey(castTo(info.idTypes[0], rs.getObject(pkColIdx, info.idTypes[0]), this))
                    val targets = byId.remove(key)
                    if (targets != null) {
                        if (plan == null) plan = buildColumnPlan(rs, info, null)
                        for (target in targets)
                            populateWithPlan(target, rs, plan, nestedContext)
                    }
                }
                0
            }
        })
        nestedContext.resolveDanglingReferences(this, null)

        return byId.values.flatten()
    }

    // --- Sequence support ---

    private fun getNextSequences(conn: Connection?, sequence: String, count: Int): List<NativeBigInteger> {
        val sql = sqlDialect.sequenceDialect(sequence, count) ?: return emptyList()
        val result = mutableListOf<NativeBigInteger>()
        // Sequence calls return at most a few rows — eager.
        readCursor(conn, NativeBigInteger::class, sql, fetchSize = 0) { result.add(it) }
        if (result.isNotEmpty())
            _dbLog("Sequence $sequence incremented by ${result.size} to ${result.last()}")
        return result
    }

    private fun isIntegralZero(value: Any): Boolean = when (value) {
        is Long -> value == 0L
        is Int -> value == 0
        is Short -> value == 0.toShort()
        is Byte -> value == 0.toByte()
        else -> false
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
            if (it is AutoTable) it.markHydrated() // Prevent lazy-load during value extraction
        }

        return ConnectionMaker(conn).useWithException("Unable to batch create") { maker ->
            val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
            val hasSinglePk = info.idDbNames.size == 1

            // Identify items needing IDs (single PK)
            val isAutoIncrement = hasSinglePk && info.primaryKeys[0].isAutoIncrement
            val sequence = if (hasSinglePk) info.idSequences[0] else ""
            val needsId = mutableListOf<Int>()
            if (hasSinglePk) {
                if (isAutoIncrement) {
                    // Auto-increment: all items need generated IDs
                    for (i in itemList.indices) needsId.add(i)
                } else {
                    for (i in itemList.indices) {
                        val id = info.getIdValues(itemList[i])[0]
                        // A primitive key cannot be null, so 0 stands for unassigned when a sequence fills it
                        if (id == null || (sequence.isNotBlank() && isIntegralZero(id)))
                            needsId.add(i)
                    }
                }
            }

            // Bulk fetch sequences if applicable
            if (needsId.isNotEmpty() && sequence.isNotBlank()) {
                val seqs = getNextSequences(maker.connection, sequence, needsId.size)
                if (seqs.size < needsId.size)
                    throw SQLException(
                        "Sequence $sequence returned ${seqs.size} value(s), but ${needsId.size} were requested"
                    )
                for (i in needsId.indices)
                    info.setField(itemList[needsId[i]], info.idDbNames[0], seqs[i], this)
                needsId.clear()
            }

            val hasGK = sqlDialect.generatedKeyRetrieval !== GeneratedKeyRetrieval.NONE
            val fetchGeneratedKeys = hasGK && needsId.size == 1
            val pkColumn = if (hasSinglePk) info.singleKeyDbName else null

            sqlDialect.prepareForInsert(maker.connection, info.createQuery, fetchGeneratedKeys, pkColumn).use { stmt ->
                for ((index, item) in itemList.withIndex()) {
                    bindAndLog(stmt, info.createQuery, info.getCreateValues(item).map { sqlData(it, false) })
                    if (fetchGeneratedKeys) {
                        stmt.executeUpdate()
                        // Read the key right after this item's own execution — with multiple
                        // executions on one statement, getGeneratedKeys() may only reflect the last one.
                        if (index == needsId[0]) {
                            stmt.getGeneratedKeys().use { rs ->
                                if (rs.next()) {
                                    if (sqlDialect.generatedKeyRetrieval === GeneratedKeyRetrieval.BY_INDEX)
                                        info.setField(
                                            item,
                                            info.singleKeyDbName,
                                            rs.getObject(1, NativeBigInteger::class),
                                            this
                                        )
                                    else
                                        populate(item, rs)
                                }
                            }
                        }
                    } else {
                        stmt.addBatch()
                    }
                }
                if (!fetchGeneratedKeys)
                    stmt.executeBatch()
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
        // An unloaded shadow reference is loaded before its values are read: a column read
        // straight from its field (no public setter) would otherwise still hold its default
        // and be written back over the stored value.
        itemList.forEach { if (it is AutoTable) it.hydrateIfNeeded() }
        val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
        for (item in itemList)
            if (getValidIds(item, info).any { it == null })
                throw SQLException("Cannot update ${info.tableName}: primary key is null")
        if (dynamicUpdate) {
            updateChangedColumns(conn, info, itemList)
            return itemList
        }
        @Suppress("UNCHECKED_CAST")
        performQuery(conn, info.updateQuery, emptyList(),
            batchItems = itemList,
            batchParamOf = { (info as TableInfo<Any>).getUpdateValues(it!!).map { v -> sqlData(v, false) } }
        ) { it.executeBatch() }
        return itemList
    }

    private class ChangedRow<T>(val item: T, val values: List<Any?>)

    /**
     * The [dynamicUpdate] path of [update]: compares each entity against its
     * [UpdateSnapshot], groups the entities by the set of changed columns and issues one
     * batched `UPDATE` per group, all on one connection.
     */
    private fun <T : Any> updateChangedColumns(conn: Connection?, info: TableInfo<T>, items: List<T>) {
        val allColumns = (0 until info.updatableCount).toList()
        val groups = LinkedHashMap<List<Int>, MutableList<ChangedRow<T>>>()
        for (item in items) {
            val values = info.getUpdatableValues(item).map { sqlData(it, false) }
            // A changed primary key points the UPDATE at another row: write it whole, as
            // without dynamic update.
            val snapshot = (item as? StormifyEntity)?._updateSnapshot?.takeIf { it.info === info }
                ?.takeIf { snap -> snapshotIds(info, item).let { ids -> ids.indices.all { sameSnapshotValue(ids[it], snap.ids[it]) } } }
            val columns = if (snapshot == null) {
                if (allColumns.isEmpty()) throw SQLException("Entity ${info.tableName} has no updatable fields")
                allColumns
            } else values.indices.filter { !sameSnapshotValue(values[it], snapshot.values[it]) }
            if (columns.isNotEmpty())
                groups.getOrPut(columns) { mutableListOf() }.add(ChangedRow(item, values))
        }
        if (groups.isEmpty()) return
        ConnectionMaker(conn).use { maker ->
            for ((columns, rows) in groups) {
                val query = if (columns.size == allColumns.size) info.updateQuery else info.partialUpdateQuery(columns)
                performQuery(maker.connection, query, emptyList(),
                    batchItems = rows,
                    batchParamOf = { row ->
                        @Suppress("UNCHECKED_CAST")
                        row as ChangedRow<T>
                        columns.map { row.values[it] } + info.getIdValues(row.item).map { sqlData(it, false) }
                    }
                ) { it.executeBatch() }
            }
            // The written values are the reference for the next update, once they are
            // final: inside a transaction of this instance they are logged, so a rollback
            // drops them again; in auto-commit they are committed. On a connection whose
            // transaction is managed elsewhere the statement may still be rolled back, so
            // the old reference stays, which at worst writes the same columns again.
            val autoCommit by lazy { maker.connection.getAutoCommit() }
            for (rows in groups.values)
                for (row in rows) {
                    val entity = row.item as? StormifyEntity ?: continue
                    if (txSnapshotLog.record(maker.connection, entity) || autoCommit)
                        entity._updateSnapshot = UpdateSnapshot(
                            info, Array(row.values.size) { snapshotCopy(row.values[it]) }, snapshotIds(info, row.item)
                        )
                }
        }
    }

    /** Snapshots replaced inside open transactions, dropped again on rollback. */
    internal val txSnapshotLog = TxSnapshotLog()

    /**
     * Copies the current updatable column values of [item] for [dynamicUpdate]. A reference
     * column takes its key from [referenceKeys] (as read by the load) when present, rather
     * than through the referenced entity, which would load that entity's row.
     */
    private fun <T : Any> takeUpdateSnapshot(
        info: TableInfo<T>,
        item: T,
        referenceKeys: Map<String, Any?> = emptyMap(),
    ): UpdateSnapshot {
        val props = info.updatableProperties
        val values = Array(props.size) { i ->
            val prop = props[i]
            val key = prop.dbName.lowercase()
            if (prop.isReference && referenceKeys.containsKey(key)) referenceKeys[key]
            else sqlData(prop.sqlValue(item), false)
        }
        return UpdateSnapshot(info, Array(values.size) { snapshotCopy(values[it]) }, snapshotIds(info, item))
    }

    private fun <T : Any> snapshotIds(info: TableInfo<T>, item: T): Array<Any?> {
        val ids = info.getIdValues(item)
        return Array(ids.size) { snapshotCopy(sqlData(ids[it], false)) }
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
            if (idValues.any { it == null })
                throw SQLException("Cannot delete from ${info.tableName}: primary key is null")
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
     * reference path (e.g. `Tables.AuditEntry_.createdBy`) instead of a string. The
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
        val parentInfo = resolveTableInfo(parent::class) as TableInfo<M>
        val parentId = this.getValidIds(parent, parentInfo)
        require(parentId.size == 1) { "Parent class ${parent::class.fullName} should have exactly one primary key" }

        val detailInfo = resolveTableInfo(detailsClass)
        val propertyDbName = detailsForeignKey(parent::class, detailsClass, propertyName)

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

    /**
     * The column on [detailsClass] that points back at [parentClass].
     *
     * With [propertyName] null the child type is scanned for exactly one field of the
     * parent's type; otherwise the named Kotlin property is looked up and checked to be
     * of that type.
     */
    private fun detailsForeignKey(
        parentClass: KClass<*>,
        detailsClass: KClass<*>,
        propertyName: String?
    ): String {
        require(propertyName == null || '.' !in propertyName) {
            "getDetails propertyName must be a single field name on ${detailsClass.fullName}, " +
                "not a traversal path — got '$propertyName'"
        }
        val nonKeyFields = resolveTableInfo(detailsClass).fieldInfos.filter { !it.isPrimaryKey }
        val matchIndex: Int
        if (propertyName == null) {
            matchIndex = findItemOnce(nonKeyFields.map { it.type }, parentClass, detailsClass.fullName)
        } else {
            matchIndex = findItemOnce(nonKeyFields.map { it.name }, propertyName, detailsClass.fullName)
            if (nonKeyFields[matchIndex].type != parentClass)
                throw SQLException("Field $propertyName is not of type ${parentClass.fullName} in class ${detailsClass.fullName}")
        }
        return nonKeyFields[matchIndex].dbName
    }

    /** The single primary key of [entity], or null when it has none or has several. */
    internal fun singleIdOrNull(entity: Any): Any? {
        val info = resolveTableInfo(entity::class)
        @Suppress("UNCHECKED_CAST")
        val ids = (info as TableInfo<Any>).getIdValues(entity)
        return ids.singleOrNull()
    }

    /**
     * Fetches the children of many parents in one query, grouped by parent id (as
     * [identityKey]).
     *
     * This is what turns a page of parents from N+1 queries into two. Returns null when
     * the batch cannot be built — a parent whose key is missing, or a child type whose
     * rows do not carry the foreign key back — and the caller falls back to querying per
     * parent rather than silently returning something incomplete.
     */
    internal fun <D : Any> getDetailsBatch(
        conn: Connection?,
        parents: List<Any>,
        detailsClass: KClass<D>,
        propertyName: String?,
    ): Map<String, List<D>>? {
        if (parents.isEmpty()) return null
        val parentClass = parents.first()::class
        // Keyed by identityKey: the key read back through the child's foreign key column
        // may differ in type or scale from the parent's own (an INT column pointing at a
        // DECIMAL key), and must still find its parent.
        val byId = LinkedHashMap<String, Any>(parents.size)
        val ids = ArrayList<Any>(parents.size)
        for (parent in parents) {
            val id = singleIdOrNull(parent) ?: return null
            byId[identityKey(id)] = parent
            ids.add(id)
        }

        val detailInfo = resolveTableInfo(detailsClass)
        val fkColumn = detailsForeignKey(parentClass, detailsClass, propertyName)
        val placeholders = ids.joinToString(", ") { "?" }

        // The property behind the foreign key column, so each loaded row can be asked
        // which parent it points at.
        val fkProperty = detailInfo.propertiesForColumn(fkColumn).firstOrNull() ?: return null

        val rows = read(
            conn,
            detailsClass,
            "SELECT * FROM ${detailInfo.tableName} WHERE $fkColumn IN ($placeholders)",
            *ids.toTypedArray(),
        )

        val grouped = HashMap<String, MutableList<D>>(byId.size)
        for (id in byId.keys) grouped[id] = mutableListOf()
        for (row in rows) {
            // The column loaded as a reference to the parent, so read that reference and
            // take its key. Reading it does not fetch anything: a primary key is a plain
            // property, not one of the lazily loaded ones.
            val ownerRef = fkProperty.getter(row) ?: return null
            val ownerId = identityKey(singleIdOrNull(ownerRef) ?: return null)
            val owner = byId[ownerId] ?: return null
            // Point the child at the parent instance the caller already holds, rather
            // than at the stub the read created, so the two sides agree.
            detailInfo.setField(row, fkColumn, owner, this)
            grouped[ownerId]?.add(row)
        }
        return grouped
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
     * Whatever [block] throws propagates unchanged after the rollback; the
     * transaction's own failures (connection, savepoint, commit) are [SQLException]s.
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
        txSnapshotLog.begin(conn)
        try {
            conn.setAutoCommit(false)
            val result = block()
            conn.commit()
            txSnapshotLog.commit(conn)
            return result
        } catch (e: Throwable) {
            // The connection may already be broken (the reason we landed here);
            // swallow rollback / autocommit failures so they do not replace the
            // original exception, which propagates unchanged.
            runCatching { conn.rollback() }
            txSnapshotLog.rollback(conn)
            throw e
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
        val mark = txSnapshotLog.mark(conn)
        return try {
            val result = block()
            if (sqlDialect.supportsReleaseSavepoint)
                runCatching { conn.releaseSavepoint(savepoint) }
            result
        } catch (e: Throwable) {
            runCatching { conn.rollback(savepoint) }
            txSnapshotLog.rollbackTo(conn, mark)
            throw e
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
                        is Sp.In -> bindParam(cs, statement, i + 1, p.value)
                        is Sp.Out<*> -> cs.registerOutParameter(i + 1, p.type)
                        is Sp.InOut<*> -> {
                            cs.registerOutParameter(i + 1, p.type)
                            bindParam(cs, statement, i + 1, p.input)
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
