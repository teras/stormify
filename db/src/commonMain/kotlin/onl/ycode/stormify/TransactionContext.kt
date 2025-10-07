package onl.ycode.stormify

import kotlinx.atomicfu.atomic
import kotlin.reflect.KClass

private val counter = atomic(0)

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
class TransactionContext internal constructor(@PublishedApi internal val stormify: Stormify) {
    @PublishedApi
    internal val conn = tryQuery("Unable to get connection") { stormify.dataSource._connection }

    internal fun start(block: TransactionContext.() -> Unit) = conn.use {
        try {
            conn._disableAutoCommit()
            block()
            conn._commit()
        } catch (e: Throwable) {
            conn._rollback()
            throw QueryException("Unable to execute transaction: ${e.message}", e)
        } finally {
            conn._enableAutoCommit()
        }
    }

    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(conn, query, params)

    inline fun <reified T : Any> read(query: String, vararg params: Any?) =
        stormify.read(conn, T::class, query, params)

    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        stormify.readOne(conn, T::class, query, params)

    inline fun <reified T : Any> readCursor(query: String, vararg params: Any?, noinline consumer: (T) -> Unit) =
        stormify.readCursor(conn, T::class, query, params, consumer = consumer)

    fun <T : Any> populate(entity: T): T = stormify.populate(conn, entity)

    fun <T : Any> create(item: T): T = stormify.create(conn, item)

    fun <T : Any> update(updatedItem: T): T = stormify.update(conn, updatedItem)

    fun <T : Any> delete(deletedItem: T) = stormify.delete(conn, deletedItem)

    fun <M : Any, D : Any> getDetails(parent: M, detailsClass: KClass<D>, propertyName: String? = null): List<D> =
        stormify.getDetails(conn, parent, detailsClass, propertyName)

    fun <T : Any> findAll(kclass: KClass<T>, whereClause: String = "", vararg arguments: Any?): List<T> =
        stormify.findAll(conn, kclass, whereClause, arguments)

    fun <T : Any> findById(kclass: KClass<T>, id: Any) = stormify.findById(conn, kclass, id)

    fun transaction(block: () -> Unit) {
        var savepoint: Savepoint? = null
        try {
            savepoint = conn._setSavepoint("stormify_" + systemMillis() + "_" + counter.getAndIncrement())
            block()
            conn._releaseSavepoint(savepoint)
        } catch (e: Throwable) {
            if (savepoint != null)
                conn._rollback(savepoint)
            throw QueryException("Unable to execute transaction: ${e.message}", e)
        }
    }
}