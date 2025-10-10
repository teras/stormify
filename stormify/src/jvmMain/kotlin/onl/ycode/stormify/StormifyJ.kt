package onl.ycode.stormify

import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.JdbcDataSource
import java.util.function.Consumer

class StormifyJ(dataSource: DataSource) {
    constructor(jdbcDataSource: javax.sql.DataSource) : this(JdbcDataSource(jdbcDataSource))

    private val stormify = Stormify(dataSource)

    val sqlDialect get() = stormify.sqlDialect

    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(null, baseClass.kotlin, query, params)

    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(null, baseClass.kotlin, query, params)

    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(null, baseClass.kotlin, query, params, consumer = { consumer.accept(it) })

    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(null, query, params)

    fun <T : Any> populate(entity: T) = stormify.populate(null, entity)

    fun <T : Any> create(item: T) = stormify.create(null, item)

    fun <T : Any> update(updatedItem: T) = stormify.update(null, updatedItem)

    fun delete(deletedItem: Any) = stormify.delete(null, deletedItem)

    @JvmOverloads
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(null, parent, detailsClass.kotlin, propertyName)

    fun <T : Any> findAll(baseClass: Class<T>) = stormify.findAll(null, baseClass.kotlin)

    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(null, baseClass.kotlin, id)

    fun transaction(block: Consumer<TransactionContextJ>) =
        TransactionContextJ(TransactionContext(stormify)).start(block)

    fun getTableInfo(baseClass: Class<*>) = TableInfo.retrieve(baseClass.kotlin)
}

