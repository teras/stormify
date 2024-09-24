package onl.ycode.stormify

import kotlinx.atomicfu.atomic
import kotlin.reflect.KClass

private val counter = atomic(0)

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