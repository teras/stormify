package onl.ycode.stormify

import onl.ycode.stormify.biglist.SortState
import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.JdbcDataSource
import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.biglist.FilterSyntax
import onl.ycode.stormify.biglist.InputParser
import onl.ycode.stormify.biglist.PagedList
import onl.ycode.stormify.biglist.PagedQuery
import onl.ycode.stormify.biglist.ReferencePath
import onl.ycode.stormify.biglist.TextFolding
import java.util.function.Consumer

/**
 * Entry point for Stormify from Java code. Provides CRUD operations, raw SQL queries,
 * stored procedure calls, and transaction management against a [javax.sql.DataSource].
 *
 * ```java
 * StormifyJ stormify = new StormifyJ(hikariDataSource);
 * stormify.asDefault();
 * User u = stormify.findById(User.class, 1);
 * ```
 *
 * From Kotlin code, use [Stormify] directly; the two share their instances through
 * [stormify] and the [StormifyJ] constructor that takes a [Stormify].
 */
class StormifyJ(
    /** The [Stormify] instance this wrapper works on. */
    val stormify: Stormify,
) {
    /** Creates the [Stormify] instance of [dataSource], registering [registrars]. */
    constructor(dataSource: DataSource, vararg registrars: EntityRegistrar) : this(Stormify(dataSource, *registrars))

    /** Convenience constructor that accepts any [javax.sql.DataSource] (HikariCP, plain JDBC driver, etc.). */
    constructor(jdbcDataSource: javax.sql.DataSource, vararg registrars: EntityRegistrar) : this(JdbcDataSource(jdbcDataSource), *registrars)

    /**
     * Single-argument convenience constructor. Exists primarily so Spring XML
     * `<constructor-arg ref="dataSource"/>` (and similar DI containers that
     * resolve constructors by arity) can pick an unambiguous one-arg match.
     */
    constructor(dataSource: DataSource) : this(dataSource, *emptyArray())

    /** Single-argument [javax.sql.DataSource] overload — see the primary one-arg constructor for the rationale. */
    constructor(jdbcDataSource: javax.sql.DataSource) : this(JdbcDataSource(jdbcDataSource), *emptyArray())

    /** The data source this instance runs its queries on. See [Stormify.dataSource]. */
    val dataSource: DataSource get() = stormify.dataSource

    /**
     * The SQL dialect, auto-detected from the data source on first access. Set it
     * manually when detection cannot see the real database, for example behind a
     * proxy or a pool that hides the driver metadata. See [Stormify.sqlDialect].
     */
    var sqlDialect: SqlDialect
        get() = stormify.sqlDialect
        set(value) { stormify.sqlDialect = value }

    /** Executes a SELECT and returns the rows as a list of [baseClass] instances. */
    @Throws(SQLException::class)
    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(null, baseClass.kotlin, query, *params)

    /**
     * Executes a SELECT and returns the rows, capturing values from columns named in [customFields]
     * (e.g. `COUNT(*) OVER () AS __total`) via the supplied consumers — those columns are not mapped
     * onto the entity. Column names are matched case-insensitively.
     */
    @Throws(SQLException::class)
    fun <T : Any> read(
        baseClass: Class<T>,
        query: String,
        customFields: Map<String, Consumer<Any?>>,
        vararg params: Any?
    ) = stormify.read(
        null, baseClass.kotlin, query, *params,
        customFields = customFields.mapValues { (_, c) -> { v: Any? -> c.accept(v) } }
    )

    /**
     * Executes a SELECT and returns every row as an array of its column values, by position,
     * so columns with the same label all come back. See [Stormify.readRows].
     */
    @Throws(SQLException::class)
    fun readRows(query: String, vararg params: Any?): List<Array<Any?>> = stormify.readRows(query, *params)

    /** Streams every row to [consumer] as an array of its column values. See [Stormify.readRowsCursor]. */
    @Throws(SQLException::class)
    fun readRowsCursor(query: String, consumer: Consumer<Array<Any?>>, vararg params: Any?): Int =
        stormify.readRowsCursor(query, *params) { consumer.accept(it) }

    /**
     * [readRowsCursor] fetching [fetchSize] rows per round trip instead of [cursorFetchSize];
     * `0` reads the whole result without a cursor. See [Stormify.readRowsCursor].
     */
    @Throws(SQLException::class)
    fun readRowsCursor(query: String, fetchSize: Int, consumer: Consumer<Array<Any?>>, vararg params: Any?): Int =
        stormify.readRowsCursor(query, *params, fetchSize = fetchSize) { consumer.accept(it) }

    /** Executes a SELECT and returns the first row as a [baseClass] instance, or `null` if empty. */
    @Throws(SQLException::class)
    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(null, baseClass.kotlin, query, *params)

    /** Executes a SELECT and streams each row to [consumer] — avoids materializing the full result. */
    @Throws(SQLException::class)
    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(null, baseClass.kotlin, query, *params, consumer = { consumer.accept(it) })

    /**
     * [readCursor] fetching [fetchSize] rows per round trip instead of [cursorFetchSize];
     * `0` reads the whole result without a cursor.
     */
    @Throws(SQLException::class)
    fun <T : Any> readCursor(baseClass: Class<T>, query: String, fetchSize: Int, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(null, baseClass.kotlin, query, *params, fetchSize = fetchSize, consumer = { consumer.accept(it) })

    /**
     * Streaming variant that captures values from columns named in [customFields] via the supplied
     * consumers instead of mapping them onto the entity. See the list-returning [read] overload
     * for the capture semantics.
     */
    @Throws(SQLException::class)
    fun <T : Any> readCursor(
        baseClass: Class<T>,
        query: String,
        customFields: Map<String, Consumer<Any?>>,
        consumer: Consumer<T>,
        vararg params: Any?
    ) = stormify.readCursor(
        null, baseClass.kotlin, query, *params,
        customFields = customFields.mapValues { (_, c) -> { v: Any? -> c.accept(v) } },
        consumer = { consumer.accept(it) }
    )

    /** Executes an INSERT / UPDATE / DELETE and returns the affected row count. */
    @Throws(SQLException::class)
    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(null, query, *params)

    /** Refreshes [entity] from the database by its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> refresh(entity: T) = stormify.refresh(null, entity)

    /** Inserts [item] into its mapped table and returns the (possibly key-populated) entity. */
    @Throws(SQLException::class)
    fun <T : Any> create(item: T) = stormify.create(null, item)

    /** Batch INSERT of [items] in a single round trip. */
    @Throws(SQLException::class)
    fun <T : Any> create(items: Collection<T>) = stormify.create(null, items)

    /** UPDATE's the row corresponding to [updatedItem] using its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> update(updatedItem: T) = stormify.update(null, updatedItem)

    /** Batch UPDATE of [items]. */
    @Throws(SQLException::class)
    fun <T : Any> update(items: Collection<T>) = stormify.update(null, items)

    /** DELETE's the row corresponding to [deletedItem] using its primary key. */
    @Throws(SQLException::class)
    fun delete(deletedItem: Any) = stormify.delete(null, deletedItem)

    /** Batch DELETE of [items]. */
    @Throws(SQLException::class)
    fun <T : Any> delete(items: Collection<T>) = stormify.delete(null, items)

    /**
     * Returns the detail rows of type [detailsClass] that reference [parent]. Use
     * [propertyName] to disambiguate when the detail class has multiple foreign keys
     * pointing at the same parent type.
     */
    @JvmOverloads
    @Throws(SQLException::class)
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(null, parent, detailsClass.kotlin, propertyName)

    /**
     * Type-safe variant of [getDetails] that accepts an annotation-processor-generated
     * reference path (e.g. `Tables.AuditEntry_.createdBy`) instead of a string.
     * The compiler guarantees the referenced property exists on the child type, so
     * typos and renames surface at build time rather than on first query.
     */
    @Throws(SQLException::class)
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, referenceField: ReferencePath) =
        stormify.getDetails(null, parent, detailsClass.kotlin, referenceField.toString().trimEnd('.'))

    /** Convenience over [read] for `SELECT * FROM <table> <whereClause>`. */
    @JvmOverloads
    @Throws(SQLException::class)
    fun <T : Any> findAll(baseClass: Class<T>, whereClause: String = "", vararg arguments: Any?) =
        stormify.findAll(null, baseClass.kotlin, whereClause, *arguments)

    /** Looks up a single row of [baseClass] by its primary key [id]. Returns `null` if not found. */
    @Throws(SQLException::class)
    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(null, baseClass.kotlin, id)

    /**
     * Invokes the stored procedure [name] with [args]. Output and bidirectional parameters
     * should be passed as [Sp.Out] / [Sp.InOut] instances; all other values are auto-wrapped as IN.
     */
    @Throws(SQLException::class)
    fun procedure(name: String, vararg args: Any?) = stormify.procedure(name, *args)

    /**
     * Runs [block] inside a database transaction. Commits on return, rolls back on
     * any thrown exception. Inside the block, all CRUD/query calls on this wrapper
     * (e.g. `stormify.create(user)`) transparently run on the transaction's
     * connection via the ambient transaction registry. Nested calls to this method
     * become savepoints automatically.
     */
    @Throws(SQLException::class)
    fun transaction(block: Runnable) =
        stormify.transaction { block.run() }

    /**
     * Returning variant of [transaction] — see that method for lifecycle semantics.
     */
    @Throws(SQLException::class)
    fun <R> transaction(block: java.util.function.Supplier<R>): R =
        stormify.transaction { block.get() }

    /** Returns the [TableInfo] metadata for [baseClass], building it on first access. */
    @Throws(SQLException::class)
    fun <T : Any> getTableInfo(baseClass: Class<T>): TableInfo<T> = stormify.getTableInfo(baseClass.kotlin)

    /** Current [NamingPolicy]. See [Stormify.namingPolicy]. */
    var namingPolicy: (String) -> String
        get() = stormify.namingPolicy
        set(value) { stormify.namingPolicy = value }

    /** See [Stormify.addBlacklistField]. */
    fun addBlacklistField(name: String) = stormify.addBlacklistField(name)
    /** See [Stormify.removeBlacklistField]. */
    fun removeBlacklistField(name: String) = stormify.removeBlacklistField(name)
    /** See [Stormify.registerPrimaryKeyResolver]. */
    fun registerPrimaryKeyResolver(priority: Int, resolver: (String, String) -> Boolean) =
        stormify.registerPrimaryKeyResolver(priority, resolver)

    /** Policy for unmatched result-set columns. See [Stormify.unmatchedColumnPolicy]. */
    var unmatchedColumnPolicy: UnmatchedColumnPolicy
        get() = stormify.unmatchedColumnPolicy
        set(value) { stormify.unmatchedColumnPolicy = value }

    /** What a reference to a missing row does. See [Stormify.danglingReferences]. */
    var danglingReferences: DanglingReferences
        get() = stormify.danglingReferences
        set(value) { stormify.danglingReferences = value }

    /** The logger used by the underlying [Stormify] instance. */
    var logger
        get() = stormify.logger
        set(value) { stormify.logger = value }

    /** Rows buffered by the driver per round trip in streaming reads. See [Stormify.cursorFetchSize]. */
    var cursorFetchSize: Int
        get() = stormify.cursorFetchSize
        set(value) { stormify.cursorFetchSize = value }

    /** Filter syntax of the facets of lists attached to this instance. See [Stormify.filterSyntax]. */
    var filterSyntax: FilterSyntax
        get() = stormify.filterSyntax
        set(value) { stormify.filterSyntax = value }

    /**
     * Default input parser for facet filter values of lists attached to this instance,
     * or `null` for none. See [Stormify.inputParser].
     *
     * ```java
     * stormify.setInputParser((input, type) -> type == Facet.Type.NUMERIC ? input.replace(",", ".") : input);
     * ```
     */
    var inputParser: InputParser?
        get() = stormify.inputParser
        set(value) { stormify.inputParser = value }

    /** Text folding of case-insensitive facet filters. See [Stormify.textFolding]. */
    var textFolding: TextFolding
        get() = stormify.textFolding
        set(value) { stormify.textFolding = value }

    /** Whether [update] writes only the changed columns. See [Stormify.dynamicUpdate]. */
    var dynamicUpdate: Boolean
        get() = stormify.dynamicUpdate
        set(value) { stormify.dynamicUpdate = value }

    /**
     * Registers the underlying [Stormify] instance as the library-wide default, the one
     * [getDefault] wraps. Returns this wrapper for fluent chaining.
     */
    fun asDefault(): StormifyJ {
        stormify.asDefault()
        return this
    }

    /**
     * Attaches the underlying Stormify instance to the entity [target], so it lazy-loads and
     * writes through this instance. Entities this instance reads are attached already.
     * Returns [target] for fluent chaining.
     *
     * ```java
     * // Manual stub: the id is known, the rest loads on first access
     * User user = stormify.attach(new User());
     * user.setId(42);
     * System.out.println(user.getName());  // triggers SELECT
     * ```
     */
    fun <T : StormifyEntity> attach(target: T): T = stormify.attach(target)

    /**
     * Creates a [PagedList] of [entityClass] working on this instance, ready to be configured.
     *
     * ```java
     * PagedList<Company> list = stormify.pagedList(Company.class)
     *         .facet(Company_.name)
     *         .sort(Company_.name, SortState.ASCENDING);
     * ```
     */
    fun <T : Any> pagedList(entityClass: Class<T>): PagedList<T> = PagedList(entityClass.kotlin, stormify)

    /**
     * Creates a [PagedQuery] of [entityClass] working on this instance, ready to be configured.
     *
     * ```java
     * PagedQuery<Customer> customers = stormify.pagedQuery(Customer.class);
     * customers.addFacet("name", Customer_.name);
     * ```
     */
    fun <T : Any> pagedQuery(entityClass: Class<T>): PagedQuery<T> = PagedQuery(entityClass.kotlin, stormify)

    /** Access to the library-wide default instance from Java. */
    companion object {
        /**
         * A wrapper of the library-wide default instance, set by [StormifyJ.asDefault] or
         * [Stormify.asDefault].
         *
         * @throws SQLException if no default instance is set
         */
        @JvmStatic
        fun getDefault(): StormifyJ = StormifyJ(
            Stormify.defaultInstance
                ?: throw SQLException("No default Stormify instance. Call stormify.asDefault() first.")
        )
    }
}
