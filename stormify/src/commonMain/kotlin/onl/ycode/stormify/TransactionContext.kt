package onl.ycode.stormify

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.Savepoint
import onl.ycode.stormify.biglist.ReferencePath
import kotlin.reflect.KClass

/**
 * Context for executing database operations within a transaction.
 *
 * All operations performed through this context share the same database connection,
 * ensuring they participate in the same transaction. The transaction is committed
 * if all operations succeed, or rolled back if any operation throws an exception.
 *
 * ## Basic Usage
 * ```kotlin
 * stormify.transaction {
 *     val user = create(User(email = "test@example.com"))
 *     create(Profile(userId = user.id, name = "Test User"))
 *     update(account)
 * }
 * ```
 *
 * ## Nested Transactions
 * Nested transactions are implemented using database savepoints:
 * ```kotlin
 * stormify.transaction {
 *     create(record1)
 *
 *     transaction {  // Creates a savepoint
 *         create(record2)
 *         // If this fails, only record2 is rolled back
 *     }
 *
 *     create(record3)  // Still executes
 * }
 * ```
 *
 * ## Extracting Complex Logic
 *
 * ### Pattern 1: Extension Functions (Recommended)
 * Define extension functions on [TransactionContext] for clean, reusable business logic:
 * ```kotlin
 * fun TransactionContext.registerUser(email: String, name: String) {
 *     val user = create(User(email = email))
 *     create(Profile(userId = user.id, name = name))
 *     create(AuditLog(action = "User registered", userId = user.id))
 * }
 *
 * fun TransactionContext.transferFunds(from: Account, to: Account, amount: Double) {
 *     require(from.balance >= amount) { "Insufficient funds" }
 *     update(from.copy(balance = from.balance - amount))
 *     update(to.copy(balance = to.balance + amount))
 *     create(Transaction(fromId = from.id, toId = to.id, amount = amount))
 * }
 *
 * // Usage
 * stormify.transaction {
 *     registerUser("alice@example.com", "Alice")
 *     transferFunds(accountA, accountB, 100.0)
 * }
 * ```
 *
 * ### Pattern 2: Service Layer Classes
 * Encapsulate transaction logic in service classes for structured applications:
 * ```kotlin
 * class UserService(private val tx: TransactionContext) {
 *     fun registerUser(email: String, name: String) {
 *         val user = tx.create(User(email = email))
 *         tx.create(Profile(userId = user.id, name = name))
 *     }
 *
 *     fun deleteUser(userId: Int) {
 *         val user = tx.findById<User>(userId)
 *         tx.delete(user)
 *     }
 * }
 *
 * // Usage
 * stormify.transaction {
 *     val service = UserService(this)
 *     service.registerUser("alice@example.com", "Alice")
 * }
 * ```
 *
 * @see Stormify.transaction
 */
class TransactionContext internal constructor(
    @PublishedApi internal val stormify: Stormify,
    @PublishedApi internal val conn: Connection,
    /**
     * True when this TransactionContext acquired the connection from the DataSource and
     * therefore must close it when the transaction ends (the historical blocking API
     * path). False when the connection was handed in externally — e.g. by a
     * `SuspendConnectionPool.use` block in the coroutines layer — in which case the
     * caller owns the connection's lifecycle and this class MUST NOT call `close()` on
     * it. Commit/rollback still happen in both modes; only the final `close()` differs.
     */
    private val ownsConnection: Boolean,
) {
    /** Backwards-compatible secondary constructor: acquires a fresh connection from the data source. */
    internal constructor(stormify: Stormify) : this(
        stormify = stormify,
        conn = tryQuery("Unable to get connection") { stormify.dataSource.getConnection() },
        ownsConnection = true,
    )

    internal fun <R> start(block: TransactionContext.() -> R): R =
        if (ownsConnection) conn.use { runBody(block) } else runBody(block)

    private fun <R> runBody(block: TransactionContext.() -> R): R {
        try {
            conn.setAutoCommit(false)
            val result = block()
            conn.commit()
            return result
        } catch (e: Throwable) {
            conn.rollback()
            e.throwQuery("Unable to execute transaction: ${e.message}")
        } finally {
            conn.setAutoCommit(true)
        }
    }

    /** Executes an SQL UPDATE/INSERT/DELETE and returns the number of affected rows. */
    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(conn, query, *params)

    /** Executes a SELECT query and returns all results as a list. */
    inline fun <reified T : Any> read(query: String, vararg params: Any?) =
        stormify.read(conn, T::class, query, *params)

    /** Executes a SELECT query and returns exactly one result, or null if none found. */
    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        stormify.readOne(conn, T::class, query, *params)

    /** Executes a SELECT query and processes results row-by-row via [consumer]. Returns the row count. */
    inline fun <reified T : Any> readCursor(query: String, vararg params: Any?, noinline consumer: (T) -> Unit) =
        stormify.readCursor(conn, T::class, query, *params, consumer = consumer)

    /** Refreshes an entity with fresh data from the database based on its primary key. */
    fun <T : Any> populate(entity: T): T = stormify.populate(conn, entity)

    /** Inserts a new entity into the database and returns it with generated values populated. */
    fun <T : Any> create(item: T): T = stormify.create(conn, item)

    /** Inserts multiple entities in a batch. */
    fun <T : Any> create(items: Collection<T>): List<T> = stormify.create(conn, items)

    /** Updates an existing entity in the database based on its primary key. */
    fun <T : Any> update(updatedItem: T): T = stormify.update(conn, updatedItem)

    /** Updates multiple entities in a batch. */
    fun <T : Any> update(items: Collection<T>): List<T> = stormify.update(conn, items)

    /** Deletes an entity from the database based on its primary key. */
    fun <T : Any> delete(deletedItem: T) = stormify.delete(conn, deletedItem)

    /** Deletes multiple entities from the database. */
    fun <T : Any> delete(items: Collection<T>) = stormify.delete(conn, items)

    /** Retrieves all detail (child) entities of type [D] related to a [parent] through a foreign key. */
    inline fun <reified D : Any> getDetails(parent: Any, propertyName: String? = null): List<D> =
        stormify.getDetails(conn, parent, D::class, propertyName)

    /** Retrieves all detail (child) entities of [detailsClass] related to a [parent] through a foreign key. */
    fun <M : Any, D : Any> getDetails(parent: M, detailsClass: KClass<D>, propertyName: String? = null): List<D> =
        stormify.getDetails(conn, parent, detailsClass, propertyName)

    /**
     * Type-safe variant of [getDetails] that accepts an annotation-processor-generated
     * reference path (e.g. `Paths.AuditEntry_.createdBy`) instead of a string.
     */
    inline fun <reified D : Any> getDetails(parent: Any, referenceField: ReferencePath): List<D> =
        stormify.getDetails(conn, parent, D::class, referenceField.path.trimEnd('.'))

    /** Finds all entities of type [T], optionally filtered by a [whereClause]. */
    inline fun <reified T : Any> findAll(whereClause: String = "", vararg arguments: Any?): List<T> =
        stormify.findAll(conn, T::class, whereClause, *arguments)

    /** Finds all entities of [kclass], optionally filtered by a [whereClause]. */
    fun <T : Any> findAll(kclass: KClass<T>, whereClause: String = "", vararg arguments: Any?): List<T> =
        stormify.findAll(conn, kclass, whereClause, *arguments)

    /** Finds a single entity of type [T] by its primary key [id], or null if not found. */
    inline fun <reified T : Any> findById(id: Any) = stormify.findById(conn, T::class, id)

    /** Finds a single entity of [kclass] by its primary key [id], or null if not found. */
    fun <T : Any> findById(kclass: KClass<T>, id: Any) = stormify.findById(conn, kclass, id)

    /** Calls a stored procedure by [name]. OUT/INOUT parameters use [Sp.Out]/[Sp.InOut]. */
    fun procedure(name: String, vararg args: Any?) = stormify.procedure(conn, name, *args)

    /** Executes a nested transaction using a database savepoint, returning [block]'s result. */
    fun <R> transaction(block: () -> R): R {
        var savepoint: Savepoint? = null
        try {
            savepoint = conn.setSavepoint(nextSavepointName())
            val result = block()
            if (stormify.sqlDialect.supportsReleaseSavepoint)
                conn.releaseSavepoint(savepoint)
            return result
        } catch (e: Throwable) {
            if (savepoint != null)
                conn.rollback(savepoint)
            e.throwQuery("Unable to execute transaction: ${e.message}")
        }
    }
}