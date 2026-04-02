// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify

import onl.ycode.kdbc.CallableStatement
import onl.ycode.kdbc.Connection
import onl.ycode.kdbc.DataSource
import onl.ycode.kdbc.PreparedStatement
import onl.ycode.kdbc.ResultSet
import onl.ycode.kdbc.SQLException
import onl.ycode.logger.LogManager
import onl.ycode.stormify.SPParam.Mode.*
import onl.ycode.stormify.SqlDialect.GeneratedKeyRetrieval
import onl.ycode.stormify.TypeUtils.castTo
import kotlin.reflect.KClass


private class Reference<T>(var item: T? = null)
private class FixedParams(val query: String, val params: List<Any?>)

/**
 * The main ORM controller. Entry point for all database operations.
 *
 * @param dataSource the data source for all database operations
 * @param registrars optional entity registrars to register at construction time
 */
open class Stormify(val dataSource: DataSource, vararg registrars: EntityRegistrar) : AutoCloseable {

    init {
        for (r in registrars) r.register()
    }

    companion object {
        /** The default Stormify instance, used by [AutoTable] when no explicit instance is set. */
        private val _defaultInstance = kotlinx.atomicfu.atomic<Stormify?>(null)
        var defaultInstance: Stormify?
            get() = _defaultInstance.value
            private set(value) { _defaultInstance.value = value }
    }

    /** Sets this instance as [defaultInstance] and returns it. */
    fun asDefault(): Stormify {
        defaultInstance = this
        return this
    }

    override fun close() {
        if (dataSource is AutoCloseable) dataSource.close()
    }

    // --- Policies ---

    private val configLock = kotlinx.atomicfu.locks.SynchronizedObject()

    var namingPolicy: NamingPolicy = NamingPolicy.LOWER_CASE_WITH_UNDERSCORES
    private val blacklist = mutableSetOf<String>()
    private val pkResolvers = mutableMapOf<Int, (String, String) -> Boolean>()

    fun addBlacklistField(name: String) = kotlinx.atomicfu.locks.synchronized(configLock) { blacklist.add(name) }
    fun removeBlacklistField(name: String) = kotlinx.atomicfu.locks.synchronized(configLock) { blacklist.remove(name) }
    fun registerPrimaryKeyResolver(priority: Int, resolver: (String, String) -> Boolean) =
        kotlinx.atomicfu.locks.synchronized(configLock) { pkResolvers[priority] = resolver }

    // --- Resolve pipeline ---

    private val tableInfoCache = mutableMapOf<KClass<*>, TableInfo<*>>()

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> resolveTableInfo(type: KClass<out T>): TableInfo<T> =
        kotlinx.atomicfu.locks.synchronized(configLock) {
            tableInfoCache.getOrPut(type) {
                val meta = EntityMeta.find(type) ?: tryReflection(type)
                ?: throw SQLException("Unknown entity: ${type.simpleName}")
                TableInfo.build(meta, namingPolicy, blacklist, pkResolvers.entries.sortedBy { it.key }.map { it.value })
            } as TableInfo<T>
        }

    // --- SQL Dialect ---

    private var _sqlDialect: SqlDialect? = null

    /**
     * The SQL dialect used by this Stormify instance. Auto-detected from the data source
     * on first access. Can be set manually for proxy scenarios where auto-detection fails.
     */
    var sqlDialect: SqlDialect
        get() {
            if (_sqlDialect == null)
                _sqlDialect = try {
                    SqlDialect.findDialect(dataSource)
                } catch (e: Throwable) {
                    e.throwQuery("Unable to find SQL dialect")
                }
            return _sqlDialect!!
        }
        set(value) = kotlinx.atomicfu.locks.synchronized(configLock) {
            _sqlDialect = value
        }

    // --- Configuration ---

    /** When enabled, throws on field/column mismatches; when disabled, logs warnings. */
    var isStrictMode: Boolean = true

    var logger = LogManager.getLogger("Stormify")

    // --- Internal connection management ---

    private inner class ConnectionMaker(connection: Connection?) : AutoCloseable {
        val connection by lazy { connection ?: dataSource.getConnection() }
        val shouldClose = connection == null
        override fun close() {
            try {
                if (shouldClose) connection.close()
            } catch (e: Throwable) {
                e.throwQuery("Unable to close connection")
            }
        }
    }

    // --- Parameter handling ---

    private fun fixParams(givenQuery: String, args: List<Any?>): FixedParams {
        if (args.isEmpty()) return FixedParams(givenQuery, emptyList<Any>())
        val params: MutableList<Any?> = mutableListOf()
        val query = StringBuilder(givenQuery.length)
        var countQuestionMarks = 0
        for (i in givenQuery.indices) {
            if (givenQuery[i] == '?') {
                if (countQuestionMarks >= args.size) throw SQLException(
                    ("The number of placeholders (" + count(
                        givenQuery,
                        '?'
                    )) + ") in query '" + givenQuery + "' exceeds the number of parameters (" + args.size + ")"
                )
                val arg = sqlData(args[countQuestionMarks++], true)
                if (arg is List<*>) {
                    query.append("(").append(nCopies("?", ", ", arg.size)).append(")")
                    params.addAll(arg)
                } else {
                    query.append("?")
                    params.add(arg)
                }
            } else query.append(givenQuery[i])
        }
        if (countQuestionMarks != args.size) throw SQLException(
            ("The number of placeholders (" + count(
                givenQuery,
                '?'
            )).toString() + ") in query '" + givenQuery + "' is less than the number of parameters (" + args.size + ")"
        )
        return FixedParams(query.toString(), params)
    }

    private fun <T> performQuery(
        conn: Connection?,
        givenQuery: String,
        givenParams: List<Any?>,
        generatedKeys: Boolean,
        code: (PreparedStatement) -> T
    ): T {
        val params = fixParams(givenQuery, givenParams)
        `!dbLog`(params.query, params.params.toTypedArray())
        val paramValues = if (params.params.isEmpty()) "" else " with values ${params.params}"
        return ConnectionMaker(conn).useWithException("Unable to execute query '${params.query}'$paramValues") { maker ->
            maker.connection.prepareStatement(
                params.query,
                generatedKeys
            ).use { statement ->
                for (i in params.params.indices)
                    statement.setObject(i + 1, params.params[i])
                code(statement)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun sqlData(value: Any?, recursively: Boolean): Any? {
        if (value == null || isScalarObject(value)) return value
        if (recursively) {
            if (value is Array<*>)
                return sqlData(value.toList(), true)
            if (value is Iterable<*>)
                return value.map { sqlData(it, false) }
        }
        @Suppress("UNCHECKED_CAST")
        val info = resolveTableInfo(value::class) as TableInfo<Any>
        return if (info.idNames.size == 1) info.getIdValues(value)[0] else
            throw SQLException("Multiple primary keys found in ${info.tableName}")
    }

    // --- Attach stormify to entity ---

    private fun attachStormify(entity: Any) {
        if (entity is StormifyEntity) entity.`!stormify` = this
    }

    // --- Read operations ---

    /** Executes an SQL UPDATE/INSERT/DELETE and returns the number of affected rows. */
    fun executeUpdate(query: String, vararg params: Any?) =
        executeUpdate(null, query, *params)

    internal fun executeUpdate(conn: Connection?, query: String, vararg params: Any?): Int {
        return performQuery(conn, query, params.toList(), false, { it.executeUpdate() })
    }

    /** Executes a SELECT query and processes results row-by-row via [consumer]. Returns row count. */
    inline fun <reified T : Any> readCursor(query: String, vararg params: Any?, noinline consumer: (T) -> Unit) =
        readCursor(null, T::class, query, *params, consumer = consumer)

    @PublishedApi
    internal fun <T : Any> readCursor(
        conn: Connection?,
        baseClass: KClass<T>,
        query: String,
        vararg params: Any?,
        consumer: (T) -> Unit
    ) = performQuery(conn, query, params.toList(), false, { statement ->
        val info = if (isScalarClass(baseClass)) null else resolveTableInfo(baseClass)
        val rs: ResultSet = statement.executeQuery()
        val context = if (info != null) PopulationContext() else null
        var count = 0
        while (rs.next()) {
            count++
            consumer(
                if (info != null) populate(info.create().also { attachStormify(it) }, rs, context)
                else castTo(baseClass, rs.getObject(1, baseClass), this)
                    ?: throw SQLException("Expecting type ${baseClass.fullName} but found null")
            )
        }
        count
    })

    /** Executes a SELECT query and returns all results as a list. */
    inline fun <reified T : Any> read(query: String, vararg params: Any?): List<T> =
        read(null, T::class, query, *params)

    @PublishedApi
    internal fun <T : Any> read(conn: Connection?, baseClass: KClass<T>, query: String, vararg params: Any?): List<T> =
        with(mutableListOf<T>()) {
            readCursor(conn, baseClass, query, *params) { add(it) }
            return this
        }

    /** Executes a SELECT query and returns exactly one result, or null if none found. */
    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        readOne(null, T::class, query, *params)

    @PublishedApi
    internal fun <T : Any> readOne(conn: Connection?, baseClass: KClass<T>, query: String, vararg params: Any?): T? {
        val result = Reference<T?>()
        readCursor(conn, baseClass, query, *params) {
            if (result.item != null)
                throw SQLException("Multiple results found for query '$query'")
            result.item = it
        }
        return result.item
    }

    // --- ID validation ---

    private fun <T : Any> getValidIds(entity: T, info: TableInfo<T>): List<Any?> {
        val idValues = info.getIdValues(entity)
        if (idValues.isEmpty())
            throw SQLException("No primary key found for object ${info.classType}")
        idValues.forEachIndexed { i, value ->
            if (value == null)
                throw SQLException("Value of primary key ${info.idNames[i]} is null of entity ${entity::class.fullName}")
        }
        return idValues
    }

    // --- Populate ---

    /** Refreshes an entity with fresh data from the database based on its primary key. */
    fun <T : Any> populate(entity: T): T =
        populate(null, entity)

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> populate(conn: Connection?, entity: T): T {
        attachStormify(entity)
        val info = resolveTableInfo(entity::class) as TableInfo<T>
        performQuery<Any>(conn, info.populateQuery, getValidIds(entity, info), false, { statement ->
            val rs: ResultSet = statement.executeQuery()
            if (rs.next()) return@performQuery populate<T>(entity, rs)
            else throw SQLException("No data found for ${info.tableName}:${info.getIdValues(entity)}")
        })
        return entity
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> populate(item: T, rs: ResultSet, context: PopulationContext? = null): T {
        attachStormify(item)
        if (item is AutoTable) item.markPopulated()
        val info = resolveTableInfo(item::class) as TableInfo<T>
        val metaData = rs.getMetaData()
        val columnCount = metaData.columnCount
        for (i in 1..columnCount) {
            val col = metaData.getColumnLabel(i)
            val colType = info.getScalarType(col)
            val value = transformResultValue(if (colType != null) rs.getObject(i, colType) else rs.getObject(i, Any::class))
            // Reference deduplication: if field is an AutoTable type and we have a context
            if (context != null && value != null && info.isReferenceField(col)) {
                val refType = info.getReferenceType(col)!!
                try {
                    val ref = context.getOrCreateReference(refType, value, this)
                    info.setField(item, col, ref, this, if (isStrictMode) null else logger)
                    continue
                } catch (_: Exception) {
                    // Fall through to normal setField
                }
            }
            try {
                info.setField(item, col, value, this, if (isStrictMode) null else logger)
            } catch (e: NullPointerException) {
                throw SQLException("Null value for non-null field '$col' in ${item::class.simpleName}", e)
            }
        }
        return item
    }

    @Suppress("UNCHECKED_CAST")
    internal fun batchPopulate(members: List<AutoTable>) {
        if (members.isEmpty()) return
        val info = resolveTableInfo(members[0]::class) as TableInfo<AutoTable>

        // Collect unique IDs and map members by string key
        val byId = LinkedHashMap<String, MutableList<AutoTable>>()
        val uniqueIds = mutableListOf<Any?>()

        for (member in members) {
            val id = info.getIdValues(member)[0]
            val key = id.toString()
            if (byId.containsKey(key))
                byId[key]!!.add(member)
            else {
                byId[key] = mutableListOf(member)
                uniqueIds.add(id)
            }
        }

        val placeholders = nCopies("?", ", ", uniqueIds.size)
        val query = "SELECT ${info.selectFieldNames} FROM ${info.tableName} WHERE ${info.idDbNames[0]} IN ($placeholders)"

        val pkDbName = info.idDbNames[0]
        val nestedContext = PopulationContext()
        performQuery<Any>(null, query, uniqueIds, false, { statement ->
            val rs = statement.executeQuery()
            val meta = rs.getMetaData()
            var pkColIdx = 1
            for (c in 1..meta.columnCount)
                if (meta.getColumnLabel(c).equals(pkDbName, ignoreCase = true)) { pkColIdx = c; break }
            while (rs.next()) {
                val key = rs.getObject(pkColIdx, info.idTypes[0]).toString()
                val targets = byId.remove(key)
                if (targets != null)
                    for (target in targets)
                        populate(target, rs, nestedContext)
            }
            0
        })

        for ((key, _) in byId)
            logger.warn("Batch populate: no data found for {} with id {}", info.tableName, key)
    }

    // --- Sequence support ---

    private fun getNextSequences(conn: Connection?, sequence: String, count: Int): List<NativeBigInteger> {
        val sql = sqlDialect.sequenceDialect(sequence, count) ?: return emptyList()
        val result = mutableListOf<NativeBigInteger>()
        readCursor(conn, NativeBigInteger::class, sql) { result.add(it) }
        if (result.isNotEmpty())
            `!dbLog`("Sequence $sequence incremented by ${result.size} to ${result.last()}", null)
        return result
    }

    private fun getNextSequence(conn: Connection?, sequence: String) =
        getNextSequences(conn, sequence, 1).firstOrNull()

    // --- Create ---

    /** Inserts a new entity into the database and returns it with generated values populated. */
    fun <T : Any> create(item: T): T = create(null, listOf(item))[0]

    fun <T : Any> create(items: Collection<T>): List<T> = create(null, items)

    internal fun <T : Any> create(conn: Connection?, item: T) = create(conn, listOf(item))[0]

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> create(conn: Connection?, items: Collection<T>): List<T> {
        if (items.isEmpty()) return emptyList()
        val itemList = if (items is List) items else items.toList()
        itemList.forEach {
            attachStormify(it)
            if (it is AutoTable) it.markPopulated() // Prevent lazy-load during value extraction
        }

        return ConnectionMaker(conn).useWithException("Unable to batch create") { maker ->
            val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
            val hasSinglePk = info.idNames.size == 1

            // Identify items needing IDs (single PK)
            val isAutoIncrement = hasSinglePk && info.primaryKeys[0].isAutoIncrement
            val needsId = mutableListOf<Int>()
            if (hasSinglePk) {
                if (isAutoIncrement) {
                    // Auto-increment: all items need generated IDs
                    for (i in itemList.indices) needsId.add(i)
                } else {
                    for (i in itemList.indices)
                        if (info.getIdValues(itemList[i])[0] == null)
                            needsId.add(i)
                }
            }

            // Bulk fetch sequences if applicable
            val sequence = if (hasSinglePk) info.idSequences[0] else ""
            if (needsId.isNotEmpty() && sequence.isNotBlank()) {
                val seqs = getNextSequences(maker.connection, sequence, needsId.size)
                for (i in needsId.indices)
                    info.setField(itemList[needsId[i]], info.idNames[0], seqs[i], this)
                needsId.clear()
            }

            val hasGK = sqlDialect.generatedKeyRetrieval !== GeneratedKeyRetrieval.NONE
            val fetchGeneratedKeys = hasGK && needsId.size == 1
            val pkColumn = if (hasSinglePk) info.singleKeyDbName else null

            `!dbLog`("${info.createQuery} [batch: ${itemList.size}]", null)
            sqlDialect.prepareForInsert(maker.connection, info.createQuery, fetchGeneratedKeys, pkColumn).use { stmt ->
                for (item in itemList) {
                    val givenParams = info.getCreateValues(item)
                    for (i in givenParams.indices)
                        stmt.setObject(i + 1, sqlData(givenParams[i], false))
                    if (fetchGeneratedKeys)
                        stmt.executeUpdate()
                    else
                        stmt.addBatch()
                }
                if (!fetchGeneratedKeys)
                    stmt.executeBatch()

                // Fetch generated key only for single item (no order guarantee for batch)
                if (fetchGeneratedKeys) {
                    stmt.getGeneratedKeys().use { rs ->
                        if (rs.next()) {
                            if (sqlDialect.generatedKeyRetrieval === GeneratedKeyRetrieval.BY_INDEX)
                                info.setField(itemList[needsId[0]], info.singleKeyDbName, rs.getObject(1, NativeBigInteger::class), this)
                            else
                                populate(itemList[needsId[0]], rs)
                        }
                    }
                }
            }
            itemList
        }
    }

    // --- Update ---

    /** Updates an existing entity in the database based on its primary key. */
    fun <T : Any> update(updatedItem: T): T = update(null, listOf(updatedItem))[0]

    fun <T : Any> update(items: Collection<T>): List<T> = update(null, items)

    internal fun <T : Any> update(conn: Connection?, updatedItem: T): T = update(conn, listOf(updatedItem))[0]

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> update(conn: Connection?, items: Collection<T>): List<T> {
        if (items.isEmpty()) return emptyList()
        val itemList = if (items is List) items else items.toList()
        itemList.forEach { attachStormify(it) }

        val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>

        `!dbLog`("${info.updateQuery} [batch: ${itemList.size}]", null)
        ConnectionMaker(conn).useWithException("Unable to batch update") { maker ->
            maker.connection.prepareStatement(info.updateQuery, false).use { stmt ->
                for (item in itemList) {
                    val params = info.getUpdateValues(item)
                    for (i in params.indices)
                        stmt.setObject(i + 1, params[i])
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }
        return itemList
    }

    // --- Delete ---

    /** Deletes an entity from the database based on its primary key. */
    fun <T : Any> delete(deletedItem: T) = delete(null, listOf(deletedItem))

    fun <T : Any> delete(items: Collection<T>) = delete(null, items)

    internal fun <T : Any> delete(conn: Connection?, deletedItem: T) = delete(conn, listOf(deletedItem))

    @Suppress("UNCHECKED_CAST")
    internal fun <T : Any> delete(conn: Connection?, items: Collection<T>) {
        if (items.isEmpty()) return
        val itemList = if (items is List) items else items.toList()

        val info = resolveTableInfo(itemList[0]::class) as TableInfo<T>
        val idCondition = info.idDbNames.joinToString(" AND ") { "$it = ?" }

        val allParams = mutableListOf<Any?>()
        val conditions = mutableListOf<String>()

        for (item in itemList) {
            val idValues = getValidIds(item, info)
            allParams.addAll(idValues)
            conditions.add("($idCondition)")
        }

        val query = "DELETE FROM ${info.tableName} WHERE ${conditions.joinToString(" OR ")}"
        performQuery<Any>(conn, query, allParams, false, PreparedStatement::executeUpdate)
    }

    // --- Detail retrieval ---

    /** Retrieves all detail (child) entities related to a parent entity through a foreign key. */
    inline fun <reified D : Any> getDetails(parent: Any, propertyName: String? = null): List<D> =
        getDetails(null, parent, D::class, propertyName)

    @PublishedApi
    @Suppress("UNCHECKED_CAST")
    internal fun <M : Any, D : Any> getDetails(
        conn: Connection?,
        parent: M,
        detailsClass: KClass<D>,
        propertyName: String? = null
    ): List<D> {
        val parentInfo = resolveTableInfo(parent::class) as TableInfo<M>
        val parentId = this.getValidIds(parent, parentInfo)
        require(parentId.size == 1) { "Parent class ${parent::class.fullName} should have exactly one primary key" }

        val detailInfo = resolveTableInfo(detailsClass)
        val nonKeyFields = detailInfo.fieldInfos.filter { !it.isPrimaryKey }

        val matchIndex: Int
        if (propertyName == null) {
            val types = nonKeyFields.map { it.type }
            matchIndex = findItemOnce(types, parent::class, detailsClass.fullName)
        } else {
            val names = nonKeyFields.map { it.name }
            matchIndex = findItemOnce(names, propertyName, detailsClass.fullName)
            if (nonKeyFields[matchIndex].type != parent::class)
                throw SQLException("Field $propertyName is not of type ${parent::class.fullName} in class ${detailsClass.fullName}")
        }
        val propertyDbName = nonKeyFields[matchIndex].dbName

        val details = read(
            conn,
            detailsClass,
            "SELECT ${detailInfo.selectFieldNames} FROM ${detailInfo.tableName} WHERE $propertyDbName = ?",
            parentId[0]
        )
        for (detail in details)
            detailInfo.setField(detail, propertyDbName, parent, this)
        return details
    }

    // --- Find operations ---

    /** Finds all entities, optionally filtered by a WHERE clause. */
    inline fun <reified T : Any> findAll(whereClause: String = "", vararg arguments: Any?): List<T> =
        findAll(null, T::class, whereClause, *arguments)

    @PublishedApi
    internal fun <T : Any> findAll(
        conn: Connection?,
        kclass: KClass<T>,
        whereClause: String = "",
        vararg arguments: Any?
    ): List<T> = read(
        conn,
        kclass,
        resolveTableInfo(kclass).let { "SELECT ${it.selectFieldNames} FROM ${it.tableName}" + (if (whereClause.isEmpty()) "" else " $whereClause") },
        *arguments
    )

    /** Finds a single entity by its primary key value (single PK only). */
    inline fun <reified T : Any> findById(id: Any) =
        findById(null, T::class, id)

    @PublishedApi
    internal fun <T : Any> findById(conn: Connection?, kclass: KClass<T>, id: Any) = resolveTableInfo(kclass).let {
        readOne(conn, kclass, "SELECT ${it.selectFieldNames} FROM ${it.tableName} WHERE ${it.singleKeyDbName} = ?", id)
    }

    // --- Transaction ---

    /** Executes a block within a database transaction with automatic commit/rollback. */
    fun transaction(block: TransactionContext.() -> Unit) = TransactionContext(this).start(block)

    // --- Stored Procedures ---

    /** Executes a stored procedure with IN/OUT/INOUT parameters. */
    fun procedure(conn: Connection?, name: String, vararg params: SPParam<*>) {
        val shouldClose = conn == null
        val connection = conn ?: dataSource.getConnection()
        try {
            val placeholders: String = nCopies("?", ", ", params.size)
            val statement = "CALL $name($placeholders)"
            `!dbLog`(statement, params)
            connection.prepareCall("{$statement}").use { cs ->
                for (i in params.indices) {
                    val p: SPParam<*> = params[i]
                    if (p.mode === OUT || p.mode === INOUT)
                        cs.registerOutParameter(i + 1, p.type)
                    if (p.mode === IN || p.mode === INOUT)
                        cs.setObject(i + 1, p.value)
                }
                cs.execute()
                for (i in params.indices) {
                    val p: SPParam<*> = params[i]
                    if (p.mode === OUT || p.mode === INOUT)
                        p.result = TypeUtils.castTo(p.type, cs.getObject(i + 1, p.type), this)
                }
            }
        } catch (e: Throwable) {
            e.throwQuery("Unable to execute stored procedure $name")
        } finally {
            if (shouldClose) connection.close()
        }
    }

    // --- Public introspection ---

    fun <T : Any> getTableInfo(kclass: KClass<T>): TableInfo<T> = resolveTableInfo(kclass)

    @Suppress("FunctionName")
    internal fun `!dbLog`(query: String, vararg params: Any?) =
        logger.debug("{}{}", query, if (params.isEmpty()) "" else " -- " + params.contentToString())
}
