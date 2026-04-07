package onl.ycode.stormify

import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.JdbcDataSource
import java.util.function.Consumer

class StormifyJ(dataSource: DataSource, vararg registrars: EntityRegistrar) {
    constructor(jdbcDataSource: javax.sql.DataSource, vararg registrars: EntityRegistrar) : this(JdbcDataSource(jdbcDataSource), *registrars)

    private val stormify = Stormify(dataSource, *registrars)

    val sqlDialect get() = stormify.sqlDialect

    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(null, baseClass.kotlin, query, *params)

    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(null, baseClass.kotlin, query, *params)

    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: Consumer<T>, vararg params: Any?) =
        stormify.readCursor(null, baseClass.kotlin, query, *params, consumer = { consumer.accept(it) })

    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(null, query, *params)

    fun <T : Any> populate(entity: T) = stormify.populate(null, entity)

    fun <T : Any> create(item: T) = stormify.create(null, item)

    fun <T : Any> create(items: Collection<T>) = stormify.create(null, items)

    fun <T : Any> update(updatedItem: T) = stormify.update(null, updatedItem)

    fun <T : Any> update(items: Collection<T>) = stormify.update(null, items)

    fun delete(deletedItem: Any) = stormify.delete(null, deletedItem)

    fun <T : Any> delete(items: Collection<T>) = stormify.delete(null, items)

    @JvmOverloads
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(null, parent, detailsClass.kotlin, propertyName)

    @JvmOverloads
    fun <T : Any> findAll(baseClass: Class<T>, whereClause: String = "", vararg arguments: Any?) =
        stormify.findAll(null, baseClass.kotlin, whereClause, *arguments)

    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(null, baseClass.kotlin, id)

    fun procedure(name: String, vararg args: Any?) = stormify.procedure(name, *args)

    fun transaction(block: Consumer<TransactionContextJ>) =
        TransactionContextJ(TransactionContext(stormify)).start(block)

    fun getTableInfo(baseClass: Class<*>) = stormify.resolveTableInfo(baseClass.kotlin)

    var namingPolicy: NamingPolicy
        get() = stormify.namingPolicy
        set(value) { stormify.namingPolicy = value }

    fun addBlacklistField(name: String) = stormify.addBlacklistField(name)
    fun removeBlacklistField(name: String) = stormify.removeBlacklistField(name)
    fun registerPrimaryKeyResolver(priority: Int, resolver: (String, String) -> Boolean) =
        stormify.registerPrimaryKeyResolver(priority, resolver)

    var isStrictMode: Boolean
        get() = stormify.isStrictMode
        set(value) { stormify.isStrictMode = value }

    var logger
        get() = stormify.logger
        set(value) { stormify.logger = value }

    fun asDefault(): StormifyJ { stormify.asDefault(); return this }
}

