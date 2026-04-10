package onl.ycode.stormify

import java.util.function.Consumer

/**
 * Java-facing wrapper around a [TransactionContext]. Exposes all CRUD and query operations
 * as idiomatic Java methods that take `Class<T>` parameters (instead of Kotlin `KClass<T>`)
 * and `Consumer<T>` / `Runnable` instead of Kotlin function types.
 *
 * Obtained from [StormifyJ.transaction] — you do not construct this directly. All operations
 * share the same underlying connection so they participate in the same database transaction.
 */
class TransactionContextJ(private val ctx: TransactionContext) {
    private val connection = ctx.conn
    private val stormify = ctx.stormify

    /** Executes a SELECT and returns the rows as a list of [baseClass] instances. */
    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(connection, baseClass.kotlin, query, *params)

    /** Executes a SELECT and returns the first row as a [baseClass] instance, or `null` if empty. */
    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(connection, baseClass.kotlin, query, *params)

    /** Executes a SELECT and streams each row to [consumer] — avoids materializing the full result. */
    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(connection, baseClass.kotlin, query, *params, consumer = { consumer.accept(it) })

    /** Executes an INSERT / UPDATE / DELETE and returns the affected row count. */
    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(connection, query, *params)

    /** Populates [entity] from the database by its primary key. Used internally by [AutoTable]. */
    fun <T : Any> populate(entity: T) = stormify.populate(connection, entity)

    /** Inserts [item] into its mapped table and returns the (possibly key-populated) entity. */
    fun <T : Any> create(item: T) = stormify.create(connection, item)

    /** Batch INSERT of [items] in a single round trip. */
    fun <T : Any> create(items: Collection<T>) = stormify.create(connection, items)

    /** UPDATE's the row corresponding to [updatedItem] using its primary key. */
    fun <T : Any> update(updatedItem: T) = stormify.update(connection, updatedItem)

    /** Batch UPDATE of [items]. */
    fun <T : Any> update(items: Collection<T>) = stormify.update(connection, items)

    /** DELETE's the row corresponding to [deletedItem] using its primary key. */
    fun delete(deletedItem: Any) = stormify.delete(connection, deletedItem)

    /** Batch DELETE of [items]. */
    fun <T : Any> delete(items: Collection<T>) = stormify.delete(connection, items)

    /**
     * Returns the detail rows of type [detailsClass] that reference [parent]. Use
     * [propertyName] to disambiguate when the detail class has multiple foreign keys
     * pointing at the same parent type.
     */
    @JvmOverloads
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(connection, parent, detailsClass.kotlin, propertyName)

    /** Convenience over [read] for `SELECT * FROM <table> <whereClause>`. */
    @JvmOverloads
    fun <T : Any> findAll(baseClass: Class<T>, whereClause: String = "", vararg arguments: Any?) =
        stormify.findAll(connection, baseClass.kotlin, whereClause, *arguments)

    /** Looks up a single row of [baseClass] by its primary key [id]. Returns `null` if not found. */
    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(connection, baseClass.kotlin, id)

    /**
     * Invokes the stored procedure [name] with [args]. Output and bidirectional parameters
     * should be passed as [Sp.Out] / [Sp.InOut] instances; all other values are auto-wrapped as IN.
     */
    fun procedure(name: String, vararg args: Any?) = stormify.procedure(connection, name, *args)

    internal fun start(block: Consumer<TransactionContextJ>) = ctx.start { block.accept(this@TransactionContextJ) }

    /** Starts a nested transaction via a savepoint. If [block] throws, only its work is rolled back. */
    fun transaction(block: Runnable) =
        ctx.transaction { block.run() }
}
