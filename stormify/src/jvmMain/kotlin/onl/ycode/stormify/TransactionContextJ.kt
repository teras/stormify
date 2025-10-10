package onl.ycode.stormify

class TransactionContextJ(private val ctx: TransactionContext) {
    private val connection = ctx.conn
    private val stormify = ctx.stormify

    fun <T : Any> read(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.read(connection, baseClass.kotlin, query, params)

    fun <T : Any> readOne(baseClass: Class<T>, query: String, vararg params: Any?) =
        stormify.readOne(connection, baseClass.kotlin, query, params)

    fun <T : Any> readCursor(baseClass: Class<T>, query: String, consumer: SafeConsumer<T>, vararg params: Any?) =
        stormify.readCursor(connection, baseClass.kotlin, query, params, consumer = { consumer.accept(it) })

    fun executeUpdate(query: String, vararg params: Any?) = stormify.executeUpdate(connection, query, params)

    fun <T : Any> populate(entity: T) = stormify.populate(connection, entity)

    fun <T : Any> create(item: T) = stormify.create(connection, item)

    fun <T : Any> update(updatedItem: T) = stormify.update(connection, updatedItem)

    fun delete(deletedItem: Any) = stormify.delete(connection, deletedItem)

    @JvmOverloads
    fun <M : Any, T : Any> getDetails(parent: M, detailsClass: Class<T>, propertyName: String? = null) =
        stormify.getDetails(connection, parent, detailsClass.kotlin, propertyName)

    fun <T : Any> findAll(baseClass: Class<T>) = stormify.findAll(connection, baseClass.kotlin)

    fun <T : Any> findById(baseClass: Class<T>, id: Any) = stormify.findById(connection, baseClass.kotlin, id)


    internal fun start(block: SafeConsumer<TransactionContextJ>) = ctx.start { block.accept(this@TransactionContextJ) }

    fun transaction(block: SafeRunnable) =
        ctx.transaction { block.run() }
}