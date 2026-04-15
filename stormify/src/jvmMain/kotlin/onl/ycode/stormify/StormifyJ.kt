package onl.ycode.stormify

import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.JdbcDataSource
import onl.ycode.stormify.biglist.ReferencePath
import java.util.function.Consumer
import java.util.function.Function

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
 * From Kotlin code, use [Stormify] directly.
 */
class StormifyJ(dataSource: DataSource, vararg registrars: EntityRegistrar) {
    /** Convenience constructor that accepts any [javax.sql.DataSource] (HikariCP, plain JDBC driver, etc.). */
    constructor(jdbcDataSource: javax.sql.DataSource, vararg registrars: EntityRegistrar) : this(JdbcDataSource(jdbcDataSource), *registrars)

    private val stormify = Stormify(dataSource, *registrars)

    /** The auto-detected SQL dialect for this data source. See [Stormify.sqlDialect]. */
    val sqlDialect get() = stormify.sqlDialect

    /** Executes a SELECT and returns the rows as a list of [baseClass] instances. */
    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(null, baseClass.kotlin, query, *params)

    /** Executes a SELECT and returns the first row as a [baseClass] instance, or `null` if empty. */
    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(null, baseClass.kotlin, query, *params)

    /** Executes a SELECT and streams each row to [consumer] — avoids materializing the full result. */
    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(null, baseClass.kotlin, query, *params, consumer = { consumer.accept(it) })

    /** Executes an INSERT / UPDATE / DELETE and returns the affected row count. */
    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(null, query, *params)

    /** Populates [entity] from the database by its primary key. Used internally by [AutoTable]. */
    fun <T : Any> populate(entity: T) = stormify.populate(null, entity)

    /** Inserts [item] into its mapped table and returns the (possibly key-populated) entity. */
    fun <T : Any> create(item: T) = stormify.create(null, item)

    /** Batch INSERT of [items] in a single round trip. */
    fun <T : Any> create(items: Collection<T>) = stormify.create(null, items)

    /** UPDATE's the row corresponding to [updatedItem] using its primary key. */
    fun <T : Any> update(updatedItem: T) = stormify.update(null, updatedItem)

    /** Batch UPDATE of [items]. */
    fun <T : Any> update(items: Collection<T>) = stormify.update(null, items)

    /** DELETE's the row corresponding to [deletedItem] using its primary key. */
    fun delete(deletedItem: Any) = stormify.delete(null, deletedItem)

    /** Batch DELETE of [items]. */
    fun <T : Any> delete(items: Collection<T>) = stormify.delete(null, items)

    /**
     * Returns the detail rows of type [detailsClass] that reference [parent]. Use
     * [propertyName] to disambiguate when the detail class has multiple foreign keys
     * pointing at the same parent type.
     */
    @JvmOverloads
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(null, parent, detailsClass.kotlin, propertyName)

    /**
     * Type-safe variant of [getDetails] that accepts an annotation-processor-generated
     * reference path (e.g. `Paths.AuditEntry_.createdBy()`) instead of a string.
     * The compiler guarantees the referenced property exists on the child type, so
     * typos and renames surface at build time rather than on first query.
     */
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, referenceField: ReferencePath) =
        stormify.getDetails(null, parent, detailsClass.kotlin, referenceField.path.trimEnd('.'))

    /** Convenience over [read] for `SELECT * FROM <table> <whereClause>`. */
    @JvmOverloads
    fun <T : Any> findAll(baseClass: Class<T>, whereClause: String = "", vararg arguments: Any?) =
        stormify.findAll(null, baseClass.kotlin, whereClause, *arguments)

    /** Looks up a single row of [baseClass] by its primary key [id]. Returns `null` if not found. */
    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(null, baseClass.kotlin, id)

    /**
     * Invokes the stored procedure [name] with [args]. Output and bidirectional parameters
     * should be passed as [Sp.Out] / [Sp.InOut] instances; all other values are auto-wrapped as IN.
     */
    fun procedure(name: String, vararg args: Any?) = stormify.procedure(name, *args)

    /**
     * Runs [block] inside a database transaction. On return the transaction commits;
     * on any exception it rolls back.
     */
    fun transaction(block: Consumer<TransactionContextJ>) =
        TransactionContextJ(TransactionContext(stormify)).start(block)

    /**
     * Runs [block] inside a database transaction and returns its result. On return the
     * transaction commits; on any exception it rolls back.
     */
    fun <R> transaction(block: Function<TransactionContextJ, R>): R =
        TransactionContextJ(TransactionContext(stormify)).start(block)

    /** Returns the [TableInfo] metadata for [baseClass], building it on first access. */
    fun getTableInfo(baseClass: Class<*>) = stormify.resolveTableInfo(baseClass.kotlin)

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

    /** Whether strict mapping mode is enabled. See [Stormify.isStrictMode]. */
    var isStrictMode: Boolean
        get() = stormify.isStrictMode
        set(value) { stormify.isStrictMode = value }

    /** The logger used by the underlying [Stormify] instance. */
    var logger
        get() = stormify.logger
        set(value) { stormify.logger = value }

    /**
     * Registers the underlying [Stormify] instance as the library-wide default **and**
     * caches this wrapper in [StormifyJ.getDefault], so Java callers can retrieve the
     * Java-friendly wrapper (not just the raw [Stormify]) via a single static call.
     * Returns this wrapper for fluent chaining.
     */
    fun asDefault(): StormifyJ {
        stormify.asDefault()
        defaultWrapper = this
        return this
    }

    /**
     * Runs [block] with this wrapper as the default, restoring the previous default
     * when the block exits. Use for scoped overrides such as per-request tenants.
     *
     * ```java
     * stormifyJ.asDefault(() -> {
     *     User u = stormifyJ.attach(new User());
     *     u.setId(42);
     *     System.out.println(u.getName());
     * });
     * ```
     */
    fun asDefault(block: java.util.function.Consumer<StormifyJ>) = asDefault<Unit> { block.accept(this) }

    /** Returning variant of [asDefault] — see the [Consumer] overload for semantics. */
    fun <R> asDefault(block: java.util.function.Function<StormifyJ, R>): R = asDefault<R> { block.apply(this) }

    // Shared implementation: delegate Stormify.defaultInstance management to the
    // scoped Stormify.asDefault(block) and only track the StormifyJ wrapper cache here.
    private inline fun <R> asDefault(crossinline body: () -> R): R {
        val prevWrapper = defaultWrapper
        defaultWrapper = this
        return try {
            stormify.asDefault { body() }
        } finally {
            defaultWrapper = prevWrapper
        }
    }

    /**
     * Attaches the underlying Stormify instance to [target] so the target can use it for
     * database operations without receiving it as an explicit parameter.
     *
     * Works for any [StormifyAware] — entity / [AutoTable] subclasses and
     * [onl.ycode.stormify.biglist.PagedList] instances. Returns [target] for fluent chaining.
     *
     * ```java
     * // Manual stub — user knows the ID, lets Stormify lazy-load the rest
     * User user = stormify.attach(new User());
     * user.setId(42);
     * System.out.println(user.getName());  // triggers SELECT
     *
     * // Paged list — attach before use (or rely on Stormify.defaultInstance)
     * PagedList<Company> list = stormify.attach(new PagedList<>(Company.class));
     * list.addFacet("name");
     * ```
     */
    fun <T : StormifyAware> attach(target: T): T = stormify.attach(target)

    /** Accessors for the library-wide default [StormifyJ] wrapper. */
    companion object {
        /**
         * The cached wrapper set by the most recent call to [StormifyJ.asDefault]. Java
         * callers typically access it via the [getDefault] static accessor below rather
         * than this field directly.
         */
        private var defaultWrapper: StormifyJ? = null

        /**
         * Returns the current default [StormifyJ] wrapper, or `null` if none has been
         * registered yet. The default is set by calling [StormifyJ.asDefault] on a
         * `StormifyJ` instance during application startup.
         */
        @JvmStatic
        fun getDefault(): StormifyJ? = defaultWrapper

        /**
         * Drops the cached wrapper. Called directly by [StormifyLifecycle.clear] so
         * that a shared-classpath webapp undeploy doesn't retain a reference to the
         * webapp's `StormifyJ` (and, through it, the entire entity ClassLoader).
         */
        internal fun clearDefaultWrapper() { defaultWrapper = null }
    }
}
