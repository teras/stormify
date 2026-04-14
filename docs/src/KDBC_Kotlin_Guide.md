# KDBC Kotlin Usage Guide

KDBC provides a unified set of database interfaces for Kotlin Multiplatform. The same code compiles
and runs on JVM (wrapping JDBC), Linux (x64 and ARM64), Windows x64, and macOS native (wrapping the
C library — all 5 drivers), Android (wrapping `SQLiteDatabase`), and iOS (wrapping SQLite via cinterop).

## Common Interfaces

All platforms share these interfaces, defined in `kdbc/src/commonMain`:

| Interface | Purpose |
|-----------|---------|
| `DataSource` | Factory for connections |
| `Connection` | Database connection with transaction control |
| `Statement` | Prepared statement with parameter binding |
| `CallableStatement` | Stored procedure calls (extends `Statement`) |
| `ResultSet` | Row-by-row result iteration |
| `ResultSetMetaData` | Column names, count, labels |
| `DatabaseMetaData` | Product name, version |
| `Savepoint` | Named savepoint handle |
| `SQLException` | Unified error type (extends `RuntimeException`) |

All interfaces implement `AutoCloseable` where appropriate, so they work with Kotlin's `use {}` blocks.

## Creating a DataSource

How you create a `DataSource` depends on the platform.

=== "JVM"

    On JVM, wrap any `javax.sql.DataSource` (HikariCP, DBCP, etc.) using `JdbcDataSource`:

    ```kotlin
    import com.zaxxer.hikari.HikariConfig
    import com.zaxxer.hikari.HikariDataSource
    import onl.ycode.kdbc.JdbcDataSource

    val hikariConfig = HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        username = "dbuser"
        password = "dbpass"
    }
    val jdbcDs = HikariDataSource(hikariConfig)
    val ds = JdbcDataSource(jdbcDs)
    ```

    The `JdbcDataSource` wrapper has zero overhead -- it delegates directly to the underlying
    JDBC types.

=== "Native (Linux / Windows / macOS)"

    On native targets, use `KdbcDataSource` with a standard JDBC URL. The URL is parsed and
    dispatched to the appropriate C driver:

    ```kotlin
    import onl.ycode.kdbc.KdbcDataSource

    // SQLite
    val sqliteDs = KdbcDataSource("jdbc:sqlite:/tmp/test.db")

    // SQLite in-memory
    val memDs = KdbcDataSource("jdbc:sqlite::memory:")

    // PostgreSQL
    val pgDs = KdbcDataSource(
        "jdbc:postgresql://localhost:5432/mydb",
        user = "dbuser",
        password = "dbpass"
    )

    // MariaDB / MySQL
    val mariaDs = KdbcDataSource(
        "jdbc:mariadb://localhost:3306/mydb",
        user = "dbuser",
        password = "dbpass"
    )

    // Oracle
    val oracleDs = KdbcDataSource(
        "jdbc:oracle:thin:@localhost:1521/XEPDB1",
        user = "system",
        password = "oracle"
    )

    // MS SQL Server
    val mssqlDs = KdbcDataSource(
        "jdbc:sqlserver://localhost:1433;databaseName=mydb",
        user = "sa",
        password = "YourPassword"
    )
    ```

    !!! note "JDBC URL compatibility"
        `KdbcDataSource` accepts standard JDBC URLs, making it easy to write code that works
        on both JVM and Native with the same connection string. The URL is parsed by
        `JdbcUrlParser` and translated to the format the C library expects.

    !!! warning "No connection pooling"
        `KdbcDataSource` on native opens a fresh connection on every `getConnection()` call.
        For applications that need pooling, use a higher-level pool on top.

=== "Android"

    On Android, wrap the platform `SQLiteDatabase` using `AndroidDataSource`:

    ```kotlin
    import onl.ycode.kdbc.AndroidDataSource

    val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
    val ds = AndroidDataSource(db)
    ```

=== "iOS"

    On iOS, use `KdbcDataSource` with a SQLite URL (SQLite is the only supported driver):

    ```kotlin
    import onl.ycode.kdbc.KdbcDataSource

    val ds = KdbcDataSource("jdbc:sqlite:/path/to/database.db")
    ```

## Basic Usage

Once you have a `DataSource`, the API is the same on all platforms:

```kotlin
val conn = ds.getConnection()
try {
    // Create a table
    val createStmt = conn.initStatement(
        "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, age INTEGER)",
        returnGeneratedKeys = false,
        columnNames = null
    )
    createStmt.executeUpdate()
    createStmt.close()

    // Insert with parameters
    val insertStmt = conn.initStatement(
        "INSERT INTO users (name, age) VALUES (?, ?)",
        returnGeneratedKeys = false,
        columnNames = null
    )
    insertStmt.setObject(1, "Alice")
    insertStmt.setObject(2, 30)
    insertStmt.executeUpdate()
    insertStmt.close()

    // Query
    val queryStmt = conn.initStatement(
        "SELECT id, name, age FROM users WHERE age > ?",
        returnGeneratedKeys = false,
        columnNames = null
    )
    queryStmt.setObject(1, 18)
    val rs = queryStmt.executeQuery()
    while (rs.next()) {
        val id = rs.getObject(1, Long::class)
        val name = rs.getObject(2, String::class)
        val age = rs.getObject(3, Int::class)
        println("$id: $name (age $age)")
    }
    rs.close()
    queryStmt.close()
} finally {
    conn.close()
}
```

### Using `use {}` for Resource Management

Since all handles implement `AutoCloseable`, prefer `use {}` blocks:

```kotlin
ds.getConnection().use { conn ->
    conn.initStatement("SELECT count(*) FROM users", false, null).use { stmt ->
        stmt.executeQuery().use { rs ->
            if (rs.next()) {
                val count = rs.getObject(1, Long::class)
                println("Total users: $count")
            }
        }
    }
}
```

## Transactions

Autocommit is on by default. For explicit transactions:

```kotlin
val conn = ds.getConnection()
try {
    conn.setAutoCommit(false)

    val stmt = conn.initStatement(
        "INSERT INTO accounts (name, balance) VALUES (?, ?)", false, null)

    stmt.setObject(1, "Alice")
    stmt.setObject(2, 1000.0)
    stmt.executeUpdate()

    stmt.setObject(1, "Bob")
    stmt.setObject(2, 2000.0)
    stmt.executeUpdate()

    conn.commit()
    stmt.close()
} catch (e: SQLException) {
    conn.rollback()
    throw e
} finally {
    conn.close()
}
```

### Savepoints

```kotlin
conn.setAutoCommit(false)

conn.initStatement("INSERT INTO t VALUES (1, 'a')", false, null).use {
    it.executeUpdate()
}

val sp = conn.setSavepoint("sp1")

conn.initStatement("INSERT INTO t VALUES (2, 'b')", false, null).use {
    it.executeUpdate()
}

// Undo only the second insert
conn.rollback(sp)
conn.releaseSavepoint(sp)

// Commit -- only the first insert survives
conn.commit()
```

!!! note "RELEASE SAVEPOINT"
    Oracle and MSSQL do not support `RELEASE SAVEPOINT`. The native KDBC implementation
    silently skips the call on these databases. On JVM, the underlying JDBC driver handles
    this.

## Parameter Binding

`setObject` accepts any supported Kotlin type. The native implementation dispatches to the
appropriate C binding function based on the value's type:

| Kotlin Type | C Binding | SQL Type |
|-------------|-----------|----------|
| `null` | `kdbc_bind_null` | NULL |
| `Byte`, `Short`, `Int` | `kdbc_bind_int` | INTEGER |
| `Long` | `kdbc_bind_long` | BIGINT |
| `Float`, `Double` | `kdbc_bind_double` | DOUBLE |
| `Boolean` | `kdbc_bind_bool` | BOOLEAN / BIT / INTEGER |
| `String` | `kdbc_bind_string` | VARCHAR / TEXT |
| `ByteArray` | `kdbc_bind_blob` | BLOB / BYTEA |
| `CharArray` | `kdbc_bind_string` | CLOB / TEXT |
| `BigInteger` | long or string | NUMERIC |
| `BigDecimal` | string | DECIMAL |
| `LocalDateTime` | `kdbc_bind_timestamp` | TIMESTAMP |
| `LocalDate` | `kdbc_bind_date` | DATE |
| `LocalTime` | `kdbc_bind_time` | TIME |
| `Instant` | `kdbc_bind_timestamp` | TIMESTAMP |

## Result Set Retrieval

`getObject(columnIndex, type)` retrieves a column value, converting to the requested Kotlin type:

```kotlin
val rs = stmt.executeQuery()
while (rs.next()) {
    val id = rs.getObject(1, Long::class)           // BIGINT
    val name = rs.getObject(2, String::class)        // VARCHAR
    val balance = rs.getObject(3, Double::class)     // DOUBLE
    val created = rs.getObject(4, LocalDateTime::class)  // TIMESTAMP
    val data = rs.getObject(5, ByteArray::class)     // BLOB
}
```

To get a value without type conversion (useful for the generic `Map<String, Any>` path):

```kotlin
val value = rs.getObject(1, Any::class)  // Returns Long, Double, or String
```

### Column Metadata

```kotlin
val meta = rs.getMetaData()
for (i in 1..meta.columnCount) {
    println("Column $i: ${meta.getColumnName(i)} (label: ${meta.getColumnLabel(i)})")
}
```

## Generated Keys

To retrieve auto-generated keys after an INSERT:

```kotlin
val stmt = conn.initStatement(
    "INSERT INTO users (name) VALUES (?)",
    returnGeneratedKeys = true,
    columnNames = arrayOf("id")  // Optional: specify which columns to return
)
stmt.setObject(1, "Alice")
stmt.executeUpdate()

stmt.getGeneratedKeys().use { keys ->
    if (keys.next()) {
        val id = keys.getObject(1, Long::class)
        println("Generated ID: $id")
    }
}
stmt.close()
```

## Batch Execution

For bulk inserts:

```kotlin
val stmt = conn.initStatement(
    "INSERT INTO users (name, age) VALUES (?, ?)", false, null)

val users = listOf("Alice" to 30, "Bob" to 25, "Carol" to 28)
for ((name, age) in users) {
    stmt.setObject(1, name)
    stmt.setObject(2, age)
    stmt.addBatch()
}

val counts = stmt.executeBatch()
println("Inserted ${counts.sum()} rows")
stmt.close()
```

## Callable Statements (Stored Procedures)

```kotlin
val stmt = conn.prepareCall("{CALL add_numbers(?, ?, ?)}")
stmt.setObject(1, 10)
stmt.setObject(2, 20)
stmt.registerOutParameter(3, Long::class)

stmt.execute()

val result = stmt.getObject(3, Long::class)
println("Result: $result")
stmt.close()
```

!!! warning "Android limitation"
    Android's SQLite does not support stored procedures. Calling `prepareCall` on Android
    throws `UnsupportedOperationException`.

## Async Cancellation

The `Connection.cancel()` method interrupts a long-running query from another thread (or
coroutine):

```kotlin
import kotlinx.coroutines.*

val conn = ds.getConnection()

val job = launch(Dispatchers.IO) {
    try {
        val stmt = conn.initStatement("SELECT pg_sleep(3600)", false, null)
        stmt.executeQuery()  // Blocks until cancelled
    } catch (e: SQLException) {
        println("Query cancelled: ${e.message}")
    }
}

delay(5000)  // Wait 5 seconds
conn.cancel()  // Interrupt from another coroutine
job.join()
conn.close()
```

On JVM, `cancel()` delegates to `java.sql.Statement.cancel()`. On native, it calls `kdbc_cancel`
which dispatches to the driver's native cancellation primitive.

!!! warning "Cancel vs Close"
    Do not call `cancel()` concurrently with `close()` on the same connection. Serialize
    cancellation with connection teardown.

## Database Metadata

```kotlin
val meta = conn.metaData
println("${meta.databaseProductName} ${meta.databaseProductVersion}")
println("Version: ${meta.databaseMajorVersion}.${meta.databaseMinorVersion}")
```

## Error Handling

All database errors throw `SQLException`, which extends `RuntimeException`:

```kotlin
try {
    conn.initStatement("SELECT * FROM nonexistent", false, null).use { stmt ->
        stmt.executeQuery()
    }
} catch (e: SQLException) {
    println("Database error: ${e.message}")
    // e.cause may contain the underlying platform exception
}
```

`SQLException` is the same class on all platforms -- no need for platform-specific catch blocks.

## JDBC URL Parsing

The `JdbcUrlParser` object can be used standalone to parse JDBC URLs into their components:

```kotlin
val parsed = JdbcUrlParser.parse(
    "jdbc:postgresql://localhost:5432/mydb?user=admin&password=secret"
)
println(parsed.kind)       // POSTGRES
println(parsed.nativeUrl)  // localhost:5432/mydb
println(parsed.user)       // admin
println(parsed.password)   // secret
```

Supported URL schemes:

| Scheme | Driver | Example |
|--------|--------|---------|
| `jdbc:sqlite:` | SQLite | `jdbc:sqlite:/path/to/file.db` |
| `jdbc:postgresql:` | PostgreSQL | `jdbc:postgresql://host:5432/db` |
| `jdbc:mariadb:` | MariaDB | `jdbc:mariadb://host:3306/db` |
| `jdbc:mysql:` | MariaDB (same driver) | `jdbc:mysql://host:3306/db` |
| `jdbc:oracle:thin:` | Oracle | `jdbc:oracle:thin:@host:1521/service` |
| `jdbc:sqlserver:` | MSSQL | `jdbc:sqlserver://host:1433;databaseName=db` |

Explicit `user`/`password` arguments to `JdbcUrlParser.parse()` override values found in the URL.

## Using KDBC with Stormify

While KDBC can be used standalone, it is most commonly used as the database layer underneath
Stormify. Stormify accepts a KDBC `DataSource` directly:

=== "JVM"

    ```kotlin
    import onl.ycode.kdbc.JdbcDataSource
    import onl.ycode.stormify.Stormify

    val hikariDs = HikariDataSource(config)
    val stormify = Stormify(hikariDs)  // Convenience: auto-wraps to JdbcDataSource
    // or
    val stormify = Stormify(JdbcDataSource(hikariDs))
    ```

=== "Native (Linux / Windows / macOS)"

    ```kotlin
    import onl.ycode.kdbc.KdbcDataSource
    import onl.ycode.stormify.Stormify

    val ds = KdbcDataSource("jdbc:postgresql://localhost:5432/mydb", "user", "pass")
    val stormify = Stormify(ds)
    ```

=== "Android"

    ```kotlin
    import onl.ycode.kdbc.AndroidDataSource
    import onl.ycode.stormify.Stormify

    val db = context.openOrCreateDatabase("mydb.db", Context.MODE_PRIVATE, null)
    val stormify = Stormify(AndroidDataSource(db))
    ```

=== "iOS"

    ```kotlin
    import onl.ycode.kdbc.KdbcDataSource
    import onl.ycode.stormify.Stormify

    val ds = KdbcDataSource("jdbc:sqlite:/path/to/database.db")
    val stormify = Stormify(ds)
    ```

Once you have a `Stormify` instance, all KDBC details are hidden behind the ORM API. See
[Entity Mapping](Entity_Mapping.md), [CRUD Operations](CRUD.md), and
[Transactions](Transactions.md) for ORM usage.
