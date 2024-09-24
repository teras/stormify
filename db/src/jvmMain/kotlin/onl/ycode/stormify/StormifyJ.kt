package onl.ycode.stormify

class StormifyJ(dataSource: DataSource) {
    private val stormify = Stormify(dataSource)

    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(null, baseClass.kotlin, query, params)

    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(null, baseClass.kotlin, query, params)

    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: SafeConsumer<T>, vararg params: Any?) =
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

    fun transaction(block: SafeConsumer<TransactionContextJ>) =
        TransactionContextJ(TransactionContext(stormify)).start(block)
}

