// SPDX-License-Identifier: Apache-2.0
// (C) Panayotis Katsaloulis
@file:Suppress("unused")

package onl.ycode.stormify


import onl.ycode.logger.LogManager
import onl.ycode.stormify.SPParam.Mode.*
import onl.ycode.stormify.SqlDialect.GeneratedKeyRetrieval
import onl.ycode.stormify.TableInfo.Companion.retrieve
import onl.ycode.stormify.TypeUtils.castTo
import kotlin.reflect.KClass


private class Reference<T>(var item: T? = null)
private class FixedParams(val query: String, val params: List<Any?>)

/**
 * The main controller for the Stormify system. It is the entrance point for all the operations on the database.
 *
 *
 * It is a singleton and r requires a data source to be set before any operations can be performed.
 * The data source is required to be a generic JDBC data source.
 *
 *
 * The controller provides methods to perform queries, create, update, and delete entities, and execute stored procedures.
 * It also provides methods to perform transactions and other database operations, like populating an entity, retrieving all details of a parent object, etc.
 */
class Stormify
/**
 *  Creates a new Stormify controller with the given data source.
 *
 * @param dataSource the data source to be used by the controller. Under JVM, Can be any generic JDBC data source.
 */
constructor(val dataSource: DataSource) {

    private inner class ConnectionMaker(connection: Connection?) : AutoCloseable {
        val connection by lazy { connection ?: dataSource._connection }
        val shouldClose = connection == null
        override fun close() {
            try {
                if (shouldClose) connection.close() // close it only if it is created here
            } catch (e: Throwable) {
                throw QueryException("Unable to close connection", e)
            }
        }
    }

    /**
     * Returns the SQL dialect used by the controller. See [SqlDialect].
     *
     * @return the SQL dialect used by the controller.
     */
    val sqlDialect: SqlDialect by lazy {
        try {
            SqlDialect.findDialect(dataSource)
        } catch (e: Throwable) {
            throw QueryException("Unable to find SQL dialect", e)
        }
    }

    /**
     * Set the object mapping to strict mode. In strict mode, the system will throw an exception if a field is not found in the entity.
     * Otherwise, it will log a warning and continue.
     *
     *
     * By default, the system is in strict mode.
     *
     * @return the current strict mode setting.
     */
    /**
     * Set the object mapping to strict mode. In strict mode, the system will throw an exception if a field is not found in the entity.
     * Otherwise, it will log a warning and continue.
     *
     *
     * By default, the system is in strict mode.
     *
     * @param strictMode the strict mode setting.
     */
    var isStrictMode: Boolean = true

    /**
     * Sets the logger to be used by the controller. By default, the logger is [LogManager.getLogger] with the name "Stormify".
     *
     * @param logger the logger to be used by the controller.
     */
    var logger = LogManager.getLogger("Stormify")

    private fun fixParams(givenQuery: String, args: List<Any?>): FixedParams {
        if (args.isEmpty()) return FixedParams(givenQuery, emptyList<Any>())
        val params: MutableList<Any?> = mutableListOf()
        val query = StringBuilder(givenQuery.length)
        var countQuestionMarks = 0
        for (i in givenQuery.indices) {
            // Have to parse the whole query in case Iterables are used as parameters
            if (givenQuery[i] == '?') {
                if (countQuestionMarks >= args.size) throw QueryException(
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
        if (countQuestionMarks != args.size) throw QueryException(
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
        return ConnectionMaker(conn).useWithException("Unable to execute query '${params.query}'") { maker ->
            maker.connection._prepareStatement(
                params.query,
                generatedKeys
            ).use { statement ->
                for (i in params.params.indices)
                    statement._setObject(i + 1, params.params[i])
                code(statement)
            }
        }
    }

    private fun sqlData(value: Any?, recursively: Boolean): Any? {
        if (value == null || isScalarObject(value)) return value
        if (recursively) {
            if (value is Array<*>)
                return sqlData(value.toList(), true)
            if (value is Iterable<*>)
                return value.map { sqlData(it, false) }
        }
        val info = retrieve(value::class) as TableInfo<Any>
        return if (info.idNames.size == 1) info.getIdValues(value) else
            throw QueryException("Multiple primary keys found in ${info.table}")
    }

    /**
     * Executes a query and returns the number of rows affected. This call is expected to update the status of the database.
     * It is used when SQL queries like INSERT, UPDATE, DELETE are executed.
     *
     * @param query  the query to be executed.
     * @param params the parameters to be used in the query.
     * @return the number of rows affected.
     */
    fun executeUpdate(query: String, vararg params: Any?) =
        executeUpdate(null, query, params)

    internal fun executeUpdate(conn: Connection?, query: String, vararg params: Any?): Int {
        return performQuery(conn, query, params.toList(), false, { it._executeUpdate() })
    }

    /**
     * Executes a read operation and returns the number of rows affected. Use this method when the strategy of parsing
     * the result row by row is preferred, instead of fetching all the results at once. Thus, data are consumed as they are
     * fetched from the database, making it ideal for large data sets.
     *
     * @param <T>       the type of the results.
     * @param baseClass the base class of the results.
     * @param query     the query to be executed.
     * @param consumer  the consumer to be used to process the results. Evey new row is passed to this consumer.
     * @param params    the parameters to be used in the query.
     * @return the number of rows affected.
     */
    inline fun <reified T : Any> readCursor(query: String, vararg params: Any?, noinline consumer: (T) -> Unit) =
        readCursor(null, T::class, query, params, consumer = consumer)

    @PublishedApi
    internal fun <T : Any> readCursor(
        conn: Connection?,
        baseClass: KClass<T>,
        query: String,
        vararg params: Any?,
        consumer: (T) -> Unit
    ) = performQuery(conn, query, params.toList(), false, { statement ->
        val info = if (isScalarClass(baseClass)) null else retrieve(baseClass)
        val rs: ResultSet = statement._executeQuery()
        var count = 0
        while (rs._next()) {
            count++
            consumer(
                if (info != null) populate(info.create(), rs)
                else castTo(baseClass, rs._getObject(1), this)
                    ?: throw QueryException("Expecting type ${baseClass.fullName} but found null")
            )
        }
        count
    })

    /**
     * Executes a read operation and returns the list of results.
     *
     * @param <T>       the type of the results.
     * @param baseClass the base class of the results.
     * @param query     the query to be executed.
     * @param params    the parameters to be used in the query.
     * @return the list of results. This list is never null.
     */
    inline fun <reified T : Any> read(query: String, vararg params: Any?): List<T> =
        read(null, T::class, query, params)

    @PublishedApi
    internal fun <T : Any> read(conn: Connection?, baseClass: KClass<T>, query: String, vararg params: Any?): List<T> =
        with(mutableListOf<T>()) {
            readCursor(conn, baseClass, query, params) { add(it) }
            return this
        }

    /**
     * Executes a read operation and returns a single result. If no results are found, null is returned. If multiple
     * results are found, a QueryException is thrown.
     *
     * @param <T>       the type of the result.
     * @param baseClass the base class of the result.
     * @param query     the query to be executed.
     * @param params    the parameters to be used in the query.
     * @return the single result. This result can be null if no data is found.
     */
    inline fun <reified T : Any> readOne(query: String, vararg params: Any?): T? =
        readOne(null, T::class, query, params)

    @PublishedApi
    internal fun <T : Any> readOne(conn: Connection?, baseClass: KClass<T>, query: String, vararg params: Any?): T? {
        val result = Reference<T?>()
        readCursor(conn, baseClass, query, params) {
            if (result.item != null)
                throw QueryException("Multiple results found for query '$query'")
            result.item = it
        }
        return result.item
    }

    private fun <T : Any> getValidIds(entity: T, info: TableInfo<T>): List<Any?> {
        val idValues = info.getIdValues(entity)
        if (idValues.isEmpty())
            throw QueryException("No primary key found for object " + info.type)
        idValues.forEachIndexed { i, value ->
            if (value == null)
                throw QueryException("Value of primary key ${info.idNames[i]} is null of entity ${entity::class.fullName}")
        }
        return idValues
    }

    /**
     * Populates the entity with the data from the database.
     *
     * @param entity the entity to be populated.
     * @param <T>    the type of the entity.
     * @return the populated entity. This is the same entity that was passed as an argument.
     */
    fun <T : Any> populate(entity: T): T =
        populate(null, entity)

    internal fun <T : Any> populate(conn: Connection?, entity: T): T {
        val info = retrieve(entity::class)
        performQuery<Any>(conn, info.populateQuery, getValidIds(entity, info), false, { statement ->
            val rs: ResultSet = statement._executeQuery()
            if (rs._next()) return@performQuery populate<T>(entity, rs)
            else throw QueryException("No data found for " + entity.toString(info))
        })
        return entity
    }

    private fun <T : Any> populate(item: T, rs: ResultSet): T {
        if (item is AutoTable && item.`!stormify` == null) item.`!stormify` = this
        val info = retrieve(item::class)
        val columnCount = rs._columnCount
        for (i in 1..columnCount)
            info.setField(item, rs._getColumnName(i), rs._getObject(i), this, if (isStrictMode) null else logger)
        return item
    }

    private fun getNextSequence(conn: Connection?, sequence: String) = sqlDialect.sequenceDialect(sequence)
        ?.let { readOne(conn, NativeBigInteger::class, it) }
        ?.also { `!dbLog`("Sequence $sequence incremented to $it", null) }

    /**
     * Creates a new entity in the database.
     *
     * @param item the entity to be created.
     * @param <T>         the type of the entity.
     * @return the created entity.
     */
    fun <T : Any> create(item: T): T = create(null, item)

    internal fun <T : Any> create(conn: Connection?, item: T) =
        ConnectionMaker(conn).useWithException("Unable to create ??") { maker ->
            val info = retrieve(item::class)
            val givenIds by lazy { info.getIdValues(item) }
            for (i in 0..<info.idSequences.size) {
                val sequence = info.idSequences[i]
                if (sequence.isNotBlank() && givenIds[i] == null)
                    info.setField(item, info.idNames[i], getNextSequence(maker.connection, sequence), this)
            }
            val hasGK = sqlDialect.generatedKeyRetrieval !== GeneratedKeyRetrieval.NONE
            val givenParams = info.getIdValues(item) + info.getRestValues(item)
            performQuery<Any>(maker.connection, info.createQuery, givenParams, hasGK) { st ->
                val affectedRows: Int = st._executeUpdate()
                if (!hasGK || affectedRows <= 0) return@performQuery affectedRows
                st._getGeneratedKeys().use { rs ->
                    if (rs._next())
                        if (sqlDialect.generatedKeyRetrieval === GeneratedKeyRetrieval.BY_INDEX)
                            info.setField(item, info.singleKeyName, rs._getObject(1), this)
                        else populate(item, rs)
                }
                affectedRows
            }
            item
        }

    /**
     * Updates an entity in the database.
     *
     * @param updatedItem the entity to be updated.
     * @param <T>         the type of the entity.
     * @return the updated entity.
     */
    fun <T : Any> update(updatedItem: T): T = update(null, updatedItem)

    internal fun <T : Any> update(conn: Connection?, updatedItem: T): T {
        val info = retrieve(updatedItem::class)
        val params = info.getRestValues(updatedItem) + getValidIds(updatedItem, info)
        performQuery<Any>(conn, info.updateQuery, params, false, PreparedStatement::_executeUpdate)
        return updatedItem
    }

    /**
     * Deletes an entity from the database.
     *
     * @param deletedItem the entity to be deleted.
     * @param <T>         the type of the entity.
     */
    fun <T : Any> delete(deletedItem: T) = delete(null, deletedItem)

    internal fun <T : Any> delete(conn: Connection?, deletedItem: T) {
        val info = retrieve(deletedItem::class)
        performQuery<Any>(
            conn,
            info.deleteQuery,
            getValidIds(deletedItem, info),
            false,
            PreparedStatement::_executeUpdate
        )
    }

    /**
     * Returns the details of the parent object.
     *
     * @param parent       the parent object.
     * @param detailsClass the class of the details. This class should have a reference to the parent class.
     * @param propertyName the name of the reference property in the details class (i.e. the foreign key property name).
     * If empty, the first field of the child class that matches the type of the parent class will be used.
     * In this case, if no fields match, or more than one field match, an exception will be thrown.
     * @param <M>          the type of the parent object.
     * @param <D>          the type of the details.
     * @return the details of the parent object as a list.
     */
    inline fun <reified D : Any> getDetails(parent: Any, propertyName: String? = null): List<D> =
        getDetails(null, parent, D::class, propertyName)

    @PublishedApi
    internal fun <M : Any, D : Any> getDetails(
        conn: Connection?,
        parent: M,
        detailsClass: KClass<D>,
        propertyName: String? = null
    ): List<D> {
        val parentInfo = retrieve(parent::class) as TableInfo<M>
        val parentId = this.getValidIds(parent, parentInfo)
        require(parentId.size == 1) { "Parent class ${parent::class.fullName} should have exactly one primary key" }

        val detailInfo = retrieve(detailsClass) as TableInfo<D>
        val propertyDbName = detailInfo.restDbNames[if (propertyName == null)
            findItemOnce(detailInfo.restTypes, parent::class, detailsClass.fullName)
        else
            findItemOnce(detailInfo.restNames, propertyName, detailsClass.fullName).also {
                if (detailInfo.restTypes[it] != parent::class)
                    throw QueryException("Field $propertyName is not of type ${parent::class.fullName} in class ${detailsClass.fullName}")
            }]
        val details = read(
            conn,
            detailsClass,
            "SELECT * FROM ${detailInfo.table} WHERE $propertyDbName = ?",
            parentId
        )
        for (detail in details)
            detailInfo.setField(detail, propertyDbName, parent, this)
        return details
    }

    /**
     * Finds all the entities of the given class, while applying the given where clause.
     *
     * @param kclass       the class of the entities.
     * @param whereClause the where clause to be applied. The clause can be empty or null. It should contain the WHERE keyword.
     * @param arguments   the arguments to be used in the where clause, if the where clause exists.
     * @param <T>         the type of the entities.
     * @return the list of entities.
     */
    inline fun <reified T : Any> findAll(whereClause: String = "", vararg arguments: Any?): List<T> =
        findAll(null, T::class, whereClause, arguments)

    @PublishedApi
    internal fun <T : Any> findAll(
        conn: Connection?,
        kclass: KClass<T>,
        whereClause: String = "",
        vararg arguments: Any?
    ): List<T> = read(
        conn,
        kclass,
        "SELECT * FROM " + retrieve(kclass).table + (if (whereClause.isEmpty()) "" else " $whereClause"),
        *arguments
    )

    /**
     * Finds the entity of the given class with the given ID.
     *
     * @param kclass the class of the entity.
     * @param id    the ID of the entity.
     * @param <T>   the type of the entity.
     * @return the entity with the given ID or null if not found.
     */
    inline fun <reified T : Any> findById(id: Any) =
        findById(null, T::class, id)

    @PublishedApi
    internal fun <T : Any> findById(conn: Connection?, kclass: KClass<T>, id: Any) = retrieve(kclass).let {
        readOne(conn, kclass, "SELECT * FROM ${it.table} WHERE ${it.singleKeyName} = ?", id)
    }


    /**
     * Executes a transaction with the given block of code.
     *
     * @param block the block of code to be executed.
     */
    fun transaction(block: TransactionContext.() -> Unit) = TransactionContext(this).start(block)

    /**
     * Executes a stored procedure with the given name and parameters.
     *
     * @param name   the name of the stored procedure.
     * @param params the parameters to be used in the stored procedure.
     */
    fun procedure(conn: Connection?, name: String, vararg params: SPParam<*>) {
        try {
            val shouldClose = conn == null
            val connection = conn ?: dataSource._connection
            val placeholders: String = nCopies("?", ", ", params.size ?: 0)
            val statement = "CALL $name($placeholders)"
            `!dbLog`(statement, params)
            connection._prepareCall("{$statement}").use { cs ->
                for (i in params.indices) {
                    val p: SPParam<*> = params[i]
                    if (p.mode === IN || p.mode === INOUT)
                        cs._setObject(i + 1, p.value)
                    if (p.mode === OUT || p.mode === INOUT)
                        cs._registerOutParameter(i + 1, convertNativeTypeToSQLType(p.type))
                }
                cs._execute()
                for (i in params.indices) {
                    val p: SPParam<*> = params[i]
                    if (p.mode === OUT || p.mode === INOUT)
                        p.result = cs._getObject(i + 1)
                }
            }
            if (shouldClose) connection.close()
        } catch (e: Throwable) {
            throw QueryException("Unable to execute stored procedure $name", e)
        }
    }

    @Suppress("FunctionName")
    internal fun `!dbLog`(query: String, vararg params: Any?) =
        logger.debug("{}{}", query, if (params.isEmpty()) "" else " -- " + params.contentToString())
}
