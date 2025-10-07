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
 * The main ORM controller for the Stormify system. This is the entry point for all database operations.
 *
 * Stormify provides a flexible, convention-over-configuration approach to database interactions. It automatically
 * maps Kotlin classes to database tables based on naming conventions, while still supporting annotations for
 * customization.
 *
 * ## Key Features
 *
 * - **Automatic Mapping**: Fields are automatically mapped to database columns based on naming policies
 * - **CRUD Operations**: Create, read, update, and delete entities with minimal configuration
 * - **Transaction Management**: Nested transaction support via database savepoints
 * - **Custom Queries**: Execute raw SQL queries with automatic result mapping
 * - **Stored Procedures**: Call database stored procedures with IN/OUT/INOUT parameters
 * - **Composite Keys**: Full support for tables with composite primary keys
 * - **Flexible Configuration**: Use annotations or naming conventions to define mappings
 *
 * ## Basic Usage
 *
 * ```kotlin
 * val dataSource = HikariDataSource(config)
 * val stormify = Stormify(dataSource)
 *
 * // CRUD operations
 * val user = stormify.create(User(name = "Alice"))
 * val users = stormify.findAll<User>()
 * stormify.update(user.copy(name = "Alice Updated"))
 * stormify.delete(user)
 *
 * // Custom queries
 * val activeUsers = stormify.read<User>("SELECT * FROM users WHERE active = ?", true)
 *
 * // Transactions
 * stormify.transaction {
 *     create(user)
 *     create(profile)
 * }
 * ```
 *
 * ## Configuration
 *
 * - **Naming Policy**: Controls how class/field names map to table/column names (default: LOWER_CASE_WITH_UNDERSCORES)
 * - **Strict Mode**: When enabled, throws exceptions for mismatched fields (default: true)
 * - **Primary Key Resolvers**: Custom functions to identify primary keys by naming conventions
 * - **Blacklist Fields**: Exclude specific fields from database operations
 *
 * @param dataSource the JDBC data source to be used for all database operations. On JVM, this can be any
 *                   JDBC-compatible DataSource (HikariCP, Apache DBCP, etc.)
 *
 * @see TransactionContext
 * @see TableInfo
 * @see SqlDialect
 */
class Stormify(val dataSource: DataSource) {

    private inner class ConnectionMaker(connection: Connection?) : AutoCloseable {
        val connection by lazy { connection ?: dataSource._connection }
        val shouldClose = connection == null
        override fun close() {
            try {
                if (shouldClose) connection.close() // close it only if it is created here
            } catch (e: Throwable) {
                e.throwQuery("Unable to close connection")
            }
        }
    }

    /**
     * The SQL dialect used for database-specific SQL generation.
     *
     * This property is lazily initialized by detecting the database type from the DataSource.
     * The dialect determines how Stormify generates SQL statements for features like:
     * - Generated key retrieval (RETURNING vs SELECT @@IDENTITY vs LAST_INSERT_ID())
     * - Sequence handling for auto-increment fields
     * - Pagination queries (LIMIT/OFFSET, ROWS FETCH, ROW_NUMBER())
     * - Database-specific syntax variations
     *
     * ## Supported Databases
     *
     * - **MySQL** (5.x, 8.x+)
     * - **MariaDB** (<10.3, 10.3+)
     * - **PostgreSQL**
     * - **Oracle** (11g and older, 12c+)
     * - **SQL Server** (2008 and older, 2012+)
     * - **SQLite**
     * - **H2**
     * - **HSQLDB**
     * - **Apache Derby**
     *
     * Detection is automatic based on JDBC metadata. Falls back to UNKNOWN dialect
     * for unrecognized databases.
     *
     * @see SqlDialect
     */
    val sqlDialect: SqlDialect by lazy {
        try {
            SqlDialect.findDialect(dataSource)
        } catch (e: Throwable) {
            e.throwQuery("Unable to find SQL dialect")
        }
    }

    /**
     * Controls strict mode for object-to-database mapping.
     *
     * When **enabled** (default): Stormify throws a [QueryException] if:
     * - A database column doesn't have a corresponding property in the Kotlin class
     * - A class property doesn't have a corresponding database column
     * - Field types don't match between class and database
     *
     * When **disabled**: Stormify logs warnings for mismatches but continues execution,
     * allowing for more flexible mappings and partial object loading.
     *
     * ## Usage
     * ```kotlin
     * stormify.isStrictMode = false // Allow flexible mapping
     * stormify.isStrictMode = true  // Enforce strict validation (default)
     * ```
     *
     * **Default**: `true`
     */
    var isStrictMode: Boolean = true

    /**
     * The logger instance used for SQL query logging and error reporting.
     *
     * By default, uses the logger named "Stormify" from [LogManager]. You can replace this with a
     * custom logger to control logging behavior.
     *
     * The logger is used to:
     * - Log executed SQL statements (at DEBUG level)
     * - Log parameter values for debugging
     * - Report warnings in lenient mode
     * - Log sequence increments and other operations
     *
     * ## Usage
     * ```kotlin
     * stormify.logger = LogManager.getLogger("MyApp.Database")
     * ```
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
        val info = retrieve(value::class)
        return if (info.idNames.size == 1) info.getIdValues(value) else
            throw QueryException("Multiple primary keys found in ${info.table}")
    }

    /**
     * Executes an SQL UPDATE, INSERT, or DELETE statement and returns the number of affected rows.
     *
     * This method is used for SQL statements that modify the database state but don't return result sets.
     * It automatically handles parameter binding and supports both scalar values and collections.
     *
     * ## Supported Parameter Types
     * - Scalar values (Int, String, Boolean, etc.)
     * - Null values
     * - Collections (automatically expanded into multiple parameters)
     * - Entity objects (extracts primary key values)
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Simple UPDATE
     * val affected = stormify.executeUpdate(
     *     "UPDATE users SET active = ? WHERE id = ?",
     *     true, 123
     * )
     *
     * // DELETE with IN clause
     * val ids = listOf(1, 2, 3)
     * stormify.executeUpdate("DELETE FROM users WHERE id IN (?)", ids)
     * // Expands to: DELETE FROM users WHERE id IN (?, ?, ?)
     *
     * // INSERT
     * stormify.executeUpdate(
     *     "INSERT INTO logs (message, timestamp) VALUES (?, ?)",
     *     "User logged in", System.currentTimeMillis()
     * )
     * ```
     *
     * ## Transaction Behavior
     * - Outside transaction: Each call uses a new connection (auto-commit)
     * - Inside transaction: Uses the transaction's connection (manual commit)
     *
     * @param query the SQL statement to execute. Use `?` as placeholders for parameters.
     * @param params the parameter values to bind to the query. Collections are automatically expanded.
     * @return the number of rows affected by the statement
     * @throws QueryException if the query execution fails or parameter count doesn't match placeholders
     *
     * @see create
     * @see update
     * @see delete
     */
    fun executeUpdate(query: String, vararg params: Any?) =
        executeUpdate(null, query, params)

    internal fun executeUpdate(conn: Connection?, query: String, vararg params: Any?): Int {
        return performQuery(conn, query, params.toList(), false, { it._executeUpdate() })
    }

    /**
     * Executes a SELECT query and processes results row-by-row using a consumer function.
     *
     * This method is ideal for processing large result sets efficiently, as it doesn't load all rows into
     * memory at once. Each row is fetched, converted to type [T], and immediately passed to the consumer.
     *
     * ## When to Use
     * - **Large datasets**: Processing millions of rows without memory issues
     * - **Streaming operations**: Real-time processing or export of data
     * - **Memory-constrained environments**: When you can't afford to load all results at once
     *
     * ## Type Mapping
     * - **Scalar types** (Int, String, Long, etc.): Maps single-column results directly to the type
     * - **Entity classes**: Maps all columns to class properties using naming conventions
     * - **Data classes**: Fully supported with automatic property mapping
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Process large result set
     * var total = 0.0
     * stormify.readCursor<Double>("SELECT amount FROM transactions WHERE year = ?", 2024) { amount ->
     *     total += amount
     * }
     *
     * // Export to CSV
     * PrintWriter("users.csv").use { writer ->
     *     writer.println("id,name,email")
     *     stormify.readCursor<User>("SELECT * FROM users") { user ->
     *         writer.println("${user.id},${user.name},${user.email}")
     *     }
     * }
     *
     * // Count matching records
     * var count = 0
     * stormify.readCursor<User>("SELECT * FROM users WHERE active = ?", true) {
     *     count++
     * }
     * ```
     *
     * ## Performance Considerations
     * - Uses database cursor/forward-only result set
     * - Minimal memory footprint (only one row in memory at a time)
     * - No random access to results
     * - Cannot be used twice on the same result set
     *
     * @param T the type to map each row to. Must be a data class or scalar type.
     * @param query the SQL SELECT statement. Use `?` for parameter placeholders.
     * @param params the parameter values to bind to the query.
     * @param consumer the function called for each result row. Receives one mapped object per row.
     * @return the total number of rows processed
     * @throws QueryException if the query execution fails or type mapping fails
     *
     * @see read
     * @see readOne
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
                else castTo(baseClass, rs._getObject(1, baseClass), this)
                    ?: throw QueryException("Expecting type ${baseClass.fullName} but found null")
            )
        }
        count
    })

    /**
     * Executes a SELECT query and returns all results as a list.
     *
     * This is the most common method for reading data from the database. It executes the query,
     * fetches all rows, maps them to type [T], and returns them as a list. All results are loaded
     * into memory, so use [readCursor] for very large result sets.
     *
     * ## Type Mapping
     * - **Scalar types** (Int, String, Long, Boolean, Double, etc.): For single-column SELECT queries
     * - **Entity classes**: Automatically maps column names to class properties
     * - **Data classes**: Fully supported with automatic constructor parameter mapping
     * - **Nullable types**: Supported for both scalar and entity types
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Read all users
     * val users: List<User> = stormify.read("SELECT * FROM users")
     *
     * // Read with parameters
     * val activeUsers: List<User> = stormify.read(
     *     "SELECT * FROM users WHERE active = ? AND role = ?",
     *     true, "admin"
     * )
     *
     * // Read scalar values
     * val userIds: List<Int> = stormify.read("SELECT id FROM users")
     * val names: List<String> = stormify.read("SELECT name FROM users WHERE age > ?", 18)
     *
     * // Read with JOIN
     * val results: List<UserProfile> = stormify.read(
     *     """
     *     SELECT u.id, u.name, p.bio, p.avatar_url
     *     FROM users u
     *     JOIN profiles p ON u.id = p.user_id
     *     WHERE u.active = ?
     *     """,
     *     true
     * )
     *
     * // Empty result handling
     * val results: List<User> = stormify.read("SELECT * FROM users WHERE id = ?", -1)
     * // Returns empty list, never null
     * ```
     *
     * ## Column Name Mapping
     *
     * Column names are mapped to properties using the configured naming policy:
     * - `LOWER_CASE_WITH_UNDERSCORES` (default): `user_name` → `userName`
     * - `CAMEL_CASE`: `userName` → `userName`
     * - `UPPER_CASE_WITH_UNDERSCORES`: `USER_NAME` → `userName`
     *
     * ## Performance
     * - All rows are loaded into memory
     * - For large datasets (>10,000 rows), consider using [readCursor]
     * - For single results, use [readOne] for better performance
     *
     * @param T the type to map each row to. Can be a scalar type or entity class.
     * @param query the SQL SELECT statement. Use `?` for parameter placeholders.
     * @param params the parameter values to bind to the query in order.
     * @return a list of mapped objects. Returns empty list if no results found (never null).
     * @throws QueryException if query execution fails, type mapping fails, or parameters don't match
     *
     * @see readOne
     * @see readCursor
     * @see findAll
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
     * Executes a SELECT query and returns exactly one result, or null if no results are found.
     *
     * This method is optimized for queries expected to return a single row. It ensures data integrity
     * by throwing an exception if multiple rows are returned, preventing subtle bugs from unexpected
     * result sets.
     *
     * ## Behavior
     * - **0 rows**: Returns `null`
     * - **1 row**: Returns the mapped object
     * - **2+ rows**: Throws [QueryException] to prevent ambiguous results
     *
     * ## Common Use Cases
     * - Looking up entities by unique identifier (ID, email, username)
     * - Aggregate queries (COUNT, SUM, AVG, MAX, MIN)
     * - Checking existence with boolean queries
     * - Single-column scalar value retrieval
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Find user by ID
     * val user: User? = stormify.readOne("SELECT * FROM users WHERE id = ?", 123)
     * if (user != null) {
     *     println("Found: ${user.name}")
     * } else {
     *     println("User not found")
     * }
     *
     * // Get aggregate value
     * val totalSales: Double? = stormify.readOne("SELECT SUM(amount) FROM orders WHERE year = ?", 2024)
     * val count: Int? = stormify.readOne("SELECT COUNT(*) FROM users WHERE active = ?", true)
     *
     * // Check existence
     * val exists: Int? = stormify.readOne("SELECT 1 FROM users WHERE email = ?", "test@example.com")
     * val userExists = exists != null
     *
     * // Find by unique column
     * val user: User? = stormify.readOne("SELECT * FROM users WHERE email = ?", "alice@example.com")
     *
     * // Safe null handling with elvis operator
     * val user = stormify.readOne<User>("SELECT * FROM users WHERE id = ?", userId)
     *     ?: throw UserNotFoundException("User $userId not found")
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     // This will throw if multiple users have the same name
     *     val user = stormify.readOne<User>("SELECT * FROM users WHERE name = ?", "John")
     * } catch (e: QueryException) {
     *     // Handle multiple results case
     *     println("Multiple users found with name John")
     * }
     * ```
     *
     * ## Performance
     * - More efficient than [read] for single results (stops after first row verification)
     * - Uses [readCursor] internally to verify only one result exists
     * - Throws exception immediately upon detecting second row
     *
     * ## Null Safety
     * - Return type is nullable (`T?`)
     * - Always check for null before accessing properties
     * - Use safe call operator (`?.`) or elvis operator (`?:`)
     *
     * @param T the type to map the result to. Can be scalar type or entity class.
     * @param query the SQL SELECT statement. Use `?` for parameter placeholders.
     * @param params the parameter values to bind to the query.
     * @return the single mapped object, or null if no results found
     * @throws QueryException if multiple rows are returned, or if query execution fails
     *
     * @see read
     * @see readCursor
     * @see findById
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
     * Populates (refreshes) an existing entity with fresh data from the database based on its primary key.
     *
     * This method fetches the current state of an entity from the database and updates all its properties.
     * Only the primary key(s) need to be set on the entity - all other fields will be overwritten with
     * database values.
     *
     * ## Use Cases
     * - **Refresh stale data**: Update an entity with the latest database state
     * - **Lazy loading**: Load full entity data after initially creating with just the ID
     * - **Optimistic locking**: Verify entity hasn't changed since last read
     * - **Partial hydration**: Complete an entity that was created with minimal information
     *
     * ## Requirements
     * - Entity must have its primary key field(s) set
     * - Primary key must exist in the database
     * - Entity class must be properly mapped (via annotations or naming conventions)
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Refresh entity with latest data
     * val user = User(id = 123)
     * stormify.populate(user)
     * println(user.name) // Now populated with database value
     *
     * // Lazy loading pattern
     * fun getUserById(id: Int): User {
     *     val user = User(id = id)
     *     return stormify.populate(user)
     * }
     *
     * // Reload after external modification
     * val user = stormify.findById<User>(123)!!
     * // ... some other process modifies this user ...
     * stormify.populate(user) // Refresh with latest values
     *
     * // With composite key
     * val detail = OrderDetail(orderId = 1, productId = 5)
     * stormify.populate(detail)
     * println("Quantity: ${detail.quantity}") // Populated from DB
     * ```
     *
     * ## Behavior
     * - **ALL non-key properties** are overwritten with database values
     * - **Primary key properties** remain unchanged
     * - Any manual changes to the entity are **lost**
     * - The same entity instance is returned (modified in-place)
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     val user = User(id = 999) // Non-existent ID
     *     stormify.populate(user)
     * } catch (e: QueryException) {
     *     println("User not found: ${e.message}")
     * }
     * ```
     *
     * ## Performance Note
     * This method executes a SELECT query for each call. For batch operations, prefer:
     * - [read] or [findAll] to fetch multiple entities at once
     * - [readCursor] for processing many entities efficiently
     *
     * @param T the type of the entity to populate
     * @param entity the entity instance with primary key(s) set. All other properties will be overwritten.
     * @return the same entity instance, now populated with database values
     * @throws QueryException if no data found for the given primary key, or if primary key is not set
     *
     * @see findById
     * @see read
     * @see AutoTable.populate
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
        for (i in 1..columnCount) {
            val col = rs._getColumnName(i)
            info.setField(item, col, rs._getObject(i, info.getType(col)), this, if (isStrictMode) null else logger)
        }
        return item
    }

    private fun getNextSequence(conn: Connection?, sequence: String) = sqlDialect.sequenceDialect(sequence)
        ?.let { readOne(conn, NativeBigInteger::class, it) }
        ?.also { `!dbLog`("Sequence $sequence incremented to $it", null) }

    /**
     * Inserts a new entity into the database and returns it with any generated values populated.
     *
     * This method generates and executes an INSERT statement based on the entity's properties and annotations.
     * It automatically handles:
     * - Auto-generated primary keys (IDENTITY, SERIAL, AUTO_INCREMENT)
     * - Database sequences (Oracle, PostgreSQL)
     * - Composite primary keys
     * - Fields marked as non-creatable
     *
     * ## Auto-Generated Keys
     *
     * After successful insertion, the entity is updated with any database-generated values:
     * - **Auto-increment IDs**: Automatically retrieved and set on the entity
     * - **Sequence values**: Fetched before insertion if `primarySequence` is specified
     * - **Default values**: Any database defaults are NOT retrieved (use [populate] if needed)
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Simple insert with auto-generated ID
     * val user = User(name = "Alice", email = "alice@example.com")
     * val created = stormify.create(user)
     * println("Generated ID: ${created.id}") // ID now populated
     *
     * // Insert with explicit ID
     * val user = User(id = 100, name = "Bob", email = "bob@example.com")
     * stormify.create(user)
     *
     * // Insert with sequence (Oracle/PostgreSQL)
     * data class User(
     *     @DbField(primaryKey = true, primarySequence = "user_id_seq")
     *     var id: Int = 0,
     *     var name: String
     * )
     * val user = stormify.create(User(name = "Charlie"))
     * // id is populated from sequence
     *
     * // Composite key insert
     * val detail = OrderDetail(
     *     orderId = 1,
     *     productId = 5,
     *     quantity = 10
     * )
     * stormify.create(detail)
     *
     * // Batch insert
     * val users = listOf(
     *     User(name = "Alice"),
     *     User(name = "Bob"),
     *     User(name = "Charlie")
     * )
     * users.forEach { stormify.create(it) }
     * // Each user now has its generated ID
     * ```
     *
     * ## Field Control
     *
     * Control which fields are included in INSERT:
     *
     * ```kotlin
     * data class User(
     *     var id: Int = 0,
     *     var name: String,
     *     @DbField(creatable = false) // Excluded from INSERT
     *     var createdAt: Long = 0,
     *     @Transient // Also excluded
     *     var tempData: String = ""
     * )
     * ```
     *
     * ## Transaction Behavior
     * - Outside transaction: Commits immediately (auto-commit)
     * - Inside transaction: Part of the transaction, committed only when transaction completes
     *
     * ```kotlin
     * stormify.transaction {
     *     val user = create(User(name = "Alice"))
     *     val profile = create(Profile(userId = user.id, bio = "..."))
     *     // Both committed together
     * }
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     stormify.create(User(name = "Alice", email = "alice@example.com"))
     *     stormify.create(User(name = "Bob", email = "alice@example.com")) // Duplicate!
     * } catch (e: QueryException) {
     *     println("Insert failed: ${e.message}")
     *     // Handle constraint violation, unique key, etc.
     * }
     * ```
     *
     * ## Performance Tips
     * - For bulk inserts, wrap in a transaction for better performance
     * - Consider using batch INSERT statements for very large datasets
     * - Auto-key retrieval adds minimal overhead (single extra query or RETURNING clause)
     *
     * @param T the type of the entity to create
     * @param item the entity instance to insert. Properties map to column values.
     * @return the same entity instance with any generated keys populated
     * @throws QueryException if insert fails due to constraints, missing required fields, or database errors
     *
     * @see update
     * @see delete
     * @see populate
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
                            info.setField(item, info.singleKeyName, rs._getObject(1, NativeBigInteger::class), this)
                        else populate(item, rs)
                }
                affectedRows
            }
            item
        }

    /**
     * Updates an existing entity in the database based on its primary key.
     *
     * This method generates and executes an UPDATE statement using the entity's current property values.
     * The entity is identified by its primary key(s), and all updatable fields are included in the SET clause.
     *
     * ## Requirements
     * - Entity must have its primary key field(s) set
     * - Primary key must exist in the database
     * - At least one updatable field must be present
     *
     * ## What Gets Updated
     * - **Updatable fields**: All properties except those marked with `@DbField(updatable = false)` or `@Transient`
     * - **Primary keys**: NEVER updated (used only in WHERE clause)
     * - **Null values**: Included in UPDATE (sets column to NULL)
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Simple update
     * val user = stormify.findById<User>(123)!!
     * user.name = "Alice Updated"
     * user.email = "new.email@example.com"
     * stormify.update(user)
     *
     * // Update with data class copy
     * val user = stormify.findById<User>(123)!!
     * val updated = user.copy(name = "New Name", active = false)
     * stormify.update(updated)
     *
     * // Partial update pattern
     * val user = User(id = 123, name = "Updated Name", email = "new@example.com")
     * stormify.update(user)
     * // Only name and email are updated, other fields set to defaults
     *
     * // Null value update
     * val user = stormify.findById<User>(123)!!
     * user.middleName = null // Clear middle name
     * stormify.update(user)
     *
     * // Composite key update
     * val detail = OrderDetail(orderId = 1, productId = 5, quantity = 15)
     * stormify.update(detail)
     * ```
     *
     * ## Field Control
     *
     * ```kotlin
     * data class User(
     *     var id: Int,
     *     var name: String,
     *     @DbField(updatable = false) // Excluded from UPDATE
     *     var createdAt: Long,
     *     var updatedAt: Long // Included in UPDATE
     * )
     * ```
     *
     * ## Transaction Behavior
     * - Outside transaction: Commits immediately (auto-commit)
     * - Inside transaction: Part of the transaction
     *
     * ```kotlin
     * stormify.transaction {
     *     val user = findById<User>(123)!!
     *     user.balance -= 100.0
     *     update(user)
     *
     *     val other = findById<User>(456)!!
     *     other.balance += 100.0
     *     update(other)
     *     // Both updates committed together
     * }
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     val user = User(id = 999, name = "Ghost") // Non-existent
     *     stormify.update(user)
     *     // Succeeds but affects 0 rows
     * } catch (e: QueryException) {
     *     println("Update failed: ${e.message}")
     * }
     * ```
     *
     * ## Important Notes
     * - **No existence check**: Update succeeds even if primary key doesn't exist (0 rows affected)
     * - **No optimistic locking**: Concurrent updates can overwrite each other
     * - **Full object update**: All updatable fields are included, not just changed ones
     * - **No version tracking**: Consider implementing version field for optimistic locking
     *
     * ## Performance
     * - Single UPDATE statement per call
     * - For batch updates, wrap in transaction
     * - Consider using custom SQL with [executeUpdate] for bulk operations
     *
     * @param T the type of the entity to update
     * @param updatedItem the entity with updated values and primary key set
     * @return the same entity instance (unchanged)
     * @throws QueryException if update fails due to constraints or database errors
     *
     * @see create
     * @see delete
     * @see populate
     */
    fun <T : Any> update(updatedItem: T): T = update(null, updatedItem)

    internal fun <T : Any> update(conn: Connection?, updatedItem: T): T {
        val info = retrieve(updatedItem::class)
        val params = info.getRestValues(updatedItem) + getValidIds(updatedItem, info)
        performQuery<Any>(conn, info.updateQuery, params, false, PreparedStatement::_executeUpdate)
        return updatedItem
    }

    /**
     * Deletes an entity from the database based on its primary key.
     *
     * This method generates and executes a DELETE statement using the entity's primary key(s) to identify
     * the row to remove. Only the primary key fields are used - all other properties are ignored.
     *
     * ## Requirements
     * - Entity must have its primary key field(s) set
     * - Only primary key values matter; other fields are ignored
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Delete after finding
     * val user = stormify.findById<User>(123)!!
     * stormify.delete(user)
     *
     * // Delete with minimal object (only ID needed)
     * val user = User(id = 123)
     * stormify.delete(user)
     * // Other fields don't matter
     *
     * // Delete with composite key
     * val detail = OrderDetail(orderId = 1, productId = 5)
     * stormify.delete(detail)
     *
     * // Delete multiple entities
     * val usersToDelete = stormify.read<User>("SELECT * FROM users WHERE inactive_days > ?", 365)
     * usersToDelete.forEach { stormify.delete(it) }
     *
     * // Conditional delete (better to use custom SQL)
     * // Instead of this:
     * val inactiveUsers = stormify.findAll<User>("WHERE active = ?", false)
     * inactiveUsers.forEach { stormify.delete(it) }
     *
     * // Consider this for bulk operations:
     * stormify.executeUpdate("DELETE FROM users WHERE active = ?", false)
     * ```
     *
     * ## Transaction Behavior
     * - Outside transaction: Commits immediately (auto-commit)
     * - Inside transaction: Part of the transaction, can be rolled back
     *
     * ```kotlin
     * stormify.transaction {
     *     val user = findById<User>(123)!!
     *     delete(user)
     *
     *     val orders = read<Order>("SELECT * FROM orders WHERE user_id = ?", 123)
     *     orders.forEach { delete(it) }
     *     // All deletes committed together, or all rolled back on error
     * }
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     val user = User(id = 999) // Non-existent
     *     stormify.delete(user)
     *     // Succeeds but affects 0 rows
     * } catch (e: QueryException) {
     *     // Only throws on actual database errors, not when entity doesn't exist
     *     println("Delete failed: ${e.message}")
     * }
     * ```
     *
     * ## Cascade Behavior
     * - **No automatic cascade**: Stormify doesn't handle foreign key cascades
     * - **Database cascades**: Respects database-level CASCADE constraints
     * - **Manual cascade**: Handle dependent entities explicitly
     *
     * ```kotlin
     * // Manual cascade example
     * stormify.transaction {
     *     val user = findById<User>(123)!!
     *
     *     // Delete dependent entities first
     *     val orders = read<Order>("SELECT * FROM orders WHERE user_id = ?", user.id)
     *     orders.forEach { delete(it) }
     *
     *     // Then delete parent
     *     delete(user)
     * }
     * ```
     *
     * ## Important Notes
     * - **No existence check**: Delete succeeds even if entity doesn't exist (0 rows affected)
     * - **No soft delete**: Physical removal from database (consider UPDATE for soft delete)
     * - **Immediate effect**: Cannot be "undone" outside of transactions
     * - **Foreign key violations**: Database will throw error if referential integrity violated
     *
     * ## Performance
     * - Single DELETE statement per call
     * - For bulk deletes, prefer [executeUpdate] with custom SQL
     * - Wrap multiple deletes in transaction for better performance
     *
     * @param T the type of the entity to delete
     * @param deletedItem the entity with primary key(s) set. Other fields are ignored.
     * @throws QueryException if delete fails due to foreign key constraints or database errors
     *
     * @see create
     * @see update
     * @see executeUpdate
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
     * Retrieves all detail (child) entities related to a parent entity through a foreign key relationship.
     *
     * This method simplifies one-to-many relationship queries by automatically finding and populating
     * detail entities that reference a parent entity. It also sets the parent reference on each detail object.
     *
     * ## Requirements
     * - Parent must have exactly **one** primary key (not composite)
     * - Detail class must have a property that references the parent entity
     * - Foreign key relationship must exist in database
     *
     * ## How It Works
     * 1. Extracts parent's primary key value
     * 2. Finds the foreign key property in the detail class (automatically or by name)
     * 3. Executes `SELECT * FROM details WHERE foreign_key = parent_id`
     * 4. Populates the parent reference on each returned detail object
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Automatic foreign key detection
     * val user = stormify.findById<User>(123)!!
     * val orders: List<Order> = stormify.getDetails(user)
     * // Finds Order.user or Order.userId property automatically
     *
     * // Explicit foreign key property name
     * val orders: List<Order> = stormify.getDetails(user, "customer")
     * // Uses Order.customer property explicitly
     *
     * // Display results
     * for (order in orders) {
     *     println("Order ${order.id} for ${order.customer.name}")
     *     // order.customer is already set to parent user
     * }
     *
     * // Multiple detail types
     * val user = stormify.findById<User>(123)!!
     * val orders = stormify.getDetails<Order>(user)
     * val reviews = stormify.getDetails<Review>(user)
     * val addresses = stormify.getDetails<Address>(user)
     * ```
     *
     * ## Automatic Property Detection
     *
     * If `propertyName` is null, Stormify finds the foreign key property automatically:
     *
     * ```kotlin
     * data class Order(
     *     var id: Int,
     *     var user: User,  // ← Automatically detected (same type as parent)
     *     var amount: Double
     * )
     *
     * val orders = stormify.getDetails<Order>(user)
     * // Uses 'user' property automatically
     * ```
     *
     * **Rules for auto-detection**:
     * - Exactly **one** property must match the parent's type
     * - If zero matches → exception
     * - If multiple matches → exception (must specify propertyName)
     *
     * ## Explicit Property Name
     *
     * ```kotlin
     * data class Order(
     *     var id: Int,
     *     var customer: User,  // Different property name
     *     var amount: Double
     * )
     *
     * val orders = stormify.getDetails<Order>(user, "customer")
     * // Explicitly uses 'customer' property
     * ```
     *
     * ## Bidirectional Relationship
     *
     * The parent reference is automatically set on each detail:
     *
     * ```kotlin
     * val user = stormify.findById<User>(123)!!
     * val orders = stormify.getDetails<Order>(user)
     *
     * orders.forEach { order ->
     *     assert(order.user === user) // Parent reference is set
     *     println("${order.id}: ${order.user.name}")
     * }
     * ```
     *
     * ## Performance Considerations
     *
     * - Single SELECT query with WHERE clause on foreign key
     * - Should have database index on foreign key column
     * - For multiple parents, consider batch loading with custom SQL
     *
     * ## Alternative for Batch Loading
     *
     * ```kotlin
     * // Instead of N+1 queries:
     * users.forEach { user ->
     *     val orders = stormify.getDetails<Order>(user) // N queries
     * }
     *
     * // Better: Single query with IN clause
     * val userIds = users.map { it.id }
     * val allOrders = stormify.read<Order>(
     *     "SELECT * FROM orders WHERE user_id IN (?)",
     *     userIds
     * )
     * // Then group by user_id manually
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     val details = stormify.getDetails<Order>(user, "wrongProperty")
     * } catch (e: QueryException) {
     *     // Property not found or wrong type
     *     println("Invalid property: ${e.message}")
     * }
     * ```
     *
     * @param D the type of the detail (child) entities
     * @param parent the parent entity instance (must have single primary key set)
     * @param propertyName optional name of the foreign key property in the detail class.
     *                     If null, auto-detects by matching parent's type.
     * @return list of detail entities with parent reference populated. Empty list if none found.
     * @throws QueryException if parent has composite key, property not found, or multiple properties match parent type
     *
     * @see findAll
     * @see read
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
        val parentInfo = retrieve(parent::class)
        val parentId = this.getValidIds(parent, parentInfo)
        require(parentId.size == 1) { "Parent class ${parent::class.fullName} should have exactly one primary key" }

        val detailInfo = retrieve(detailsClass)
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
     * Finds all entities of a given type, optionally filtered by a WHERE clause.
     *
     * This is a convenience method that generates `SELECT * FROM table [WHERE ...]` queries.
     * It's useful for simple queries where you want all columns from a table.
     *
     * ## Basic Usage
     *
     * ```kotlin
     * // Get all users
     * val allUsers: List<User> = stormify.findAll<User>()
     *
     * // Get filtered users
     * val activeUsers = stormify.findAll<User>("WHERE active = ?", true)
     *
     * // Multiple conditions
     * val users = stormify.findAll<User>(
     *     "WHERE role = ? AND created_at > ?",
     *     "admin", startDate
     * )
     *
     * // With ORDER BY
     * val sorted = stormify.findAll<User>(
     *     "WHERE active = ? ORDER BY name ASC",
     *     true
     * )
     *
     * // With LIMIT
     * val recent = stormify.findAll<User>(
     *     "WHERE active = ? ORDER BY created_at DESC LIMIT 10",
     *     true
     * )
     * ```
     *
     * ## WHERE Clause Format
     *
     * **IMPORTANT**: The WHERE clause must include the `WHERE` keyword:
     *
     * ```kotlin
     * // ✅ CORRECT
     * findAll<User>("WHERE id > ?", 100)
     *
     * // ❌ WRONG - missing WHERE keyword
     * findAll<User>("id > ?", 100)
     * ```
     *
     * ## Advanced Filtering
     *
     * ```kotlin
     * // IN clause with collection
     * val ids = listOf(1, 2, 3)
     * val users = stormify.findAll<User>("WHERE id IN (?)", ids)
     *
     * // LIKE pattern
     * val users = stormify.findAll<User>("WHERE name LIKE ?", "%john%")
     *
     * // BETWEEN
     * val users = stormify.findAll<User>(
     *     "WHERE age BETWEEN ? AND ?",
     *     18, 65
     * )
     *
     * // IS NULL / IS NOT NULL
     * val users = stormify.findAll<User>("WHERE deleted_at IS NULL")
     *
     * // Complex conditions
     * val users = stormify.findAll<User>(
     *     """
     *     WHERE (role = ? OR role = ?)
     *       AND active = ?
     *       AND created_at > ?
     *     ORDER BY name
     *     """,
     *     "admin", "moderator", true, startDate
     * )
     * ```
     *
     * ## Empty Results
     *
     * ```kotlin
     * val users = stormify.findAll<User>("WHERE id = ?", -1)
     * // Returns empty list [], never null
     * println(users.isEmpty()) // true
     * ```
     *
     * ## Performance Considerations
     *
     * - **All rows loaded**: Results are fully loaded into memory
     * - **Large datasets**: For > 10,000 rows, use [readCursor] instead
     * - **Column selection**: If you only need specific columns, use [read] with custom SELECT
     * - **Pagination**: Add LIMIT/OFFSET to the WHERE clause for pagination
     *
     * ```kotlin
     * // Pagination example
     * fun getUsersPage(page: Int, pageSize: Int): List<User> {
     *     val offset = page * pageSize
     *     return stormify.findAll(
     *         "WHERE active = ? ORDER BY id LIMIT ? OFFSET ?",
     *         true, pageSize, offset
     *     )
     * }
     * ```
     *
     * ## Alternative for Complex Queries
     *
     * For JOIN queries or specific column selection, use [read]:
     *
     * ```kotlin
     * // findAll() limitation - can't do JOINs
     * // Use read() instead:
     * val results: List<UserWithProfile> = stormify.read(
     *     """
     *     SELECT u.id, u.name, p.bio
     *     FROM users u
     *     JOIN profiles p ON u.id = p.user_id
     *     WHERE u.active = ?
     *     """,
     *     true
     * )
     * ```
     *
     * ## Transaction Behavior
     * - Inside transaction: Uses transaction's connection
     * - Outside transaction: Creates new connection per call
     *
     * @param T the type of entity to find
     * @param whereClause optional SQL WHERE clause (must include "WHERE" keyword). Can include ORDER BY, LIMIT, etc.
     * @param arguments parameter values for placeholders in the WHERE clause
     * @return list of entities matching the criteria. Empty list if none found (never null).
     * @throws QueryException if query execution fails or arguments don't match placeholders
     *
     * @see read
     * @see findById
     * @see readCursor
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
     * Finds and returns a single entity by its primary key value.
     *
     * This is a convenience method that generates and executes `SELECT * FROM table WHERE id = ?`.
     * It only works for entities with a **single primary key field** (not composite keys).
     *
     * ## Requirements
     * - Entity class must have exactly **one** primary key field
     * - For composite keys, use [read] or [readOne] with custom SQL
     *
     * ## Return Value
     * - Returns the entity if found
     * - Returns `null` if not found
     * - Never throws exception for non-existent ID
     *
     * ## Usage Examples
     *
     * ```kotlin
     * // Simple lookup
     * val user: User? = stormify.findById<User>(123)
     * if (user != null) {
     *     println("Found: ${user.name}")
     * }
     *
     * // With null safety
     * val user = stormify.findById<User>(123)
     *     ?: throw UserNotFoundException("User not found")
     *
     * // Safe call chaining
     * val email = stormify.findById<User>(123)?.email
     *
     * // Using in conditional
     * stormify.findById<User>(userId)?.let { user ->
     *     println("Welcome back, ${user.name}")
     * } ?: println("User not found")
     *
     * // Different ID types
     * val user = stormify.findById<User>(123)           // Int
     * val post = stormify.findById<Post>(456L)          // Long
     * val doc = stormify.findById<Document>("abc-123")  // String
     * val uuid = stormify.findById<Record>(UUID.randomUUID())
     * ```
     *
     * ## Primary Key Detection
     *
     * Primary key is identified by:
     * 1. `@DbField(primaryKey = true)` annotation
     * 2. JPA `@Id` annotation
     * 3. Registered primary key resolver functions
     *
     * ## Composite Key Alternative
     *
     * For entities with composite keys, use [readOne]:
     *
     * ```kotlin
     * // Composite key example
     * val detail: OrderDetail? = stormify.readOne(
     *     "SELECT * FROM order_details WHERE order_id = ? AND product_id = ?",
     *     orderId, productId
     * )
     * ```
     *
     * ## Performance
     * - Single SELECT query with WHERE clause on primary key
     * - Should use database index on primary key (automatic in most databases)
     * - Efficient for lookups
     * - For batch lookups, prefer [read] with IN clause
     *
     * ## Comparison with populate()
     *
     * ```kotlin
     * // findById() - creates new instance
     * val user = stormify.findById<User>(123)
     *
     * // populate() - requires existing instance
     * val user = User(id = 123)
     * stormify.populate(user)
     * ```
     *
     * Use [findById] when you don't have an instance yet.
     * Use [populate] when you already have an instance with just the ID.
     *
     * ## Transaction Behavior
     * - Uses current transaction connection if inside transaction
     * - Creates new connection if outside transaction
     *
     * @param T the type of the entity to find. Must have a single primary key.
     * @param id the primary key value to search for. Type must match the entity's primary key type.
     * @return the entity if found, or null if not found
     * @throws QueryException if entity has composite keys (multiple primary key fields)
     *
     * @see findAll
     * @see readOne
     * @see populate
     */
    inline fun <reified T : Any> findById(id: Any) =
        findById(null, T::class, id)

    @PublishedApi
    internal fun <T : Any> findById(conn: Connection?, kclass: KClass<T>, id: Any) = retrieve(kclass).let {
        readOne(conn, kclass, "SELECT * FROM ${it.table} WHERE ${it.singleKeyName} = ?", id)
    }


    /**
     * Executes a block of code within a database transaction with automatic commit/rollback.
     *
     * All database operations within the transaction block share the same connection and are
     * committed together if successful, or rolled back if any operation fails. Supports nested
     * transactions through database savepoints.
     *
     * ## Transaction Guarantees
     * - **Atomicity**: All operations succeed together, or all fail together
     * - **Isolation**: Operations are isolated from other transactions
     * - **Consistency**: Database constraints are enforced at commit time
     * - **Durability**: Committed changes are permanent
     *
     * ## Basic Usage
     *
     * ```kotlin
     * stormify.transaction {
     *     val user = create(User(name = "Alice"))
     *     create(Profile(userId = user.id, bio = "Developer"))
     *     update(account)
     *     // All committed together
     * }
     * ```
     *
     * ## Automatic Rollback
     *
     * ```kotlin
     * try {
     *     stormify.transaction {
     *         create(user)
     *         create(profile)
     *         throw RuntimeException("Something went wrong")
     *         // Both creates are rolled back automatically
     *     }
     * } catch (e: Exception) {
     *     println("Transaction failed: ${e.message}")
     * }
     * ```
     *
     * ## Nested Transactions (Savepoints)
     *
     * ```kotlin
     * stormify.transaction {
     *     create(user)
     *
     *     try {
     *         transaction {  // Nested - creates savepoint
     *             create(optionalData)
     *             throw Exception("Optional operation failed")
     *         }
     *     } catch (e: Exception) {
     *         // Only nested transaction rolled back to savepoint
     *         // User creation still committed
     *     }
     *
     *     create(profile) // Still executes
     * }
     * ```
     *
     * ## Connection Sharing
     *
     * All methods called within the transaction automatically use the same connection:
     *
     * ```kotlin
     * stormify.transaction {
     *     // All these share the same connection
     *     val user = create(User(name = "Alice"))
     *     val orders = findAll<Order>("WHERE user_id = ?", user.id)
     *     orders.forEach { update(it) }
     *     delete(oldRecord)
     * }
     * ```
     *
     * ## Extracting Transaction Logic
     *
     * ### Pattern 1: Extension Functions
     * ```kotlin
     * fun TransactionContext.transferFunds(from: Account, to: Account, amount: Double) {
     *     require(from.balance >= amount) { "Insufficient funds" }
     *     update(from.copy(balance = from.balance - amount))
     *     update(to.copy(balance = to.balance + amount))
     *     create(Transaction(fromId = from.id, toId = to.id, amount = amount))
     * }
     *
     * stormify.transaction {
     *     transferFunds(accountA, accountB, 100.0)
     * }
     * ```
     *
     * ### Pattern 2: Service Classes
     * ```kotlin
     * class UserService(private val tx: TransactionContext) {
     *     fun registerUser(email: String) {
     *         tx.create(User(email = email))
     *         tx.create(AuditLog("User registered"))
     *     }
     * }
     *
     * stormify.transaction {
     *     val service = UserService(this)
     *     service.registerUser("test@example.com")
     * }
     * ```
     *
     * ## Performance Tips
     *
     * ```kotlin
     * // ✅ GOOD: Single transaction for batch operations
     * stormify.transaction {
     *     users.forEach { create(it) }
     * }
     *
     * // ❌ BAD: Separate transaction per operation
     * users.forEach { user ->
     *     stormify.transaction {
     *         create(user)
     *     }
     * }
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * stormify.transaction {
     *     try {
     *         create(user)
     *         riskyOperation()
     *     } catch (e: SpecificException) {
     *         // Handle but don't rethrow - transaction commits
     *         logger.warn("Handled error", e)
     *     }
     *     // Transaction commits because no exception escaped the block
     * }
     * ```
     *
     * ## Important Notes
     * - **No return value**: Transaction block returns Unit (use captured variables for results)
     * - **Single connection**: All operations use the same connection
     * - **Auto-commit disabled**: Connection auto-commit is disabled during transaction
     * - **Savepoint naming**: Nested transactions use unique savepoint names
     * - **Exception propagation**: Any uncaught exception triggers rollback
     *
     * ## Comparison with Manual Connection Management
     *
     * ```kotlin
     * // ❌ Manual (don't do this)
     * val conn = dataSource.connection
     * try {
     *     conn.autoCommit = false
     *     // operations...
     *     conn.commit()
     * } catch (e: Exception) {
     *     conn.rollback()
     *     throw e
     * } finally {
     *     conn.autoCommit = true
     *     conn.close()
     * }
     *
     * // ✅ With Stormify (do this)
     * stormify.transaction {
     *     // operations...
     * }
     * ```
     *
     * @param block the transaction block with [TransactionContext] as receiver. All CRUD methods
     *              are available directly within this block.
     * @throws QueryException if any database operation fails, wrapped with transaction context
     *
     * @see TransactionContext
     */
    fun transaction(block: TransactionContext.() -> Unit) = TransactionContext(this).start(block)

    /**
     * Executes a database stored procedure with IN, OUT, and INOUT parameters.
     *
     * This method calls stored procedures using the JDBC `CallableStatement` API, supporting
     * input parameters, output parameters, and bidirectional parameters. Results from OUT/INOUT
     * parameters are stored back in the [SPParam] objects.
     *
     * ## Parameter Modes
     * - **IN**: Input-only parameters (values passed to procedure)
     * - **OUT**: Output-only parameters (values returned from procedure)
     * - **INOUT**: Bidirectional parameters (values passed in and returned out)
     *
     * ## Basic Usage
     *
     * ```kotlin
     * // Simple IN parameters
     * stormify.procedure(
     *     null,
     *     "insert_user",
     *     SPParam.`in`(String::class, "Alice"),
     *     SPParam.`in`(String::class, "alice@example.com")
     * )
     *
     * // OUT parameter
     * val countParam = SPParam.out(Int::class)
     * stormify.procedure(null, "get_user_count", countParam)
     * val totalUsers = countParam.result as Int
     * println("Total users: $totalUsers")
     *
     * // INOUT parameter
     * val balanceParam = SPParam.inout(Double::class, 100.0)
     * stormify.procedure(null, "apply_interest", balanceParam)
     * val newBalance = balanceParam.result as Double
     * println("New balance: $newBalance")
     * ```
     *
     * ## Complete Example
     *
     * ```kotlin
     * // Procedure: create_order(IN user_id INT, IN amount DOUBLE, OUT order_id INT)
     * val orderIdParam = SPParam.out(Int::class)
     * stormify.procedure(
     *     null,
     *     "create_order",
     *     SPParam.`in`(Int::class, 123),        // user_id
     *     SPParam.`in`(Double::class, 99.99),   // amount
     *     orderIdParam                           // order_id (OUT)
     * )
     *
     * val orderId = orderIdParam.result as Int
     * println("Created order: $orderId")
     * ```
     *
     * ## Mixed Parameter Example
     *
     * ```kotlin
     * // Procedure: process_payment(
     * //   IN user_id INT,
     * //   INOUT balance DOUBLE,
     * //   IN amount DOUBLE,
     * //   OUT transaction_id INT
     * // )
     *
     * val balanceParam = SPParam.inout(Double::class, 1000.0)
     * val transactionIdParam = SPParam.out(Int::class)
     *
     * stormify.procedure(
     *     null,
     *     "process_payment",
     *     SPParam.`in`(Int::class, 123),
     *     balanceParam,
     *     SPParam.`in`(Double::class, 50.0),
     *     transactionIdParam
     * )
     *
     * println("New balance: ${balanceParam.result}")
     * println("Transaction ID: ${transactionIdParam.result}")
     * ```
     *
     * ## Parameter Type Mapping
     *
     * Common Kotlin types map to SQL types:
     * - `Int::class` → SQL INTEGER
     * - `Long::class` → SQL BIGINT
     * - `String::class` → SQL VARCHAR
     * - `Double::class` → SQL DOUBLE
     * - `Boolean::class` → SQL BOOLEAN
     * - `java.sql.Date::class` → SQL DATE
     * - `java.sql.Timestamp::class` → SQL TIMESTAMP
     *
     * ## Transaction Support
     *
     * ```kotlin
     * stormify.transaction {
     *     val idParam = SPParam.out(Int::class)
     *     // Note: procedure() needs explicit connection parameter in transactions
     *     // Currently not supported directly in transaction block
     *     // Call outside transaction or use executeUpdate for stored procedures
     * }
     * ```
     *
     * ## Error Handling
     *
     * ```kotlin
     * try {
     *     val param = SPParam.out(Int::class)
     *     stormify.procedure(null, "risky_procedure", param)
     *     println("Result: ${param.result}")
     * } catch (e: QueryException) {
     *     println("Procedure failed: ${e.message}")
     *     // Handle database errors, invalid parameters, etc.
     * }
     * ```
     *
     * ## Important Notes
     * - **Parameter order matters**: Must match stored procedure signature exactly
     * - **Type safety**: Types must match stored procedure parameter types
     * - **NULL handling**: OUT parameters can return null
     * - **Connection management**: Pass connection or let method create one
     * - **JDBC standard**: Uses JDBC `{CALL procedure_name(?, ?, ?)}` syntax
     *
     * ## Retrieving Results
     *
     * ```kotlin
     * val param1 = SPParam.out(String::class)
     * val param2 = SPParam.out(Int::class)
     * stormify.procedure(null, "get_user_info", param1, param2)
     *
     * val name = param1.result as String?
     * val age = param2.result as Int?
     *
     * // Safe null handling
     * if (name != null && age != null) {
     *     println("$name is $age years old")
     * }
     * ```
     *
     * @param conn optional connection to use. If null, creates a new connection.
     * @param name the name of the stored procedure to execute
     * @param params the parameters for the stored procedure. Use [SPParam.in], [SPParam.out], or [SPParam.inout].
     * @throws QueryException if procedure execution fails or parameters are invalid
     *
     * @see SPParam
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
                        p.result = cs._getObject(i + 1, params[i].type)
                }
            }
            if (shouldClose) connection.close()
        } catch (e: Throwable) {
            e.throwQuery("Unable to execute stored procedure $name")
        }
    }

    @Suppress("FunctionName")
    internal fun `!dbLog`(query: String, vararg params: Any?) =
        logger.debug("{}{}", query, if (params.isEmpty()) "" else " -- " + params.contentToString())
}
