package onl.ycode.stormify

import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.SQLException
import onl.ycode.kdbc.Savepoint
import onl.ycode.stormify.biglist.ReferencePath
import kotlin.jvm.JvmName
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
    @Throws(SQLException::class)
    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(conn, query, *params)

    /** Executes a SELECT query and returns all results as a list. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> read(query: String, vararg params: Any?) =
        stormify.read(conn, T::class, query, *params)

    /** Executes a SELECT query and returns exactly one result, or null if none found. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        stormify.readOne(conn, T::class, query, *params)

    /** Executes a SELECT query and processes results row-by-row via [consumer]. Returns the row count. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> readCursor(query: String, vararg params: Any?, noinline consumer: (T) -> Unit) =
        stormify.readCursor(conn, T::class, query, *params, consumer = consumer)

    /** Refreshes an entity with fresh data from the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> populate(entity: T): T = stormify.populate(conn, entity)

    /** Inserts a new entity into the database and returns it with generated values populated. */
    @Throws(SQLException::class)
    fun <T : Any> create(item: T): T = stormify.create(conn, item)

    /** Inserts multiple entities in a batch. */
    @Throws(SQLException::class)
    fun <T : Any> create(items: Collection<T>): List<T> = stormify.create(conn, items)

    /** Updates an existing entity in the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> update(updatedItem: T): T = stormify.update(conn, updatedItem)

    /** Updates multiple entities in a batch. */
    @Throws(SQLException::class)
    fun <T : Any> update(items: Collection<T>): List<T> = stormify.update(conn, items)

    /** Deletes an entity from the database based on its primary key. */
    @Throws(SQLException::class)
    fun <T : Any> delete(deletedItem: T) = stormify.delete(conn, deletedItem)

    /** Deletes multiple entities from the database. */
    @Throws(SQLException::class)
    fun <T : Any> delete(items: Collection<T>) = stormify.delete(conn, items)

    /** Retrieves all detail (child) entities of type [D] related to a [parent] through a foreign key. */
    @Throws(SQLException::class)
    inline fun <reified D : Any> getDetails(parent: Any, propertyName: String? = null): List<D> =
        stormify.getDetails(conn, parent, D::class, propertyName)

    /** Retrieves all detail (child) entities of [detailsClass] related to a [parent] through a foreign key. */
    @Throws(SQLException::class)
    fun <M : Any, D : Any> getDetails(parent: M, detailsClass: KClass<D>, propertyName: String? = null): List<D> =
        stormify.getDetails(conn, parent, detailsClass, propertyName)

    /**
     * Type-safe variant of [getDetails] that accepts an annotation-processor-generated
     * reference path (e.g. `Paths.AuditEntry_.createdBy`) instead of a string.
     */
    @Throws(SQLException::class)
    inline fun <reified D : Any> getDetails(parent: Any, referenceField: ReferencePath): List<D> =
        stormify.getDetails(conn, parent, D::class, referenceField.path.trimEnd('.'))

    /** Finds all entities of type [T], optionally filtered by a [whereClause]. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> findAll(whereClause: String = "", vararg arguments: Any?): List<T> =
        stormify.findAll(conn, T::class, whereClause, *arguments)

    /** Finds all entities of [kclass], optionally filtered by a [whereClause]. */
    @Throws(SQLException::class)
    fun <T : Any> findAll(kclass: KClass<T>, whereClause: String = "", vararg arguments: Any?): List<T> =
        stormify.findAll(conn, kclass, whereClause, *arguments)

    /** Finds a single entity of type [T] by its primary key [id], or null if not found. */
    @Throws(SQLException::class)
    inline fun <reified T : Any> findById(id: Any) = stormify.findById(conn, T::class, id)

    /** Finds a single entity of [kclass] by its primary key [id], or null if not found. */
    @Throws(SQLException::class)
    fun <T : Any> findById(kclass: KClass<T>, id: Any) = stormify.findById(conn, kclass, id)

    /** Calls a stored procedure by [name]. OUT/INOUT parameters use [Sp.Out]/[Sp.InOut]. */
    @Throws(SQLException::class)
    fun procedure(name: String, vararg args: Any?) = stormify.procedure(conn, name, *args)

    // --- Receiver-style extensions (shadow the top-level ones in StormifyBindings.kt
    // when called inside a `transaction { }` block, so they run on this transaction's
    // connection instead of the default Stormify instance). ---

    /** Inserts this entity into the database using this transaction's connection. */
    @Throws(SQLException::class)
    @JvmName("createTx")
    fun <T : Any> T.create(): T = stormify.create(conn, this)

    /** Updates this entity in the database using this transaction's connection. */
    @Throws(SQLException::class)
    @JvmName("updateTx")
    fun <T : Any> T.update(): T = stormify.update(conn, this)

    /** Deletes this entity from the database using this transaction's connection. */
    @Throws(SQLException::class)
    @JvmName("deleteTx")
    fun <T : Any> T.delete() = stormify.delete(conn, this)

    /** Executes this SQL as an UPDATE/INSERT/DELETE on this transaction's connection. */
    @Throws(SQLException::class)
    @JvmName("executeUpdateTx")
    fun String.executeUpdate(vararg args: Any?) = stormify.executeUpdate(conn, this, *args)

    /** Executes this SQL as a SELECT on this transaction's connection and returns all results. */
    @Throws(SQLException::class)
    @JvmName("readTx")
    inline fun <reified T : Any> String.read(vararg args: Any?): List<T> =
        stormify.read(conn, T::class, this, *args)

    /** Executes this SQL as a SELECT on this transaction's connection and returns a single result, or null. */
    @Throws(SQLException::class)
    @JvmName("readOneTx")
    inline fun <reified T : Any> String.readOne(vararg args: Any?): T? =
        stormify.readOne(conn, T::class, this, *args)

    /** Executes this SQL as a SELECT on this transaction's connection and processes rows via [consumer]. */
    @Throws(SQLException::class)
    @JvmName("readCursorTx")
    inline fun <reified T : Any> String.readCursor(vararg args: Any?, noinline consumer: (T) -> Unit): Int =
        stormify.readCursor(conn, T::class, this, *args, consumer = consumer)

    /** Calls the stored procedure named by this string on this transaction's connection. */
    @Throws(SQLException::class)
    @JvmName("procedureTx")
    fun String.procedure(vararg args: Any?) = stormify.procedure(conn, this, *args)

    /** Returns all detail (child) entities of type [D] related to this parent through a foreign key. */
    @Throws(SQLException::class)
    @JvmName("detailsTx")
    inline fun <reified D : Any> Any.details(propertyName: String? = null): List<D> =
        stormify.getDetails(conn, this, D::class, propertyName)

    /** Type-safe variant of [details] that accepts a generated [ReferencePath]. */
    @Throws(SQLException::class)
    @JvmName("detailsByPathTx")
    inline fun <reified D : Any> Any.details(referenceField: ReferencePath): List<D> =
        stormify.getDetails(conn, this, D::class, referenceField.path.trimEnd('.'))

    /** Executes a nested transaction using a database savepoint, returning [block]'s result. */
    @Throws(SQLException::class)
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