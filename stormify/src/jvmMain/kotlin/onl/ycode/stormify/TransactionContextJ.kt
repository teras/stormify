package onl.ycode.stormify

import onl.ycode.kdbc.SQLException
import onl.ycode.stormify.biglist.ReferencePath
import java.util.function.Consumer
import java.util.function.Function

/**
 * Scope of an active database transaction. Provides CRUD operations, raw SQL queries,
 * stored procedure calls, and nested transactions; every operation runs on the same
 * connection and participates in the same transaction.
 *
 * Obtained from [StormifyJ.transaction].
 */
class TransactionContextJ(private val ctx: TransactionContext) {
    private val connection = ctx.conn
    private val stormify = ctx.stormify

    /** Executes a SELECT and returns the rows as a list of [baseClass] instances. */
    @Throws(SQLException::class)
    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(connection, baseClass.kotlin, query, *params)

    /** Executes a SELECT and returns the first row as a [baseClass] instance, or `null` if empty. */
    @Throws(SQLException::class)
    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(connection, baseClass.kotlin, query, *params)

    /** Executes a SELECT and streams each row to [consumer] — avoids materializing the full result. */
    @Throws(SQLException::class)
    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(connection, baseClass.kotlin, query, *params, consumer = { consumer.accept(it) })

    /** Executes an INSERT / UPDATE / DELETE and returns the affected row count. */
    @Throws(SQLException::class)
    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(connection, query, *params)

    /** Populates [entity] from the database by its primary key. Used internally by [AutoTable]. */
    @Throws(SQLException::class)
    fun <T : Any> populate(entity: T) = stormify.populate(connection, entity)

    /** Inserts [item] into its mapped table and returns the (possibly key-populated) entity. */
    @Throws(SQLException::class)
    fun <T : Any> create(item: T) = stormify.create(connection, item)

    /** Batch INSERT of [items] in a single round trip. */
    @Throws(SQLException::class)
    fun <T : Any> create(items: Collection<T>) = stormify.create(connection, items)

    /** UPDATE's the row corresponding to [updatedItem] using its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> update(updatedItem: T) = stormify.update(connection, updatedItem)

    /** Batch UPDATE of [items]. */
    @Throws(SQLException::class)
    fun <T : Any> update(items: Collection<T>) = stormify.update(connection, items)

    /** DELETE's the row corresponding to [deletedItem] using its primary key. */
    @Throws(SQLException::class)
    fun delete(deletedItem: Any) = stormify.delete(connection, deletedItem)

    /** Batch DELETE of [items]. */
    @Throws(SQLException::class)
    fun <T : Any> delete(items: Collection<T>) = stormify.delete(connection, items)

    /**
     * Returns the detail rows of type [detailsClass] that reference [parent]. Use
     * [propertyName] to disambiguate when the detail class has multiple foreign keys
     * pointing at the same parent type.
     */
    @JvmOverloads
    @Throws(SQLException::class)
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(connection, parent, detailsClass.kotlin, propertyName)

    /**
     * Type-safe variant of [getDetails] that accepts an annotation-processor-generated
     * reference path (e.g. `Paths.AuditEntry_.createdBy()`) instead of a string.
     */
    @Throws(SQLException::class)
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, referenceField: ReferencePath) =
        stormify.getDetails(connection, parent, detailsClass.kotlin, referenceField.path.trimEnd('.'))

    /** Convenience over [read] for `SELECT * FROM <table> <whereClause>`. */
    @JvmOverloads
    @Throws(SQLException::class)
    fun <T : Any> findAll(baseClass: Class<T>, whereClause: String = "", vararg arguments: Any?) =
        stormify.findAll(connection, baseClass.kotlin, whereClause, *arguments)

    /** Looks up a single row of [baseClass] by its primary key [id]. Returns `null` if not found. */
    @Throws(SQLException::class)
    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(connection, baseClass.kotlin, id)

    /**
     * Invokes the stored procedure [name] with [args]. Output and bidirectional parameters
     * should be passed as [Sp.Out] / [Sp.InOut] instances; all other values are auto-wrapped as IN.
     */
    @Throws(SQLException::class)
    fun procedure(name: String, vararg args: Any?) = stormify.procedure(connection, name, *args)

    internal fun start(block: Consumer<TransactionContextJ>) = ctx.start { block.accept(this@TransactionContextJ) }

    internal fun <R> start(block: Function<TransactionContextJ, R>): R =
        ctx.start { block.apply(this@TransactionContextJ) }

    /** Starts a nested transaction via a savepoint. If [block] throws, only its work is rolled back. */
    @Throws(SQLException::class)
    fun transaction(block: Runnable) =
        ctx.transaction { block.run() }

    /**
     * Starts a nested transaction via a savepoint and returns [block]'s result. If [block]
     * throws, only its work is rolled back.
     */
    @Throws(SQLException::class)
    fun <R> transaction(block: Function<TransactionContextJ, R>): R =
        ctx.transaction { block.apply(this@TransactionContextJ) }
}
